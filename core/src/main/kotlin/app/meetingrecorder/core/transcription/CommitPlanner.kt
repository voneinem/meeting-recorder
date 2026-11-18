package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingTranscription

public sealed interface RecoveryAction {
    /** Delete these files of the meeting folder. */
    public data class DeleteStaging(val names: Set<String>) : RecoveryAction

    /** Repeat the whole commit from the complete work file (idempotent, no decoding). */
    public data object RedoCommit : RecoveryAction

    /** Turn a stale `in-progress` job into `failed / interrupted`. */
    public data class MarkInterrupted(val resumable: Boolean) : RecoveryAction

    /** `meeting.json` is unreadable but `meeting.json.part` is complete: make the part file the real one. */
    public data object PromoteMetaPart : RecoveryAction
}

/**
 * The recovery rules of contracts/transcription-service.md, as a pure function of what the folder
 * holds. Running the planned actions and planning again yields no actions (idempotent).
 */
public object CommitPlanner {

    private val metaPart = MeetingFolderLayout.partName(MeetingFolderLayout.METADATA_FILE)
    private val transcriptStaging = listOf(MeetingFolderLayout.TRANSCRIPT_JSON_FILE, MeetingFolderLayout.TRANSCRIPT_MD_FILE)
        .map(MeetingFolderLayout::partName)

    /**
     * @param work `NONE` also when a work file exists but its header cannot be used; such a file is deleted.
     * @param transcript state of `transcript.json`; the current rules do not depend on it (a complete
     *   work file is redone whatever `transcript.json` holds), it is part of the signature so that
     *   callers pass the full picture of the folder.
     * @param metaReadable `meeting.json` exists and decodes.
     * @param live a job for this folder is running in this process: nothing is touched then.
     */
    public fun plan(
        fileNames: Set<String>,
        work: WorkFileState,
        @Suppress("UNUSED_PARAMETER") transcript: TranscriptState,
        block: MeetingTranscription?,
        metaReadable: Boolean,
        live: Boolean,
    ): List<RecoveryAction> {
        if (live) return emptyList()
        val actions = mutableListOf<RecoveryAction>()
        val hasMetaPart = metaPart in fileNames
        if (hasMetaPart && !metaReadable) actions += RecoveryAction.PromoteMetaPart

        val delete = linkedSetOf<String>()
        if (hasMetaPart && metaReadable) delete += metaPart
        if (work == WorkFileState.NONE) {
            // Staging files without a usable work file are meaningless leftovers.
            if (MeetingFolderLayout.TRANSCRIPT_WORK_FILE in fileNames) delete += MeetingFolderLayout.TRANSCRIPT_WORK_FILE
            delete += transcriptStaging.filter { it in fileNames }
        }
        if (delete.isNotEmpty()) actions += RecoveryAction.DeleteStaging(delete)

        if (work == WorkFileState.COMPLETE) {
            actions += RecoveryAction.RedoCommit
        } else if (block?.job?.state == JobState.IN_PROGRESS) {
            actions += RecoveryAction.MarkInterrupted(resumable = work == WorkFileState.INCOMPLETE)
        }
        return actions
    }
}
