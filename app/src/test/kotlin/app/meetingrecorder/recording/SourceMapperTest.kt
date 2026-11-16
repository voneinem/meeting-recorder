package app.meetingrecorder.recording

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceMapperTest {

    @Test
    fun `the built-in microphone is mono with the fixed English name`() {
        val info = SourceMapper.map(AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom", intArrayOf(1), intArrayOf())
        assertEquals(SourceInfo("Built-in microphone", SourceType.BUILTIN, 1), info)
    }

    @Test
    fun `a usb device keeps its product name and the channels it reports`() {
        val info = SourceMapper.map(AudioDeviceInfo.TYPE_USB_DEVICE, "RØDE Wireless PRO", intArrayOf(1, 2), intArrayOf())
        assertEquals(SourceInfo("RØDE Wireless PRO", SourceType.USB, 2), info)
        assertEquals(SourceType.USB, SourceMapper.map(AudioDeviceInfo.TYPE_USB_HEADSET, "Headset", intArrayOf(1), intArrayOf())?.type)
        assertEquals(SourceType.USB, SourceMapper.map(AudioDeviceInfo.TYPE_USB_ACCESSORY, "Accessory", intArrayOf(1), intArrayOf())?.type)
    }

    @Test
    fun `a blank product name gets a generic name`() {
        assertEquals("USB microphone", SourceMapper.map(AudioDeviceInfo.TYPE_USB_DEVICE, "  ", intArrayOf(1), intArrayOf())?.name)
        assertEquals("USB microphone", SourceMapper.map(AudioDeviceInfo.TYPE_USB_DEVICE, null, intArrayOf(1), intArrayOf())?.name)
    }

    @Test
    fun `a wired headset is an other source`() {
        assertEquals(SourceType.OTHER, SourceMapper.map(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset", intArrayOf(1), intArrayOf())?.type)
    }

    @Test
    fun `devices the app does not offer are skipped`() {
        assertNull(SourceMapper.map(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Car", intArrayOf(1), intArrayOf()))
        assertNull(SourceMapper.map(AudioDeviceInfo.TYPE_TELEPHONY, "Phone", intArrayOf(), intArrayOf()))
    }

    @Test
    fun `channel masks are counted when no channel counts are reported`() {
        assertEquals(2, SourceMapper.deliveredChannels(intArrayOf(), intArrayOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO)))
        assertEquals(1, SourceMapper.deliveredChannels(intArrayOf(), intArrayOf(AudioFormat.CHANNEL_IN_MONO)))
    }

    @Test
    fun `unknown channels mean mono and the most reported channels win`() {
        assertEquals(1, SourceMapper.deliveredChannels(intArrayOf(), intArrayOf()))
        assertEquals(2, SourceMapper.deliveredChannels(intArrayOf(2, 1), intArrayOf()))
    }
}
