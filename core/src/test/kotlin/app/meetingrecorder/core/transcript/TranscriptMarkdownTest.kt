package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.TestTranscripts
import app.meetingrecorder.core.model.Photo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptMarkdownTest {

    private val hash = "0123456789abcdef".repeat(4)
    private val meeting = TestTranscripts.germanMeeting
    private val transcript = TestTranscripts.german

    private fun generate(m: app.meetingrecorder.core.model.MeetingMetadata = meeting, t: Transcript = transcript) =
        TranscriptMarkdown.generate(m, t, hash)

    @Test
    fun `output equals the golden bytes`() {
        assertEquals(TestMeetings.resource("/golden/transcript-german.md"), generate())
    }

    @Test
    fun `the same input gives the same bytes`() {
        assertEquals(generate(), generate())
    }

    @Test
    fun `detected appears only when the language was detected`() {
        assertTrue("language de (detected)" in generate())
        val fixed = transcript.copy(language = transcript.language.copy(requested = "de", detected = false))
        assertTrue("language de ·" in generate(t = fixed))
        assertTrue("(detected)" !in generate(t = fixed))
    }

    @Test
    fun `positions use mm ss and h mm ss from one hour on`() {
        val long = transcript.copy(
            audio = transcript.audio.copy(durationFrames = 48_000L * 7_300),
            segments = listOf(
                TranscriptSegment(0, 48_000L * 59 + 47_999, 48_000L * 60, "before the minute"),
                TranscriptSegment(1, 48_000L * 3_599, 48_000L * 3_600, "last second of the hour"),
                TranscriptSegment(2, 48_000L * 3_600, 48_000L * 3_700, "first of the second hour"),
                TranscriptSegment(3, 48_000L * 7_265, 48_000L * 7_270, "late"),
            ),
        )
        val text = generate(meeting.copy(photos = emptyList()), long)
        assertTrue("**[00:59]** before the minute" in text)
        assertTrue("**[59:59]** last second of the hour" in text)
        assertTrue("**[1:00:00]** first of the second hour" in text)
        assertTrue("**[2:01:05]** late" in text)
    }

    @Test
    fun `a photo uses its own position`() {
        val m = meeting.copy(photos = listOf(Photo("photos/photo-001.jpg", 48_000L * 3_725, 3_725_000)))
        assertTrue("![Photo 1 (1:02:05)](photos/photo-001.jpg)" in generate(m, transcript.copy(segments = emptyList())))
    }

    @Test
    fun `blocks are separated by one blank line and the file ends with one newline`() {
        val text = generate()
        assertTrue(text.endsWith(")\n"))
        assertTrue(!text.endsWith("\n\n"))
        assertTrue("\n\n\n" !in text)
    }

    @Test
    fun `no speech gives the single line after the header comment`() {
        val text = generate(t = transcript.copy(segments = emptyList()), m = meeting.copy(photos = emptyList()))
        assertEquals(
            "# Weekly sync\n\n" +
                "*Transcript · model Small (q5_1) · language de (detected) · 2026-11-16 · whisper.cpp 1.9.5*\n\n" +
                "<!-- generated-from: transcript.json sha256=$hash -->\n\n" +
                "*No speech was detected.*\n",
            text,
        )
    }

    @Test
    fun `a title with Markdown characters is written verbatim`() {
        val text = generate(meeting.copy(title = "Q3 *plan* [draft] #1"))
        assertTrue(text.startsWith("# Q3 *plan* [draft] #1\n"))
    }

    @Test
    fun `the date is the local date of generatedAt`() {
        val late = transcript.copy(generatedAt = "2026-12-31T23:59:59-05:00")
        assertTrue("· 2026-12-31 ·" in generate(t = late))
    }
}
