package app.meetingrecorder.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.meetingrecorder.core.channels.ChannelPolicy
import app.meetingrecorder.core.channels.SourceOption
import app.meetingrecorder.core.channels.SourceSelector
import app.meetingrecorder.core.level.ChannelLevel
import app.meetingrecorder.core.level.LevelMeter
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.recording.RecordingGuard
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
    private val sources = SourceMonitor(context)

    /** The microphone the recording currently uses, and its id in the meeting's `sources` list. */
    @Volatile
    private var activeSource: SourceOption? = null
    private var activeMeetingSourceId = 0

    /** Channels delivered by the running capture; fewer than the file's channels means they are duplicated. */
    @Volatile
    private var captureChannels = 1

    /** Channels of the audio file; fixed for the whole meeting when it starts (FR-011). */
    @Volatile
    private var fileChannels = 1

    // Decides about storage and size-limit warnings; touched at start and then only on the capture thread.
    @Volatile
    private var guard: RecordingGuard? = null

    @Volatile
    private var state = RecordingState()

    fun currentState(): RecordingState = state

    /**
     * Starts a new meeting in the storage folder [treeUri] on the input device [deviceId]
     * ([BUILT_IN_DEVICE_ID] or an unknown id means the built-in microphone). Returns false when nothing was started.
     */
    fun start(title: String?, treeUri: Uri, deviceId: Int = BUILT_IN_DEVICE_ID): Boolean = synchronized(lifecycle) {
        if (!isPermitted()) return@synchronized reject("no_microphone_permission")
        if (machine.apply(Command.START) is Result.Rejected) return@synchronized reject("rejected_start")
        try {
            val storage = SafMeetingStorage(context, treeUri)
            val fileWriter = MeetingFileWriter(storage)
            val chosen = chooseSource(deviceId)
            val meeting = fileWriter.create(title, OffsetDateTime.now(), SAMPLE_RATE, chosen.info)
            val channels = meeting.meeting.audio.channels
            val freeBytes = runCatching { meeting.audioStore.freeBytes() }.getOrNull()
            val recordingGuard = RecordingGuard(SAMPLE_RATE, channels, SystemClock::elapsedRealtime)
            if (!recordingGuard.canStart(freeBytes)) {
                fileWriter.discard(meeting)
                resetToIdle()
                emit(RecordingEvent.Error("storage_too_small"))
                return@synchronized false
            }
            guard = recordingGuard
            val wavWriter = try {
                WavWriter(meeting.audioStore, SAMPLE_RATE, channels, writerListener)
            } catch (e: IOException) {
                fileWriter.discard(meeting)
                throw e
            }
            files = fileWriter
            open = meeting
            writer = wavWriter
            activeSource = chosen
            fileChannels = channels
            activeMeetingSourceId = meeting.meeting.initialSourceId
            try {
                startCapture(chosen)
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
                activeSourceName = chosen.info.name,
                photoCount = 0,
            ) }
            sources.start(::onSourcesChanged)
            recordingGuard.onStarted(freeBytes)?.let(::handle)
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
                resumeOnAvailableSource()
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
            // After a fallback from a stereo source the built-in microphone feeds both channels (FR-014).
            val data = if (ChannelPolicy.needsDuplication(fileChannels, captureChannels)) {
                ChannelPolicy.duplicateMonoToStereo(buffer, frames)
            } else {
                buffer
            }
            wavWriter.append(data, frames)
            val levels = LevelMeter.measure(data, frames, fileChannels)
            update { it.copy(framesAppended = wavWriter.framesAppended, levels = levels) }
            guard?.check(wavWriter.framesAppended) { runCatching { open?.audioStore?.freeBytes() }.getOrNull() }
                ?.forEach(::handle)
        }

        override fun onError(message: String) {
            // Stopping capture joins the capture thread, so it must not happen on that thread.
            mainHandler.post { handleCaptureFailure() }
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
            emit(if (RecordingGuard.isOutOfSpace(error)) RecordingEvent.StorageFull else RecordingEvent.Error("write_failed", error.message))
            // Keep what was recorded: finalize the meeting. Stopping joins the writer thread, so post it.
            mainHandler.post { stopIfActive() }
        }
    }

    // --- internals ---

    /** The source for a new recording: the requested device, else the built-in microphone. */
    private fun chooseSource(deviceId: Int): SourceOption {
        val options = sources.current()
        return options.firstOrNull { it.id == deviceId && deviceId != BUILT_IN_DEVICE_ID }
            ?: SourceSelector.builtIn(options)
            ?: SourceOption(BUILT_IN_DEVICE_ID, BUILT_IN_SOURCE)
    }

    private fun startCapture(source: SourceOption) {
        val channels = minOf(source.recordedChannels, fileChannels)
        val device = sources.deviceFor(source.id)
        val newCapture = AudioCapture(SAMPLE_RATE, channels, captureListener, device)
        newCapture.start()
        captureChannels = channels
        capture = newCapture
        monitor.start(newCapture.audioSessionId)
    }

    /**
     * Resumes on the source the recording had, or, when that external source is gone, on the built-in
     * microphone with the switch logged. A re-plugged device is never taken over by itself (FR-015).
     */
    private fun resumeOnAvailableSource() {
        val active = activeSource ?: SourceOption(BUILT_IN_DEVICE_ID, BUILT_IN_SOURCE)
        val target = SourceSelector.fallbackFor(active.id, sources.current())?.takeIf { active.info.type != SourceType.BUILTIN } ?: active
        startCapture(target)
        if (target != active) logSwitch(active, target)
    }

    /** Called on the main thread when a microphone was plugged in or unplugged while recording. */
    private fun onSourcesChanged(before: List<SourceOption>, now: List<SourceOption>) {
        synchronized(lifecycle) {
            val active = activeSource ?: return
            if (machine.phase == Phase.IDLE || machine.phase == Phase.FINALIZING) return
            SourceSelector.newlyAvailableExternal(before, now).forEach { emit(RecordingEvent.ExternalAvailableAgain(it.info.name)) }
            if (active.info.type == SourceType.BUILTIN) return
            SourceSelector.fallbackFor(active.id, now)?.let { fallbackTo(active, it) }
        }
    }

    /** A read error: when the cause is an unplugged external source, continue on the built-in one; otherwise suspend. */
    private fun handleCaptureFailure() {
        synchronized(lifecycle) {
            if (machine.phase != Phase.RECORDING) return
            val active = activeSource
            val fallback = active?.takeIf { it.info.type != SourceType.BUILTIN }
                ?.let { SourceSelector.fallbackFor(it.id, sources.current()) }
            if (active != null && fallback != null) fallbackTo(active, fallback) else suspendBecauseOf(REASON_READ_ERROR)
        }
    }

    /**
     * Moves the recording from [from] to [to] without losing audio: capture is re-opened on [to], the
     * file layout stays as it is and the switch is logged at its timeline position (FR-012, FR-013).
     * If [to] cannot be opened the recording is suspended with everything so far intact (FR-014).
     */
    private fun fallbackTo(from: SourceOption, to: SourceOption) {
        synchronized(lifecycle) {
            if (activeSource != from) return
            if (machine.phase == Phase.RECORDING) {
                stopCaptureAndSync()
                try {
                    startCapture(to)
                } catch (_: IOException) {
                    suspendBecauseOf(REASON_FALLBACK_FAILED)
                    return
                }
            }
            logSwitch(from, to)
        }
    }

    /** Records a change of source in the meeting data, publishes the new name and tells the UI. */
    private fun logSwitch(from: SourceOption, to: SourceOption) {
        val atFrame = writer?.framesAppended ?: return
        synchronized(metadata) {
            val meeting = open ?: return
            val (withSource, toId) = MeetingEditor.addSource(meeting.meeting, to.info)
            if (toId != activeMeetingSourceId) {
                meeting.meeting = MeetingEditor.addSourceSwitch(withSource, atFrame, activeMeetingSourceId, toId)
                activeMeetingSourceId = toId
                try {
                    files?.writeMetadata(meeting)
                } catch (e: IOException) {
                    emit(RecordingEvent.Error("metadata_write_failed", e.message))
                }
            }
        }
        activeSource = to
        update { it.copy(activeSourceName = to.info.name) }
        emit(RecordingEvent.SourceSwitched(from.info.name, to.info.name, atFrame))
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

    /** Turns a signal of the [RecordingGuard] into a notice, and stops cleanly when the size limit is reached (FR-034). */
    private fun handle(signal: RecordingGuard.Signal) {
        when (signal) {
            is RecordingGuard.Signal.LimitApproaching -> emit(RecordingEvent.LimitApproaching(signal.secondsLeft))
            RecordingGuard.Signal.LimitReached -> {
                emit(RecordingEvent.LimitReached)
                mainHandler.post { stopIfActive() }
            }
            is RecordingGuard.Signal.StorageLow -> emit(RecordingEvent.StorageLow(signal.bytesLeft))
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
        guard = null
        sources.stop()
        activeSource = null
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
        const val REASON_FALLBACK_FAILED = "fallback_failed"

        /** The id the UI sends for "use the built-in microphone". */
        const val BUILT_IN_DEVICE_ID = -1


        /** Used only when the system lists no built-in microphone at all. */
        val BUILT_IN_SOURCE = SourceInfo(SourceMapper.BUILT_IN_NAME, SourceType.BUILTIN, deliveredChannels = 1)
    }
}
