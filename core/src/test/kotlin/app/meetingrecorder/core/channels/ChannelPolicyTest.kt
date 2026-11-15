package app.meetingrecorder.core.channels

import app.meetingrecorder.core.model.SourceType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelPolicyTest {

    @Test
    fun `the built in microphone is always mono`() {
        assertEquals(1, ChannelPolicy.recordedChannels(SourceType.BUILTIN, 1))
        assertEquals(1, ChannelPolicy.recordedChannels(SourceType.BUILTIN, 2))
    }

    @Test
    fun `external sources are stereo when they deliver two channels`() {
        assertEquals(2, ChannelPolicy.recordedChannels(SourceType.USB, 2))
        assertEquals(1, ChannelPolicy.recordedChannels(SourceType.USB, 1))
        assertEquals(2, ChannelPolicy.recordedChannels(SourceType.OTHER, 2))
    }

    @Test
    fun `more than two channels records the first two`() {
        assertEquals(2, ChannelPolicy.recordedChannels(SourceType.USB, 4))
        assertEquals(2, ChannelPolicy.recordedChannels(SourceType.USB, 8))
    }

    @Test
    fun `no channels is rejected`() {
        assertFailsWith<IllegalArgumentException> { ChannelPolicy.recordedChannels(SourceType.USB, 0) }
    }

    @Test
    fun `duplication is needed when the source has fewer channels than the file`() {
        assertTrue(ChannelPolicy.needsDuplication(fileChannels = 2, sourceChannels = 1))
        assertFalse(ChannelPolicy.needsDuplication(fileChannels = 2, sourceChannels = 2))
        assertFalse(ChannelPolicy.needsDuplication(fileChannels = 1, sourceChannels = 1))
    }

    @Test
    fun `mono frames are copied into both channels`() {
        val output = ChannelPolicy.duplicateMonoToStereo(shortArrayOf(1, -2, 3, 32_767, -32_768), 5)
        assertContentEquals(shortArrayOf(1, 1, -2, -2, 3, 3, 32_767, 32_767, -32_768, -32_768), output)
    }

    @Test
    fun `only the requested frames are duplicated`() {
        assertContentEquals(shortArrayOf(7, 7, 8, 8), ChannelPolicy.duplicateMonoToStereo(shortArrayOf(7, 8, 9, 10), 2))
        assertEquals(0, ChannelPolicy.duplicateMonoToStereo(ShortArray(0), 0).size)
        assertFailsWith<IllegalArgumentException> { ChannelPolicy.duplicateMonoToStereo(ShortArray(2), 3) }
    }
}
