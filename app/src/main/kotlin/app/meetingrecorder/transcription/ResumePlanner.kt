package app.meetingrecorder.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.transcription.TranscriptionPipeline
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.storage.FolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore

sealed interface ResumePlan {
    /** The work file can continue at [startFrame]; [elapsedBeforeMs] is the processing time of the earlier sessions. */
    data class Ready(val startFrame: Long, val elapsedBeforeMs: Long) : ResumePlan

    /** Only "start over" is offered; [field] names what differs (`model`, `modelSha256`, `requestedLanguage`, ...). */
    data class NotPossible(val field: String) : ResumePlan
}

/**
 * Decides whether a work file may be resumed (contracts/transcript-folder.md, "Work file"): the model must be
 * installed and its hash, the language, `vad`, the sample rate, the duration and the pipeline version must equal
 * the new request. Nothing is ever resumed automatically (FR-017): this only answers the user's request.
 */
object ResumePlanner {
    fun plan(
        files: FolderFiles,
        requestedLanguage: String,
        installedModelSha256: String?,
        sampleRate: Int,
        durationFrames: Long,
    ): ResumePlan {
        val read = TranscriptionIo.readWorkFile(files) ?: return ResumePlan.NotPossible("workFile")
        val header = read.records.firstOrNull() as? WorkRecord.Header ?: return ResumePlan.NotPossible("header")
        val check = WorkFile.canResume(
            header,
            WorkFile.ResumeRequest(requestedLanguage, header.vad, sampleRate, durationFrames, TranscriptionPipeline.PIPELINE_VERSION),
            installedModelSha256,
        )
        if (check is WorkFile.ResumeCheck.Mismatch) return ResumePlan.NotPossible(check.field)
        return ResumePlan.Ready(WorkFile.resumeFrame(read.records).coerceIn(0, durationFrames), WorkFile.elapsedMs(read.records))
    }
}

/** "Dismiss": removes the work file and the `job` block of a failed job; a committed transcript stays. */
object JobDismissal {
    fun dismiss(files: FolderFiles, metadata: MeetingMetadataStore, folderName: String): Boolean {
        if (!files.delete(MeetingFolderLayout.TRANSCRIPT_WORK_FILE)) return false
        return metadata.update(folderName) { MeetingEditor.clearJob(it) }
    }
}
