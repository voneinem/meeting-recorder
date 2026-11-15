package app.meetingrecorder.ipc

import android.os.Parcelable
import app.meetingrecorder.core.level.ChannelLevel
import app.meetingrecorder.recording.Phase
import app.meetingrecorder.recording.RecordingEvent
import app.meetingrecorder.recording.RecordingState
import kotlinx.parcelize.Parcelize

/** Parcelable form of [RecordingState] for the AIDL interface. */
@Parcelize
data class RecordingStateParcel(
    val phase: Int,
    val meetingFolder: String?,
    val framesAppended: Long,
    val sampleRate: Int,
    val channels: Int,
    val peakDb: List<Float>,
    val rmsDb: List<Float>,
    val activeSourceName: String,
    val photoCount: Int,
) : Parcelable

/** Parcelable form of [RecordingEvent]: a type tag plus generic fields. */
@Parcelize
data class RecordingEventParcel(
    val type: Int,
    val atFrame: Long,
    val value: Long,
    val text: String?,
    val text2: String?,
) : Parcelable

fun RecordingState.toParcel(): RecordingStateParcel = RecordingStateParcel(
    phase = phase.ordinal,
    meetingFolder = meetingFolder,
    framesAppended = framesAppended,
    sampleRate = sampleRate,
    channels = channels,
    peakDb = levels.map { it.peakDb },
    rmsDb = levels.map { it.rmsDb },
    activeSourceName = activeSourceName,
    photoCount = photoCount,
)

fun RecordingStateParcel.toState(): RecordingState = RecordingState(
    phase = Phase.entries.getOrElse(phase) { Phase.IDLE },
    meetingFolder = meetingFolder,
    framesAppended = framesAppended,
    sampleRate = sampleRate,
    channels = channels,
    levels = peakDb.indices.map { ChannelLevel(peakDb[it], rmsDb.getOrElse(it) { peakDb[it] }) },
    activeSourceName = activeSourceName,
    photoCount = photoCount,
)

private object EventType {
    const val SUSPENDED = 1
    const val AUDIO_DROPPED = 2
    const val STORAGE_LOW = 3
    const val STORAGE_FULL = 4
    const val LIMIT_APPROACHING = 5
    const val LIMIT_REACHED = 6
    const val SOURCE_SWITCHED = 7
    const val EXTERNAL_AVAILABLE_AGAIN = 8
    const val ERROR = 9
}

fun RecordingEvent.toParcel(): RecordingEventParcel = when (this) {
    is RecordingEvent.Suspended -> RecordingEventParcel(EventType.SUSPENDED, 0, 0, reason, null)
    is RecordingEvent.AudioDropped -> RecordingEventParcel(EventType.AUDIO_DROPPED, atFrame, frames, null, null)
    is RecordingEvent.StorageLow -> RecordingEventParcel(EventType.STORAGE_LOW, 0, bytesLeft, null, null)
    RecordingEvent.StorageFull -> RecordingEventParcel(EventType.STORAGE_FULL, 0, 0, null, null)
    is RecordingEvent.LimitApproaching -> RecordingEventParcel(EventType.LIMIT_APPROACHING, 0, secondsLeft, null, null)
    RecordingEvent.LimitReached -> RecordingEventParcel(EventType.LIMIT_REACHED, 0, 0, null, null)
    is RecordingEvent.SourceSwitched -> RecordingEventParcel(EventType.SOURCE_SWITCHED, atFrame, 0, fromName, toName)
    is RecordingEvent.ExternalAvailableAgain -> RecordingEventParcel(EventType.EXTERNAL_AVAILABLE_AGAIN, 0, 0, name, null)
    is RecordingEvent.Error -> RecordingEventParcel(EventType.ERROR, 0, 0, code, detail)
}

fun RecordingEventParcel.toEvent(): RecordingEvent = when (type) {
    EventType.SUSPENDED -> RecordingEvent.Suspended(text.orEmpty())
    EventType.AUDIO_DROPPED -> RecordingEvent.AudioDropped(atFrame, value)
    EventType.STORAGE_LOW -> RecordingEvent.StorageLow(value)
    EventType.STORAGE_FULL -> RecordingEvent.StorageFull
    EventType.LIMIT_APPROACHING -> RecordingEvent.LimitApproaching(value)
    EventType.LIMIT_REACHED -> RecordingEvent.LimitReached
    EventType.SOURCE_SWITCHED -> RecordingEvent.SourceSwitched(text.orEmpty(), text2.orEmpty(), atFrame)
    EventType.EXTERNAL_AVAILABLE_AGAIN -> RecordingEvent.ExternalAvailableAgain(text.orEmpty())
    else -> RecordingEvent.Error(text ?: "unknown", text2)
}
