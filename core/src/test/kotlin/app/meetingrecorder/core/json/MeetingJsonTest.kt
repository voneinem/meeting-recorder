package app.meetingrecorder.core.json

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.json.MeetingJson.DecodeResult
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingTranscription
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.Photo
import app.meetingrecorder.core.model.Dropout
import app.meetingrecorder.core.model.SourceSwitch
import app.meetingrecorder.core.model.SwitchReason
import app.meetingrecorder.core.model.TranscriptInfo
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

    // --- Phase 2: the optional "transcription" block ---

    private val transcribedPath = "/golden/meeting-with-transcription.json"

    @Test
    fun `a missing transcription block is not written and decodes to null`() {
        assertTrue("transcription" !in MeetingJson.encode(TestMeetings.mono))
        val decoded = MeetingJson.decode(TestMeetings.resource("/golden/meeting-mono-complete.json"))
        assertEquals(null, (decoded as DecodeResult.Success).meeting.transcription)
    }

    @Test
    fun `the transcription golden round-trips and validates`() {
        val text = TestMeetings.resource(transcribedPath)
        assertEquals(text, MeetingJson.encode(TestMeetings.monoTranscribed))
        assertEquals(DecodeResult.Success(TestMeetings.monoTranscribed), MeetingJson.decode(text))
        assertEquals(emptyList(), MeetingValidator.validate(TestMeetings.monoTranscribed))
        assertEquals(emptyList(), SchemaCheck.violations(text))
    }

    @Test
    fun `unknown keys inside the transcription block are ignored`() {
        val text = TestMeetings.resource(transcribedPath)
            .replace("\"segmentCount\": 4", "\"segmentCount\": 4,\n      \"futureField\": [1, 2]")
            .replace("\"resumable\": true", "\"resumable\": true,\n      \"futureJobField\": \"x\"")
        assertEquals(DecodeResult.Success(TestMeetings.monoTranscribed), MeetingJson.decode(text))
    }

    @Test
    fun `the schema rejects a broken transcription block`() {
        val text = TestMeetings.resource(transcribedPath)
        assertTrue(SchemaCheck.violations(text.replace("\"language\": \"de\"", "\"language\": \"DE\"")).isNotEmpty())
        assertTrue(SchemaCheck.violations(text.replace("\"failureReason\": \"recording-started\",", "")).isNotEmpty())
        assertTrue(SchemaCheck.violations(text.replace("\"state\": \"failed\"", "\"state\": \"done\"")).isNotEmpty())
    }

    private fun transcribed(
        transcript: (TranscriptInfo) -> TranscriptInfo = { it },
        job: (app.meetingrecorder.core.model.JobInfo) -> app.meetingrecorder.core.model.JobInfo = { it },
    ): MeetingMetadata {
        val block = TestMeetings.monoTranscribed.transcription!!
        return TestMeetings.monoTranscribed.copy(
            transcription = MeetingTranscription(transcript(block.transcript!!), job(block.job!!)),
        )
    }

    @Test
    fun `the transcript block rules are reported`() {
        assertTrue("transcription" in rules(transcribed({ it.copy(requestedLanguage = "automatic!") })))
        assertTrue("transcription" in rules(transcribed({ it.copy(requestedLanguage = "AUTO") })))
        assertTrue("transcription" in rules(transcribed({ it.copy(language = "auto") })))
        assertTrue("transcription" in rules(transcribed({ it.copy(language = "d") })))
        assertTrue("transcription" in rules(transcribed({ it.copy(modelId = "") })))
        assertTrue("transcription" in rules(transcribed({ it.copy(modelName = "") })))
        assertTrue("transcription" in rules(transcribed({ it.copy(processingMs = -1) })))
        assertTrue("transcription" in rules(transcribed({ it.copy(segmentCount = -1) })))
        assertTrue("transcription" in rules(transcribed({ it.copy(completedAt = "tomorrow") })))
        assertEquals(emptyList(), MeetingValidator.validate(transcribed({ it.copy(requestedLanguage = "de", language = "eng") })))
    }

    @Test
    fun `the job block rules are reported`() {
        assertTrue("transcription" in rules(transcribed(job = { it.copy(requestedLanguage = "German") })))
        assertTrue("transcription" in rules(transcribed(job = { it.copy(modelId = "") })))
        assertTrue("transcription" in rules(transcribed(job = { it.copy(startedAt = "now") })))
        assertTrue("transcription" in rules(transcribed(job = { it.copy(failureReason = null) })))
        // An in-progress job needs no failure reason.
        assertEquals(
            emptyList(),
            MeetingValidator.validate(
                transcribed(job = { it.copy(state = JobState.IN_PROGRESS, failureReason = null, failureMessage = null, resumable = false) }),
            ),
        )
        assertEquals(FailureReason.RECORDING_STARTED, TestMeetings.monoTranscribed.transcription!!.job!!.failureReason)
    }
}
