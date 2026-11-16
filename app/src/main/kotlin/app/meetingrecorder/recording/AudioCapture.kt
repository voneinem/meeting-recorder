package app.meetingrecorder.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.IOException

/**
 * Reads 16-bit PCM from the microphone on a dedicated high-priority thread and hands every buffer to
 * the [Listener]. One instance records once: create a new one to resume after a pause.
 * The caller must hold the RECORD_AUDIO permission.
 */
class AudioCapture(
    private val sampleRate: Int,
    private val channels: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the capture thread; the buffer is reused afterwards, so copy what you keep. */
        fun onAudio(buffer: ShortArray, frames: Int)

        /** Called on the capture thread when reading fails; capture has stopped reading. */
        fun onError(message: String)
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** The audio session of the running capture, used to recognize it in recording callbacks. */
    val audioSessionId: Int get() = record?.audioSessionId ?: 0

    @SuppressLint("MissingPermission") // checked by the caller before the session starts
    fun start() {
        check(record == null) { "AudioCapture can only be started once" }
        val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw IOException("the microphone does not support $sampleRate Hz, $channels channel(s)")
        val chunkFrames = sampleRate / CHUNKS_PER_SECOND
        val bufferBytes = maxOf(minBytes, chunkFrames * channels * BYTES_PER_SAMPLE * INTERNAL_BUFFER_CHUNKS)
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IOException("the microphone could not be opened")
        }
        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release()
            throw IOException("the microphone is in use by another app")
        }
        record = audioRecord
        running = true
        thread = Thread({ readLoop(audioRecord, chunkFrames) }, "audio-capture").also { it.start() }
    }

    private fun readLoop(audioRecord: AudioRecord, chunkFrames: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(chunkFrames * channels)
        while (running) {
            val read = audioRecord.read(buffer, 0, buffer.size)
            when {
                read > 0 -> listener.onAudio(buffer, read / channels)
                read < 0 -> {
                    if (running) listener.onError("reading from the microphone failed ($read)")
                    return
                }
            }
        }
    }

    /** Stops reading and releases the microphone. Safe to call more than once, but not from the capture thread. */
    fun stop() {
        running = false
        val audioRecord = record ?: return
        record = null
        try {
            audioRecord.stop()
        } catch (_: IllegalStateException) {
            // already stopped by the system
        }
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        audioRecord.release()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val CHUNKS_PER_SECOND = 20 // 50 ms buffers also give the level meter its 20 Hz
        const val INTERNAL_BUFFER_CHUNKS = 8
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
