package app.meetingrecorder.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import app.meetingrecorder.core.folder.MeetingFolderLayout

/**
 * How [SafMeetingStorage.replaceText] replaces a file: [STAGED] writes `<name>.part`, then swaps it in
 * (a crash leaves either the old file or a complete `.part`); [DIRECT] truncates and writes in place, for
 * providers that cannot rename or delete (research R-12).
 */
enum class ReplaceStrategy { STAGED, DIRECT }

/** A file or folder inside the storage folder. */
data class DocumentRef(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/**
 * Access to the user-chosen storage folder through the Storage Access Framework (research R-8).
 * Listing uses a single projection query per folder; audio and metadata are written through
 * file descriptors so that the header can be patched and the file synced.
 */
class SafMeetingStorage(context: Context, private val treeUri: Uri) {
    private val resolver = context.contentResolver

    /** What the provider supports, read from `COLUMN_FLAGS` by [probe]; [ReplaceStrategy.DIRECT] until then. */
    @Volatile
    var replaceStrategy: ReplaceStrategy = ReplaceStrategy.DIRECT
        private set

    /** The document that represents the storage folder itself. */
    val rootUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** True while the persisted read and write permission for the tree is still granted. */
    fun hasPersistedPermission(): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }

    /**
     * Checks that the folder is usable: creates a test file, writes, patches at offset 0, reads back,
     * syncs and deletes it. Providers that fail are rejected (research R-8).
     */
    fun probe(): Result<Unit> = runCatching {
        val probe = createFile(rootUri, PROBE_NAME, MIME_OCTET_STREAM)
            ?: throw IOException("the folder does not allow creating files")
        try {
            replaceStrategy = strategyFor(documentFlags(probe))
            ParcelSeekableStore(openDescriptor(probe, "rw")).use { store ->
                store.writeAt(0, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
                store.writeAt(0, byteArrayOf(9))
                store.sync()
                val back = ByteArray(8)
                if (store.readAt(0, back) != 8 || back[0] != 9.toByte() || back[7] != 8.toByte()) {
                    throw IOException("the folder does not support reading and patching files")
                }
            }
        } finally {
            delete(probe)
        }
    }

    fun listChildren(directory: Uri): List<DocumentRef> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(directory))
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<DocumentRef>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result += DocumentRef(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)),
                    name = cursor.getString(1),
                    isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                    sizeBytes = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                    lastModifiedMs = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                )
            }
        }
        return result
    }

    fun findChild(directory: Uri, name: String): DocumentRef? = listChildren(directory).firstOrNull { it.name == name }

    fun createDirectory(parent: Uri, name: String): Uri? =
        DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)

    fun createFile(parent: Uri, name: String, mimeType: String): Uri? =
        DocumentsContract.createDocument(resolver, parent, mimeType, name)

    /** Opens a file for random access ("rw"); the caller closes the store. */
    fun openStore(file: Uri): ParcelSeekableStore = ParcelSeekableStore(openDescriptor(file, "rw"))

    /** Replaces the content of [file] with [text] in a single truncating write. */
    fun writeText(file: Uri, text: String) {
        openDescriptor(file, "wt").use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }

    /**
     * Replaces (or creates) [name] in [parent] with [text] and returns the resulting document. With
     * [ReplaceStrategy.STAGED] a crash at any step leaves the old file or a complete `<name>.part`; the
     * strategy is chosen from the flags of the file being replaced. Throws [IOException] on failure.
     */
    fun replaceText(parent: Uri, name: String, text: String, mimeType: String = MIME_OCTET_STREAM): Uri {
        val children = listChildren(parent)
        val existing = children.firstOrNull { it.name == name && !it.isDirectory }
        if (existing == null) {
            val created = createFile(parent, name, mimeType) ?: throw IOException("cannot create $name")
            writeText(created, text)
            return created
        }
        if (strategyFor(documentFlags(existing.uri)) == ReplaceStrategy.DIRECT) {
            writeText(existing.uri, text)
            return existing.uri
        }
        val partName = MeetingFolderLayout.partName(name)
        children.firstOrNull { it.name == partName }?.let { delete(it.uri) }
        // octet-stream: a provider keeps the exact name `<name>.part` only for this type (see SafFolderFiles.mimeTypeOf)
        val part = createFile(parent, partName, MIME_OCTET_STREAM) ?: throw IOException("cannot create $partName")
        openDescriptor(part, "wt").use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            descriptor.fileDescriptor.sync()
        }
        if (!delete(existing.uri)) throw IOException("cannot remove the old $name; the new content is in $partName")
        return rename(part, name) ?: throw IOException("cannot rename $partName to $name; the new content is in $partName")
    }

    /** Renames [document]; returns the renamed document or null when the provider refuses. */
    fun rename(document: Uri, newName: String): Uri? =
        runCatching { DocumentsContract.renameDocument(resolver, document, newName) }.getOrNull()

    /** Opens [file] for random access positioned for appending: use [ParcelSeekableStore.append]. */
    fun appendStore(file: Uri): ParcelSeekableStore = ParcelSeekableStore(openDescriptor(file, "rw"))

    /** Opens [file] for reading only; writes through the store fail. */
    fun openReadOnlyStore(file: Uri): ParcelSeekableStore = ParcelSeekableStore(openDescriptor(file, "r"), writable = false)

    private fun documentFlags(document: Uri): Int =
        resolver.query(document, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0
        } ?: 0

    private fun strategyFor(flags: Int): ReplaceStrategy =
        if (flags and Document.FLAG_SUPPORTS_RENAME != 0 && flags and Document.FLAG_SUPPORTS_DELETE != 0) {
            ReplaceStrategy.STAGED
        } else {
            ReplaceStrategy.DIRECT
        }

    fun readText(file: Uri): String? = runCatching {
        openDescriptor(file, "r").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()

    /** Deletes a file or a whole folder. Returns true when it is gone. */
    fun delete(document: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, document) }.getOrDefault(false)

    /** Bytes still free where the storage folder lives, measured through a short-lived test file. */
    fun freeBytes(): Long? = runCatching {
        val probe = createFile(rootUri, PROBE_NAME, MIME_OCTET_STREAM) ?: return@runCatching null
        try {
            ParcelSeekableStore(openDescriptor(probe, "rw")).use { it.freeBytes() }
        } finally {
            delete(probe)
        }
    }.getOrNull()

    private fun openDescriptor(file: Uri, mode: String) =
        resolver.openFileDescriptor(file, mode) ?: throw IOException("cannot open $file")

    companion object {
        const val MIME_JSON = "application/json"
        const val MIME_MARKDOWN = "text/markdown"
        const val MIME_WAV = "audio/x-wav"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_OCTET_STREAM = "application/octet-stream"
        private const val PROBE_NAME = ".meeting-recorder-probe"
    }
}
