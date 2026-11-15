package app.meetingrecorder.core.wav

/** Test double for [SeekableStore] backed by a growable byte array. */
class InMemorySeekableStore(initial: ByteArray = ByteArray(0)) : SeekableStore {
    private var data: ByteArray = initial.copyOf()
    var syncCount: Int = 0
        private set

    val bytes: ByteArray get() = data.copyOf()

    override fun length(): Long = data.size.toLong()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (offset >= data.size) return -1
        val count = minOf(length.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buffer, bufferOffset, count)
        return count
    }

    override fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
        val end = offset.toInt() + length
        if (end > data.size) data = data.copyOf(end)
        System.arraycopy(buffer, bufferOffset, data, offset.toInt(), length)
    }

    override fun truncate(length: Long) {
        data = data.copyOf(length.toInt())
    }

    override fun sync() {
        syncCount++
    }
}
