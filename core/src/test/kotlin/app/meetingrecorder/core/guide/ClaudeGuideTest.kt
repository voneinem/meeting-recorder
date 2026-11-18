package app.meetingrecorder.core.guide

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.model.MeetingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeGuideTest {

    @Test
    fun `mono guide matches the golden file`() {
        assertEquals(TestMeetings.resource("/golden/claude-guide-mono.md"), ClaudeGuide.generate(TestMeetings.mono))
    }

    @Test
    fun `stereo guide with switch photos and dropout matches the golden file`() {
        assertEquals(
            TestMeetings.resource("/golden/claude-guide-stereo-switch-photos.md"),
            ClaudeGuide.generate(TestMeetings.stereo),
        )
    }

    @Test
    fun `the guide has the required sections in English`() {
        val guide = ClaudeGuide.generate(TestMeetings.stereo)
        listOf(
            "# Weekly sync",
            "## Folder contents",
            "## How to read the timeline",
            "## Channels",
            "## Photos",
            "## Suggested follow-up tasks",
            "## Format reference",
        ).forEach { assertTrue(it in guide, "missing: $it") }
        assertTrue("48000 Hz" in guide)
        assertTrue("No transcript has been created for this meeting yet." in guide)
        assertFalse("Transcription is not part of this folder yet" in guide)
    }

    @Test
    fun `a meeting with a transcript gets the transcript section and matches the golden file`() {
        assertEquals(
            TestMeetings.resource("/golden/claude-guide-mono-transcript.md"),
            ClaudeGuide.generate(TestMeetings.monoTranscribed),
        )
    }

    @Test
    fun `the transcript section lists its five items in the contract order`() {
        val guide = ClaudeGuide.generate(TestMeetings.monoTranscribed)
        val order = listOf(
            "1. **Files**", "2. **Fields of `transcript.json`**", "3. **Photos in the text**",
            "4. **The `transcription` block of `meeting.json`**", "5. **Suggested follow-up tasks**",
        ).map { guide.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "missing item: $order")
        assertEquals(order.sorted(), order)
        assertFalse("No transcript has been created" in guide)
        assertTrue("`speaker` (always `null` for now)" in guide)
        assertTrue("names and numbers may be wrong" in guide)
    }

    @Test
    fun `only a committed transcript counts, a job alone does not`() {
        val jobOnly = TestMeetings.monoTranscribed.copy(
            transcription = TestMeetings.monoTranscribed.transcription!!.copy(transcript = null),
        )
        val guide = ClaudeGuide.generate(jobOnly)
        assertTrue("No transcript has been created for this meeting yet." in guide)
        assertFalse("## Transcript" in guide)
        assertEquals(ClaudeGuide.generate(TestMeetings.mono), guide)
    }

    @Test
    fun `the transcript section is English whatever the model name is`() {
        val block = TestMeetings.monoTranscribed.transcription!!
        val german = TestMeetings.monoTranscribed.copy(
            transcription = block.copy(transcript = block.transcript!!.copy(modelName = "Klein (q5_1)")),
        )
        assertTrue("\"Klein (q5_1)\"" in ClaudeGuide.generate(german))
    }

    @Test
    fun `switch and photo positions are listed as mm ss with frames`() {
        val guide = ClaudeGuide.generate(TestMeetings.stereo)
        assertTrue("At 00:30 (frame 1440000)" in guide)
        assertTrue("no longer correspond to individual speakers" in guide)
        assertTrue("- 00:10 (frame 480000): `photos/photo-001.jpg`" in guide)
        assertTrue("- 00:25 (frame 1200000): `photos/photo-002.jpg`" in guide)
        assertTrue("At 00:50 (frame 2400000): 100 ms lost." in guide)
    }

    @Test
    fun `a meeting without photos says so`() {
        val guide = ClaudeGuide.generate(TestMeetings.mono)
        assertTrue("No photos were taken." in guide)
        assertFalse("Source switches:" in guide)
        assertFalse("Dropouts" in guide)
    }

    @Test
    fun `a recovered meeting is flagged`() {
        val guide = ClaudeGuide.generate(TestMeetings.mono.copy(status = MeetingStatus.RECOVERED))
        assertTrue("was recovered after the app was interrupted" in guide)
    }

    @Test
    fun `only the first channels are mentioned when a source delivered more`() {
        val wide = TestMeetings.stereo.copy(
            sources = TestMeetings.stereo.sources.map { if (it.id == 0) it.copy(deliveredChannels = 4) else it },
        )
        assertTrue("only the first 2 channels were recorded" in ClaudeGuide.generate(wide))
    }

    @Test
    fun `the guide ends with a single newline`() {
        val guide = ClaudeGuide.generate(TestMeetings.mono)
        assertTrue(guide.endsWith("\n") && !guide.endsWith("\n\n"))
    }
}
