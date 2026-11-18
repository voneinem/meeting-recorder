package app.meetingrecorder.transcription

import app.meetingrecorder.core.audio.PcmWindowSource
import app.meetingrecorder.core.audio.UnreadableWavException
import app.meetingrecorder.core.audio.WavPcmReader
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.transcription.PipelineOutcome
import app.meetingrecorder.core.transcription.PipelineRequest
import app.meetingrecorder.core.transcription.SpeechDecoder
import app.meetingrecorder.core.transcription.TranscriptionPipeline
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.recording.RecordingCheck
import app.meetingrecorder.storage.FolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The Android-free orchestration of one transcription job (run sequence of contracts/transcription-service.md,
 * steps 2 to 7). The worker is a thin shell around it. It never writes `audio.wav`, writes `meeting.json` only
 * through [MeetingMetadataStore], and maps every failure to a [FailureReason] in the job block.
 *
 * [run] blocks its thread for the whole decode: the worker calls it from a dedicated thread. [abort] may be
 * called from any thread and makes the running decode return soon.
 */
class TranscriptionJobRunner(
    private val folderFiles: (folderName: String) -> FolderFiles?,
    private val metadata: MeetingMetadataStore,
    private val models: (modelId: String) -> InstalledModel?,
    private val decoders: DecoderFactory,
    private val recordingCheck: RecordingCheck,
    private val threads: Int,
    private val engineVersion: String,
    private val deviceLanguage: () -> String,
    private val vad: () -> Boolean,
    private val clock: () -> Long,
    private val now: () -> OffsetDateTime,
    private val modelName: (InstalledModel) -> String = { it.displayName },
) {
    @Volatile
    private var decoder: SpeechDecoder? = null

    /** Makes a running decode return `Aborted` soon. Thread-safe. */
    fun abort() {
        decoder?.abort()
    }

    @Suppress("ReturnCount", "LongMethod")
    suspend fun run(request: TranscriptionRequest, stop: StopSignal, listener: RunListener): RunOutcome {
        val folder = request.folderName
        val files = folderFiles(folder) ?: return RunOutcome.MeetingNotFound
        val meeting = metadata.read(folder) ?: return RunOutcome.MeetingNotFound
        if (meeting.status == MeetingStatus.RECORDING) return RunOutcome.MeetingStillRecording

        // The job block is the first write of a run: the list shows "in progress" from here on.
        val started = metadata.update(folder) { MeetingEditor.startJob(it, request.modelId, request.requestedLanguage, now()) }
        if (!started) return RunOutcome.Failed(FailureReason.STORAGE_FULL)

        val model = models(request.modelId) ?: return failed(folder, files, FailureReason.MODEL_MISSING, "the model is not installed")
        // The recording has priority (FR-017): it may have started since the user tapped Transcribe.
        if (recordingCheck.isRecording()) return failed(folder, files, FailureReason.RECORDING_STARTED, "a recording started")

        return try {
            TranscriptionIo.openAudio(files).use { store ->
                val reader = WavPcmReader(store)
                execute(request, model, files, reader, stop, listener)
            }
        } catch (e: UnreadableWavException) {
            failed(folder, files, FailureReason.AUDIO_UNREADABLE, e.message)
        } catch (e: IOException) {
            failed(folder, files, FailureReason.AUDIO_UNREADABLE, "the audio file could not be opened")
        } finally {
            closeDecoder()
        }
    }

    @Suppress("ReturnCount", "LongParameterList")
    private fun execute(
        request: TranscriptionRequest,
        model: InstalledModel,
        files: FolderFiles,
        reader: WavPcmReader,
        stop: StopSignal,
        listener: RunListener,
    ): RunOutcome {
        val folder = request.folderName
        val pipelineRequest = PipelineRequest(
            modelId = model.id,
            modelSha256 = model.sha256,
            modelLanguages = model.languages,
            requestedLanguage = request.requestedLanguage,
            deviceLanguage = deviceLanguage(),
            vad = vad(),
            threads = threads,
            engineVersion = engineVersion,
            startedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now()),
            sampleRate = reader.sampleRate,
            durationFrames = reader.frameCount,
        )

        val resumeFrom = prepareWorkFile(request, pipelineRequest, model, files) ?: return failed(
            folder, files, FailureReason.ERROR, "the work file cannot be resumed",
        )

        listener.onPhase(JobPhase.PREPARING)
        val speech = try {
            decoders.create(model, threads)
        } catch (e: DecoderLoadException) {
            return failed(folder, files, e.reason, e.message)
        }
        decoder = speech
        if (stop.isCancelled()) return stopped(request, files, stop)

        listener.onPhase(JobPhase.RUNNING)
        val pipeline = TranscriptionPipeline(
            source = PcmWindowSource(reader),
            decoder = speech,
            sink = WorkFileSink(files),
            clock = clock,
            listener = { framesDone, wallClockMs -> listener.onAnchor(framesDone, pipelineRequest.durationFrames, wallClockMs) },
        )
        return when (val outcome = pipeline.run(pipelineRequest, resumeFrom.takeIf { it.isNotEmpty() }, stop)) {
            PipelineOutcome.Finished -> {
                closeDecoder() // free the model before the commit
                listener.onPhase(JobPhase.COMMITTING)
                commit(folder, files, model)
            }
            PipelineOutcome.Cancelled -> stopped(request, files, stop)
            is PipelineOutcome.Failed -> failed(folder, files, outcome.reason, null)
        }
    }

    /**
     * RESUME loads the work file and checks it against the request (a torn tail is cut off); START and RESTART
     * delete any old work file and create an empty one. Returns the records to resume from (empty for a new
     * job), or null when a resume is not possible.
     */
    private fun prepareWorkFile(
        request: TranscriptionRequest,
        pipelineRequest: PipelineRequest,
        model: InstalledModel,
        files: FolderFiles,
    ): List<WorkRecord>? {
        if (request.mode != Mode.RESUME) {
            TranscriptionIo.createWorkFile(files)
            return emptyList()
        }
        val read = TranscriptionIo.readWorkFile(files) ?: return null
        val header = read.records.firstOrNull() as? WorkRecord.Header ?: return null
        val check = WorkFile.canResume(
            header,
            WorkFile.ResumeRequest(
                requestedLanguage = pipelineRequest.requestedLanguage,
                vad = pipelineRequest.vad,
                sampleRate = pipelineRequest.sampleRate,
                durationFrames = pipelineRequest.durationFrames,
                pipeline = app.meetingrecorder.core.transcription.TranscriptionPipeline.PIPELINE_VERSION,
            ),
            installedModelSha256 = model.sha256,
        )
        if (check != WorkFile.ResumeCheck.Allowed) return null
        TranscriptionIo.truncateTornTail(files, read)
        return read.records
    }

    private fun commit(folder: String, files: FolderFiles, model: InstalledModel): RunOutcome {
        val committer = TranscriptCommitter(files, metadata, folder, modelName(model), model.origin, now)
        return when (val result = committer.commit()) {
            CommitOutcome.Committed -> RunOutcome.Completed
            CommitOutcome.NothingToCommit -> failed(folder, files, FailureReason.ERROR, "the work file is not complete")
            is CommitOutcome.Failed -> failed(
                folder, files,
                if (result.cause is IOException) FailureReason.STORAGE_FULL else FailureReason.ERROR,
                "the transcript could not be committed",
            )
        }
    }

    /** The run stopped before the end. A user cancel discards everything; the other reasons keep the work file. */
    private fun stopped(request: TranscriptionRequest, files: FolderFiles, stop: StopSignal): RunOutcome {
        val folder = request.folderName
        closeDecoder()
        return when (stop.reason) {
            StopReason.USER_CANCEL -> {
                files.delete(MeetingFolderLayout.TRANSCRIPT_WORK_FILE)
                metadata.update(folder) { MeetingEditor.clearJob(it) }
                RunOutcome.Cancelled
            }
            StopReason.RECORDING_STARTED -> {
                writeFailure(folder, FailureReason.RECORDING_STARTED, "stopped because a recording started", resumable = true)
                RunOutcome.Stopped(FailureReason.RECORDING_STARTED)
            }
            StopReason.SYSTEM -> {
                writeFailure(folder, FailureReason.INTERRUPTED, "stopped by the system", resumable = true)
                RunOutcome.Stopped(FailureReason.INTERRUPTED)
            }
        }
    }

    private fun failed(folder: String, files: FolderFiles, reason: FailureReason, message: String?): RunOutcome {
        closeDecoder()
        val resumable = runCatching { TranscriptionIo.readWorkFile(files)?.records?.firstOrNull() is WorkRecord.Header }.getOrDefault(false)
        writeFailure(folder, reason, message, resumable)
        return RunOutcome.Failed(reason)
    }

    private fun writeFailure(folder: String, reason: FailureReason, message: String?, resumable: Boolean) {
        metadata.update(folder) { MeetingEditor.failJob(it, reason, message, resumable) }
    }

    private fun closeDecoder() {
        val current = decoder ?: return
        decoder = null
        runCatching { current.close() }
    }
}
