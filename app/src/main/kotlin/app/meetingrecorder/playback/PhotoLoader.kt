package app.meetingrecorder.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max

/** Decodes photos at a reduced size. [ImageDecoder] applies the EXIF orientation of the JPEG. */
object PhotoLoader {

    /** A bitmap whose longer edge is at most about [maxEdge] pixels, or null when it cannot be decoded. */
    fun load(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val largest = max(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, largest / maxEdge))
        }
    }.getOrNull()
}
