package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.TestTranscripts
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcription.WorkRecord.End
import app.meetingrecorder.core.transcription.WorkRecord.Header
import app.meetingrecorder.core.transcription.WorkRecord.Language
import app.meetingrecorder.core.transcription.WorkRecord.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkFileTest {

    private val bytes: ByteArray = checkNotNull(WorkFileTest::class.java.getResourceAsStream("/golden/transcript.work.jsonl")).readBytes()

    private val header = Header(
        modelId = "small-q5_1",
        modelSha256 = TestTranscripts.SHA,
        requestedLanguage = "auto",
        vad = false,
        sampleRate = 48_000,
        durationFrames = 960_000,
        engineVersion = "1.9.5",
        pipeline = 1,
        startedAt = "2026-11-16T22:30:50+01:00",
    )
    private val language = Language("de", 0.97, listOf(96_000, 384_000, 672_000))
    private val window1 = Window(
        0, 576_000, 8_000,
        listOf(
            WorkSegment(0, 201_600, "Guten Morgen zusammen, wir fangen mit dem Rückblick an."),
            WorkSegment(201_600, 470_400, "Die Zahlen aus dem letzten Quartal sehen gut aus."),
        ),
    )
    private val window2 = Window(
        576_000, 960_000, 6_200,
        listOf(
            WorkSegment(576_000, 744_000, "Hier auf dem Whiteboard sieht man die neue Planung."),
            WorkSegment(744_000, 912_000, "Gibt es dazu Fragen?"),
        ),
    )
    private val end = End(4, 14_200)
    private val all = listOf(header, language, window1, window2, end)

    @Test
    fun `the sample decodes to the five records`() {
        val decoded = WorkFile.decode(bytes)
        assertEquals(all, decoded.records)
        assertEquals(bytes.size, decoded.goodLength)
        assertEquals(false, decoded.torn)
    }

    @Test
    fun `encoding each record reproduces the sample line by line and every line ends with a newline`() {
        val encoded = all.joinToString("") { WorkFile.encodeLine(it) }
        assertEquals(bytes.toString(Charsets.UTF_8), encoded)
        for (record in all) assertTrue(WorkFile.encodeLine(record).endsWith("\n"))
        assertEquals(1, WorkFile.encodeLine(window1).count { it == '\n' })
    }

    @Test
    fun `truncating the sample at every byte offset yields exactly the complete lines before it`() {
        val lineEnds = bytes.indices.filter { bytes[it] == '\n'.code.toByte() }.map { it + 1 }
        for (cut in 0..bytes.size) {
            val decoded = WorkFile.decode(bytes.copyOf(cut))
            val complete = lineEnds.count { it <= cut }
            val goodLength = if (complete == 0) 0 else lineEnds[complete - 1]
            assertEquals(all.take(complete), decoded.records, "records at $cut")
            assertEquals(goodLength, decoded.goodLength, "goodLength at $cut")
            assertEquals(cut > goodLength, decoded.torn, "torn at $cut")
        }
    }

    @Test
    fun `a line that does not parse ends the read`() {
        val text = bytes.toString(Charsets.UTF_8).lines()
        val broken = (text.take(3) + "{\"t\":\"window\",\"fromFrame\":oops}" + text[3] + "").joinToString("\n").toByteArray()
        val decoded = WorkFile.decode(broken)
        assertEquals(all.take(3), decoded.records)
        assertEquals(text.take(3).sumOf { it.toByteArray().size + 1 }, decoded.goodLength)
        assertTrue(decoded.torn)
    }

    @Test
    fun `a file that does not start with a header yields nothing`() {
        val noHeader = WorkFile.encodeLine(language).toByteArray() + WorkFile.encodeLine(window1).toByteArray()
        val decoded = WorkFile.decode(noHeader)
        assertEquals(emptyList(), decoded.records)
        assertEquals(0, decoded.goodLength)
        assertTrue(decoded.torn)
        assertEquals(WorkFile.Decoded(emptyList(), 0, false), WorkFile.decode(ByteArray(0)))
    }

    @Test
    fun `an unknown record type or version ends the read`() {
        val future = (WorkFile.encodeLine(header).replace("\"v\":1", "\"v\":2")).toByteArray()
        assertEquals(emptyList(), WorkFile.decode(future).records)
        val unknown = WorkFile.encodeLine(header).toByteArray() + "{\"t\":\"novel\"}\n".toByteArray()
        val decoded = WorkFile.decode(unknown)
        assertEquals(listOf<WorkRecord>(header), decoded.records)
        assertTrue(decoded.torn)
    }

    @Test
    fun `the window elapsed times sum to the processing time`() {
        assertEquals(14_200, WorkFile.elapsedMs(all))
        assertEquals(8_000, WorkFile.elapsedMs(all.take(3)))
        assertEquals(0, WorkFile.elapsedMs(listOf(header)))
    }

    @Test
    fun `the work file is complete only with an end line`() {
        assertTrue(WorkFile.isComplete(all))
        assertTrue(!WorkFile.isComplete(all.dropLast(1)))
    }

    @Test
    fun `the checkpoint to resume from is the next frame of the last window`() {
        assertEquals(0L, WorkFile.resumeFrame(listOf(header, language)))
        assertEquals(576_000L, WorkFile.resumeFrame(listOf(header, language, window1)))
        assertEquals(960_000L, WorkFile.resumeFrame(all))
    }

    private val request = WorkFile.ResumeRequest(
        requestedLanguage = "auto", vad = false, sampleRate = 48_000, durationFrames = 960_000, pipeline = 1,
    )

    @Test
    fun `resume is allowed when everything matches and the model is installed`() {
        assertEquals(WorkFile.ResumeCheck.Allowed, WorkFile.canResume(header, request, TestTranscripts.SHA))
    }

    @Test
    fun `resume names the mismatching field`() {
        fun mismatch(r: WorkFile.ResumeRequest = request, sha: String? = TestTranscripts.SHA) =
            (WorkFile.canResume(header, r, sha) as WorkFile.ResumeCheck.Mismatch).field
        assertEquals("model", mismatch(sha = null))
        assertEquals("modelSha256", mismatch(sha = "f".repeat(64)))
        assertEquals("requestedLanguage", mismatch(request.copy(requestedLanguage = "de")))
        assertEquals("vad", mismatch(request.copy(vad = true)))
        assertEquals("sampleRate", mismatch(request.copy(sampleRate = 44_100)))
        assertEquals("durationFrames", mismatch(request.copy(durationFrames = 960_001)))
        assertEquals("pipeline", mismatch(request.copy(pipeline = 2)))
    }

    @Test
    fun `toTranscript numbers the segments in order and carries language and processing time`() {
        val transcript = WorkFile.toTranscript(all, "Small (q5_1)", ModelOrigin.CATALOG, "2026-11-16T22:31:08+01:00")
        assertEquals(TestTranscripts.german, transcript)
        assertEquals(listOf(0, 1, 2, 3), transcript.segments.map { it.id })
    }

    @Test
    fun `toTranscript collapses runaway repetitions and handles no speech`() {
        val loop = Window(0, 960_000, 100, (0 until 6).map { WorkSegment(it * 1000L, it * 1000L + 900, "Thank you.") })
        val collapsed = WorkFile.toTranscript(listOf(header, language, loop, End(6, 100)), "m", ModelOrigin.IMPORTED, "2026-11-16T22:31:08+01:00")
        assertEquals(listOf(WorkSegment(0, 5_900, "Thank you.")), collapsed.segments.map { WorkSegment(it.startFrame, it.endFrame, it.text) })
        val silent = WorkFile.toTranscript(listOf(header, language, Window(0, 960_000, 50, emptyList()), End(0, 50)), "m", ModelOrigin.CATALOG, "2026-11-16T22:31:08+01:00")
        assertEquals(emptyList(), silent.segments)
        assertEquals(50, silent.processing!!.durationMs)
    }

    @Test
    fun `toTranscript needs a header and a language line`() {
        assertFailsWith<IllegalArgumentException> { WorkFile.toTranscript(listOf(header), "m", ModelOrigin.CATALOG, "2026-11-16T22:31:08+01:00") }
        assertFailsWith<IllegalArgumentException> { WorkFile.toTranscript(emptyList(), "m", ModelOrigin.CATALOG, "2026-11-16T22:31:08+01:00") }
    }

    @Test
    fun `a null confidence is written as null and read back`() {
        val record = Language("en", null, emptyList())
        val line = WorkFile.encodeLine(record)
        assertEquals("{\"t\":\"language\",\"code\":\"en\",\"confidence\":null,\"probes\":[]}\n", line)
        assertIs<Language>(WorkFile.decode((WorkFile.encodeLine(header) + line).toByteArray()).records.last())
    }
}
