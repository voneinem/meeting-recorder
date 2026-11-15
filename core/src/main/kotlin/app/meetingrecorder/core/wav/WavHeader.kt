package app.meetingrecorder.core.wav

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Format fields of a canonical 44-byte PCM WAV header. [declaredDataBytes] is the size the header claims. */
public data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val declaredDataBytes: Long,
) {
    val blockAlign: Int get() = channels * bitsPerSample / BITS_PER_BYTE
}

/**
 * Canonical 44-byte PCM WAV header (RIFF/WAVE, format tag 1, 16-bit little endian).
 *
 * PCM data is appended at a fixed position, so a file is recoverable from its length alone: the
 * header sizes are an optimization that [repair] re-derives (research R-3).
 */
public object WavHeader {
    public const val SIZE: Int = 44
    public const val BITS_PER_SAMPLE: Int = 16

    /** Largest data size a 32-bit WAV header can declare (RIFF size is data size + 36). */
    public const val MAX_DECLARED_DATA_BYTES: Long = 0xFFFFFFFFL - 36

    private const val RIFF_SIZE_OFFSET = 4L
    private const val DATA_SIZE_OFFSET = 40L
    private const val FMT_CHUNK_SIZE = 16
    private const val PCM_FORMAT_TAG = 1

    public fun build(sampleRate: Int, channels: Int, dataBytes: Long = 0): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(dataBytes in 0..MAX_DECLARED_DATA_BYTES) { "dataBytes out of range: $dataBytes" }
        val blockAlign = channels * BITS_PER_SAMPLE / BITS_PER_BYTE
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((dataBytes + 36).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(FMT_CHUNK_SIZE)
        buffer.putShort(PCM_FORMAT_TAG.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * blockAlign)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes.toInt())
        return buffer.array()
    }

    /** Parses a canonical header, or returns null when [bytes] is not a 16-bit PCM WAV header. */
    public fun parse(bytes: ByteArray): WavFormat? {
        if (bytes.size < SIZE) return null
        val buffer = ByteBuffer.wrap(bytes, 0, SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, TAG_LENGTH, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(12) != "fmt " || tag(36) != "data") return null
        if (buffer.getInt(16) != FMT_CHUNK_SIZE) return null
        if (buffer.getShort(20).toInt() != PCM_FORMAT_TAG) return null
        val channels = buffer.getShort(22).toInt()
        val sampleRate = buffer.getInt(24)
        val bits = buffer.getShort(34).toInt()
        if (channels !in 1..2 || sampleRate <= 0 || bits != BITS_PER_SAMPLE) return null
        return WavFormat(sampleRate, channels, bits, buffer.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    /** Rewrites the two size fields of the header at the start of [store] for [dataBytes] of audio. */
    public fun updateSizes(store: SeekableStore, dataBytes: Long) {
        require(dataBytes in 0..MAX_DECLARED_DATA_BYTES) { "dataBytes out of range: $dataBytes" }
        store.writeAt(RIFF_SIZE_OFFSET, littleEndianInt(dataBytes + 36))
        store.writeAt(DATA_SIZE_OFFSET, littleEndianInt(dataBytes))
    }

    /**
     * Makes the file in [store] a valid WAV whose header matches its length: drops a trailing partial
     * frame and rewrites the sizes. The header's format fields must already be valid.
     */
    public fun repair(store: SeekableStore): RepairResult {
        val length = store.length()
        if (length < SIZE) return RepairResult.Unrecoverable("file is shorter than a WAV header ($length bytes)")
        val header = ByteArray(SIZE)
        if (store.readAt(0, header) < SIZE) return RepairResult.Unrecoverable("header could not be read")
        val format = parse(header) ?: return RepairResult.Unrecoverable("header is not a 16-bit PCM WAV header")

        val available = length - SIZE
        val alignedBytes = available - available % format.blockAlign
        val truncatedBytes = available - alignedBytes
        if (truncatedBytes > 0) store.truncate(SIZE + alignedBytes)
        val declared = minOf(alignedBytes, MAX_DECLARED_DATA_BYTES)
        val headerWasWrong = format.declaredDataBytes != declared
        updateSizes(store, declared)
        store.sync()
        return RepairResult.Repaired(
            format = format,
            dataBytes = alignedBytes,
            frames = alignedBytes / format.blockAlign,
            truncatedBytes = truncatedBytes,
            headerWasWrong = headerWasWrong,
        )
    }

    private fun littleEndianInt(value: Long): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private const val TAG_LENGTH = 4
}

private const val BITS_PER_BYTE = 8

public sealed interface RepairResult {
    public data class Repaired(
        val format: WavFormat,
        /** Bytes of audio data now in the file (a whole number of frames). */
        val dataBytes: Long,
        val frames: Long,
        /** Bytes of a trailing partial frame that were removed. */
        val truncatedBytes: Long,
        /** True when the header sizes did not match the file before the repair. */
        val headerWasWrong: Boolean,
    ) : RepairResult

    public data class Unrecoverable(val reason: String) : RepairResult
}
