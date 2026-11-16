package app.meetingrecorder.recording

import android.media.AudioDeviceInfo
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType

/**
 * Turns the properties of an Android input device into a [SourceInfo]. It takes plain values instead
 * of an `AudioDeviceInfo`, so the mapping is testable on the JVM.
 */
object SourceMapper {
    const val BUILT_IN_NAME = "Built-in microphone"
    private const val USB_FALLBACK_NAME = "USB microphone"
    private const val WIRED_FALLBACK_NAME = "Wired headset"

    /** Null for device types the app does not offer (Bluetooth, telephony and others). */
    fun map(type: Int, productName: CharSequence?, channelCounts: IntArray, channelMasks: IntArray): SourceInfo? {
        val delivered = deliveredChannels(channelCounts, channelMasks)
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> SourceInfo(BUILT_IN_NAME, SourceType.BUILTIN, delivered)
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                SourceInfo(nameOr(productName, USB_FALLBACK_NAME), SourceType.USB, delivered)
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> SourceInfo(nameOr(productName, WIRED_FALLBACK_NAME), SourceType.OTHER, delivered)
            else -> null
        }
    }

    /**
     * The most channels the device reports. The reported channel counts come first; some devices
     * only report channel masks, then the bits of the widest mask are counted. Unknown means mono.
     */
    fun deliveredChannels(channelCounts: IntArray, channelMasks: IntArray): Int =
        channelCounts.maxOrNull()?.takeIf { it > 0 }
            ?: channelMasks.maxOfOrNull { Integer.bitCount(it) }?.takeIf { it > 0 }
            ?: 1

    private fun nameOr(productName: CharSequence?, fallback: String) = productName?.toString()?.trim().orEmpty().ifEmpty { fallback }
}
