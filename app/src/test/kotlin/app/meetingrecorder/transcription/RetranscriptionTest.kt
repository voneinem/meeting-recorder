package app.meetingrecorder.transcription

import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcription.DecodeOutcome
import app.meetingrecorder.core.transcription.DecodeRequest
import app.meetingrecorder.core.transcription.DetailHint
import app.meetingrecorder.core.transcription.LanguageProbability
import app.meetingrecorder.core.transcription.PcmWindow
import app.meetingrecorder.core.transcription.RawSegment
import app.meetingrecorder.core.transcription.SegmentSink
import app.meetingrecorder.core.transcription.SpeechDecoder
import app.meetingrecorder.core.transcription.StatusResolver
import app.meetingrecorder.core.transcription.TranscriptState
import app.meetingrecorder.core.transcription.TranscriptionStatus
import app.meetingrecorder.core.transcription.WorkFileState
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.recording.RecordingCheck
import app.meetingrecorder.storage.FakeFolderFiles
import app.meetingrecorder.storage.FolderStep
import app.meetingrecorder.storage.MeetingMetadataStore
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Re-transcription (FR-033, FR-034): the old transcript stays complete and unchanged until the new one is complete. */
class RetranscriptionTest {
    private val sha = "9".repeat(64)
    private val now = OffsetDateTime.parse("2026-11-17T09:00:00+01:00")
    private val model = InstalledModel("tiny", "Tiny", ModelOrigin.CATALOG, sha, 1000, ModelLanguages.MULTILINGUAL, "x")
    private val meeting: MeetingMetadata = MeetingEditor.finish(
        MeetingEditor.create("Weekly", OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), 16_000, SourceInfo("Built-in", SourceType.BUILTIN, 1)),
        20 * 16_000L,
    )

    private class Decoder(private val text: String, private val during: () -> Unit = {}, private val fail: Boolean = false, private val stop: (() -> Unit)? = null) : SpeechDecoder {
        override fun detectLanguage(window: PcmWindow) = listOf(LanguageProbability("de", 1.0))
        override fun decode(window: PcmWindow, request: DecodeRequest, sink: SegmentSink): DecodeOutcome {
            during()
            if (fail) throw IllegalStateException("boom")
            sink.onSegment(RawSegment(0, 500, text))
            if (stop != null) {
                stop()
                return DecodeOutcome.Aborted
            }
            return DecodeOutcome.Completed
        }
        override fun abort() = Unit
        override fun close() = Unit
    }

    private class Stop(val reason0: StopReason) : StopSignal {
        @Volatile var cancelled = false
        override val reason get() = reason0
        override fun isCancelled() = cancelled
    }

    private val listener = object : RunListener {
        override fun onPhase(phase: JobPhase) = Unit
        override fun onAnchor(framesDone: Long, durationFrames: Long, wallClockMs: Long) = Unit
    }

    private fun audio(): ByteArray {
        val data = ByteArray(20 * 16_000 * 2) { if (it % 2 == 0) (it / 2 % 50).toByte() else 0 }
        return WavHeader.build(16_000, 1, data.size.toLong()) + data
    }

    private fun store(files: FakeFolderFiles) = MeetingMetadataStore(warn = { _, _ -> }) { if (it == "m1") files else null }

    private fun run(files: FakeFolderFiles, decoder: SpeechDecoder, mode: Mode = Mode.RESTART, stop: StopSignal = Stop(StopReason.USER_CANCEL)): RunOutcome = runBlocking {
        TranscriptionJobRunner(
            folderFiles = { if (it == "m1") files else null },
            metadata = store(files),
            models = { model.takeIf { m -> m.id == it } },
            decoders = DecoderFactory { _, _ -> decoder },
            recordingCheck = RecordingCheck { false },
            threads = 2, engineVersion = "1.9.5", deviceLanguage = { "de" }, vad = { false },
            clock = { System.nanoTime() / 1_000_000 }, now = { now },
        ).run(TranscriptionRequest("m1", "tiny", "de", mode), stop, listener)
    }

    /** A folder with a committed first transcript ("old text"). */
    private fun transcribed(): FakeFolderFiles {
        val files = FakeFolderFiles(mapOf("meeting.json" to MeetingJson.encode(meeting)))
        files.putBytes("audio.wav", audio())
        assertEquals(RunOutcome.Completed, run(files, Decoder("old text"), Mode.START))
        return files
    }

    private fun metaOf(files: FakeFolderFiles) = (MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting

    private fun status(files: FakeFolderFiles, live: Boolean = false) = StatusResolver.resolve(
        block = metaOf(files).transcription,
        transcript = if ("transcript.json" in files.names()) TranscriptState.VALID else TranscriptState.MISSING,
        work = when {
            "transcript.work.jsonl" !in files.names() -> WorkFileState.NONE
            else -> WorkFileState.INCOMPLETE
        },
        live = live,
    )

    private fun oldTranscriptIsIntact(files: FakeFolderFiles, jsonBefore: String, mdBefore: String) {
        assertEquals(jsonBefore, files.text("transcript.json"))
        assertEquals(mdBefore, files.text("transcript.md"))
        assertNotNull(metaOf(files).transcription?.transcript, "the transcript block stays")
    }

    @Test
    fun whileTheNewRunGoesOnTheOldTranscriptIsByteIdenticalAndStillReadable() {
        val files = transcribed()
        val jsonBefore = files.text("transcript.json")!!
        var seen: String? = null
        var liveStatus: TranscriptionStatus? = null
        val during = {
            seen = files.text("transcript.json")
            liveStatus = status(files, live = true).status
        }

        assertEquals(RunOutcome.Completed, run(files, Decoder("new text", during)))

        assertEquals(jsonBefore, seen, "transcript.json is byte-identical while the new run decodes")
        assertEquals(TranscriptionStatus.InProgress, liveStatus)
        assertTrue(TranscriptJson.decode(seen!!) is TranscriptJson.DecodeResult.Success, "and still readable")
    }

    @Test
    fun aCancelLeavesTheOldTranscriptCompleteAndTheStatusDone() {
        val files = transcribed()
        val jsonBefore = files.text("transcript.json")!!
        val mdBefore = files.text("transcript.md")!!
        val stop = Stop(StopReason.USER_CANCEL)

        assertEquals(RunOutcome.Cancelled, run(files, Decoder("new text", stop = { stop.cancelled = true }), stop = stop))

        oldTranscriptIsIntact(files, jsonBefore, mdBefore)
        assertEquals(TranscriptionStatus.Done, status(files).status)
        assertNull(metaOf(files).transcription?.job)
    }

    @Test
    fun aFailureLeavesTheOldTranscriptAndShowsTheReTranscriptionHint() {
        val files = transcribed()
        val jsonBefore = files.text("transcript.json")!!
        val mdBefore = files.text("transcript.md")!!

        assertEquals(RunOutcome.Failed(FailureReason.ERROR), run(files, Decoder("new text", fail = true)))

        oldTranscriptIsIntact(files, jsonBefore, mdBefore)
        val resolved = status(files)
        assertEquals(TranscriptionStatus.Done, resolved.status, "the list still says done")
        assertEquals(DetailHint.JobFailed(FailureReason.ERROR, resumable = false), resolved.hint.let { it as? DetailHint.JobFailed }?.copy(resumable = false))
        assertEquals(JobState.FAILED, metaOf(files).transcription?.job?.state)
    }

    @Test
    fun aKillLeavesTheOldTranscriptAndRecoveryMarksTheRunInterrupted() {
        val files = transcribed()
        val jsonBefore = files.text("transcript.json")!!
        val mdBefore = files.text("transcript.md")!!
        // A kill gives no chance to write: the job block says in-progress and the work file has only its header.
        var killedDuringDecode = false
        val during = { killedDuringDecode = true }
        run(files, Decoder("new text", during, stop = { }), stop = Stop(StopReason.SYSTEM).also { it.cancelled = false })
        assertTrue(killedDuringDecode)
        // Put the folder into the state a kill leaves behind.
        files.put("meeting.json", MeetingJson.encode(MeetingEditor.startJob(metaOf(files), "tiny", "de", now)))
        files.put("transcript.work.jsonl", app.meetingrecorder.core.transcription.WorkFile.encodeLine(
            app.meetingrecorder.core.transcription.WorkRecord.Header("tiny", sha, "de", false, 16_000, 20 * 16_000L, "1.9.5", 1, "x"),
        ))
        files.put("transcript.json", jsonBefore)
        files.put("transcript.md", mdBefore)

        TranscriptionRecovery(
            folderNames = { listOf("m1") }, folderFiles = { files.takeIf { _ -> true } }, metadata = store(files),
            isLive = { false }, cancelWork = {}, modelInfo = { "Tiny" to ModelOrigin.CATALOG }, now = { now },
        ).run()

        oldTranscriptIsIntact(files, jsonBefore, mdBefore)
        val resolved = status(files)
        assertEquals(TranscriptionStatus.Done, resolved.status)
        assertEquals(FailureReason.INTERRUPTED, (resolved.hint as DetailHint.JobFailed).reason)
    }

    @Test
    fun aSuccessfulRunReplacesAllFilesTogetherAndLeavesNoLeftovers() {
        val files = transcribed()
        val jsonBefore = files.text("transcript.json")!!

        assertEquals(RunOutcome.Completed, run(files, Decoder("new text")))

        val transcript = (TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success).transcript
        assertEquals("new text", transcript.segments.single().text)
        assertTrue(files.text("transcript.json") != jsonBefore)
        assertTrue(files.text("transcript.md")!!.contains("new text"))
        assertFalse(files.text("transcript.md")!!.contains("old text"))
        assertEquals(4, metaOf(files).transcription?.transcript?.let { 4 })
        assertNull(metaOf(files).transcription?.job)
        assertTrue(files.text("CLAUDE.md")!!.contains("## Transcript"))
        assertFalse(files.names().any { it.endsWith(".part") || it == "transcript.work.jsonl" })
    }

    @Test
    fun aCrashBetweenDeleteOldAndRenameEndsWithTheNewTranscriptAfterTheRepeatedCommit() {
        val files = transcribed()
        // The first RENAME of the run is the one of transcript.json in the commit.
        val decoder = Decoder("new text", during = { files.failOnNth(FolderStep.RENAME, 1) })

        val outcome = run(files, decoder)

        assertTrue(outcome is RunOutcome.Failed, "the commit failed at the rename")
        assertTrue("transcript.work.jsonl" in files.names(), "the complete work file is the commit record")
        TranscriptionRecovery(
            folderNames = { listOf("m1") }, folderFiles = { files }, metadata = store(files),
            isLive = { false }, cancelWork = {}, modelInfo = { "Tiny" to ModelOrigin.CATALOG }, now = { now },
        ).run()

        val transcript = (TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success).transcript
        assertEquals("new text", transcript.segments.single().text)
        assertFalse(files.names().any { it.endsWith(".part") || it == "transcript.work.jsonl" })
    }
}
