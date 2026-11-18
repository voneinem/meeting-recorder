package app.meetingrecorder.storage

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.model.MeetingTranscription
import app.meetingrecorder.core.transcription.DetailHint
import app.meetingrecorder.core.transcription.StatusResolver
import app.meetingrecorder.core.transcription.TranscriptState
import app.meetingrecorder.core.transcription.TranscriptionStatus
import app.meetingrecorder.core.transcription.WorkFileState

/**
 * The status of a meeting in the list (FR-035), computed from what the folder listing already returned (the
 * file names), the `transcription` block of `meeting.json` that is read anyway, and the registry's live flag.
 * No extra file is read: whether `transcript.json` is valid is checked when a meeting is opened, and a work file
 * counts as incomplete (a finished one whose commit is pending is completed by the recovery within seconds).
 */
object MeetingEntryStatus {
    fun of(fileNames: Set<String>, block: MeetingTranscription?, live: Boolean): TranscriptionStatus =
        resolve(fileNames, block, live).status

    /** True when a failed job can continue from its work file. */
    fun resumable(fileNames: Set<String>, block: MeetingTranscription?, live: Boolean): Boolean =
        (resolve(fileNames, block, live).hint as? DetailHint.JobFailed)?.resumable == true

    private fun resolve(fileNames: Set<String>, block: MeetingTranscription?, live: Boolean) = StatusResolver.resolve(
        block = block,
        transcript = if (MeetingFolderLayout.TRANSCRIPT_JSON_FILE in fileNames) TranscriptState.VALID else TranscriptState.MISSING,
        work = if (MeetingFolderLayout.TRANSCRIPT_WORK_FILE in fileNames) WorkFileState.INCOMPLETE else WorkFileState.NONE,
        live = live,
    )
}
