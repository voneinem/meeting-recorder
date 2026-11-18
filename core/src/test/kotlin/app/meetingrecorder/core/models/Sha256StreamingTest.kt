package app.meetingrecorder.core.models

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256StreamingTest {

    @Test
    fun `known vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256Streaming().hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256Streaming().update("abc".toByteArray()).hex())
        val million = Sha256Streaming()
        val block = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { million.update(block) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", million.hex())
    }

    @Test
    fun `any chunking gives the same digest`() {
        val data = Random(3).nextBytes(100_000)
        val whole = Sha256Streaming().update(data).hex()
        for (chunk in listOf(1, 7, 64, 4096, 99_999)) {
            val streaming = Sha256Streaming()
            var position = 0
            while (position < data.size) {
                val length = minOf(chunk, data.size - position)
                streaming.update(data, position, length)
                position += length
            }
            assertEquals(whole, streaming.hex(), "chunk $chunk")
        }
    }

    @Test
    fun `resume feeds the existing bytes first`() {
        val data = Random(4).nextBytes(10_000)
        val resumed = Sha256Streaming.resume(data.copyOf(6_000)).update(data, 6_000, 4_000)
        assertEquals(Sha256Streaming().update(data).hex(), resumed.hex())
    }

    @Test
    fun `reading the digest does not disturb the stream`() {
        val streaming = Sha256Streaming().update("ab".toByteArray())
        streaming.hex()
        assertEquals(Sha256Streaming().update("abc".toByteArray()).hex(), streaming.update("c".toByteArray()).hex())
    }
}
