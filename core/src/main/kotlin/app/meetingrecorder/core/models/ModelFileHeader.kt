package app.meetingrecorder.core.models

import java.nio.ByteBuffer
import java.nio.ByteOrder

public sealed interface HeaderResult {
    public data class Speech(val languages: ModelLanguages) : HeaderResult

    public data class Rejected(val reason: String) : HeaderResult
}

/**
 * Recognises a speech recognition model from the first 48 bytes of a file, before anything is copied
 * (contracts/model-catalog.md, "Import protocol"). The header is the 4-byte magic `ggml` followed by
 * eleven little-endian `int32` values.
 */
public object ModelFileHeader {
    public const val SIZE: Int = 48

    private const val MAGIC = 0x67676d6c
    private const val AUDIO_CTX = 1500
    private const val TEXT_CTX = 448
    private const val VOCAB_ENGLISH_ONLY = 51_864
    private val vocabMultilingual = setOf(51_865, 51_866)
    private val melBins = setOf(80, 128)

    /** Offsets of the values inside the header, after the magic. */
    private const val N_VOCAB = 4
    private const val N_AUDIO_CTX = 8
    private const val N_TEXT_CTX = 24
    private const val N_MELS = 40

    public fun parse(first48Bytes: ByteArray): HeaderResult {
        if (first48Bytes.size < SIZE) return HeaderResult.Rejected("the file is too short to be a model (${first48Bytes.size} bytes)")
        val header = ByteBuffer.wrap(first48Bytes, 0, SIZE).order(ByteOrder.LITTLE_ENDIAN)
        if (header.getInt(0) != MAGIC) return HeaderResult.Rejected("the file does not start with the ggml magic")
        val vocab = header.getInt(N_VOCAB)
        if (header.getInt(N_AUDIO_CTX) != AUDIO_CTX) return HeaderResult.Rejected("n_audio_ctx is not $AUDIO_CTX")
        if (header.getInt(N_TEXT_CTX) != TEXT_CTX) return HeaderResult.Rejected("n_text_ctx is not $TEXT_CTX")
        if (header.getInt(N_MELS) !in melBins) return HeaderResult.Rejected("n_mels is not 80 or 128")
        return when (vocab) {
            VOCAB_ENGLISH_ONLY -> HeaderResult.Speech(ModelLanguages.ENGLISH_ONLY)
            in vocabMultilingual -> HeaderResult.Speech(ModelLanguages.MULTILINGUAL)
            else -> HeaderResult.Rejected("n_vocab $vocab is not a Whisper vocabulary")
        }
    }
}
