package app.meetingrecorder.transcription

import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.model.TranscriptInfo
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
import app.meetingrecorder.core.transcription.TranscriptionPipeline
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.recording.RecordingCheck
import app.meetingrecorder.storage.FakeFolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TranscriptionResumeTest {
    private val sha = "f".repeat(64)
    private val now = OffsetDateTime.parse("2026-11-17T09:00:00+01:00")
    private val model = InstalledModel("tiny", "Tiny", ModelOrigin.CATALOG, sha, 1000, ModelLanguages.MULTILINGUAL, "x")
    private val seconds = 500 // five windows of at most 120 s
    private val durationFrames = seconds * 16_000L

    private val meeting: MeetingMetadata = MeetingEditor.finish(
        MeetingEditor.create("Weekly", OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), 16_000, SourceInfo("Built-in", SourceType.BUILTIN, 1)),
        durationFrames,
    )

    private val audio: ByteArray by lazy {
        val data = ByteArray(seconds * 16_000 * 2) { if (it % 2 == 0) (it / 2 % 50).toByte() else 0 }
        WavHeader.build(16_000, 1, data.size.toLong()) + data
    }

    private fun folder(meta: MeetingMetadata = meeting, extra: Map<String, String> = emptyMap()) =
        FakeFolderFiles(mapOf("meeting.json" to MeetingJson.encode(meta)) + extra).also { it.putBytes("audio.wav", audio) }

    /** One segment per window whose text carries the window's sample count: windows are equal iff the output is. */
    private class Decoder(private val stopAfter: Int? = null, private val onStop: () -> Unit = {}) : SpeechDecoder {
        var decodes = 0
        override fun detectLanguage(window: PcmWindow) = listOf(LanguageProbability("de", 1.0))
        override fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome {
            decodes++
            sink.onSegment(RawSegment(0, 500, "window of ${window.count} samples"))
            if (stopAfter != null && decodes >= stopAfter) {
                onStop()
                return DecodeOutcome.Aborted
            }
            return DecodeOutcome.Completed
        }
        override fun abort() = Unit
        override fun close() = Unit
    }

    private class Stop(var reason0: StopReason = StopReason.SYSTEM) : StopSignal {
        @Volatile var cancelled = false
        override val reason get() = reason0
        override fun isCancelled() = cancelled
    }

    private val listener = object : RunListener {
        override fun onPhase(phase: JobPhase) = Unit
        override fun onAnchor(framesDone: Long, durationFrames: Long, wallClockMs: Long) = Unit
    }

    private fun run(files: FakeFolderFiles, decoder: SpeechDecoder, mode: Mode, stop: Stop = Stop(), installed: InstalledModel? = model, language: String = "de"): RunOutcome {
        val tick = AtomicLong()
        val runner = TranscriptionJobRunner(
            folderFiles = { if (it == "m1") files else null },
            metadata = MeetingMetadataStore(warn = { _, _ -> }) { if (it == "m1") files else null },
            models = { installed?.takeIf { m -> m.id == it } },
            decoders = DecoderFactory { _, _ -> decoder },
            recordingCheck = RecordingCheck { false },
            threads = 2,
            engineVersion = "1.9.5",
            deviceLanguage = { "de" },
            vad = { false },
            clock = { tick.addAndGet(1_000) }, // every decode "takes" one second
            now = { now },
        )
        return runBlocking { runner.run(TranscriptionRequest("m1", "tiny", language, mode), stop, listener) }
    }

    private fun transcriptOf(files: FakeFolderFiles) =
        (TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success).transcript

    @Test
    fun aRunKilledAfterAnyWindowAndResumedEqualsAnUninterruptedRun() {
        val baselineFiles = folder()
        val baselineDecoder = Decoder()
        assertEquals(RunOutcome.Completed, run(baselineFiles, baselineDecoder, Mode.START))
        val total = baselineDecoder.decodes
        assertTrue(total >= 4, "the audio spans several windows, got $total")
        val baseline = transcriptOf(baselineFiles)

        for (killedAfter in 1 until total) {
            val files = folder()
            val stop = Stop()
            val first = Decoder(stopAfter = killedAfter) { stop.cancelled = true }
            assertEquals(RunOutcome.Stopped(app.meetingrecorder.core.model.FailureReason.INTERRUPTED), run(files, first, Mode.START, stop), "kill after $killedAfter")
            assertTrue("transcript.work.jsonl" in files.names())

            val second = Decoder()
            assertEquals(RunOutcome.Completed, run(files, second, Mode.RESUME), "resume after $killedAfter")

            val resumed = transcriptOf(files)
            assertEquals(baseline.segments, resumed.segments, "segments after a kill after window $killedAfter")
            // The window that was in progress when the run stopped is not in the work file: exactly that one is repeated.
            assertEquals(total - (killedAfter - 1), second.decodes, "windows decoded after the resume (kill after $killedAfter)")
            assertEquals(baseline.processing?.durationMs, resumed.processing?.durationMs, "the processing time of both sessions adds up")
        }
    }

    @Test
    fun aTornWorkFileTailIsTruncatedAndIgnored() {
        val files = folder()
        val stop = Stop()
        run(files, Decoder(stopAfter = 2) { stop.cancelled = true }, Mode.START, stop)
        files.put("transcript.work.jsonl", files.text("transcript.work.jsonl")!! + "{\"t\":\"window\",\"fromFr")

        assertEquals(RunOutcome.Completed, run(files, Decoder(), Mode.RESUME))

        assertEquals(transcriptOf(folder().also { run(it, Decoder(), Mode.START) }).segments, transcriptOf(files).segments)
    }

    @Test
    fun startOverDeletesTheOldWorkFileFirst() {
        val files = folder()
        val stop = Stop()
        run(files, Decoder(stopAfter = 2) { stop.cancelled = true }, Mode.START, stop)

        val decoder = Decoder()
        assertEquals(RunOutcome.Completed, run(files, decoder, Mode.RESTART))

        assertEquals(5, decoder.decodes, "all windows are decoded again")
    }

    private fun interruptedFolder(): FakeFolderFiles {
        val files = folder()
        val stop = Stop()
        run(files, Decoder(stopAfter = 2) { stop.cancelled = true }, Mode.START, stop)
        return files
    }

    @Test
    fun aHeaderMismatchGivesNotPossibleAndOnlyStartOverIsOffered() {
        val files = interruptedFolder()
        fun plan(language: String = "de", sha256: String? = sha, rate: Int = 16_000, frames: Long = durationFrames) =
            ResumePlanner.plan(files, language, sha256, rate, frames)

        assertTrue(plan() is ResumePlan.Ready)
        assertEquals(ResumePlan.NotPossible("modelSha256"), plan(sha256 = "0".repeat(64)))
        assertEquals(ResumePlan.NotPossible("requestedLanguage"), plan(language = "en"))
        assertEquals(ResumePlan.NotPossible("sampleRate"), plan(rate = 48_000))
        assertEquals(ResumePlan.NotPossible("durationFrames"), plan(frames = durationFrames + 1))
    }

    @Test
    fun aDifferentVadSettingOrPipelineVersionIsAMismatch() {
        val header = WorkRecord.Header("tiny", sha, "de", vad = true, sampleRate = 16_000, durationFrames = durationFrames, engineVersion = "1.9.5", pipeline = TranscriptionPipeline.PIPELINE_VERSION, startedAt = "x")
        val vadFiles = folder(extra = mapOf("transcript.work.jsonl" to WorkFile.encodeLine(header)))
        // the request carries vad = false (the pipeline's current setting): the work file with vad = true does not match
        val plan = WorkFile.canResume(header, WorkFile.ResumeRequest("de", false, 16_000, durationFrames, TranscriptionPipeline.PIPELINE_VERSION), sha)
        assertEquals(WorkFile.ResumeCheck.Mismatch("vad"), plan)
        assertTrue(ResumePlanner.plan(vadFiles, "de", sha, 16_000, durationFrames) is ResumePlan.Ready, "the planner takes vad from the header")

        val oldPipeline = header.copy(vad = false, pipeline = TranscriptionPipeline.PIPELINE_VERSION - 1)
        val oldFiles = folder(extra = mapOf("transcript.work.jsonl" to WorkFile.encodeLine(oldPipeline)))
        assertEquals(ResumePlan.NotPossible("pipeline"), ResumePlanner.plan(oldFiles, "de", sha, 16_000, durationFrames))
    }

    @Test
    fun aDeletedModelMeansOnlyStartOver() {
        val files = interruptedFolder()
        assertEquals(ResumePlan.NotPossible("model"), ResumePlanner.plan(files, "de", null, 16_000, durationFrames))
    }

    @Test
    fun noWorkFileMeansNotPossible() {
        assertEquals(ResumePlan.NotPossible("workFile"), ResumePlanner.plan(folder(), "de", sha, 16_000, durationFrames))
    }

    @Test
    fun theResumePlanCarriesTheStartFrameAndTheElapsedTime() {
        val files = interruptedFolder()
        val plan = ResumePlanner.plan(files, "de", sha, 16_000, durationFrames) as ResumePlan.Ready
        assertTrue(plan.startFrame in 1 until durationFrames)
        assertEquals(1_000L, plan.elapsedBeforeMs, "the stop came during the second window: one finished window of one second")
    }

    @Test
    fun dismissRemovesTheWorkFileAndTheJobButKeepsTheCommittedTranscript() {
        val committed = MeetingEditor.commitTranscript(meeting, TranscriptInfo("tiny", "Tiny", "de", "de", "2026-11-16T12:00:00+01:00", 1000, 3))
        val files = folder(meta = MeetingEditor.failJob(MeetingEditor.startJob(committed, "tiny", "de", now), app.meetingrecorder.core.model.FailureReason.INTERRUPTED, null, true),
            extra = mapOf("transcript.work.jsonl" to "x", "transcript.json" to "COMMITTED"))

        assertTrue(JobDismissal.dismiss(files, MeetingMetadataStore(warn = { _, _ -> }) { if (it == "m1") files else null }, "m1"))

        assertFalse("transcript.work.jsonl" in files.names())
        assertEquals("COMMITTED", files.text("transcript.json"))
        val block = assertNotNull((MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting.transcription)
        assertNotNull(block.transcript)
        assertNull(block.job)
    }
}
