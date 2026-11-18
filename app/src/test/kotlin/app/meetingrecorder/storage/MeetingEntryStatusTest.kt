package app.meetingrecorder.storage

import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobInfo
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingTranscription
import app.meetingrecorder.core.transcription.TranscriptionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class MeetingEntryStatusTest {
    private val phase1 = setOf("audio.wav", "meeting.json", "CLAUDE.md", "photos")
    private val withTranscript = phase1 + setOf("transcript.json", "transcript.md")

    private fun job(state: JobState, reason: FailureReason? = null, resumable: Boolean = false) = MeetingTranscription(
        job = JobInfo(state, "tiny", "auto", "2026-11-17T09:00:00+01:00", failureReason = reason, resumable = resumable),
    )

    @Test
    fun aPhase1MeetingWithoutABlockHasNoStatus() {
        assertEquals(TranscriptionStatus.None, MeetingEntryStatus.of(phase1, null, live = false))
    }

    @Test
    fun aFolderCopiedInWithATranscriptAndNoBlockIsDone() {
        assertEquals(TranscriptionStatus.Done, MeetingEntryStatus.of(withTranscript, null, live = false))
    }

    @Test
    fun aLiveJobIsInProgress() {
        assertEquals(TranscriptionStatus.InProgress, MeetingEntryStatus.of(phase1 + "transcript.work.jsonl", job(JobState.IN_PROGRESS), live = true))
    }

    @Test
    fun anInProgressBlockWithAnEmptyRegistryIsAnInterruptedFailure() {
        val status = MeetingEntryStatus.of(phase1 + "transcript.work.jsonl", job(JobState.IN_PROGRESS), live = false)
        assertEquals(TranscriptionStatus.Failed(FailureReason.INTERRUPTED), status)
    }

    @Test
    fun aFailedJobShowsItsReason() {
        val status = MeetingEntryStatus.of(phase1, job(JobState.FAILED, FailureReason.MODEL_MISSING), live = false)
        assertEquals(TranscriptionStatus.Failed(FailureReason.MODEL_MISSING), status)
    }

    @Test
    fun aTranscribedMeetingWithAFailedRetryStaysDone() {
        val status = MeetingEntryStatus.of(withTranscript, job(JobState.FAILED, FailureReason.ERROR), live = false)
        assertEquals(TranscriptionStatus.Done, status)
    }

    @Test
    fun aCheckpointWithoutAJobBlockIsTreatedAsInterrupted() {
        val status = MeetingEntryStatus.of(phase1 + "transcript.work.jsonl", null, live = false)
        assertEquals(TranscriptionStatus.Failed(FailureReason.INTERRUPTED), status)
    }

    @Test
    fun theJobDetailsForTheRowActionsComeFromTheBlock() {
        val block = job(JobState.FAILED, FailureReason.INTERRUPTED, resumable = true)
        val files = phase1 + "transcript.work.jsonl"
        assertEquals(true, MeetingEntryStatus.resumable(files, block, live = false))
        assertEquals(false, MeetingEntryStatus.resumable(phase1, block, live = false), "no work file, nothing to resume")
        assertEquals(false, MeetingEntryStatus.resumable(files, job(JobState.FAILED, FailureReason.ERROR, resumable = false), live = false))
    }
}
