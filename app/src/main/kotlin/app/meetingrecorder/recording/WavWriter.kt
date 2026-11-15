package app.meetingrecorder.recording

import app.meetingrecorder.core.wav.RecordingLimits
import app.meetingrecorder.core.wav.SeekableStore
import app.meetingrecorder.core.wav.WavHeader
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Appends PCM audio to a WAV file through a bounded queue, so that capture never waits for storage
 * (research R-2, R-3, R-4).
 *
 * - The header is written at the start and rewritten with a `sync()` every second of audio and on
 *   [flush]; a crash therefore loses at most the audio still in the queue, and recovery re-derives
 *   the sizes from the file length anyway.
 * - [framesAppended] is the timeline position: frames accepted into the queue. Frames that do not fit
 *   are dropped, never blocked on (and never counted), and reported through [Listener.onDropped].
 */
class WavWriter(
    private val store: SeekableStore,
    private val sampleRate: Int,
    private val channels: Int,
    private val listener: Listener,
    queueCapacityChunks: Int = DEFAULT_QUEUE_CHUNKS,
) {
    interface Listener {
        /** Audio was lost because the queue was full; [atFrame] is the timeline position of the gap. */
        fun onDropped(atFrame: Long, frames: Long)

        /** Writing failed (for example the storage is full); later audio is discarded. */
        fun onWriteFailed(error: IOException)
    }

    private sealed interface Item {
        class Chunk(val bytes: ByteArray, val frames: Int) : Item
        class Flush(val done: CountDownLatch) : Item
        data object Stop : Item
    }

    private val blockAlign = channels * BYTES_PER_SAMPLE
    private val queue = ArrayBlockingQueue<Item>(queueCapacityChunks)
    private val appended = AtomicLong(0)
    private val written = AtomicLong(0)
    private val dropLock = Any()
    private var pendingDropAt = 0L
    private var pendingDropFrames = 0L

    @Volatile
    private var failure: IOException? = null

    // Only touched by the writer thread.
    private var dataBytes = 0L
    private var framesSinceHeader = 0L

    private val thread: Thread

    init {
        store.writeAt(0, WavHeader.build(sampleRate, channels, 0))
        store.sync()
        thread = Thread(::drain, "wav-writer").apply { start() }
    }

    /** Timeline position: frames accepted so far. */
    val framesAppended: Long get() = appended.get()

    /** Frames that reached the file (equals [framesAppended] unless a write failed). */
    val framesWritten: Long get() = written.get()

    /** The write error, if writing has failed. */
    val writeError: IOException? get() = failure

    /**
     * Queues [frames] interleaved frames from [buffer]. Returns false when the audio was dropped.
     * Called from the capture thread; never blocks.
     */
    fun append(buffer: ShortArray, frames: Int): Boolean {
        require(frames >= 0 && frames * channels <= buffer.size) { "buffer too small for $frames frames" }
        if (frames == 0) return true
        if (failure != null) return false
        val bytes = ByteArray(frames * blockAlign)
        for (i in 0 until frames * channels) {
            val sample = buffer[i].toInt()
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
        val accepted = queue.offer(Item.Chunk(bytes, frames))
        synchronized(dropLock) {
            if (accepted) {
                appended.addAndGet(frames.toLong())
                reportPendingDrop()
            } else {
                if (pendingDropFrames == 0L) pendingDropAt = appended.get()
                pendingDropFrames += frames
            }
        }
        return accepted
    }

    /** Waits until everything queued is on disk, with an up-to-date header and a `sync()`. */
    fun flush() {
        val done = CountDownLatch(1)
        queue.put(Item.Flush(done))
        done.await()
        synchronized(dropLock) { reportPendingDrop() }
    }

    /** Flushes and stops the writer thread. The store stays open; the caller closes it. */
    fun close() {
        flush()
        queue.put(Item.Stop)
        thread.join()
    }

    private fun reportPendingDrop() {
        if (pendingDropFrames > 0) {
            val frames = pendingDropFrames
            val at = pendingDropAt
            pendingDropFrames = 0
            listener.onDropped(at, frames)
        }
    }

    private fun drain() {
        while (true) {
            when (val item = queue.take()) {
                is Item.Chunk -> write(item)
                is Item.Flush -> {
                    updateHeader()
                    item.done.countDown()
                }
                Item.Stop -> return
            }
        }
    }

    private fun write(chunk: Item.Chunk) {
        if (failure != null) return
        try {
            if (dataBytes + chunk.bytes.size > RecordingLimits.MAX_DATA_BYTES) {
                throw IOException("the WAV size limit was reached")
            }
            store.writeAt(WavHeader.SIZE + dataBytes, chunk.bytes)
            dataBytes += chunk.bytes.size
            written.addAndGet(chunk.frames.toLong())
            framesSinceHeader += chunk.frames
            if (framesSinceHeader >= sampleRate) updateHeader()
        } catch (e: IOException) {
            failure = e
            listener.onWriteFailed(e)
        }
    }

    private fun updateHeader() {
        framesSinceHeader = 0
        if (failure != null && dataBytes == 0L) return
        try {
            WavHeader.updateSizes(store, dataBytes)
            store.sync()
        } catch (e: IOException) {
            if (failure == null) {
                failure = e
                listener.onWriteFailed(e)
            }
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2

        /** About ten seconds of audio in 50 ms chunks. */
        const val DEFAULT_QUEUE_CHUNKS = 200
    }
}
