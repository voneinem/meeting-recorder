package app.meetingrecorder.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI-side client of the recording service (contracts/recording-service.md). It binds to the service
 * in the other process and exposes its state and events as flows. A recording keeps running when the
 * UI process dies; a new controller re-attaches with [attachIfRunning].
 */
class RecordingController(context: Context) {
    private val context = context.applicationContext

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RecordingEvent> = _events.asSharedFlow()

    @Volatile
    private var service: IRecordingService? = null

    @Volatile
    private var connected: CompletableDeferred<IRecordingService>? = null

    private val callback = object : IRecordingCallback.Stub() {
        override fun onState(state: RecordingStateParcel) {
            _state.value = state.toState()
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

    /** Connects to a recording that is already running (for example after the UI was restarted). */
    fun attachIfRunning() {
        if (service == null) context.bindService(serviceIntent(), connection, 0)
    }

    /**
     * Starts the foreground service, connects to it and starts a meeting. Returns false when the
     * recording did not start; the reason arrives as a [RecordingEvent.Error] on [events].
     */
    suspend fun start(title: String?, treeUri: Uri): Boolean {
        val bound = service ?: connect() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                bound.start(title.orEmpty(), BUILT_IN_DEVICE_ID, treeUri.toString())
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
        const val BUILT_IN_DEVICE_ID = -1
        private const val EVENT_BUFFER = 16
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
