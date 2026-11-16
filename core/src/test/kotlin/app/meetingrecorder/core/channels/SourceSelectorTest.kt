package app.meetingrecorder.core.channels

import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceSelectorTest {
    private val builtIn = SourceOption(1, SourceInfo("Built-in microphone", SourceType.BUILTIN, 1))
    private val rode = SourceOption(7, SourceInfo("RØDE Wireless PRO", SourceType.USB, 2))
    private val headset = SourceOption(9, SourceInfo("Wired headset", SourceType.OTHER, 1))

    @Test
    fun `nothing is suggested without an external source`() {
        assertNull(SourceSelector.suggested(listOf(builtIn)))
    }

    @Test
    fun `a usb source is suggested before other external sources`() {
        assertEquals(rode, SourceSelector.suggested(listOf(builtIn, headset, rode)))
        assertEquals(headset, SourceSelector.suggested(listOf(builtIn, headset)))
    }

    @Test
    fun `a new recording uses the choice, else the suggestion, else the built-in microphone`() {
        val all = listOf(builtIn, rode)
        assertEquals(builtIn, SourceSelector.resolve(builtIn.id, all))
        assertEquals(rode, SourceSelector.resolve(null, all))
        assertEquals(rode, SourceSelector.resolve(42, all)) // the chosen device is gone
        assertEquals(builtIn, SourceSelector.resolve(rode.id, listOf(builtIn)))
    }

    @Test
    fun `a choice of the built-in microphone survives a plugged-in external source`() {
        assertEquals(builtIn, SourceSelector.resolve(builtIn.id, listOf(builtIn, rode)))
    }

    @Test
    fun `the recording falls back to the built-in microphone only when its source is gone`() {
        assertNull(SourceSelector.fallbackFor(rode.id, listOf(builtIn, rode)))
        assertEquals(builtIn, SourceSelector.fallbackFor(rode.id, listOf(builtIn)))
        assertNull(SourceSelector.fallbackFor(rode.id, emptyList()))
    }

    @Test
    fun `a reconnected external source is reported but the built-in one never is`() {
        assertEquals(listOf(rode), SourceSelector.newlyAvailableExternal(listOf(builtIn), listOf(builtIn, rode)))
        assertEquals(emptyList(), SourceSelector.newlyAvailableExternal(listOf(builtIn, rode), listOf(builtIn, rode)))
        assertEquals(emptyList(), SourceSelector.newlyAvailableExternal(emptyList(), listOf(builtIn)))
    }

    @Test
    fun `recorded channels follow the channel policy`() {
        assertEquals(2, rode.recordedChannels)
        assertEquals(1, builtIn.recordedChannels)
        assertEquals(1, headset.recordedChannels)
    }
}
