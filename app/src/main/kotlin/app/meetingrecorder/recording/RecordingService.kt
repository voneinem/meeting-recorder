package app.meetingrecorder.recording

import androidx.core.net.toUri
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.core.app.ServiceCompat
import app.meetingrecorder.ipc.IRecordingCallback
import app.meetingrecorder.ipc.IRecordingService
import app.meetingrecorder.ipc.RecordingStateParcel
import app.meetingrecorder.ipc.toParcel
import java.util.concurrent.Executors

/**
 * The foreground service (type microphone) that owns the [RecordingSession]. It runs in its own
 * process, so a crash or kill of the UI does not end a recording (research R-5).
 */
class RecordingService : Service() {

    private val callbacks = RemoteCallbackList<IRecordingCallback>()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var session: RecordingSession
    private lateinit var notification: RecordingNotification

    @Volatile
    private var lastPhase = Phase.IDLE
    private var lastSourceName = ""

    override fun onCreate() {
        super.onCreate()
        notification = RecordingNotification(this)
        session = RecordingSession(applicationContext, ::broadcastEvent, ::broadcastState)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground service must call startForeground soon after it was started from the UI.
        startForegroundNow()
        when (intent?.action) {
            ACTION_STOP -> worker.execute { session.stop() }
            ACTION_RESUME -> worker.execute { session.resume() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdown()
        callbacks.kill()
        super.onDestroy()
    }

    private fun startForegroundNow() {
        ServiceCompat.startForeground(
            this,
            RecordingNotification.NOTIFICATION_ID,
            notification.build(session.currentState()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun broadcastState(state: RecordingState) {
        val parcel = state.toParcel()
        forEachCallback { it.onState(parcel) }
        if (state.phase != Phase.IDLE && state.phase == lastPhase && state.activeSourceName != lastSourceName) {
            // The microphone changed during a fallback: the notification names the one in use.
            notification.update(state)
        }
        lastSourceName = state.activeSourceName
        if (state.phase != lastPhase) {
            val previous = lastPhase
            lastPhase = state.phase
            if (state.phase == Phase.IDLE) {
                if (previous != Phase.IDLE) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } else {
                notification.update(state)
            }
        }
    }

    private fun broadcastEvent(event: RecordingEvent) {
        val parcel = event.toParcel()
        forEachCallback { it.onEvent(parcel) }
    }

    private inline fun forEachCallback(call: (IRecordingCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    call(callbacks.getBroadcastItem(i))
                } catch (_: RemoteException) {
                    // the UI process died; RemoteCallbackList drops the callback itself
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private val binder = object : IRecordingService.Stub() {
        override fun registerCallback(callback: IRecordingCallback) {
            callbacks.register(callback)
            callback.onState(session.currentState().toParcel())
        }

        override fun unregisterCallback(callback: IRecordingCallback) {
            callbacks.unregister(callback)
        }

        override fun getState(): RecordingStateParcel = session.currentState().toParcel()

        override fun start(title: String?, deviceId: Int, storageTreeUri: String): Boolean {
            val started = session.start(title?.takeIf { it.isNotBlank() }, storageTreeUri.toUri(), deviceId)
            if (!started) {
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return started
        }

        override fun pause() = session.pause()

        override fun resume() = session.resume()

        override fun stop() = session.stop()

        override fun markPhotoPosition(): Long = session.markPhotoPosition()

        override fun addPhoto(file: String, atFrame: Long) = session.addPhoto(atFrame)

        override fun createPhotoFile(): String? = session.createPhotoFile()?.toString()
    }

    companion object {
        const val ACTION_STOP = "app.meetingrecorder.action.STOP_RECORDING"
        const val ACTION_RESUME = "app.meetingrecorder.action.RESUME_RECORDING"
    }
}
