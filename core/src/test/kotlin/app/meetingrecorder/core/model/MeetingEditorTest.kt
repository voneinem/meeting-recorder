package app.meetingrecorder.core.model

import app.meetingrecorder.core.TestMeetings
import app.meetingrecorder.core.json.MeetingValidator
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeetingEditorTest {

    private val start = OffsetDateTime.parse("2026-11-15T09:05:12+01:00")
    private val builtIn = SourceInfo("Built-in microphone", SourceType.BUILTIN, 1)
    private val rode = SourceInfo("RØDE Wireless PRO", SourceType.USB, 2)

    private fun newMeeting(title: String? = null, source: SourceInfo = builtIn) =
        MeetingEditor.create(title, start, 48_000, source)

    @Test
    fun `a blank title becomes the start date and time`() {
        assertEquals("2026-11-15 09:05", newMeeting(null).title)
        assertEquals("2026-11-15 09:05", newMeeting("").title)
        assertEquals("2026-11-15 09:05", newMeeting("   ").title)
    }

    @Test
    fun `a given title is trimmed`() {
        assertEquals("Weekly sync", newMeeting("  Weekly sync ").title)
    }

    @Test
    fun `a title above 200 characters is rejected`() {
        assertEquals(200, newMeeting("x".repeat(200)).title.length)
        assertFailsWith<IllegalArgumentException> { newMeeting("x".repeat(201)) }
    }

    @Test
    fun `a new meeting is recording and mono for the built in microphone`() {
        val meeting = newMeeting()
        assertEquals(MeetingStatus.RECORDING, meeting.status)
        assertEquals(1, meeting.audio.channels)
        assertEquals(ChannelLayout.MONO, meeting.audio.channelLayout)
        assertEquals(0, meeting.audio.durationFrames)
        assertEquals("2026-11-15T09:05:12+01:00", meeting.startedAt)
        assertEquals(listOf(TestMeetings.builtInSource), meeting.sources)
        assertEquals(0, meeting.initialSourceId)
        assertEquals(emptyList(), MeetingValidator.validate(meeting))
    }

    @Test
    fun `a two channel source makes the meeting stereo`() {
        val meeting = newMeeting(source = rode)
        assertEquals(2, meeting.audio.channels)
        assertEquals(ChannelLayout.STEREO, meeting.audio.channelLayout)
        assertEquals(2, meeting.sources.single().channels)
    }

    @Test
    fun `a source delivering four channels is recorded as stereo and remembers the original count`() {
        val meeting = newMeeting(source = SourceInfo("Mixer", SourceType.USB, 4))
        assertEquals(2, meeting.audio.channels)
        assertEquals(4, meeting.sources.single().deliveredChannels)
        assertEquals(emptyList(), MeetingValidator.validate(meeting))
    }

    @Test
    fun `photos get consecutive file names and derived milliseconds`() {
        var meeting = newMeeting()
        meeting = MeetingEditor.addPhoto(meeting, 480_000)
        meeting = MeetingEditor.addPhoto(meeting, 480_000) // same position is allowed
        meeting = MeetingEditor.addPhoto(meeting, 1_200_000)
        assertEquals(listOf("photos/photo-001.jpg", "photos/photo-002.jpg", "photos/photo-003.jpg"), meeting.photos.map { it.file })
        assertEquals(listOf(10_000L, 10_000L, 25_000L), meeting.photos.map { it.atMs })
        assertEquals(emptyList(), MeetingValidator.validate(meeting))
    }

    @Test
    fun `photo positions must not go backwards or be negative`() {
        val meeting = MeetingEditor.addPhoto(newMeeting(), 1_000)
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addPhoto(meeting, 999) }
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addPhoto(newMeeting(), -1) }
    }

    @Test
    fun `positions are checked against the duration once the meeting is finished`() {
        val finished = MeetingEditor.finish(newMeeting(), durationFrames = 96_000)
        assertEquals(emptyList(), MeetingValidator.validate(MeetingEditor.addPhoto(finished, 96_000)))
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addPhoto(finished, 96_001) }
    }

    @Test
    fun `a source switch from a stereo file to a mono source duplicates the channels`() {
        val stereo = newMeeting(source = rode)
        val (withBuiltIn, builtInId) = MeetingEditor.addSource(stereo, builtIn)
        assertEquals(1, builtInId)
        val switched = MeetingEditor.addSourceSwitch(withBuiltIn, 1_440_000, fromSourceId = 0, toSourceId = builtInId)
        val switch = switched.sourceSwitches.single()
        assertEquals(30_000, switch.atMs)
        assertEquals(SwitchReason.DEVICE_REMOVED, switch.reason)
        assertTrue(switch.channelsDuplicated)
        assertEquals(emptyList(), MeetingValidator.validate(switched))
    }

    @Test
    fun `a switch between sources with the same channel count does not duplicate`() {
        val mono = newMeeting(source = SourceInfo("USB mic", SourceType.USB, 1))
        val (withBuiltIn, id) = MeetingEditor.addSource(mono, builtIn)
        val switched = MeetingEditor.addSourceSwitch(withBuiltIn, 100, 0, id)
        assertEquals(false, switched.sourceSwitches.single().channelsDuplicated)
    }

    @Test
    fun `adding a known source returns its existing id`() {
        val meeting = newMeeting(source = rode)
        val (same, id) = MeetingEditor.addSource(meeting, rode)
        assertEquals(0, id)
        assertEquals(meeting, same)
    }

    @Test
    fun `invalid switches are rejected`() {
        val meeting = newMeeting(source = rode)
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addSourceSwitch(meeting, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addSourceSwitch(meeting, 0, 0, 9) }
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addSourceSwitch(meeting, 0, 9, 0) }
    }

    @Test
    fun `dropouts are recorded in order`() {
        val meeting = MeetingEditor.addDropout(newMeeting(), 96_000, 4_800)
        assertEquals(Dropout(96_000, 2_000, 4_800), meeting.dropouts.single())
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addDropout(meeting, 96_000, 0) }
        assertFailsWith<IllegalArgumentException> { MeetingEditor.addDropout(meeting, 1, 10) }
    }

    @Test
    fun `renaming changes only the title`() {
        val renamed = MeetingEditor.rename(TestMeetings.stereo, "  New title ")
        assertEquals("New title", renamed.title)
        assertEquals(TestMeetings.stereo, renamed.copy(title = TestMeetings.stereo.title))
        assertFailsWith<IllegalArgumentException> { MeetingEditor.rename(TestMeetings.stereo, "  ") }
        assertFailsWith<IllegalArgumentException> { MeetingEditor.rename(TestMeetings.stereo, "x".repeat(201)) }
    }

    @Test
    fun `finishing sets the status and recomputes the duration`() {
        val finished = MeetingEditor.finish(newMeeting(), 2_880_000)
        assertEquals(MeetingStatus.COMPLETE, finished.status)
        assertEquals(2_880_000, finished.audio.durationFrames)
        assertEquals(60_000, finished.audio.durationMs)
        assertEquals(emptyList(), MeetingValidator.validate(finished))
        assertFailsWith<IllegalArgumentException> { MeetingEditor.finish(newMeeting(), -1) }
    }

    @Test
    fun `a recovered meeting gets the measured duration`() {
        val recovered = MeetingEditor.markRecovered(newMeeting(), 48_000)
        assertEquals(MeetingStatus.RECOVERED, recovered.status)
        assertEquals(1_000, recovered.audio.durationMs)
    }

    // --- Phase 2: transcription job and transcript ---

    private val jobStart = OffsetDateTime.parse("2026-11-16T22:40:00+01:00")
    private val transcript = TestMeetings.monoTranscribed.transcription!!.transcript!!
    private val finishedMeeting = TestMeetings.mono

    @Test
    fun `startJob writes an in-progress job and leaves a committed transcript untouched`() {
        val fresh = MeetingEditor.startJob(finishedMeeting, "small-q5_1", "auto", jobStart)
        val job = fresh.transcription!!.job!!
        assertEquals(JobState.IN_PROGRESS, job.state)
        assertEquals("small-q5_1", job.modelId)
        assertEquals("auto", job.requestedLanguage)
        assertEquals("2026-11-16T22:40:00+01:00", job.startedAt)
        assertEquals(null, job.failureReason)
        assertEquals(null, fresh.transcription.transcript)

        val withTranscript = finishedMeeting.copy(transcription = MeetingTranscription(transcript = transcript))
        val again = MeetingEditor.startJob(withTranscript, "medium-q5_0", "de", jobStart)
        assertEquals(transcript, again.transcription!!.transcript)
        assertEquals("medium-q5_0", again.transcription.job!!.modelId)
        assertEquals(emptyList(), MeetingValidator.validate(again))
    }

    @Test
    fun `startJob replaces an older job`() {
        val first = MeetingEditor.startJob(finishedMeeting, "small-q5_1", "auto", jobStart)
        val failed = MeetingEditor.failJob(first, FailureReason.INTERRUPTED, null, resumable = true)
        val second = MeetingEditor.startJob(failed, "small-q5_1", "de", jobStart)
        val job = second.transcription!!.job!!
        assertEquals(JobState.IN_PROGRESS, job.state)
        assertEquals("de", job.requestedLanguage)
        assertEquals(null, job.failureReason)
        assertEquals(false, job.resumable)
    }

    @Test
    fun `failJob records reason, message and resumable`() {
        val running = MeetingEditor.startJob(finishedMeeting, "small-q5_1", "auto", jobStart)
        val failed = MeetingEditor.failJob(running, FailureReason.RECORDING_STARTED, "stopped because a recording was started", true)
        val job = failed.transcription!!.job!!
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.RECORDING_STARTED, job.failureReason)
        assertEquals("stopped because a recording was started", job.failureMessage)
        assertTrue(job.resumable)
        assertEquals("small-q5_1", job.modelId)
        assertEquals(emptyList(), MeetingValidator.validate(failed))
    }

    @Test
    fun `failJob needs a job`() {
        assertFailsWith<IllegalArgumentException> {
            MeetingEditor.failJob(finishedMeeting, FailureReason.ERROR, null, false)
        }
    }

    @Test
    fun `clearJob removes only the job`() {
        val both = TestMeetings.monoTranscribed
        val cleared = MeetingEditor.clearJob(both)
        assertEquals(transcript, cleared.transcription!!.transcript)
        assertEquals(null, cleared.transcription.job)
    }

    @Test
    fun `clearJob on a meeting with no transcript leaves no empty block`() {
        val running = MeetingEditor.startJob(finishedMeeting, "small-q5_1", "auto", jobStart)
        assertEquals(finishedMeeting, MeetingEditor.clearJob(running))
        assertEquals(finishedMeeting, MeetingEditor.clearJob(finishedMeeting))
    }

    @Test
    fun `commitTranscript sets the transcript and removes the job`() {
        val running = MeetingEditor.startJob(finishedMeeting, "small-q5_1", "auto", jobStart)
        val done = MeetingEditor.commitTranscript(running, transcript)
        assertEquals(MeetingTranscription(transcript = transcript, job = null), done.transcription)
        assertEquals(emptyList(), MeetingValidator.validate(done))

        val replaced = MeetingEditor.commitTranscript(TestMeetings.monoTranscribed, transcript.copy(language = "en"))
        assertEquals("en", replaced.transcription!!.transcript!!.language)
        assertEquals(null, replaced.transcription.job)
    }

    @Test
    fun `renaming keeps the transcription block`() {
        val renamed = MeetingEditor.rename(TestMeetings.monoTranscribed, "New title")
        assertEquals("New title", renamed.title)
        assertEquals(TestMeetings.monoTranscribed.transcription, renamed.transcription)
    }
}
