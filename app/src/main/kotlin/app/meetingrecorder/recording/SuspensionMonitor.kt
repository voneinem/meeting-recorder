package app.meetingrecorder.recording

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Notices when the system silences our microphone, for example during a phone call or when a more
 * privileged app takes it (research R-6). The session then suspends the recording instead of writing
 * silence into the file.
 */
class SuspensionMonitor(context: Context, private val onSilenced: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioManager.AudioRecordingCallback? = null

    /** Watches the capture that uses [audioSessionId]. Replaces any earlier watch. */
    fun start(audioSessionId: Int) {
        stop()
        val newCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val ours = configs.firstOrNull { it.clientAudioSessionId == audioSessionId } ?: return
                if (ours.isClientSilenced) onSilenced()
            }
        }
        audioManager.registerAudioRecordingCallback(newCallback, handler)
        callback = newCallback
    }

    fun stop() {
        callback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        callback = null
    }
}
