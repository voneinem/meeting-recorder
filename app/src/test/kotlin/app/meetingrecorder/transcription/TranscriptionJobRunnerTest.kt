package app.meetingrecorder.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcription.DecodeOutcome
import app.meetingrecorder.core.transcription.DecodeRequest
import app.meetingrecorder.core.transcription.LanguageProbability
import app.meetingrecorder.core.transcription.PcmWindow
import app.meetingrecorder.core.transcription.RawSegment
import app.meetingrecorder.core.transcription.SegmentSink
import app.meetingrecorder.core.transcription.SpeechDecoder
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.core.transcription.WorkSegment
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.recording.RecordingCheck
import app.meetingrecorder.storage.FakeFolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TranscriptionJobRunnerTest {
    private val sha = "c".repeat(64)
    private val now = OffsetDateTime.parse("2026-11-17T09:00:00+01:00")
    private val model = InstalledModel("tiny", "Tiny", ModelOrigin.CATALOG, sha, 1000, ModelLanguages.MULTILINGUAL, "x")

    private val baseMeeting: MeetingMetadata = MeetingEditor.create(
        "Weekly", OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), 16_000, SourceInfo("Built-in", SourceType.BUILTIN, 1),
    )

    /** A 16 kHz mono WAV of [seconds] of low noise (so a language probe would find signal). */
    private fun wav(seconds: Int): ByteArray {
        val frames = seconds * 16_000
        val data = ByteArray(frames * 2) { if (it % 2 == 0) (it / 2 % 50).toByte() else 0 }
        return WavHeader.build(16_000, 1, data.size.toLong()) + data
    }

    private fun meeting(status: MeetingStatus = MeetingStatus.COMPLETE, seconds: Int = 20): MeetingMetadata {
        val finished = MeetingEditor.finish(baseMeeting, seconds * 16_000L)
        return finished.copy(status = status)
    }

    private fun folder(
        meeting: MeetingMetadata? = meeting(),
        audio: ByteArray? = wav(20),
        extra: Map<String, String> = emptyMap(),
    ): FakeFolderFiles {
        val files = FakeFolderFiles(buildMap {
            meeting?.let { put("meeting.json", MeetingJson.encode(it)) }
            putAll(extra)
        })
        audio?.let { files.putBytes("audio.wav", it) }
        return files
    }

    /** Scripted decoder: one segment per window; records the calls. */
    private class Decoder(
        val onDecode: (call: Int, sink: SegmentSink) -> DecodeOutcome = { call, sink ->
            sink.onSegment(RawSegment(0, 500, "window $call"))
            DecodeOutcome.Completed
        },
    ) : SpeechDecoder {
        var decodes = 0
        var aborted = false
        var closed = false

        override fun detectLanguage(window: PcmWindow) = listOf(LanguageProbability("de", 0.9), LanguageProbability("en", 0.1))

        override fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome {
            decodes++
            return onDecode(decodes, sink)
        }

        override fun abort() {
            aborted = true
        }

        override fun close() {
            closed = true
        }
    }

    private class Stop(var reason0: StopReason = StopReason.USER_CANCEL, var cancelled: Boolean = false) : StopSignal {
        override val reason get() = reason0
        override fun isCancelled() = cancelled
    }

    private val phases = mutableListOf<JobPhase>()
    private val anchors = mutableListOf<Long>()
    private val listener = object : RunListener {
        override fun onPhase(phase: JobPhase) {
            phases += phase
        }

        override fun onAnchor(framesDone: Long, durationFrames: Long, wallClockMs: Long) {
            anchors += framesDone
        }
    }

    private var recording = false
    private var installed: InstalledModel? = model
    private var decoder = Decoder()
    private var loadFailure: DecoderLoadException? = null

    private fun runner(files: FakeFolderFiles): TranscriptionJobRunner = TranscriptionJobRunner(
        folderFiles = { if (it == "m1") files else null },
        metadata = MeetingMetadataStore(warn = { _, _ -> }) { if (it == "m1") files else null },
        models = { installed?.takeIf { m -> m.id == it } },
        decoders = DecoderFactory { _, _ ->
            loadFailure?.let { throw it }
            decoder
        },
        recordingCheck = RecordingCheck { recording },
        threads = 4,
        engineVersion = "1.9.5",
        deviceLanguage = { "de" },
        vad = { false },
        clock = { System.nanoTime() / 1_000_000 },
        now = { now },
    )

    private fun run(files: FakeFolderFiles, stop: Stop = Stop(), mode: Mode = Mode.START, language: String = "de"): RunOutcome =
        runBlocking { runner(files).run(TranscriptionRequest("m1", "tiny", language, mode), stop, listener) }

    private fun metaOf(files: FakeFolderFiles): MeetingMetadata =
        (MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting

    @Test
    fun aFinishedRunCommitsAndLeavesNoPartOrWorkFile() {
        val files = folder()

        assertEquals(RunOutcome.Completed, run(files))

        assertTrue("transcript.json" in files.names() && "transcript.md" in files.names())
        assertFalse(files.names().any { it.endsWith(".part") || it == "transcript.work.jsonl" })
        val meta = metaOf(files)
        assertNull(meta.transcription?.job)
        assertNotNull(meta.transcription?.transcript)
        val transcript = (TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success).transcript
        assertEquals("window 1", transcript.segments.first().text)
        assertEquals(listOf(JobPhase.PREPARING, JobPhase.RUNNING, JobPhase.COMMITTING), phases)
        assertTrue(anchors.isNotEmpty() && anchors.last() == 20 * 16_000L)
        assertTrue(decoder.closed, "the model is freed")
    }

    @Test
    fun aStartWritesTheInProgressJobBeforeAnythingElse() {
        val files = folder()
        var jobStateWhenDecoding: JobState? = null
        decoder = Decoder { call, sink ->
            jobStateWhenDecoding = metaOf(files).transcription?.job?.state
            sink.onSegment(RawSegment(0, 500, "w$call"))
            DecodeOutcome.Completed
        }

        run(files)

        assertEquals(JobState.IN_PROGRESS, jobStateWhenDecoding)
    }

    @Test
    fun autoLanguageDetectsOnceAndRecordsIt() {
        val files = folder()
        assertEquals(RunOutcome.Completed, run(files, language = "auto"))
        val transcript = (TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success).transcript
        assertEquals("auto", transcript.language.requested)
        assertEquals("de", transcript.language.used)
        assertTrue(transcript.language.detected)
    }

    @Test
    fun aMissingModelGivesFailedModelMissing() {
        installed = null
        val files = folder()

        assertEquals(RunOutcome.Failed(FailureReason.MODEL_MISSING), run(files))

        val job = assertNotNull(metaOf(files).transcription?.job)
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.MODEL_MISSING, job.failureReason)
        assertEquals(0, decoder.decodes)
    }

    @Test
    fun unreadableAudioGivesAudioUnreadable() {
        for (audio in listOf(null, ByteArray(10), ByteArray(100) { 7 })) {
            val files = folder(audio = audio)
            assertEquals(RunOutcome.Failed(FailureReason.AUDIO_UNREADABLE), run(files), "audio=${audio?.size}")
            assertEquals(FailureReason.AUDIO_UNREADABLE, metaOf(files).transcription?.job?.failureReason)
        }
    }

    @Test
    fun aWriteFailureGivesStorageFullAndKeepsTheWorkFile() {
        val files = folder()
        decoder = Decoder { call, sink ->
            sink.onSegment(RawSegment(0, 500, "w$call"))
            files.failAt(app.meetingrecorder.storage.FolderStep.APPEND) // the next work-file append fails
            DecodeOutcome.Completed
        }

        val outcome = run(files)

        assertEquals(RunOutcome.Failed(FailureReason.STORAGE_FULL), outcome)
        assertTrue("transcript.work.jsonl" in files.names())
        val job = assertNotNull(metaOf(files).transcription?.job)
        assertEquals(FailureReason.STORAGE_FULL, job.failureReason)
        assertTrue(job.resumable, "the header and earlier lines are intact")
    }

    @Test
    fun aDecoderExceptionGivesError() {
        decoder = Decoder { _, _ -> throw IllegalStateException("boom") }
        val files = folder()

        assertEquals(RunOutcome.Failed(FailureReason.ERROR), run(files))
        assertEquals(FailureReason.ERROR, metaOf(files).transcription?.job?.failureReason)
    }

    @Test
    fun aDecoderLoadFailureGivesModelInvalid() {
        loadFailure = DecoderLoadException(FailureReason.MODEL_INVALID, "bad file")
        val files = folder()

        assertEquals(RunOutcome.Failed(FailureReason.MODEL_INVALID), run(files))
        assertEquals(FailureReason.MODEL_INVALID, metaOf(files).transcription?.job?.failureReason)
    }

    @Test
    fun aRecordingReportedRightBeforeTheModelIsLoadedEndsTheRunAndKeepsTheWorkFile() {
        recording = true
        val files = folder()

        assertEquals(RunOutcome.Failed(FailureReason.RECORDING_STARTED), run(files))

        val job = assertNotNull(metaOf(files).transcription?.job)
        assertEquals(FailureReason.RECORDING_STARTED, job.failureReason)
        assertEquals(0, decoder.decodes)
    }

    @Test
    fun aRecoveredMeetingIsAcceptedButOneThatIsStillRecordingIsRefused() {
        assertEquals(RunOutcome.Completed, run(folder(meeting = meeting(MeetingStatus.RECOVERED))))

        val recordingFolder = folder(meeting = meeting(MeetingStatus.RECORDING))
        assertEquals(RunOutcome.MeetingStillRecording, run(recordingFolder))
        assertNull(metaOf(recordingFolder).transcription, "nothing was written")
    }

    @Test
    fun anUnknownOrUnreadableMeetingIsNotFound() {
        assertEquals(RunOutcome.MeetingNotFound, runBlocking {
            runner(folder()).run(TranscriptionRequest("other", "tiny", "de", Mode.START), Stop(), listener)
        })
        assertEquals(RunOutcome.MeetingNotFound, run(folder(meeting = null)))
    }

    @Test
    fun cancelAbortsFreesTheDecoderDeletesTheWorkFileAndRemovesTheJob() {
        val stop = Stop()
        val files = folder()
        decoder = Decoder { call, sink ->
            sink.onSegment(RawSegment(0, 500, "w$call"))
            stop.cancelled = true // the user taps Cancel while the engine is decoding
            DecodeOutcome.Aborted
        }

        assertEquals(RunOutcome.Cancelled, run(files, stop))

        assertTrue(decoder.closed)
        assertFalse("transcript.work.jsonl" in files.names())
        assertNull(metaOf(files).transcription, "the job block is gone and no empty block remains")
        assertFalse("transcript.json" in files.names())
    }

    @Test
    fun cancelLeavesACommittedTranscriptUntouched() {
        val committed = MeetingEditor.commitTranscript(
            meeting(),
            app.meetingrecorder.core.model.TranscriptInfo("tiny", "Tiny", "de", "de", "2026-11-16T12:00:00+01:00", 1000, 3),
        )
        val files = folder(meeting = committed, extra = mapOf("transcript.json" to "COMMITTED", "transcript.md" to "COMMITTED MD"))
        val stop = Stop()
        decoder = Decoder { _, _ ->
            stop.cancelled = true
            DecodeOutcome.Aborted
        }

        assertEquals(RunOutcome.Cancelled, run(files, stop, mode = Mode.RESTART))

        assertEquals("COMMITTED", files.text("transcript.json"))
        assertEquals("COMMITTED MD", files.text("transcript.md"))
        val block = assertNotNull(metaOf(files).transcription)
        assertNotNull(block.transcript)
        assertNull(block.job)
    }

    @Test
    fun stoppedForARecordingKeepsTheWorkFileAndWritesAResumableFailure() {
        val stop = Stop(StopReason.RECORDING_STARTED)
        val files = folder()
        decoder = Decoder { call, sink ->
            sink.onSegment(RawSegment(0, 500, "w$call"))
            stop.cancelled = true
            DecodeOutcome.Aborted
        }

        assertEquals(RunOutcome.Stopped(FailureReason.RECORDING_STARTED), run(files, stop))

        assertTrue("transcript.work.jsonl" in files.names())
        val job = assertNotNull(metaOf(files).transcription?.job)
        assertEquals(FailureReason.RECORDING_STARTED, job.failureReason)
        assertTrue(job.resumable)
        assertTrue(decoder.closed)
    }

    @Test
    fun aSystemStopIsRecordedAsInterruptedAndResumable() {
        val stop = Stop(StopReason.SYSTEM)
        val files = folder()
        decoder = Decoder { _, _ ->
            stop.cancelled = true
            DecodeOutcome.Aborted
        }

        assertEquals(RunOutcome.Stopped(FailureReason.INTERRUPTED), run(files, stop))
        assertEquals(FailureReason.INTERRUPTED, metaOf(files).transcription?.job?.failureReason)
    }

    @Test
    fun abortReachesTheRunningDecoder() {
        val files = folder()
        val runner = runner(files)
        decoder = Decoder { _, _ ->
            runner.abort()
            DecodeOutcome.Aborted
        }
        runBlocking { runner.run(TranscriptionRequest("m1", "tiny", "de", Mode.START), Stop(), listener) }
        assertTrue(decoder.aborted)
    }

    @Test
    fun aResumeContinuesAfterTheLastWindowOfTheWorkFileAndATornTailIsCut() {
        val header = WorkRecord.Header(
            modelId = "tiny", modelSha256 = sha, requestedLanguage = "de", vad = false, sampleRate = 16_000,
            durationFrames = 20 * 16_000L, engineVersion = "1.9.5", pipeline = 1, startedAt = "2026-11-17T08:50:00+01:00",
        )
        val records = listOf(
            header,
            WorkRecord.Language("de", null, emptyList()),
            WorkRecord.Window(0, 10 * 16_000L, 3000, listOf(WorkSegment(0, 80_000, "earlier text"))),
        )
        val torn = records.joinToString("") { WorkFile.encodeLine(it) } + "{\"t\":\"window\",\"fromFr"
        val files = folder(
            meeting = MeetingEditor.failJob(MeetingEditor.startJob(meeting(), "tiny", "de", now), FailureReason.INTERRUPTED, null, true),
            extra = mapOf("transcript.work.jsonl" to torn),
        )

        assertEquals(RunOutcome.Completed, run(files, mode = Mode.RESUME))

        val transcript = (TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success).transcript
        assertEquals("earlier text", transcript.segments.first().text)
        assertTrue(transcript.segments.size >= 2, "the resumed window added its text")
        assertEquals(1, decoder.decodes)
    }

    @Test
    fun aResumeThatDoesNotMatchTheRequestFails() {
        val header = WorkRecord.Header(
            modelId = "tiny", modelSha256 = "d".repeat(64), requestedLanguage = "de", vad = false, sampleRate = 16_000,
            durationFrames = 20 * 16_000L, engineVersion = "1.9.5", pipeline = 1, startedAt = "2026-11-17T08:50:00+01:00",
        )
        val files = folder(extra = mapOf("transcript.work.jsonl" to WorkFile.encodeLine(header)))

        val outcome = run(files, mode = Mode.RESUME)

        assertIs<RunOutcome.Failed>(outcome)
        assertEquals(0, decoder.decodes)
    }

    @Test
    fun aRestartThrowsTheOldWorkFileAway() {
        val header = WorkRecord.Header(
            modelId = "tiny", modelSha256 = sha, requestedLanguage = "de", vad = false, sampleRate = 16_000,
            durationFrames = 20 * 16_000L, engineVersion = "1.9.5", pipeline = 1, startedAt = "2026-11-17T08:50:00+01:00",
        )
        val old = listOf(header, WorkRecord.Language("de", null, emptyList()), WorkRecord.Window(0, 320_000, 1, listOf(WorkSegment(0, 80_000, "stale text"))))
        val files = folder(extra = mapOf("transcript.work.jsonl" to old.joinToString("") { WorkFile.encodeLine(it) }))

        assertEquals(RunOutcome.Completed, run(files, mode = Mode.RESTART))

        assertFalse(files.text("transcript.json")!!.contains("stale text"))
    }

    @Test
    fun theAudioFileIsOnlyOpenedForReading() {
        val files = folder()
        val before = files.bytes("audio.wav")!!.copyOf()

        run(files)

        assertTrue(before.contentEquals(files.bytes("audio.wav")!!))
        assertTrue(files.operations.none { "audio.wav" in it })
    }

    @Test
    fun aCommitFailureAfterTheDecodingGivesStorageFullAndKeepsTheCompleteWorkFile() {
        val files = folder()
        decoder = Decoder { call, sink ->
            sink.onSegment(RawSegment(0, 500, "w$call"))
            // The next staged write is the first step of the commit: transcript.json.part.
            files.failOnNth(app.meetingrecorder.storage.FolderStep.STAGE_PART, 1)
            DecodeOutcome.Completed
        }

        val outcome = run(files)

        assertEquals(RunOutcome.Failed(FailureReason.STORAGE_FULL), outcome)
        assertFalse("transcript.json" in files.names())
        val work = WorkFile.decode(files.text("transcript.work.jsonl")!!.toByteArray())
        assertTrue(WorkFile.isComplete(work.records), "recovery repeats the commit from the complete work file")
        assertEquals(FailureReason.STORAGE_FULL, metaOf(files).transcription?.job?.failureReason)
    }
}
