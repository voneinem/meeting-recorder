package app.meetingrecorder.recording

import app.meetingrecorder.core.channels.ChannelPolicy
import app.meetingrecorder.core.channels.SourceOption
import app.meetingrecorder.core.channels.SourceSelector
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.wav.SeekableStore
import app.meetingrecorder.core.wav.WavHeader
import java.io.IOException
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unplug fallback end to end, without a device (T081): a stereo recording from an external
 * microphone continues on the built-in one at a known frame. The pieces are the ones the session
 * uses: [SourceSelector] decides, [ChannelPolicy] spreads mono over both channels, [WavWriter] keeps
 * one continuous file and [MeetingEditor] logs the switch.
 */
class SourceFallbackTest {

    private class MemoryStore : SeekableStore {
        private var data = ByteArray(0)

        @Synchronized fun snapshot(): ByteArray = data.copyOf()

        @Synchronized override fun length(): Long = data.size.toLong()

        @Synchronized override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            if (offset >= data.size) return -1
            val count = minOf(length.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), buffer, bufferOffset, count)
            return count
        }

        @Synchronized override fun writeAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
            val end = offset.toInt() + length
            if (end > data.size) data = data.copyOf(end)
            System.arraycopy(buffer, bufferOffset, data, offset.toInt(), length)
        }

        @Synchronized override fun truncate(length: Long) {
            data = data.copyOf(length.toInt())
        }

        override fun sync() = Unit
    }

    private val noListener = object : WavWriter.Listener {
        override fun onDropped(atFrame: Long, frames: Long) = Unit
        override fun onWriteFailed(error: IOException) = Unit
    }

    private val builtIn = SourceOption(1, SourceInfo("Built-in microphone", SourceType.BUILTIN, 1))
    private val rode = SourceOption(7, SourceInfo("RØDE Wireless PRO", SourceType.USB, 2))

    private fun pcmAt(data: ByteArray, frame: Int, channel: Int, channels: Int): Short {
        val offset = WavHeader.SIZE + (frame * channels + channel) * 2
        return ((data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)).toShort()
    }

    @Test
    fun `a stereo recording continues on the built-in microphone with one logged switch`() {
        val sampleRate = 48_000
        val switchAtFrame = 30L * sampleRate
        val secondHalfFrames = 30 * sampleRate

        // Start on the suggested external source: a stereo file.
        val chosen = checkNotNull(SourceSelector.resolve(null, listOf(builtIn, rode)))
        var meeting = MeetingEditor.create(null, OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), sampleRate, chosen.info)
        assertEquals(ChannelLayout.STEREO, meeting.audio.channelLayout)
        val store = MemoryStore()
        val writer = WavWriter(store, sampleRate, meeting.audio.channels, noListener)

        // First half: left and right carry different signals.
        val stereo = ShortArray(sampleRate * 2) { if (it % 2 == 0) 1000 else -2000 }
        repeat(30) { check(writer.append(stereo, sampleRate)) }
        writer.flush()
        assertEquals(switchAtFrame, writer.framesAppended)

        // The external microphone disappears: the selector names the fallback, and capture continues mono.
        val fallback = checkNotNull(SourceSelector.fallbackFor(rode.id, listOf(builtIn)))
        assertEquals(builtIn, fallback)
        val (withSource, toId) = MeetingEditor.addSource(meeting, fallback.info)
        meeting = MeetingEditor.addSourceSwitch(withSource, writer.framesAppended, meeting.initialSourceId, toId)
        val mono = ShortArray(sampleRate) { 500 }
        assertTrue(ChannelPolicy.needsDuplication(meeting.audio.channels, fallback.recordedChannels))
        repeat(30) { check(writer.append(ChannelPolicy.duplicateMonoToStereo(mono, sampleRate), sampleRate)) }
        writer.close()

        // One switch at the known frame, marked as duplicated, and the file layout never changed.
        val switch = meeting.sourceSwitches.single()
        assertEquals(switchAtFrame, switch.atFrame)
        assertEquals(30_000L, switch.atMs)
        assertTrue(switch.channelsDuplicated)
        assertEquals(listOf("RØDE Wireless PRO", "Built-in microphone"), meeting.sources.map { it.name })
        assertEquals(ChannelLayout.STEREO, meeting.audio.channelLayout)

        // The audio is one continuous stereo file: independent channels, then the same signal on both.
        val bytes = store.snapshot()
        val format = checkNotNull(WavHeader.parse(bytes.copyOf(WavHeader.SIZE)))
        assertEquals(2, format.channels)
        assertEquals(switchAtFrame + secondHalfFrames, ((bytes.size - WavHeader.SIZE) / format.blockAlign).toLong())
        val last = (switchAtFrame - 1).toInt()
        assertEquals(1000, pcmAt(bytes, last, 0, 2).toInt())
        assertEquals(-2000, pcmAt(bytes, last, 1, 2).toInt())
        val first = switchAtFrame.toInt()
        assertEquals(500, pcmAt(bytes, first, 0, 2).toInt())
        assertEquals(500, pcmAt(bytes, first, 1, 2).toInt())
    }

    @Test
    fun `a re-plugged microphone is only reported and never taken over`() {
        val reappeared = rode.copy(id = 12) // the system hands out a new id
        assertEquals(listOf(reappeared), SourceSelector.newlyAvailableExternal(listOf(builtIn), listOf(builtIn, reappeared)))
        // The running recording is on the built-in microphone, which is still there: no fallback, no switch.
        assertNull(SourceSelector.fallbackFor(builtIn.id, listOf(builtIn, reappeared)))
    }
}
