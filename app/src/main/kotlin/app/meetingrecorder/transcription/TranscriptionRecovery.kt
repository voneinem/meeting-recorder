package app.meetingrecorder.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptValidator
import app.meetingrecorder.core.transcription.CommitPlanner
import app.meetingrecorder.core.transcription.RecoveryAction
import app.meetingrecorder.core.transcription.TranscriptState
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkFileState
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.storage.FolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import app.meetingrecorder.work.JobKind
import java.time.OffsetDateTime

/**
 * Runs once per main-process start, after Phase 1's `RecoveryRunner` (contracts/transcription-service.md,
 * "Recovery at app start"): cancels the persisted unique work (a transcription or an install is always the
 * user's decision, never WorkManager's), then executes the [CommitPlanner]'s actions for every meeting:
 * delete orphaned staging files, redo a commit whose work file is complete, turn a stale `in-progress` job into
 * `failed / interrupted` (resumable when the work file has a header) and promote a `meeting.json.part`. Every
 * step is idempotent; a meeting with a live job is never touched. Does blocking storage I/O.
 */
class TranscriptionRecovery(
    private val folderNames: () -> List<String>,
    private val folderFiles: (String) -> FolderFiles?,
    private val metadata: MeetingMetadataStore,
    private val isLive: (folderName: String) -> Boolean,
    private val cancelWork: (uniqueWorkName: String) -> Unit,
    /** Name and origin of the model with this id, for the transcript of a redone commit. */
    private val modelInfo: (modelId: String) -> Pair<String, ModelOrigin>,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    fun run() {
        cancelWork(JobKind.TRANSCRIPTION.uniqueWorkName)
        cancelWork(JobKind.MODEL_INSTALL.uniqueWorkName)
        for (name in folderNames()) {
            // One damaged meeting must not stop the repair of the others.
            runCatching { recover(name) }
        }
    }

    private fun recover(folderName: String) {
        val files = folderFiles(folderName) ?: return
        val names = files.list().filter { !it.isDirectory }.map { it.name }.toSet()
        val metaText = files.readText(MeetingFolderLayout.METADATA_FILE)
        val meta = metaText?.let(MeetingJson::decode) as? MeetingJson.DecodeResult.Success
        val workRead = TranscriptionIo.readWorkFile(files)
        val work = when {
            workRead == null || workRead.records.firstOrNull() !is WorkRecord.Header -> WorkFileState.NONE
            WorkFile.isComplete(workRead.records) -> WorkFileState.COMPLETE
            else -> WorkFileState.INCOMPLETE
        }
        val plan = CommitPlanner.plan(
            fileNames = names,
            work = work,
            transcript = transcriptState(files, names),
            block = meta?.meeting?.transcription,
            metaReadable = meta != null,
            live = isLive(folderName),
        )
        for (action in plan) execute(action, folderName, files, workRead)
    }

    private fun transcriptState(files: FolderFiles, names: Set<String>): TranscriptState {
        if (MeetingFolderLayout.TRANSCRIPT_JSON_FILE !in names) return TranscriptState.MISSING
        val decoded = files.readText(MeetingFolderLayout.TRANSCRIPT_JSON_FILE)?.let(TranscriptJson::decode) as? TranscriptJson.DecodeResult.Success
        return if (decoded != null && TranscriptValidator.validate(decoded.transcript).isEmpty()) TranscriptState.VALID else TranscriptState.UNREADABLE
    }

    private fun execute(action: RecoveryAction, folderName: String, files: FolderFiles, work: TranscriptionIo.WorkRead?) {
        when (action) {
            is RecoveryAction.DeleteStaging -> action.names.forEach { files.delete(it) }
            RecoveryAction.PromoteMetaPart -> {
                val part = files.readText(MeetingFolderLayout.partName(MeetingFolderLayout.METADATA_FILE))
                val meeting = (part?.let(MeetingJson::decode) as? MeetingJson.DecodeResult.Success)?.meeting ?: return
                metadata.replace(folderName, meeting)
                files.delete(MeetingFolderLayout.partName(MeetingFolderLayout.METADATA_FILE))
            }
            RecoveryAction.RedoCommit -> {
                val header = work?.records?.firstOrNull() as? WorkRecord.Header ?: return
                val (name, origin) = modelInfo(header.modelId)
                TranscriptCommitter(files, metadata, folderName, name, origin, now).commit()
            }
            is RecoveryAction.MarkInterrupted -> metadata.update(folderName) {
                MeetingEditor.failJob(it, FailureReason.INTERRUPTED, "the app was closed or the phone restarted", action.resumable)
            }
        }
    }
}
