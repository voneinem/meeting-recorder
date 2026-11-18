package app.meetingrecorder.storage

import android.util.Log
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.guide.ClaudeGuide
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single path for every write to `meeting.json` (research R-12). A rename, the transcription runner
 * and the recovery all change the same file; each change is one read-modify-write under a per-meeting
 * lock that re-reads inside the lock, so none of them overwrites another's change with a stale copy.
 * The file is replaced through [FolderFiles.replaceText] (staged), and `CLAUDE.md` is regenerated from
 * the result so the folder stays consistent. Does blocking storage I/O: call it off the main thread.
 *
 * [folderFiles] maps a meeting's folder name to its files, or null when the folder does not exist.
 */
class MeetingMetadataStore(
    private val warn: (message: String, cause: Throwable) -> Unit = { message, cause -> Log.w(TAG, message, cause) },
    private val folderFiles: (folderName: String) -> FolderFiles?,
) {

    /**
     * Applies [transform] to the current metadata of [folderName] and writes the result. Returns false
     * when the folder or a readable metadata is missing, or when a write fails (the previous content then
     * stays). A `meeting.json.part` left by an interrupted write is used when `meeting.json` is missing
     * or unreadable; a file written by a newer app version is never touched. With [strictGuide] a failure to
     * regenerate `CLAUDE.md` also makes the update fail (the commit of a transcript must not end with a stale
     * guide); otherwise it is logged only.
     */
    fun update(folderName: String, strictGuide: Boolean = false, transform: (MeetingMetadata) -> MeetingMetadata): Boolean =
        locked(folderName) { files ->
            val current = readCurrent(files) ?: return@locked false
            write(files, transform(current), strictGuide)
        }

    /** The current metadata of [folderName] (with the `.part` fallback), or null. Read under the same lock as the writes. */
    fun read(folderName: String): MeetingMetadata? {
        val files = folderFiles(folderName) ?: return null
        return lockFor(folderName).withLock { runCatching { readCurrent(files) }.getOrNull() }
    }

    /** Writes [meeting] without reading the current file (recovery of a missing or unreadable one). */
    fun replace(folderName: String, meeting: MeetingMetadata): Boolean =
        locked(folderName) { files -> write(files, meeting, strictGuide = false) }

    private inline fun locked(folderName: String, block: (FolderFiles) -> Boolean): Boolean {
        val files = folderFiles(folderName) ?: return false
        return lockFor(folderName).withLock {
            try {
                block(files)
            } catch (e: java.io.IOException) {
                warn("writing the metadata of $folderName failed", e)
                false
            }
        }
    }

    private fun readCurrent(files: FolderFiles): MeetingMetadata? {
        val main = files.readText(MeetingFolderLayout.METADATA_FILE)?.let(MeetingJson::decode)
        if (main is MeetingJson.DecodeResult.Success) return main.meeting
        if (main is MeetingJson.DecodeResult.UnsupportedVersion) return null
        val part = files.readText(MeetingFolderLayout.partName(MeetingFolderLayout.METADATA_FILE))?.let(MeetingJson::decode)
        return (part as? MeetingJson.DecodeResult.Success)?.meeting
    }

    private fun write(files: FolderFiles, meeting: MeetingMetadata, strictGuide: Boolean): Boolean {
        files.replaceText(MeetingFolderLayout.METADATA_FILE, MeetingJson.encode(meeting))
        files.delete(MeetingFolderLayout.partName(MeetingFolderLayout.METADATA_FILE))
        if (strictGuide) {
            files.replaceText(MeetingFolderLayout.GUIDE_FILE, ClaudeGuide.generate(meeting))
        } else {
            // The guide is derived from the metadata; a failure here must not turn a saved change into a failure.
            runCatching { files.replaceText(MeetingFolderLayout.GUIDE_FILE, ClaudeGuide.generate(meeting)) }
                .onFailure { warn("CLAUDE.md could not be regenerated", it) }
        }
        return true
    }

    companion object {
        private const val TAG = "MeetingMetadataStore"

        // Process-wide: the repository, the runner and the recovery use separate store instances.
        private val locks = ConcurrentHashMap<String, ReentrantLock>()

        private fun lockFor(folderName: String): ReentrantLock = locks.getOrPut(folderName) { ReentrantLock() }

        /** A store over the meeting folders of [storage]. */
        fun forStorage(storage: SafMeetingStorage): MeetingMetadataStore = MeetingMetadataStore(folderFiles = { name ->
            storage.findChild(storage.rootUri, name)?.takeIf { it.isDirectory }?.let { SafFolderFiles(storage, it.uri) }
        })
    }
}
