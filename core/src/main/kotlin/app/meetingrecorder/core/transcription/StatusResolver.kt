package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobInfo
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingTranscription

/**
 * The status table of contracts/transcript-folder.md. The `transcription` block of `meeting.json` is
 * only a cache; the status comes from the folder, so it is right for folders copied in from outside,
 * for folders touched by an older app version and after a crash.
 */
public object StatusResolver {

    /**
     * @param live the in-process registry holds a job for this folder. After a process death the
     *   registry is empty, so every `in-progress` job block is stale by definition.
     */
    public fun resolve(
        block: MeetingTranscription?,
        transcript: TranscriptState,
        work: WorkFileState,
        live: Boolean,
    ): Resolved {
        if (transcript == TranscriptState.UNREADABLE) {
            return Resolved(TranscriptionStatus.Failed(FailureReason.ERROR), DetailHint.TranscriptUnreadable)
        }
        if (live) return Resolved(TranscriptionStatus.InProgress, DetailHint.None)

        val job = block?.job
        val hint = job?.let { jobHint(it, work) }
        if (transcript == TranscriptState.VALID) return Resolved(TranscriptionStatus.Done, hint ?: DetailHint.None)

        // No transcript.json.
        if (work == WorkFileState.COMPLETE && (job == null || job.state == JobState.IN_PROGRESS)) {
            // The commit has not finished; recovery completes it within seconds.
            return Resolved(TranscriptionStatus.InProgress, DetailHint.None)
        }
        if (hint != null) return Resolved(TranscriptionStatus.Failed(hint.reason), hint)
        if (work == WorkFileState.INCOMPLETE) {
            // A checkpoint without a job block (for example a folder copied in): treat it as interrupted.
            val orphan = DetailHint.JobFailed(FailureReason.INTERRUPTED, resumable = true)
            return Resolved(TranscriptionStatus.Failed(orphan.reason), orphan)
        }
        return Resolved(TranscriptionStatus.None, DetailHint.None)
    }

    /** A job that is not live is a failed job: an `in-progress` block is an interrupted one. */
    private fun jobHint(job: JobInfo, work: WorkFileState): DetailHint.JobFailed {
        val reason = if (job.state == JobState.FAILED) job.failureReason ?: FailureReason.INTERRUPTED else FailureReason.INTERRUPTED
        val claimedResumable = job.state == JobState.IN_PROGRESS || job.resumable
        return DetailHint.JobFailed(reason, resumable = claimedResumable && work != WorkFileState.NONE)
    }
}
