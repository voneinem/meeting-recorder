package app.meetingrecorder.core.audio

import app.meetingrecorder.core.audio.TestAudio.ReadOnlyStore
import app.meetingrecorder.core.wav.InMemorySeekableStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavPcmReaderTest {

    @Test
    fun `mono 16-bit samples become floats in minus one to one`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, shortArrayOf(0, 16384, -16384, 32767, -32768)))
        assertEquals(48_000, reader.sampleRate)
        assertEquals(1, reader.channels)
        assertEquals(5, reader.frameCount)
        assertContentEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1f), reader.readFrames(0, 5))
    }

    @Test
    fun `stereo frames are returned interleaved`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 2, shortArrayOf(16384, -16384, 8192, 0, 0, 8192)))
        assertEquals(2, reader.channels)
        assertEquals(3, reader.frameCount)
        assertContentEquals(floatArrayOf(0.5f, -0.5f, 0.25f, 0f, 0f, 0.25f), reader.readFrames(0, 3))
        assertContentEquals(floatArrayOf(0.25f, 0f), reader.readFrames(1, 1))
    }

    @Test
    fun `the frame count comes from the file length, not from the header`() {
        // A crash-truncated file: the header still claims zero bytes of data.
        val bytes = TestAudio.wav(48_000, 1, shortArrayOf(1, 2, 3, 4), declaredDataBytes = 0)
        assertEquals(4, WavPcmReader(InMemorySeekableStore(bytes)).frameCount)
        val lying = TestAudio.wav(48_000, 1, shortArrayOf(1, 2, 3, 4), declaredDataBytes = 4_000_000)
        assertEquals(4, WavPcmReader(InMemorySeekableStore(lying)).frameCount)
    }

    @Test
    fun `a trailing partial frame is ignored`() {
        val stereo = TestAudio.wav(48_000, 2, shortArrayOf(1, 2, 3, 4))
        for (extra in 1..3) {
            val reader = WavPcmReader(InMemorySeekableStore(stereo + ByteArray(extra)))
            assertEquals(2, reader.frameCount, "extra bytes: $extra")
            assertEquals(4, reader.readFrames(0, 100).size)
        }
    }

    @Test
    fun `44_1 kHz and 48 kHz headers are understood`() {
        assertEquals(44_100, WavPcmReader(TestAudio.store(44_100, 1, shortArrayOf(0))).sampleRate)
        assertEquals(48_000, WavPcmReader(TestAudio.store(48_000, 2, shortArrayOf(0, 0))).sampleRate)
    }

    @Test
    fun `an empty data chunk has no frames`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, ShortArray(0)))
        assertEquals(0, reader.frameCount)
        assertEquals(0, reader.readFrames(0, 10).size)
    }

    @Test
    fun `a file shorter than a header or with another header is unreadable`() {
        assertFailsWith<UnreadableWavException> { WavPcmReader(InMemorySeekableStore(ByteArray(43))) }
        assertFailsWith<UnreadableWavException> { WavPcmReader(InMemorySeekableStore(ByteArray(0))) }
        assertFailsWith<UnreadableWavException> { WavPcmReader(InMemorySeekableStore(ByteArray(100))) }
    }

    @Test
    fun `reading past the end returns what is there`() {
        val reader = WavPcmReader(TestAudio.store(48_000, 1, ShortArray(10) { it.toShort() }))
        assertEquals(4, reader.readFrames(6, 100).size)
        assertEquals(0, reader.readFrames(10, 5).size)
        assertEquals(0, reader.readFrames(500, 5).size)
        assertFailsWith<IllegalArgumentException> { reader.readFrames(-1, 5) }
    }

    @Test
    fun `blocks of one second add up to the whole file`() {
        val samples = TestAudio.tone(2 * 48_000 + 1234, 48_000, 440.0, channels = 2)
        val reader = WavPcmReader(TestAudio.store(48_000, 2, samples))
        val whole = reader.readFrames(0, Int.MAX_VALUE)
        val blocks = ArrayList<Float>()
        var position = 0L
        while (position < reader.frameCount) {
            val block = reader.readFrames(position, 48_000)
            blocks += block.toList()
            position += block.size / 2
        }
        assertContentEquals(whole, blocks.toFloatArray())
        assertEquals(samples.size, whole.size)
    }

    @Test
    fun `the reader never writes to the store`() {
        val store = ReadOnlyStore(TestAudio.store(48_000, 1, ShortArray(1000)))
        val reader = WavPcmReader(store)
        reader.readFrames(0, 1000)
        assertEquals(44L + 2000, store.bytesRead)
    }
}
