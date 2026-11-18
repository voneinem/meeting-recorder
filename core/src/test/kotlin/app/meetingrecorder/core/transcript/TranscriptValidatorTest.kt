package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.TestTranscripts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptValidatorTest {

    private val base = TestTranscripts.german

    private fun rules(t: Transcript): Set<String> = TranscriptValidator.validate(t).map { it.rule }.toSet()

    private fun withSegments(vararg s: TranscriptSegment) = base.copy(segments = s.toList())

    @Test
    fun `the example transcript is valid`() {
        assertEquals(emptyList(), TranscriptValidator.validate(base))
    }

    @Test
    fun `an empty segment list is valid`() {
        assertEquals(emptyList(), TranscriptValidator.validate(base.copy(segments = emptyList())))
    }

    @Test
    fun `startFrame is at most endFrame is at most durationFrames`() {
        assertTrue("segments.range" in rules(withSegments(TranscriptSegment(0, 500, 400, "a"))))
        assertTrue("segments.range" in rules(withSegments(TranscriptSegment(0, 0, 960_001, "a"))))
        assertTrue("segments.range" in rules(withSegments(TranscriptSegment(0, -1, 10, "a"))))
        assertEquals(emptyList(), TranscriptValidator.validate(withSegments(TranscriptSegment(0, 960_000, 960_000, "a"))))
        assertEquals(emptyList(), TranscriptValidator.validate(withSegments(TranscriptSegment(0, 7, 7, "a"))))
    }

    @Test
    fun `segments are sorted by startFrame, non-decreasing`() {
        assertTrue("segments.order" in rules(withSegments(TranscriptSegment(0, 500, 600, "a"), TranscriptSegment(1, 400, 700, "b"))))
        assertEquals(
            emptyList(),
            TranscriptValidator.validate(withSegments(TranscriptSegment(0, 500, 600, "a"), TranscriptSegment(1, 500, 700, "b"))),
        )
    }

    @Test
    fun `id is 0 to n-1 in file order`() {
        assertTrue("segments.id" in rules(withSegments(TranscriptSegment(1, 0, 10, "a"))))
        assertTrue("segments.id" in rules(withSegments(TranscriptSegment(0, 0, 10, "a"), TranscriptSegment(0, 10, 20, "b"))))
        assertTrue("segments.id" in rules(withSegments(TranscriptSegment(0, 0, 10, "a"), TranscriptSegment(2, 10, 20, "b"))))
    }

    @Test
    fun `text is trimmed and never empty`() {
        assertTrue("segments.text" in rules(withSegments(TranscriptSegment(0, 0, 10, ""))))
        assertTrue("segments.text" in rules(withSegments(TranscriptSegment(0, 0, 10, "   "))))
        assertTrue("segments.text" in rules(withSegments(TranscriptSegment(0, 0, 10, " padded"))))
        assertTrue("segments.text" in rules(withSegments(TranscriptSegment(0, 0, 10, "padded\n"))))
    }

    @Test
    fun `speaker is null and a string is accepted on read`() {
        assertEquals(null, base.segments.first().speaker)
        assertEquals(emptyList(), TranscriptValidator.validate(withSegments(TranscriptSegment(0, 0, 10, "a", speaker = "Anna"))))
    }

    @Test
    fun `language used is a language code and never auto`() {
        assertTrue("language" in rules(base.copy(language = base.language.copy(used = "auto"))))
        assertTrue("language" in rules(base.copy(language = base.language.copy(used = "DE"))))
        assertTrue("language" in rules(base.copy(language = base.language.copy(used = "d"))))
        assertTrue("language" in rules(base.copy(language = base.language.copy(requested = "german"))))
        assertEquals(emptyList(), TranscriptValidator.validate(base.copy(language = base.language.copy(requested = "de", used = "deu", detected = false))))
        assertTrue("language" in rules(base.copy(language = base.language.copy(confidence = 1.5))))
        assertEquals(emptyList(), TranscriptValidator.validate(base.copy(language = base.language.copy(confidence = null))))
    }

    @Test
    fun `model sha256 is 64 lower-case hex digits`() {
        assertTrue("model" in rules(base.copy(model = base.model.copy(sha256 = "abc"))))
        assertTrue("model" in rules(base.copy(model = base.model.copy(sha256 = TestTranscripts.SHA.uppercase()))))
        assertTrue("model" in rules(base.copy(model = base.model.copy(sha256 = TestTranscripts.SHA + "0"))))
        assertTrue("model" in rules(base.copy(model = base.model.copy(id = ""))))
        assertTrue("model" in rules(base.copy(model = base.model.copy(name = ""))))
    }

    @Test
    fun `the engine is whisper dot cpp`() {
        assertTrue("engine" in rules(base.copy(engine = base.engine.copy(name = "other"))))
        assertTrue("engine" in rules(base.copy(engine = base.engine.copy(version = ""))))
    }

    @Test
    fun `header fields are checked`() {
        assertTrue("schemaVersion" in rules(base.copy(schemaVersion = 2)))
        assertTrue("generatedAt" in rules(base.copy(generatedAt = "yesterday")))
        assertTrue("audio" in rules(base.copy(audio = base.audio.copy(sampleRate = 0))))
        assertTrue("audio" in rules(base.copy(audio = base.audio.copy(durationFrames = -1))))
    }
}
