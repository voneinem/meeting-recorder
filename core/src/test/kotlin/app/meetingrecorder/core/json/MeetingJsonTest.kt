package app.meetingrecorder.core.json

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.json.MeetingJson.DecodeResult
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.model.Dropout
import app.meetingrecorder.core.model.SourceSwitch
import app.meetingrecorder.core.model.SwitchReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeetingJsonTest {

    private val goldenFiles = mapOf(
        "/golden/meeting-mono-complete.json" to TestMeetings.mono,
        "/golden/meeting-stereo-switch-photos.json" to TestMeetings.stereo,
    )

    @Test
    fun `encoding reproduces the golden files byte for byte`() {
        for ((path, meeting) in goldenFiles) {
            assertEquals(TestMeetings.resource(path), MeetingJson.encode(meeting), path)
        }
    }

    @Test
    fun `golden files decode to the sample meetings`() {
        for ((path, meeting) in goldenFiles) {
            val result = MeetingJson.decode(TestMeetings.resource(path))
            assertEquals(DecodeResult.Success(meeting), result, path)
        }
    }

    @Test
    fun `golden files are valid and match the documented schema`() {
        for ((path, meeting) in goldenFiles) {
            assertEquals(emptyList(), MeetingValidator.validate(meeting), path)
            assertEquals(emptyList(), SchemaCheck.violations(TestMeetings.resource(path)), path)
            assertEquals(emptyList(), SchemaCheck.violations(MeetingJson.encode(meeting)), path)
        }
    }

    @Test
    fun `the documented schema rejects a broken sample`() {
        val broken = TestMeetings.resource("/golden/meeting-mono-complete.json")
            .replace("\"channelLayout\": \"mono\"", "\"channelLayout\": \"surround\"")
        assertTrue(SchemaCheck.violations(broken).isNotEmpty())
        val missingField = TestMeetings.resource("/golden/meeting-mono-complete.json")
            .replace("\"dropouts\": []", "\"unrelated\": []")
        assertTrue(SchemaCheck.violations(missingField).isNotEmpty())
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = TestMeetings.resource("/golden/meeting-mono-complete.json")
            .replace("\"dropouts\": []", "\"dropouts\": [],\n  \"futureField\": {\"a\": 1}")
        assertEquals(DecodeResult.Success(TestMeetings.mono), MeetingJson.decode(text))
    }

    @Test
    fun `an unknown schema version is rejected`() {
        val text = TestMeetings.resource("/golden/meeting-mono-complete.json")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertEquals(DecodeResult.UnsupportedVersion(2), MeetingJson.decode(text))
    }

    @Test
    fun `unreadable input is reported as malformed`() {
        assertIs<DecodeResult.Malformed>(MeetingJson.decode(""))
        assertIs<DecodeResult.Malformed>(MeetingJson.decode("{ \"schemaVersion\": 1, \"title\": "))
        assertIs<DecodeResult.Malformed>(MeetingJson.decode("[]"))
        assertIs<DecodeResult.Malformed>(MeetingJson.decode("{\"title\": \"no version\"}"))
        assertIs<DecodeResult.Malformed>(MeetingJson.decode("{\"schemaVersion\": 1, \"title\": \"incomplete\"}"))
    }

    // --- validation rules from data-model.md ---

    private fun rules(meeting: MeetingMetadata): Set<String> = MeetingValidator.validate(meeting).map { it.rule }.toSet()

    @Test
    fun `channels must match the layout`() {
        val bad = TestMeetings.mono.copy(audio = TestMeetings.mono.audio.copy(channelLayout = ChannelLayout.STEREO))
        assertTrue("audio" in rules(bad))
    }

    @Test
    fun `positions outside the timeline are reported`() {
        val late = TestMeetings.stereo.copy(photos = listOf(Photo("photos/photo-001.jpg", 99_999_999, 2_083_333)))
        assertTrue("photos" in rules(late))
    }

    @Test
    fun `positions are not range-checked while the meeting is being recorded`() {
        val recording = TestMeetings.stereo.copy(
            status = app.meetingrecorder.core.model.MeetingStatus.RECORDING,
            audio = TestMeetings.stereo.audio.copy(durationFrames = 0, durationMs = 0),
        )
        assertEquals(emptyList(), MeetingValidator.validate(recording))
    }

    @Test
    fun `lists must be sorted by position`() {
        val unsorted = TestMeetings.stereo.copy(
            photos = TestMeetings.stereo.photos.reversed(),
        )
        assertTrue("photos" in rules(unsorted))
    }

    @Test
    fun `derived milliseconds must match the frames`() {
        val wrong = TestMeetings.stereo.copy(photos = listOf(Photo("photos/photo-001.jpg", 480_000, 9_999)))
        assertTrue("photos" in rules(wrong))
        val wrongDuration = TestMeetings.mono.copy(audio = TestMeetings.mono.audio.copy(durationMs = 1))
        assertTrue("audio" in rules(wrongDuration))
    }

    @Test
    fun `source references must exist and a switch must change the source`() {
        val unknown = TestMeetings.stereo.copy(initialSourceId = 7)
        assertTrue("sources" in rules(unknown))
        val sameSource = TestMeetings.stereo.copy(
            sourceSwitches = listOf(SourceSwitch(0, 0, 0, 0, SwitchReason.DEVICE_REMOVED, false)),
        )
        assertTrue("sourceSwitches" in rules(sameSource))
        val unknownTarget = TestMeetings.stereo.copy(
            sourceSwitches = listOf(SourceSwitch(0, 0, 0, 5, SwitchReason.DEVICE_REMOVED, false)),
        )
        assertTrue("sourceSwitches" in rules(unknownTarget))
    }

    @Test
    fun `recorded channels may not exceed delivered channels or two`() {
        val tooMany = TestMeetings.stereo.copy(
            sources = TestMeetings.stereo.sources.map { if (it.id == 0) it.copy(deliveredChannels = 1) else it },
        )
        assertTrue("sources" in rules(tooMany))
        val three = TestMeetings.stereo.copy(
            sources = TestMeetings.stereo.sources.map { if (it.id == 0) it.copy(deliveredChannels = 4, channels = 3) else it },
        )
        assertTrue("sources" in rules(three))
    }

    @Test
    fun `photo file names must follow the pattern and numbering`() {
        val badName = TestMeetings.stereo.copy(photos = listOf(Photo("photos/img.jpg", 480_000, 10_000)))
        assertTrue("photos" in rules(badName))
        val startsAtTwo = TestMeetings.stereo.copy(photos = listOf(Photo("photos/photo-002.jpg", 480_000, 10_000)))
        assertTrue("photos" in rules(startsAtTwo))
        val repeated = TestMeetings.stereo.copy(
            photos = listOf(Photo("photos/photo-001.jpg", 480_000, 10_000), Photo("photos/photo-001.jpg", 480_000, 10_000)),
        )
        assertTrue("photos" in rules(repeated))
    }

    @Test
    fun `dropouts must lose at least one frame`() {
        val none = TestMeetings.stereo.copy(dropouts = listOf(Dropout(2_400_000, 50_000, 0)))
        assertTrue("dropouts" in rules(none))
    }

    @Test
    fun `title and start time are checked`() {
        assertTrue("title" in rules(TestMeetings.mono.copy(title = "   ")))
        assertTrue("title" in rules(TestMeetings.mono.copy(title = "x".repeat(MeetingMetadata.MAX_TITLE_LENGTH + 1))))
        assertTrue("startedAt" in rules(TestMeetings.mono.copy(startedAt = "yesterday")))
        assertTrue("schemaVersion" in rules(TestMeetings.mono.copy(schemaVersion = 3)))
    }
}
