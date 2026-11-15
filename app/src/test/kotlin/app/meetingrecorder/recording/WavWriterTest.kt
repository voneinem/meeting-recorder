package app.meetingrecorder.recording

import app.meetingrecorder.core.wav.SeekableStore
import app.meetingrecorder.core.wav.WavHeader
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WavWriterTest {

    /** In-memory file with hooks to stall or fail writes of audio data. */
    private class TestStore : SeekableStore {
        private var data = ByteArray(0)
        val dataWriteStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockDataWrites = false
        @Volatile var failDataWrites = false
        @Volatile var syncCount = 0

        @Synchronized fun snapshot(): ByteArray = data.copyOf()

        @Synchronized override fun length(): Long = data.size.toLong()

        @Synchronized override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            if (offset >= data.size) return -1
            val count = minOf(length.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), buffer, bufferOffset, count)
            return count
        }

        override fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
            if (offset >= WavHeader.SIZE) {
                dataWriteStarted.countDown()
                if (blockDataWrites) release.await()
                if (failDataWrites) throw IOException("No space left on device")
            }
            synchronized(this) {
                val end = offset.toInt() + length
                if (end > data.size) data = data.copyOf(end)
                System.arraycopy(buffer, bufferOffset, data, offset.toInt(), length)
            }
        }

        @Synchronized override fun truncate(length: Long) {
            data = data.copyOf(length.toInt())
        }

        override fun sync() {
            syncCount++
        }
    }

    private class Recorder : WavWriter.Listener {
        val drops = mutableListOf<Pair<Long, Long>>()
        val failures = mutableListOf<IOException>()
        override fun onDropped(atFrame: Long, frames: Long) {
            synchronized(drops) { drops += atFrame to frames }
        }
        override fun onWriteFailed(error: IOException) {
            synchronized(failures) { failures += error }
        }
    }

    private fun samples(frames: Int, channels: Int, start: Int = 1) =
        ShortArray(frames * channels) { (start + it).toShort() }

    @Test
    fun `the header is written at start and audio is appended after it`() {
        val store = TestStore()
        val writer = WavWriter(store, sampleRate = 48_000, channels = 1, listener = Recorder())
        assertTrue(writer.append(samples(100, 1), 100))
        assertTrue(writer.append(samples(50, 1, start = 1000), 50))
        writer.close()

        val bytes = store.snapshot()
        val format = assertNotNull(WavHeader.parse(bytes))
        assertEquals(48_000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals(300, format.declaredDataBytes)
        assertEquals(WavHeader.SIZE + 300, bytes.size)
        assertEquals(150, writer.framesAppended)
        assertEquals(150, writer.framesWritten)
        // first sample is 1 (little endian), the 101st frame starts with 1000 = 0x03E8
        assertEquals(1, bytes[WavHeader.SIZE].toInt())
        assertEquals(0, bytes[WavHeader.SIZE + 1].toInt())
        assertEquals(0xE8, bytes[WavHeader.SIZE + 200].toInt() and 0xFF)
        assertEquals(0x03, bytes[WavHeader.SIZE + 201].toInt())
        assertTrue(store.syncCount > 0)
    }

    @Test
    fun `stereo frames are interleaved and counted as frames`() {
        val store = TestStore()
        val writer = WavWriter(store, 48_000, 2, Recorder())
        assertTrue(writer.append(samples(10, 2), 10))
        writer.close()
        val format = assertNotNull(WavHeader.parse(store.snapshot()))
        assertEquals(2, format.channels)
        assertEquals(40, format.declaredDataBytes)
        assertEquals(10, writer.framesWritten)
    }

    @Test
    fun `the header is updated every second of audio without a flush`() {
        val store = TestStore()
        val writer = WavWriter(store, sampleRate = 1_000, channels = 1, listener = Recorder())
        repeat(5) { assertTrue(writer.append(samples(500, 1), 500)) } // 2.5 s
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var declared = 0L
        while (System.nanoTime() < deadline) {
            declared = WavHeader.parse(store.snapshot())?.declaredDataBytes ?: 0
            if (declared >= 2_000) break
            Thread.sleep(10)
        }
        assertTrue(declared >= 2_000, "header declared $declared bytes before any flush")
        writer.close()
    }

    @Test
    fun `a full queue drops audio without blocking and reports the gap`() {
        val store = TestStore().apply { blockDataWrites = true }
        val recorder = Recorder()
        val writer = WavWriter(store, 48_000, 1, recorder, queueCapacityChunks = 3)

        assertTrue(writer.append(samples(100, 1), 100)) // taken by the writer, which stalls on it
        assertTrue(store.dataWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(writer.append(samples(100, 1), 100)) // queued
        assertTrue(writer.append(samples(100, 1), 100))
        assertTrue(writer.append(samples(100, 1), 100))
        assertFalse(writer.append(samples(100, 1), 100)) // queue full: dropped, not blocked
        assertFalse(writer.append(samples(60, 1), 60))
        assertEquals(400, writer.framesAppended, "dropped frames are not part of the timeline")

        store.release.countDown()
        writer.flush() // drains the queue and reports the gap
        assertEquals(listOf(400L to 160L), synchronized(recorder.drops) { recorder.drops.toList() })
        assertTrue(writer.append(samples(100, 1), 100)) // space again
        writer.close()

        assertEquals(1, synchronized(recorder.drops) { recorder.drops.size })
        assertEquals(500, writer.framesAppended)
        assertEquals(500, writer.framesWritten)
        assertEquals(1_000, assertNotNull(WavHeader.parse(store.snapshot())).declaredDataBytes)
    }

    @Test
    fun `a gap at the end is reported when the writer is closed`() {
        val store = TestStore().apply { blockDataWrites = true }
        val recorder = Recorder()
        val writer = WavWriter(store, 48_000, 1, recorder, queueCapacityChunks = 1)
        assertTrue(writer.append(samples(10, 1), 10))
        assertTrue(store.dataWriteStarted.await(5, TimeUnit.SECONDS))
        assertTrue(writer.append(samples(10, 1), 10))
        assertFalse(writer.append(samples(10, 1), 10))
        store.release.countDown()
        writer.close()
        assertEquals(listOf(20L to 10L), synchronized(recorder.drops) { recorder.drops.toList() })
    }

    @Test
    fun `a write failure is reported once and later audio is discarded without hanging`() {
        val store = TestStore().apply { failDataWrites = true }
        val recorder = Recorder()
        val writer = WavWriter(store, 48_000, 1, recorder)
        writer.append(samples(100, 1), 100)
        writer.flush() // must not hang
        assertEquals(1, synchronized(recorder.failures) { recorder.failures.size })
        assertNotNull(writer.writeError)
        assertFalse(writer.append(samples(100, 1), 100))
        writer.close()
        assertEquals(0, writer.framesWritten)
        assertEquals(1, synchronized(recorder.failures) { recorder.failures.size })
    }

    @Test
    fun `an empty append is accepted and counts nothing`() {
        val writer = WavWriter(TestStore(), 48_000, 1, Recorder())
        assertTrue(writer.append(ShortArray(0), 0))
        writer.close()
        assertEquals(0, writer.framesAppended)
    }
}
