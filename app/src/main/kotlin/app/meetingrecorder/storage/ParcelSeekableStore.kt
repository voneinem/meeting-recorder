package app.meetingrecorder.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import app.meetingrecorder.core.wav.SeekableStore
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * [SeekableStore] over a file descriptor opened with mode "rw" (research R-1, R-8). Reads and writes
 * are positional, so header updates at offset 0 never disturb appending audio data.
 */
class ParcelSeekableStore(private val descriptor: ParcelFileDescriptor) : SeekableStore, Closeable {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    override fun length(): Long = output.channel.size()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int =
        input.channel.read(ByteBuffer.wrap(buffer, bufferOffset, length), offset)

    override fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
        val bytes = ByteBuffer.wrap(buffer, bufferOffset, length)
        var position = offset
        while (bytes.hasRemaining()) {
            position += output.channel.write(bytes, position)
        }
    }

    override fun truncate(length: Long) {
        output.channel.truncate(length)
    }

    override fun sync() {
        descriptor.fileDescriptor.sync()
    }

    /** Bytes still free on the file system that holds this file. */
    fun freeBytes(): Long {
        val stat = Os.fstatvfs(descriptor.fileDescriptor)
        return stat.f_bavail * stat.f_frsize
    }

    override fun close() {
        input.close()
        output.close()
        descriptor.close()
    }
}
