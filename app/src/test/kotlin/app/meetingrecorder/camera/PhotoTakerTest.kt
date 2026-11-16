package app.meetingrecorder.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhotoTakerTest {

    private class FakeTarget(override val fileName: String) : PhotoTarget {
        val bytes = ByteArrayOutputStream()
        var discarded = false
        override fun openOutput(): OutputStream = bytes
        override fun discard() {
            discarded = true
        }
    }

    private class FakeTimeline(var position: Long = 0, var hasFile: Boolean = true) : PhotoTimeline {
        val targets = mutableListOf<FakeTarget>()
        val registered = mutableListOf<Pair<String, Long>>()

        override suspend fun markPosition(): Long = position

        override suspend fun createFile(): PhotoTarget? {
            if (!hasFile) return null
            return FakeTarget("photo-%03d.jpg".format(targets.size + 1)).also { targets += it }
        }

        override suspend fun register(fileName: String, atFrame: Long) {
            registered += fileName to atFrame
        }
    }

    @Test
    fun `the position is taken before the capture even when the capture is slow`() = runBlocking {
        val timeline = FakeTimeline(position = 480_000)
        val release = CompletableDeferred<Unit>()
        val taker = PhotoTaker(timeline, PhotoCamera { out ->
            release.await()
            out.write(byteArrayOf(1, 2, 3))
        })
        val result = async(start = CoroutineStart.UNDISPATCHED) { taker.take() }
        timeline.position = 960_000 // the recording moves on while the photo is being saved
        release.complete(Unit)
        assertEquals(PhotoResult.Saved("photo-001.jpg", 480_000), result.await())
        assertEquals(listOf("photo-001.jpg" to 480_000L), timeline.registered)
        assertEquals(3, timeline.targets.single().bytes.size())
    }

    @Test
    fun `a photo after a pause reports the frame of the pause point`() = runBlocking {
        val timeline = FakeTimeline(position = 1_440_000) // the counter stands still while paused
        val taker = PhotoTaker(timeline, PhotoCamera { it.write(1) })
        val first = taker.take()
        val second = taker.take()
        assertIs<PhotoResult.Saved>(first)
        assertIs<PhotoResult.Saved>(second)
        assertEquals(listOf("photo-001.jpg" to 1_440_000L, "photo-002.jpg" to 1_440_000L), timeline.registered)
    }

    @Test
    fun `a failed capture removes the file and adds no entry`() = runBlocking {
        val timeline = FakeTimeline()
        val taker = PhotoTaker(timeline, PhotoCamera { throw IOException("camera error") })
        assertEquals(PhotoResult.Failed(PhotoResult.Reason.CAPTURE), taker.take())
        assertTrue(timeline.targets.single().discarded)
        assertTrue(timeline.registered.isEmpty())
    }

    @Test
    fun `no meeting means no photo`() = runBlocking {
        val timeline = FakeTimeline(position = -1)
        val taker = PhotoTaker(timeline, PhotoCamera { it.write(1) })
        assertEquals(PhotoResult.Failed(PhotoResult.Reason.NO_MEETING), taker.take())
        assertTrue(timeline.targets.isEmpty())
    }

    @Test
    fun `a file that cannot be created fails without capturing`() = runBlocking {
        val timeline = FakeTimeline(hasFile = false)
        var captured = false
        val taker = PhotoTaker(timeline, PhotoCamera { captured = true })
        assertEquals(PhotoResult.Failed(PhotoResult.Reason.NO_FILE), taker.take())
        assertFalse(captured)
        assertTrue(timeline.registered.isEmpty())
    }
}
