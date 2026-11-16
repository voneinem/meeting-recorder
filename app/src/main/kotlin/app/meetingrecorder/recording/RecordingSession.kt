package app.meetingrecorder.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.meetingrecorder.core.level.ChannelLevel
import app.meetingrecorder.core.level.LevelMeter
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.wav.RecordingLimits
import app.meetingrecorder.recording.RecordingStateMachine.Result
import app.meetingrecorder.storage.SafMeetingStorage
import java.io.IOException
import java.time.OffsetDateTime

/**
 * One recording at a time: orchestrates capture, the WAV writer, level metering and the meeting
 * files under the [RecordingStateMachine]. It lives in the service process and reports through the
 * two callbacks. The timeline position is always the writer's `framesAppended` (research R-4).
 *
 * Locking: [lifecycle] serializes start/pause/resume/stop; [metadata] guards the meeting metadata and
 * is never held while waiting for another thread, because capture and writer threads need it.
 */
class RecordingSession(
    private val context: Context,
    private val emit: (RecordingEvent) -> Unit,
    private val publish: (RecordingState) -> Unit,
) {
    private val lifecycle = Any()
    private val metadata = Any()
    private val stateLock = Any()
    private val machine = RecordingStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var files: MeetingFileWriter? = null
    private var open: MeetingFileWriter.OpenMeeting? = null
    private var writer: WavWriter? = null
    private var capture: AudioCapture? = null
    private val monitor = SuspensionMonitor(context) { suspendBecauseOf(REASON_MICROPHONE_TAKEN) }

    // Per-recording flags and timers, only touched on the capture thread or under [lifecycle] at start.
    private var lastStorageCheckMs = 0L
    private var lowStorageWarned = false
    private var limitWarned = false
    private var limitStopping = false

    @Volatile
    private var state = RecordingState()

    fun currentState(): RecordingState = state

    /** Starts a new meeting in the storage folder [treeUri]. Returns false when nothing was started. */
    fun start(title: String?, treeUri: Uri): Boolean = synchronized(lifecycle) {
        if (!isPermitted()) return@synchronized reject("no_microphone_permission")
        if (machine.apply(Command.START) is Result.Rejected) return@synchronized reject("rejected_start")
        try {
            val storage = SafMeetingStorage(context, treeUri)
            val fileWriter = MeetingFileWriter(storage)
            val meeting = fileWriter.create(title, OffsetDateTime.now(), SAMPLE_RATE, BUILT_IN_SOURCE)
            val channels = meeting.meeting.audio.channels
            val freeBytes = runCatching { meeting.audioStore.freeBytes() }.getOrNull()
            if (freeBytes != null && freeBytes < MIN_START_BYTES) {
                fileWriter.discard(meeting)
                resetToIdle()
                emit(RecordingEvent.Error("storage_too_small"))
                return@synchronized false
            }
            lastStorageCheckMs = SystemClock.elapsedRealtime()
            lowStorageWarned = false
            limitWarned = false
            limitStopping = false
            val wavWriter = try {
                WavWriter(meeting.audioStore, SAMPLE_RATE, channels, writerListener)
            } catch (e: IOException) {
                fileWriter.discard(meeting)
                throw e
            }
            files = fileWriter
            open = meeting
            writer = wavWriter
            try {
                startCapture(channels)
            } catch (e: IOException) {
                wavWriter.close()
                fileWriter.discard(meeting)
                clear()
                throw e
            }
            update { it.copy(
                phase = Phase.RECORDING,
                meetingFolder = meeting.folderName,
                framesAppended = 0,
                sampleRate = SAMPLE_RATE,
                channels = channels,
                levels = List(channels) { ChannelLevel(LevelMeter.FLOOR_DB, LevelMeter.FLOOR_DB) },
                activeSourceName = BUILT_IN_SOURCE.name,
                photoCount = 0,
            ) }
            if (freeBytes != null && freeBytes < LOW_STORAGE_BYTES) {
                lowStorageWarned = true
                emit(RecordingEvent.StorageLow(freeBytes))
            }
            true
        } catch (e: IOException) {
            resetToIdle()
            emit(RecordingEvent.Error("start_failed", e.message))
            false
        } catch (e: SecurityException) {
            resetToIdle()
            emit(RecordingEvent.Error("start_failed", e.message))
            false
        }
    }

    fun pause() {
        synchronized(lifecycle) {
            if (machine.apply(Command.PAUSE) is Result.Rejected) {
                reject("rejected_pause")
                return@synchronized
            }
            stopCaptureAndSync()
            update { it.copy(phase = Phase.PAUSED, levels = silence(it.channels)) }
        }
    }

    fun resume() {
        synchronized(lifecycle) {
            if (machine.apply(Command.RESUME) is Result.Rejected) {
                reject("rejected_resume")
                return@synchronized
            }
            try {
                startCapture(state.channels)
                update { it.copy(phase = Phase.RECORDING) }
            } catch (e: IOException) {
                machine.apply(Command.SUSPEND)
                update { it.copy(phase = Phase.SUSPENDED, levels = silence(it.channels)) }
                emit(RecordingEvent.Suspended(e.message ?: "microphone unavailable"))
            }
        }
    }

    fun stop() {
        synchronized(lifecycle) {
            if (machine.apply(Command.STOP) is Result.Rejected) {
                reject("rejected_stop")
                return@synchronized
            }
            update { it.copy(phase = Phase.FINALIZING, levels = silence(it.channels)) }
            monitor.stop()
            capture?.stop()
            capture = null
            val wavWriter = writer
            val meeting = open
            val fileWriter = files
            try {
                wavWriter?.close()
                val frames = wavWriter?.framesWritten ?: 0
                if (meeting != null && fileWriter != null) {
                    if (frames == 0L) {
                        fileWriter.discard(meeting)
                        emit(RecordingEvent.Error("nothing_recorded"))
                    } else {
                        synchronized(metadata) { fileWriter.finalize(meeting, frames) }
                    }
                }
            } catch (e: IOException) {
                emit(RecordingEvent.Error("finalize_failed", e.message))
            } finally {
                clear()
                machine.apply(Command.FINALIZED)
                update { RecordingState() }
            }
        }
    }

    /** The timeline position at this moment, or -1 when no meeting is active. */
    fun markPhotoPosition(): Long = writer?.framesAppended ?: -1

    /** Creates the next empty photo file and returns its URI; null when no meeting is active. */
    fun createPhotoFile(): Uri? {
        val meeting = open ?: return null
        val fileWriter = files ?: return null
        return synchronized(metadata) {
            try {
                fileWriter.createPhotoFile(meeting)
            } catch (e: IOException) {
                emit(RecordingEvent.Error("photo_file_failed", e.message))
                null
            }
        }
    }

    fun addPhoto(atFrame: Long) {
        val meeting = open ?: return
        val fileWriter = files ?: return
        if (machine.apply(Command.PHOTO) is Result.Rejected) return
        synchronized(metadata) {
            meeting.meeting = MeetingEditor.addPhoto(meeting.meeting, atFrame)
            try {
                fileWriter.writeMetadata(meeting)
            } catch (e: IOException) {
                emit(RecordingEvent.Error("metadata_write_failed", e.message))
            }
            update { it.copy(photoCount = meeting.meeting.photos.size) }
        }
    }

    // --- capture thread ---

    private val captureListener = object : AudioCapture.Listener {
        override fun onAudio(buffer: ShortArray, frames: Int) {
            val wavWriter = writer ?: return
            wavWriter.append(buffer, frames)
            val levels = LevelMeter.measure(buffer, frames, state.channels)
            update { it.copy(framesAppended = wavWriter.framesAppended, levels = levels) }
            checkLimits(wavWriter.framesAppended)
            checkStorage()
        }

        override fun onError(message: String) {
            // Stopping capture joins the capture thread, so it must not happen on that thread.
            mainHandler.post { suspendBecauseOf(REASON_READ_ERROR) }
        }
    }

    // --- writer thread ---

    private val writerListener = object : WavWriter.Listener {
        override fun onDropped(atFrame: Long, frames: Long) {
            synchronized(metadata) {
                val meeting = open ?: return
                meeting.meeting = MeetingEditor.addDropout(meeting.meeting, atFrame, frames)
                runCatching { files?.writeMetadata(meeting) }
            }
            emit(RecordingEvent.AudioDropped(atFrame, frames))
        }

        override fun onWriteFailed(error: IOException) {
            val full = error.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left") }
            emit(if (full) RecordingEvent.StorageFull else RecordingEvent.Error("write_failed", error.message))
            // Keep what was recorded: finalize the meeting. Stopping joins the writer thread, so post it.
            mainHandler.post { stopIfActive() }
        }
    }

    // --- internals ---

    private fun startCapture(channels: Int) {
        val newCapture = AudioCapture(SAMPLE_RATE, channels, captureListener)
        newCapture.start()
        capture = newCapture
        monitor.start(newCapture.audioSessionId)
    }

    private fun stopCaptureAndSync() {
        monitor.stop()
        capture?.stop()
        capture = null
        writer?.flush()
    }

    private fun suspendBecauseOf(reason: String) {
        synchronized(lifecycle) {
            if (machine.apply(Command.SUSPEND) is Result.Rejected) return@synchronized
            stopCaptureAndSync()
            update { it.copy(phase = Phase.SUSPENDED, levels = silence(it.channels)) }
            emit(RecordingEvent.Suspended(reason))
        }
    }

    /** Stops the recording when one is active; used by the automatic stops (storage full, size limit). */
    private fun stopIfActive() {
        synchronized(lifecycle) {
            if (machine.phase != Phase.IDLE && machine.phase != Phase.FINALIZING) stop()
        }
    }

    /** Warns 15 minutes before the WAV size limit and stops cleanly when it is reached (FR-034). */
    private fun checkLimits(framesAppended: Long) {
        val channels = state.channels
        if (!limitWarned && RecordingLimits.isWarningDue(framesAppended, SAMPLE_RATE, channels)) {
            limitWarned = true
            emit(RecordingEvent.LimitApproaching(RecordingLimits.remainingSeconds(framesAppended, SAMPLE_RATE, channels)))
        }
        if (!limitStopping && RecordingLimits.isLimitReached(framesAppended, channels)) {
            limitStopping = true
            emit(RecordingEvent.LimitReached)
            mainHandler.post { stopIfActive() }
        }
    }

    /** Checks the free storage every few seconds and warns once when it gets low. */
    private fun checkStorage() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStorageCheckMs < STORAGE_CHECK_INTERVAL_MS) return
        lastStorageCheckMs = now
        val free = runCatching { open?.audioStore?.freeBytes() }.getOrNull() ?: return
        if (free < LOW_STORAGE_BYTES && !lowStorageWarned) {
            lowStorageWarned = true
            emit(RecordingEvent.StorageLow(free))
        }
    }

    private fun isPermitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun reject(code: String): Boolean {
        emit(RecordingEvent.Error(code))
        return false
    }

    private fun resetToIdle() {
        clear()
        machine.apply(Command.STOP)
        machine.apply(Command.FINALIZED)
        update { RecordingState() }
    }

    private fun clear() {
        files = null
        open = null
        writer = null
        capture = null
    }

    private fun silence(channels: Int) = List(channels) { ChannelLevel(LevelMeter.FLOOR_DB, LevelMeter.FLOOR_DB) }

    /** Atomically applies [change] to the state (capture and control threads both write it) and publishes it. */
    private inline fun update(change: (RecordingState) -> RecordingState) {
        val next = synchronized(stateLock) {
            val updated = change(state)
            state = updated
            updated
        }
        publish(next)
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        /** Reasons of a [RecordingEvent.Suspended]; the UI maps them to messages. */
        const val REASON_MICROPHONE_TAKEN = "microphone_taken"
        const val REASON_READ_ERROR = "read_error"

        private const val MIN_START_BYTES = 10L * 1024 * 1024
        private const val LOW_STORAGE_BYTES = 100L * 1024 * 1024
        private const val STORAGE_CHECK_INTERVAL_MS = 10_000L

        /** Until the source list exists (user story 2) every meeting starts on the built-in microphone. */
        val BUILT_IN_SOURCE = SourceInfo("Built-in microphone", SourceType.BUILTIN, deliveredChannels = 1)
    }
}
