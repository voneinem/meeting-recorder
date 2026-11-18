package app.meetingrecorder.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModelFileHeaderTest {

    private fun fixture(name: String): ByteArray {
        val text = checkNotNull(ModelFileHeaderTest::class.java.getResourceAsStream("/models/$name.header.hex")) { "missing $name" }
            .readBytes().toString(Charsets.UTF_8).trim()
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val candidates = listOf(
        "ggml-tiny-q5_1.bin", "ggml-base-q5_1.bin", "ggml-base-q8_0.bin", "ggml-small-q5_1.bin",
        "ggml-small-q8_0.bin", "ggml-medium-q5_0.bin", "ggml-large-v3-turbo-q5_0.bin", "ggml-large-v3-turbo-q8_0.bin",
    )

    @Test
    fun `every catalog candidate is accepted as a multilingual speech model`() {
        for (name in candidates) {
            assertEquals(HeaderResult.Speech(ModelLanguages.MULTILINGUAL), ModelFileHeader.parse(fixture(name)), name)
        }
    }

    @Test
    fun `an English-only vocabulary is recognised`() {
        val bytes = fixture("ggml-tiny-q5_1.bin")
        bytes[4] = 0x98.toByte() // n_vocab 51864
        assertEquals(HeaderResult.Speech(ModelLanguages.ENGLISH_ONLY), ModelFileHeader.parse(bytes))
    }

    @Test
    fun `the voice-activity model has the same magic but is rejected`() {
        assertIs<HeaderResult.Rejected>(ModelFileHeader.parse(fixture("ggml-silero-v6.2.0.bin")))
    }

    @Test
    fun `bad magic and a truncated header are rejected with a reason`() {
        val badMagic = ModelFileHeader.parse(fixture("bad-magic"))
        assertIs<HeaderResult.Rejected>(badMagic)
        assertEquals(true, badMagic.reason.isNotBlank())
        val truncated = ModelFileHeader.parse(fixture("truncated"))
        assertIs<HeaderResult.Rejected>(truncated)
        assertEquals(true, truncated.reason.isNotBlank())
        assertIs<HeaderResult.Rejected>(ModelFileHeader.parse(ByteArray(0)))
    }

    @Test
    fun `each value of the rule is checked`() {
        fun with(offset: Int, value: Int): HeaderResult {
            val bytes = fixture("ggml-small-q5_1.bin")
            for (i in 0 until 4) bytes[offset + i] = (value shr (8 * i)).toByte()
            return ModelFileHeader.parse(bytes)
        }
        assertIs<HeaderResult.Rejected>(with(8, 1499)) // n_audio_ctx
        assertIs<HeaderResult.Rejected>(with(24, 447)) // n_text_ctx
        assertIs<HeaderResult.Rejected>(with(40, 40)) // n_mels
        assertIs<HeaderResult.Rejected>(with(4, 50_000)) // n_vocab
        assertIs<HeaderResult.Speech>(with(40, 128))
        assertIs<HeaderResult.Speech>(with(4, 51_866))
    }
}
