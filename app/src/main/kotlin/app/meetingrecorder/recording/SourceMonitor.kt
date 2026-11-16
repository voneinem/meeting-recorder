package app.meetingrecorder.recording

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.meetingrecorder.core.channels.SourceOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the list of available microphones up to date from `AudioDeviceCallback` (research R-2) and
 * tells a listener what changed. Used by the record screen to offer sources and by the recording
 * service to notice an unplugged microphone. Callbacks arrive on the main thread.
 */
class SourceMonitor(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioDeviceCallback? = null
    private var devices: List<AudioDeviceInfo> = emptyList()

    private val _options = MutableStateFlow(read())

    /** The sources available now: one built-in microphone and every external input the app supports. */
    val options: StateFlow<List<SourceOption>> = _options.asStateFlow()

    /** The sources as seen right now, read straight from the system. */
    fun current(): List<SourceOption> = read().also { _options.value = it }

    /** The platform device behind [id], for `setPreferredDevice`; null when it is gone. */
    fun deviceFor(id: Int): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }

    /** Starts watching; [onChanged] gets the list before and after every change. Replaces an earlier watch. */
    fun start(onChanged: ((before: List<SourceOption>, now: List<SourceOption>) -> Unit)? = null) {
        stop()
        _options.value = read()
        val newCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()

            private fun changed() {
                val before = _options.value
                val now = read()
                _options.value = now
                if (now != before) onChanged?.invoke(before, now)
            }
        }
        audioManager.registerAudioDeviceCallback(newCallback, handler)
        callback = newCallback
    }

    fun stop() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
    }

    private fun read(): List<SourceOption> {
        devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val mapped = devices.mapNotNull { device ->
            SourceMapper.map(device.type, device.productName, device.channelCounts, device.channelMasks)
                ?.let { SourceOption(device.id, it) }
        }
        // Phones can list the built-in microphone more than once; one entry is enough.
        val builtIn = mapped.firstOrNull { it.info.type == app.meetingrecorder.core.model.SourceType.BUILTIN }
        return listOfNotNull(builtIn) + mapped.filter { it.info.type != app.meetingrecorder.core.model.SourceType.BUILTIN }
    }
}
