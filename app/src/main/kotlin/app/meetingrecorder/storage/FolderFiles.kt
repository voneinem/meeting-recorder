package app.meetingrecorder.storage

import android.net.Uri
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.wav.SeekableStore
import java.io.Closeable

/** One entry of a meeting folder. */
data class FolderEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

/** A [SeekableStore] that must be closed. */
interface FileStore : SeekableStore, Closeable {
    /** Appends [length] bytes of [buffer] at the current end of the file. */
    fun append(buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) {
        writeAt(length(), buffer, bufferOffset, length)
    }
}

/**
 * The files of one meeting folder, by name. A small seam over the Storage Access Framework so that the
 * commit, recovery and metadata logic can be tested against a fake that fails at a chosen step.
 * All calls do blocking storage I/O.
 */
interface FolderFiles {
    fun list(): List<FolderEntry>

    /** The text of [name], or null when it does not exist or cannot be read. */
    fun readText(name: String): String?

    /**
     * Replaces (or creates) [name] with [text] through the staged protocol where the provider allows it:
     * `<name>.part` is written and synced, the old file deleted, the part renamed. Throws `IOException`.
     */
    fun replaceText(name: String, text: String)

    /** Opens [name] for appending (see [FileStore.append]); the file must exist. */
    fun appendStore(name: String): FileStore

    fun openReadOnlyStore(name: String): FileStore

    /** Deletes a file or folder; true when it is gone afterwards (also when it never existed). */
    fun delete(name: String): Boolean
}

/** [FolderFiles] of one meeting folder in the storage tree. */
class SafFolderFiles(private val storage: SafMeetingStorage, private val folder: Uri) : FolderFiles {
    override fun list(): List<FolderEntry> =
        storage.listChildren(folder).map { FolderEntry(it.name, it.isDirectory, it.sizeBytes) }

    override fun readText(name: String): String? = storage.findChild(folder, name)?.let { storage.readText(it.uri) }

    override fun replaceText(name: String, text: String) {
        storage.replaceText(folder, name, text, mimeTypeOf(name))
    }

    override fun appendStore(name: String): FileStore = storage.appendStore(requireChild(name))

    override fun openReadOnlyStore(name: String): FileStore = storage.openReadOnlyStore(requireChild(name))

    override fun delete(name: String): Boolean {
        val child = storage.findChild(folder, name) ?: return true
        return storage.delete(child.uri)
    }

    private fun requireChild(name: String): Uri =
        storage.findChild(folder, name)?.uri ?: throw java.io.FileNotFoundException("$name does not exist")

    /**
     * A staging file is always `application/octet-stream`: the system storage provider appends the extension of
     * the type to a name that does not fit it, so `transcript.json.part` created as JSON becomes
     * `transcript.json.part.json` and can no longer be found by its name (found on the phone, S13).
     */
    private fun mimeTypeOf(name: String): String = when {
        name.endsWith(MeetingFolderLayout.PART_SUFFIX) -> SafMeetingStorage.MIME_OCTET_STREAM
        name.endsWith(".json") -> SafMeetingStorage.MIME_JSON
        name.endsWith(".md") -> SafMeetingStorage.MIME_MARKDOWN
        else -> SafMeetingStorage.MIME_OCTET_STREAM
    }
}
