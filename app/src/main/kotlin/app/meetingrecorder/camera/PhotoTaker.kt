package app.meetingrecorder.camera

import java.io.OutputStream

/** The timeline and meeting side of a photo, implemented by the recording service client. */
interface PhotoTimeline {
    /** The timeline position now (frames appended), or a negative value when no meeting is active. */
    suspend fun markPosition(): Long

    /** Creates the next empty photo file in the current meeting, or null when that is not possible. */
    suspend fun createFile(): PhotoTarget?

    /** Records the finished photo [fileName] at [atFrame] in the meeting data. */
    suspend fun register(fileName: String, atFrame: Long)
}

/** An empty photo file. [discard] removes it again when the capture failed. */
interface PhotoTarget {
    val fileName: String
    fun openOutput(): OutputStream
    fun discard()
}

/** Takes a JPEG and writes it to [output]; throws when the capture or the write fails. */
fun interface PhotoCamera {
    suspend fun capture(output: OutputStream)
}

sealed interface PhotoResult {
    data class Saved(val fileName: String, val atFrame: Long) : PhotoResult
    data class Failed(val reason: Reason) : PhotoResult

    enum class Reason { NO_MEETING, NO_FILE, CAPTURE }
}

/**
 * One photo: the position on the audio timeline is taken first, at the moment of the tap (Principle V,
 * FR-016), so a slow capture or save never shifts it. Only after the JPEG is complete is it entered
 * in the meeting data; a failed capture leaves no file and no entry behind (FR-017).
 */
class PhotoTaker(private val timeline: PhotoTimeline, private val camera: PhotoCamera) {

    suspend fun take(): PhotoResult {
        val atFrame = timeline.markPosition()
        if (atFrame < 0) return PhotoResult.Failed(PhotoResult.Reason.NO_MEETING)
        val target = timeline.createFile() ?: return PhotoResult.Failed(PhotoResult.Reason.NO_FILE)
        try {
            target.openOutput().use { camera.capture(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            target.discard()
            throw e
        } catch (_: Exception) {
            target.discard()
            return PhotoResult.Failed(PhotoResult.Reason.CAPTURE)
        }
        timeline.register(target.fileName, atFrame)
        return PhotoResult.Saved(target.fileName, atFrame)
    }
}
