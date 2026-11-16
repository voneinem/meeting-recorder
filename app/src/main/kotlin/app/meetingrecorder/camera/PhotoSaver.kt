package app.meetingrecorder.camera

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.meetingrecorder.recording.RecordingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Connects [PhotoTaker] to the recording service and to the storage folder: the service creates
 * `photos/photo-NNN.jpg` (it knows the next number), this process writes the JPEG into it through
 * the persisted folder permission.
 */
class PhotoSaver(context: Context, private val controller: RecordingController) : PhotoTimeline {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun markPosition(): Long = controller.markPhotoPosition()

    override suspend fun createFile(): PhotoTarget? {
        val uri = controller.createPhotoFile() ?: return null
        return withContext(Dispatchers.IO) { DocumentTarget(uri) }
    }

    override suspend fun register(fileName: String, atFrame: Long) = controller.addPhoto(fileName, atFrame)

    private inner class DocumentTarget(private val uri: Uri) : PhotoTarget {
        override val fileName: String = displayName() ?: throw IOException("the photo file has no name")

        override fun openOutput(): OutputStream =
            resolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")

        override fun discard() {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }

        private fun displayName(): String? =
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
    }
}
