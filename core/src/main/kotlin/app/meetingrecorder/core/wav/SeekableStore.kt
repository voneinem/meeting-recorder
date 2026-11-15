package app.meetingrecorder.core.wav

/**
 * Minimal random-access file abstraction. `:core` stays free of Android classes; the app implements
 * this on top of a `ParcelFileDescriptor`, tests use an in-memory implementation.
 */
public interface SeekableStore {
    public fun length(): Long

    /** Reads up to [length] bytes at [offset] into [buffer]; returns the bytes read, or -1 at end of file. */
    public fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset): Int

    /** Writes [length] bytes of [buffer] at [offset], growing the file when needed. */
    public fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset)

    public fun truncate(length: Long)

    /** Forces written data to stable storage. */
    public fun sync()
}
