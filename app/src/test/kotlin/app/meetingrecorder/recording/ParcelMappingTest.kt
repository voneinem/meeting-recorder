package app.meetingrecorder.recording

import app.meetingrecorder.core.level.ChannelLevel
import app.meetingrecorder.ipc.toEvent
import app.meetingrecorder.ipc.toParcel
import app.meetingrecorder.ipc.toState
import kotlin.test.Test
import kotlin.test.assertEquals

class ParcelMappingTest {

    @Test
    fun `state survives the round trip through its parcel`() {
        val state = RecordingState(
            phase = Phase.PAUSED,
            meetingFolder = "2026-11-15_09-05-07",
            framesAppended = 123_456,
            sampleRate = 48_000,
            channels = 2,
            levels = listOf(ChannelLevel(-3f, -12f), ChannelLevel(-90f, -90f)),
            activeSourceName = "Built-in microphone",
            photoCount = 3,
        )
        assertEquals(state, state.toParcel().toState())
    }

    @Test
    fun `an idle state round trips`() {
        assertEquals(RecordingState(), RecordingState().toParcel().toState())
    }

    @Test
    fun `every event survives the round trip`() {
        val events = listOf(
            RecordingEvent.Suspended("phone call"),
            RecordingEvent.AudioDropped(4_800, 960),
            RecordingEvent.StorageLow(50_000_000),
            RecordingEvent.StorageFull,
            RecordingEvent.LimitApproaching(900),
            RecordingEvent.LimitReached,
            RecordingEvent.SourceSwitched("RØDE Wireless PRO", "Built-in microphone", 1_440_000),
            RecordingEvent.ExternalAvailableAgain("RØDE Wireless PRO"),
            RecordingEvent.Error("start_failed", "no space"),
            RecordingEvent.Error("rejected"),
        )
        for (event in events) assertEquals(event, event.toParcel().toEvent(), event.toString())
    }
}
