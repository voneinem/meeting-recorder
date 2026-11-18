package app.meetingrecorder.models

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

/** The file the user picked with `ACTION_OPEN_DOCUMENT`; no storage permission is needed for it. */
class UriImportSource(private val resolver: ContentResolver, private val uri: Uri) : ImportSource {
    private val columns: Pair<String?, Long?> by lazy {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                name to size
            }
        }.getOrNull() ?: (null to null)
    }

    override val displayName: String? get() = columns.first
    override val sizeBytes: Long? get() = columns.second

    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
}
