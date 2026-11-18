package app.meetingrecorder.debug

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A minimal document provider for instrumented tests (debug builds only). Document ids are paths
 * relative to `cache/test-documents`, so a test tree is simply a folder there. The provider lives in
 * the app's main process; the recording service reaches it over binder like any other provider.
 */
class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var base: File

    @Volatile
    private var renameSupported = true

    override fun onCreate(): Boolean {
        base = File(checkNotNull(context).cacheDir, BASE_DIR).apply { mkdirs() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        fileFor(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { addRow(cursor, it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val file = File(fileFor(parentDocumentId), if (mimeType == Document.MIME_TYPE_DIR) displayName else nameForMime(displayName, mimeType))
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return idFor(file)
    }

    /**
     * What the platform's external storage provider does (`FileUtils.buildUniqueFile`; never for `application/octet-stream`): when the extension of
     * [displayName] does not belong to [mimeType], the extension of the type is appended, so a file created
     * as `transcript.json.part` with the type `application/json` becomes `transcript.json.part.json`.
     */
    private fun nameForMime(displayName: String, mimeType: String): String {
        if (mimeType == "application/octet-stream") return displayName
        val map = android.webkit.MimeTypeMap.getSingleton()
        val extension = displayName.substringAfterLast('.', "")
        if (extension.isNotEmpty() && map.getMimeTypeFromExtension(extension.lowercase()) == mimeType) return displayName
        val typeExtension = map.getExtensionFromMimeType(mimeType) ?: return displayName
        return "$displayName.$typeExtension"
    }

    override fun deleteDocument(documentId: String) {
        fileFor(documentId).deleteRecursively()
    }

    /** Like the common providers: a name that already exists is refused (the caller must delete first). */
    override fun renameDocument(documentId: String, displayName: String): String {
        if (!renameSupported) throw UnsupportedOperationException("rename is switched off")
        val source = fileFor(documentId)
        val target = File(source.parentFile, displayName)
        if (target.exists()) throw IllegalStateException("a document named $displayName already exists")
        if (!source.renameTo(target)) throw FileNotFoundException("cannot rename $documentId")
        return idFor(target)
    }

    /** Test switches: `setRenameSupported` with `extras.putBoolean("supported", ...)` toggles `FLAG_SUPPORTS_RENAME`. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_SET_RENAME_SUPPORTED) {
            renameSupported = extras?.getBoolean(EXTRA_SUPPORTED, true) ?: true
            return Bundle.EMPTY
        }
        return super.call(method, arg, extras)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    private fun fileFor(documentId: String): File {
        val file = File(base, documentId).canonicalFile
        if (!file.path.startsWith(base.canonicalPath)) throw FileNotFoundException("outside the test tree: $documentId")
        return file
    }

    private fun idFor(file: File): String = file.relativeTo(base.canonicalFile).path

    private fun addRow(cursor: MatrixCursor, file: File) {
        val isDir = file.isDirectory
        val flags = Document.FLAG_SUPPORTS_DELETE or
            (if (renameSupported) Document.FLAG_SUPPORTS_RENAME else 0) or
            (if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idFor(file))
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    companion object {
        const val AUTHORITY = "app.meetingrecorder.testdocuments"
        const val BASE_DIR = "test-documents"
        const val METHOD_SET_RENAME_SUPPORTED = "setRenameSupported"
        const val EXTRA_SUPPORTED = "supported"

        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE)
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
