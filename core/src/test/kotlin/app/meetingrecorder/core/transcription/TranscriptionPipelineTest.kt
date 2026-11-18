package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.audio.UnreadableWavException
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.ModelOrigin
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranscriptionPipelineTest {

    private val sr = 48_000
    private val framesPerCs = sr / 100L

    /** About 25 segments of 1 to 10 s with gaps of 0.5 to 25 s over 20 minutes; every time is a multiple of 10 ms. */
    private val script: List<ScriptedSegment> = run {
        val random = Random(42)
        var cs = 100L
        buildList {
            var i = 0
            while (cs < 118_000) {
                val length = 100L + random.nextInt(900)
                add(ScriptedSegment(cs, cs + length, "segment $i"))
                cs += length + 50 + random.nextInt(2_450)
                i++
            }
        }
    }
    private val durationFrames = 1_200L * sr

    private fun request(
        requested: String = "auto",
        languages: ModelLanguages = ModelLanguages.MULTILINGUAL,
        duration: Long = durationFrames,
    ) = PipelineRequest(
        modelId = "small-q5_1", modelSha256 = "a".repeat(64), modelLanguages = languages, requestedLanguage = requested,
        deviceLanguage = "en", vad = false, threads = 4, engineVersion = "1.9.5",
        startedAt = "2026-11-16T22:30:50+01:00", sampleRate = sr, durationFrames = duration,
    )

    private class RecordingSink : WorkSink {
        val records = mutableListOf<WorkRecord>()
        var failWith: IOException? = null
        var killAfterWindows: Int? = null
        val bytes: ByteArray get() = records.joinToString("") { WorkFile.encodeLine(it) }.toByteArray()

        override fun append(record: WorkRecord) {
            failWith?.let { throw it }
            records += record
        }
    }

    private class Harness(
        script: List<ScriptedSegment>,
        sr: Int,
        windowSeconds: Int = 120,
        rmsOf: (Long, Long) -> Double = { _, _ -> 0.1 },
        throwOnCall: Int? = null,
        failOnCall: Pair<Int, FailureReason>? = null,
        languages: List<LanguageProbability> = listOf(LanguageProbability("de", 0.9), LanguageProbability("en", 0.1)),
    ) {
        val source = FakeWindowSource(sr, rmsOf)
        val decoder = ScriptedDecoder(script, sr, source, languages, throwOnCall, failOnCall)
        val sink = RecordingSink()
        val anchors = mutableListOf<Pair<Long, Long>>()
        var now = 0L
        val pipeline = TranscriptionPipeline(
            source, decoder, sink, { now += 10; now }, { f, t -> anchors += f to t }, WindowPlanner(windowSeconds),
        )
    }

    private fun transcriptOf(records: List<WorkRecord>) =
        WorkFile.toTranscript(records, "Small", ModelOrigin.CATALOG, "2026-11-16T22:31:08+01:00")

    private fun frames(s: ScriptedSegment) = WorkSegment(s.startCs * framesPerCs, s.endCs * framesPerCs, s.text)

    private fun segmentsOf(records: List<WorkRecord>) = transcriptOf(records).segments.map { WorkSegment(it.startFrame, it.endFrame, it.text) }

    @Test
    fun `a scripted transcript is reproduced identically for every window size from 20 s to 600 s`() {
        val expected = script.map(::frames)
        for (seconds in listOf(20, 21, 30, 45, 60, 90, 120, 240, 599, 600)) {
            val h = Harness(script, sr, windowSeconds = seconds)
            val outcome = h.pipeline.run(request(), null, { false })
            assertEquals(PipelineOutcome.Finished, outcome, "window $seconds")
            assertEquals(expected, segmentsOf(h.sink.records), "window $seconds")
            val end = h.sink.records.last() as WorkRecord.End
            assertEquals(expected.size, end.segmentCount)
        }
    }

    @Test
    fun `killing the run after any window and resuming gives the same transcript as an uninterrupted run`() {
        val whole = Harness(script, sr, windowSeconds = 60)
        whole.pipeline.run(request(), null, { false })
        val windowCount = whole.sink.records.count { it is WorkRecord.Window }
        assertTrue(windowCount > 10)
        val expected = whole.sink.records

        for (kill in 0..windowCount) {
            val first = Harness(script, sr, windowSeconds = 60)
            var windows = 0
            val sinkWithKill = WorkSink { record ->
                if (record is WorkRecord.Window && windows == kill) throw KillSwitch()
                if (record is WorkRecord.Window) windows++
                first.sink.append(record)
            }
            val killed = TranscriptionPipeline(first.source, first.decoder, sinkWithKill, { 0 }, { _, _ -> }, WindowPlanner(60))
            runCatching { killed.run(request(), null, { false }) } // the kill is reported as a failure; the file is what counts

            // A torn tail of the next line is left behind, as after a real kill.
            val torn = first.sink.bytes + WorkFile.encodeLine(expected[2 + kill.coerceAtMost(windowCount - 1)]).toByteArray().copyOf(7)
            val decoded = WorkFile.decode(torn)
            val resumed = Harness(script, sr, windowSeconds = 60)
            resumed.sink.records += decoded.records
            val outcome = resumed.pipeline.run(request(), decoded.records, { false })
            assertEquals(PipelineOutcome.Finished, outcome, "kill after $kill windows")
            assertEquals(segmentsOf(expected), segmentsOf(resumed.sink.records), "kill after $kill windows")
            assertEquals(1, resumed.sink.records.count { it is WorkRecord.End })
            assertEquals(1, resumed.sink.records.count { it is WorkRecord.Header })
        }
    }

    private class KillSwitch : IOException("killed")

    @Test
    fun `the language is chosen once, written once and a resumed job decodes in the recorded language`() {
        val h = Harness(script, sr)
        h.pipeline.run(request(), null) { h.sink.records.count { it is WorkRecord.Window } >= 2 }
        assertEquals(1, h.sink.records.count { it is WorkRecord.Language })
        assertEquals(3, h.decoder.detectCalls)
        assertTrue(h.decoder.requests.all { it.language == "de" })

        val before = h.decoder.detectCalls
        val resumed = Harness(script, sr, languages = listOf(LanguageProbability("fr", 1.0)))
        resumed.sink.records += h.sink.records
        resumed.pipeline.run(request(), h.sink.records) { false }
        assertEquals(0, resumed.decoder.detectCalls, "no detection after resume (had $before)")
        assertTrue(resumed.decoder.requests.all { it.language == "de" })
        assertEquals(1, resumed.sink.records.count { it is WorkRecord.Language })
    }

    @Test
    fun `a fixed language or an English-only model skips detection`() {
        val fixed = Harness(script, sr)
        fixed.pipeline.run(request(requested = "de", duration = 300L * sr), null) { false }
        assertEquals(0, fixed.decoder.detectCalls)
        assertEquals(WorkRecord.Language("de", null, emptyList()), fixed.sink.records[1])

        val english = Harness(script, sr)
        english.pipeline.run(request(languages = ModelLanguages.ENGLISH_ONLY, duration = 300L * sr), null) { false }
        assertEquals(0, english.decoder.detectCalls)
        assertEquals("en", (english.sink.records[1] as WorkRecord.Language).code)
    }

    @Test
    fun `without signal in any probe the device language is used`() {
        val h = Harness(script, sr, rmsOf = { _, _ -> 0.0 })
        h.pipeline.run(request(duration = 300L * sr), null) { false }
        assertEquals(0, h.decoder.detectCalls)
        assertEquals(WorkRecord.Language("en", null, emptyList()), h.sink.records[1])
    }

    @Test
    fun `progress anchors are published after every window`() {
        val h = Harness(script, sr, windowSeconds = 120)
        h.pipeline.run(request(), null) { false }
        val windows = h.sink.records.filterIsInstance<WorkRecord.Window>()
        assertEquals(windows.map { it.nextFrame }, h.anchors.map { it.first })
        assertEquals(durationFrames, h.anchors.last().first)
        assertEquals(h.anchors.map { it.second }, h.anchors.map { it.second }.sorted())
    }

    @Test
    fun `cancel stops the run between windows`() {
        val h = Harness(script, sr)
        val outcome = h.pipeline.run(request(), null) { h.sink.records.count { it is WorkRecord.Window } >= 2 }
        assertEquals(PipelineOutcome.Cancelled, outcome)
        assertEquals(2, h.sink.records.count { it is WorkRecord.Window })
        assertTrue(h.sink.records.none { it is WorkRecord.End })
        assertEquals(0, h.decoder.abortCalls)
    }

    @Test
    fun `cancel before the start decodes nothing`() {
        val h = Harness(script, sr)
        assertEquals(PipelineOutcome.Cancelled, h.pipeline.run(request(), null) { true })
        assertEquals(0, h.decoder.windows.size)
    }

    @Test
    fun `cancel inside a decode aborts the decoder and keeps no partial window`() {
        val h = Harness(script, sr)
        // True from the moment the first decode has started: the run is cancelled while the engine works.
        val cancel = CancelSignal { h.decoder.windows.isNotEmpty() }
        val outcome = h.pipeline.run(request(), null, cancel)
        assertEquals(PipelineOutcome.Cancelled, outcome)
        assertEquals(1, h.decoder.abortCalls)
        assertTrue(h.sink.records.none { it is WorkRecord.Window })
        assertTrue(h.sink.records.none { it is WorkRecord.End })
    }

    @Test
    fun `a decoder that throws ends the run with the work file intact up to the last full window`() {
        val h = Harness(script, sr, throwOnCall = 3)
        val outcome = h.pipeline.run(request(), null) { false }
        assertEquals(PipelineOutcome.Failed(FailureReason.ERROR), outcome)
        assertEquals(2, h.sink.records.count { it is WorkRecord.Window })
        assertEquals(h.sink.records, WorkFile.decode(h.sink.bytes).records)
        assertTrue(h.sink.records.none { it is WorkRecord.End })
    }

    @Test
    fun `a decoder failure keeps its reason and a full disk or unreadable audio are mapped`() {
        val oom = Harness(script, sr, failOnCall = 1 to FailureReason.OUT_OF_MEMORY)
        assertEquals(PipelineOutcome.Failed(FailureReason.OUT_OF_MEMORY), oom.pipeline.run(request(), null) { false })

        val full = Harness(script, sr)
        full.sink.failWith = IOException("No space left on device")
        assertEquals(PipelineOutcome.Failed(FailureReason.STORAGE_FULL), full.pipeline.run(request(), null) { false })

        val unreadable = Harness(script, sr)
        unreadable.source.failWith = UnreadableWavException("cut")
        assertEquals(PipelineOutcome.Failed(FailureReason.AUDIO_UNREADABLE), unreadable.pipeline.run(request(), null) { false })
    }

    @Test
    fun `a recording without speech finishes with an empty segment list`() {
        val h = Harness(emptyList(), sr)
        assertEquals(PipelineOutcome.Finished, h.pipeline.run(request(duration = 300L * sr), null) { false })
        assertEquals(emptyList(), segmentsOf(h.sink.records))
        assertEquals(WorkRecord.End(0, WorkFile.elapsedMs(h.sink.records)), h.sink.records.last())
    }

    @Test
    fun `the last window is padded to 1_1 seconds and its segments are clamped to the audio`() {
        val duration = 120L * sr + sr / 2 // 0.5 s remain after one full window
        val h = Harness(listOf(ScriptedSegment(12_000, 12_100, "tail")), sr)
        h.pipeline.run(request(requested = "de", duration = duration), null) { false }
        assertEquals(2, h.decoder.windows.size)
        assertEquals(17_600, h.decoder.windows.last().second)
        val last = h.sink.records.filterIsInstance<WorkRecord.Window>().last()
        assertEquals(duration, last.nextFrame)
        assertEquals(listOf(WorkSegment(12_000 * framesPerCs, duration, "tail")), last.segments)
    }

    @Test
    fun `the repetition guard is applied to the transcript and the end line counts the result`() {
        val loop = (0 until 6).map { ScriptedSegment(1_000L + it * 300, 1_000L + it * 300 + 200, "Thank you.") }
        val h = Harness(loop, sr)
        h.pipeline.run(request(requested = "de", duration = 60L * sr), null) { false }
        assertEquals(1, segmentsOf(h.sink.records).size)
        assertEquals(1, (h.sink.records.last() as WorkRecord.End).segmentCount)
    }

    @Test
    fun `the end line is written exactly once and a complete work file is not extended`() {
        val h = Harness(script, sr)
        h.pipeline.run(request(), null) { false }
        assertEquals(1, h.sink.records.count { it is WorkRecord.End })
        val again = Harness(script, sr)
        again.sink.records += h.sink.records
        assertEquals(PipelineOutcome.Finished, again.pipeline.run(request(), h.sink.records) { false })
        assertEquals(h.sink.records, again.sink.records)
        assertEquals(0, again.decoder.windows.size)
    }

    @Test
    fun `the header records the request and the processing times add up`() {
        val h = Harness(script, sr)
        h.pipeline.run(request(), null) { false }
        val header = assertIs<WorkRecord.Header>(h.sink.records.first())
        assertEquals("small-q5_1", header.modelId)
        assertEquals(TranscriptionPipeline.PIPELINE_VERSION, header.pipeline)
        assertEquals(durationFrames, header.durationFrames)
        val end = h.sink.records.last() as WorkRecord.End
        assertEquals(WorkFile.elapsedMs(h.sink.records), end.elapsedMsTotal)
        assertContentEquals(listOf(true), listOf(end.elapsedMsTotal > 0))
    }
}
