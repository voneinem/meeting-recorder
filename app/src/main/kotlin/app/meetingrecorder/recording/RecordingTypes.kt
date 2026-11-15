package app.meetingrecorder.recording

import app.meetingrecorder.core.level.ChannelLevel

/** The phases of the recording session (data-model.md, "State machine"). */
enum class Phase { IDLE, RECORDING, PAUSED, SUSPENDED, FINALIZING }

/** What the UI renders (contracts/recording-service.md). Positions come only from [framesAppended]. */
data class RecordingState(
    val phase: Phase = Phase.IDLE,
    /** Name of the current meeting folder, or null when idle. */
    val meetingFolder: String? = null,
    /** Timeline position: frames appended to the audio stream so far. */
    val framesAppended: Long = 0,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val channels: Int = 1,
    val levels: List<ChannelLevel> = emptyList(),
    val activeSourceName: String = "",
    val photoCount: Int = 0,
) {
    /** Elapsed recorded time, derived from the frame counter. */
    val elapsedMs: Long get() = framesAppended * MS_PER_SECOND / sampleRate

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        private const val MS_PER_SECOND = 1000L
    }
}

/** One-shot notices from the service to the UI (contracts/recording-service.md). */
sealed interface RecordingEvent {
    data class Suspended(val reason: String) : RecordingEvent
    data class AudioDropped(val atFrame: Long, val frames: Long) : RecordingEvent
    data class StorageLow(val bytesLeft: Long) : RecordingEvent
    data object StorageFull : RecordingEvent
    data class LimitApproaching(val secondsLeft: Long) : RecordingEvent
    data object LimitReached : RecordingEvent
    data class SourceSwitched(val fromName: String, val toName: String, val atFrame: Long) : RecordingEvent
    data class ExternalAvailableAgain(val name: String) : RecordingEvent

    /** A command was refused or something unrecoverable happened; the data written so far stays valid. */
    data class Error(val code: String, val detail: String? = null) : RecordingEvent
}
