package app.meetingrecorder.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.core.content.ContextCompat
import app.meetingrecorder.ipc.IRecordingCallback
import app.meetingrecorder.ipc.IRecordingService
import app.meetingrecorder.ipc.RecordingEventParcel
import app.meetingrecorder.ipc.RecordingStateParcel
import app.meetingrecorder.ipc.toEvent
import app.meetingrecorder.ipc.toState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI-side client of the recording service (contracts/recording-service.md). It binds to the service
 * in the other process and exposes its state and events as flows. A recording keeps running when the
 * UI process dies; a new controller re-attaches with [attachIfRunning].
 */
class RecordingController(
    context: Context,
    /**
     * Called as the very first statement of [start]: the recording has priority over transcription and model
     * downloads (FR-017), so `MeetingRecorderApp` stops them here. It must not block and is not awaited.
     */
    private val onBeforeStart: () -> Unit = {},
) {
    private val context = context.applicationContext

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RecordingEvent> = _events.asSharedFlow()

    @Volatile
    private var service: IRecordingService? = null

    @Volatile
    private var connected: CompletableDeferred<IRecordingService>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : IRecordingCallback.Stub() {
        override fun onState(state: RecordingStateParcel) {
            val next = state.toState()
            val previous = _state.getAndUpdate { next }
            // The recording is over: let go of the service so the next one starts a fresh instance.
            if (previous.phase != Phase.IDLE && next.phase == Phase.IDLE) mainHandler.post { releaseService() }
        }

        override fun onEvent(event: RecordingEventParcel) {
            _events.tryEmit(event.toEvent())
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IRecordingService.Stub.asInterface(binder)
            service = bound
            try {
                bound.registerCallback(callback)
            } catch (_: RemoteException) {
                service = null
                return
            }
            connected?.complete(bound)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.value = RecordingState()
        }
    }

    /**
     * Unbinds from the service after a recording. A service that stays bound outlives its recording,
     * and the next recording would reuse that instance. The system then denies it the right to keep
     * using the microphone in the background (foreground-service "while in use" restriction), so the
     * capture is silenced a few seconds after the app leaves the screen. A new instance is granted it.
     */
    private fun releaseService() {
        if (_state.value.phase != Phase.IDLE) return // a new recording already started
        service = null
        connected = null
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // not bound any more
        }
    }

    /** Connects to a recording that is already running (for example after the UI was restarted). */
    fun attachIfRunning() {
        if (service == null) context.bindService(serviceIntent(), connection, 0)
    }

    /**
     * Starts the foreground service, connects to it and starts a meeting. Returns false when the
     * recording did not start; the reason arrives as a [RecordingEvent.Error] on [events].
     */
    suspend fun start(title: String?, treeUri: Uri, deviceId: Int = BUILT_IN_DEVICE_ID): Boolean {
        onBeforeStart()
        val bound = service ?: connect() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                bound.start(title.orEmpty(), deviceId, treeUri.toString())
            } catch (_: RemoteException) {
                false
            }
        }
    }

    suspend fun pause() = call { it.pause() }

    suspend fun resume() = call { it.resume() }

    suspend fun stop() = call { it.stop() }

    /** The timeline position now, or -1 when no meeting is active. */
    suspend fun markPhotoPosition(): Long = withContext(Dispatchers.IO) {
        try {
            service?.markPhotoPosition() ?: -1
        } catch (_: RemoteException) {
            -1
        }
    }

    suspend fun addPhoto(file: String, atFrame: Long) = call { it.addPhoto(file, atFrame) }

    /** Creates the next empty photo file in the current meeting and returns its document URI. */
    suspend fun createPhotoFile(): Uri? = withContext(Dispatchers.IO) {
        try {
            service?.createPhotoFile()?.let(Uri::parse)
        } catch (_: RemoteException) {
            null
        }
    }

    private suspend fun call(command: (IRecordingService) -> Unit) {
        val bound = service ?: return
        withContext(Dispatchers.IO) {
            try {
                command(bound)
            } catch (_: RemoteException) {
                // the service is gone; onServiceDisconnected resets the state
            }
        }
    }

    private suspend fun connect(): IRecordingService? {
        val deferred = CompletableDeferred<IRecordingService>()
        connected = deferred
        ContextCompat.startForegroundService(context, serviceIntent())
        if (!context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)) return null
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    private fun serviceIntent() = Intent(context, RecordingService::class.java)

    companion object {
        /** Tells the service to use the built-in microphone. */
        const val BUILT_IN_DEVICE_ID = RecordingSession.BUILT_IN_DEVICE_ID
        private const val EVENT_BUFFER = 16
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
