package app.meetingrecorder.core.wav

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavHeaderTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `canonical header of an empty mono file`() {
        val expected = "52494646" + "24000000" + "57415645" + // RIFF, 36, WAVE
            "666d7420" + "10000000" + "0100" + "0100" + // fmt , 16, PCM, 1 channel
            "80bb0000" + "00770100" + "0200" + "1000" + // 48000 Hz, 96000 B/s, align 2, 16 bit
            "64617461" + "00000000" // data, 0
        assertEquals(expected, hex(WavHeader.build(48_000, 1)))
    }

    @Test
    fun `canonical header of a stereo file with data`() {
        val header = WavHeader.build(48_000, 2, dataBytes = 192_000)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(WavHeader.SIZE, header.size)
        assertEquals(36 + 192_000, buffer.getInt(4))
        assertEquals(2, buffer.getShort(22).toInt())
        assertEquals(48_000, buffer.getInt(24))
        assertEquals(192_000, buffer.getInt(28)) // byte rate
        assertEquals(4, buffer.getShort(32).toInt()) // block align
        assertEquals(192_000, buffer.getInt(40))
    }

    @Test
    fun `parse reads back what build wrote`() {
        val format = assertNotNull(WavHeader.parse(WavHeader.build(44_100, 2, 4_000)))
        assertEquals(44_100, format.sampleRate)
        assertEquals(2, format.channels)
        assertEquals(16, format.bitsPerSample)
        assertEquals(4_000, format.declaredDataBytes)
        assertEquals(4, format.blockAlign)
    }

    @Test
    fun `parse rejects anything that is not a 16 bit pcm wav header`() {
        assertNull(WavHeader.parse(ByteArray(10)))
        assertNull(WavHeader.parse(ByteArray(44)))
        val notRiff = WavHeader.build(48_000, 1).also { it[0] = 'X'.code.toByte() }
        assertNull(WavHeader.parse(notRiff))
        val floatFormat = WavHeader.build(48_000, 1).also { it[20] = 3 }
        assertNull(WavHeader.parse(floatFormat))
    }

    @Test
    fun `build rejects invalid arguments`() {
        assertFailsWith<IllegalArgumentException> { WavHeader.build(0, 1) }
        assertFailsWith<IllegalArgumentException> { WavHeader.build(48_000, 3) }
        assertFailsWith<IllegalArgumentException> { WavHeader.build(48_000, 1, -1) }
        assertFailsWith<IllegalArgumentException> { WavHeader.build(48_000, 1, WavHeader.MAX_DECLARED_DATA_BYTES + 1) }
    }

    @Test
    fun `updateSizes rewrites only the two size fields`() {
        val data = ByteArray(1_000) { (it % 251).toByte() }
        val store = InMemorySeekableStore(WavHeader.build(48_000, 1) + data)
        WavHeader.updateSizes(store, 1_000)
        val format = assertNotNull(WavHeader.parse(store.bytes.copyOf(WavHeader.SIZE)))
        assertEquals(1_000, format.declaredDataBytes)
        assertEquals(36 + 1_000, ByteBuffer.wrap(store.bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertContentEquals(data, store.bytes.copyOfRange(WavHeader.SIZE, WavHeader.SIZE + 1_000))
    }

    @Test
    fun `sizes above the 32 bit limit are rejected`() {
        val store = InMemorySeekableStore(WavHeader.build(48_000, 1))
        assertFailsWith<IllegalArgumentException> { WavHeader.updateSizes(store, WavHeader.MAX_DECLARED_DATA_BYTES + 1) }
    }

    @Test
    fun `repair fixes a header that still says zero`() {
        val audio = ByteArray(96_000) { 1 } // one second, mono
        val store = InMemorySeekableStore(WavHeader.build(48_000, 1, 0) + audio)
        val result = assertIs<RepairResult.Repaired>(WavHeader.repair(store))
        assertEquals(96_000, result.dataBytes)
        assertEquals(48_000, result.frames)
        assertEquals(0, result.truncatedBytes)
        assertTrue(result.headerWasWrong)
        assertEquals(96_000, assertNotNull(WavHeader.parse(store.bytes)).declaredDataBytes)
        assertTrue(store.syncCount > 0)
    }

    @Test
    fun `repair of a healthy file changes nothing and reports it`() {
        val audio = ByteArray(400)
        val store = InMemorySeekableStore(WavHeader.build(48_000, 2, 400) + audio)
        val before = store.bytes
        val result = assertIs<RepairResult.Repaired>(WavHeader.repair(store))
        assertFalse(result.headerWasWrong)
        assertEquals(100, result.frames)
        assertContentEquals(before, store.bytes)
    }

    @Test
    fun `repair drops a trailing partial frame`() {
        val audio = ByteArray(4 * 10 + 3) // ten stereo frames and 3 stray bytes
        val store = InMemorySeekableStore(WavHeader.build(48_000, 2, 0) + audio)
        val result = assertIs<RepairResult.Repaired>(WavHeader.repair(store))
        assertEquals(40, result.dataBytes)
        assertEquals(10, result.frames)
        assertEquals(3, result.truncatedBytes)
        assertEquals((WavHeader.SIZE + 40).toLong(), store.length())
    }

    @Test
    fun `repair of a file with only a header gives an empty but valid file`() {
        val store = InMemorySeekableStore(WavHeader.build(48_000, 1, 0))
        val result = assertIs<RepairResult.Repaired>(WavHeader.repair(store))
        assertEquals(0, result.frames)
        assertFalse(result.headerWasWrong)
    }

    @Test
    fun `files that cannot be repaired are reported`() {
        assertIs<RepairResult.Unrecoverable>(WavHeader.repair(InMemorySeekableStore(ByteArray(10))))
        assertIs<RepairResult.Unrecoverable>(WavHeader.repair(InMemorySeekableStore(ByteArray(2_000))))
        assertIs<RepairResult.Unrecoverable>(WavHeader.repair(InMemorySeekableStore()))
    }
}
