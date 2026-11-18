package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobInfo
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingTranscription
import app.meetingrecorder.core.model.TranscriptInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** One test per row of the status table in contracts/transcript-folder.md. */
class StatusResolverTest {

    private val info = TranscriptInfo("small-q5_1", "Small", "auto", "de", "2026-11-16T22:31:08+01:00", 1000, 4)
    private val running = JobInfo(JobState.IN_PROGRESS, "small-q5_1", "auto", "2026-11-16T22:40:00+01:00")
    private val failed = running.copy(state = JobState.FAILED, failureReason = FailureReason.RECORDING_STARTED, resumable = true)

    private fun block(transcript: TranscriptInfo? = null, job: JobInfo? = null) = MeetingTranscription(transcript, job)

    private fun resolve(
        block: MeetingTranscription?,
        transcript: TranscriptState,
        work: WorkFileState = WorkFileState.NONE,
        live: Boolean = false,
    ) = StatusResolver.resolve(block, transcript, work, live)

    @Test
    fun `row 1 - no transcript and no job is none`() {
        assertEquals(Resolved(TranscriptionStatus.None, DetailHint.None), resolve(null, TranscriptState.MISSING))
        assertEquals(Resolved(TranscriptionStatus.None, DetailHint.None), resolve(block(), TranscriptState.MISSING))
    }

    @Test
    fun `row 2 - no transcript and a running job is in progress`() {
        assertEquals(Resolved(TranscriptionStatus.InProgress, DetailHint.None), resolve(block(job = running), TranscriptState.MISSING, WorkFileState.INCOMPLETE, live = true))
        assertEquals(Resolved(TranscriptionStatus.InProgress, DetailHint.None), resolve(null, TranscriptState.MISSING, WorkFileState.NONE, live = true))
    }

    @Test
    fun `row 3 - no transcript and a stale or failed job is failed`() {
        assertEquals(
            Resolved(TranscriptionStatus.Failed(FailureReason.INTERRUPTED), DetailHint.JobFailed(FailureReason.INTERRUPTED, resumable = true)),
            resolve(block(job = running), TranscriptState.MISSING, WorkFileState.INCOMPLETE),
        )
        assertEquals(
            Resolved(TranscriptionStatus.Failed(FailureReason.INTERRUPTED), DetailHint.JobFailed(FailureReason.INTERRUPTED, resumable = false)),
            resolve(block(job = running), TranscriptState.MISSING, WorkFileState.NONE),
        )
        assertEquals(
            Resolved(TranscriptionStatus.Failed(FailureReason.RECORDING_STARTED), DetailHint.JobFailed(FailureReason.RECORDING_STARTED, resumable = true)),
            resolve(block(job = failed), TranscriptState.MISSING, WorkFileState.INCOMPLETE),
        )
        // The block says resumable but the work file is gone: not resumable.
        assertEquals(
            Resolved(TranscriptionStatus.Failed(FailureReason.RECORDING_STARTED), DetailHint.JobFailed(FailureReason.RECORDING_STARTED, resumable = false)),
            resolve(block(job = failed), TranscriptState.MISSING, WorkFileState.NONE),
        )
    }

    @Test
    fun `row 4 - a work file with an end line is in progress until the commit finishes`() {
        assertEquals(Resolved(TranscriptionStatus.InProgress, DetailHint.None), resolve(block(job = running), TranscriptState.MISSING, WorkFileState.COMPLETE))
        assertEquals(Resolved(TranscriptionStatus.InProgress, DetailHint.None), resolve(null, TranscriptState.MISSING, WorkFileState.COMPLETE))
    }

    @Test
    fun `row 5 - a valid transcript and no job is done`() {
        assertEquals(Resolved(TranscriptionStatus.Done, DetailHint.None), resolve(block(transcript = info), TranscriptState.VALID))
    }

    @Test
    fun `row 6 - a valid transcript with a live job is in progress and the old transcript stays readable`() {
        assertEquals(
            Resolved(TranscriptionStatus.InProgress, DetailHint.None),
            resolve(block(transcript = info, job = running), TranscriptState.VALID, WorkFileState.INCOMPLETE, live = true),
        )
    }

    @Test
    fun `row 7 - a valid transcript with a failed or stale job is done plus the re-transcription hint`() {
        assertEquals(
            Resolved(TranscriptionStatus.Done, DetailHint.JobFailed(FailureReason.RECORDING_STARTED, resumable = true)),
            resolve(block(transcript = info, job = failed), TranscriptState.VALID, WorkFileState.INCOMPLETE),
        )
        assertEquals(
            Resolved(TranscriptionStatus.Done, DetailHint.JobFailed(FailureReason.INTERRUPTED, resumable = false)),
            resolve(block(transcript = info, job = running), TranscriptState.VALID, WorkFileState.NONE),
        )
    }

    @Test
    fun `row 8 - an unreadable transcript is failed whatever the job says`() {
        val expected = Resolved(TranscriptionStatus.Failed(FailureReason.ERROR), DetailHint.TranscriptUnreadable)
        assertEquals(expected, resolve(block(transcript = info), TranscriptState.UNREADABLE))
        assertEquals(expected, resolve(null, TranscriptState.UNREADABLE))
        assertEquals(expected, resolve(block(transcript = info, job = failed), TranscriptState.UNREADABLE, WorkFileState.INCOMPLETE))
    }

    @Test
    fun `row 9 - a valid transcript without a transcription block is done`() {
        assertEquals(Resolved(TranscriptionStatus.Done, DetailHint.None), resolve(null, TranscriptState.VALID))
        assertEquals(Resolved(TranscriptionStatus.Done, DetailHint.None), resolve(block(), TranscriptState.VALID))
    }

    @Test
    fun `an empty registry makes every in-progress block stale`() {
        for (transcript in listOf(TranscriptState.MISSING, TranscriptState.VALID)) {
            val resolved = resolve(block(job = running), transcript, WorkFileState.INCOMPLETE, live = false)
            assertEquals(DetailHint.JobFailed(FailureReason.INTERRUPTED, resumable = true), resolved.hint)
        }
    }

    @Test
    fun `a failed commit with a complete work file is shown as failed`() {
        val storageFull = failed.copy(failureReason = FailureReason.STORAGE_FULL)
        assertEquals(
            TranscriptionStatus.Failed(FailureReason.STORAGE_FULL),
            resolve(block(job = storageFull), TranscriptState.MISSING, WorkFileState.COMPLETE).status,
        )
    }
}
