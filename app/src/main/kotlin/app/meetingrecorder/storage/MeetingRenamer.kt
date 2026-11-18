package app.meetingrecorder.storage

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.models.Sha256Streaming
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptMarkdown

/**
 * Renames a meeting (FR-027): only the title changes, in `meeting.json` and in the generated files. The
 * metadata write and `CLAUDE.md` go through [MeetingMetadataStore]; when a transcript exists `transcript.md` is
 * regenerated from `transcript.json` (research R-13), which itself is never touched. The folder name does not
 * change and the transcription status stays as it is. Does blocking storage I/O.
 */
class MeetingRenamer(
    private val metadata: MeetingMetadataStore,
    private val folderFiles: (folderName: String) -> FolderFiles?,
) {
    /** False when the metadata cannot be read, the title is invalid or a write fails. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun rename(folderName: String, newTitle: String): Boolean {
        val renamed = try {
            metadata.update(folderName) { MeetingEditor.rename(it, newTitle) }
        } catch (e: IllegalArgumentException) {
            false // an invalid title
        }
        if (!renamed) return false
        refreshTranscriptMarkdown(folderName)
        return true
    }

    private fun refreshTranscriptMarkdown(folderName: String) {
        val files = folderFiles(folderName) ?: return
        val meeting = metadata.read(folderName) ?: return
        val json = files.readText(MeetingFolderLayout.TRANSCRIPT_JSON_FILE) ?: return
        val transcript = (TranscriptJson.decode(json) as? TranscriptJson.DecodeResult.Success)?.transcript ?: return
        val hash = Sha256Streaming().update(json.toByteArray(Charsets.UTF_8)).hex()
        runCatching { files.replaceText(MeetingFolderLayout.TRANSCRIPT_MD_FILE, TranscriptMarkdown.generate(meeting, transcript, hash)) }
    }
}
