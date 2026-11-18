package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.model.FailureReason

/** What the meeting list shows for a meeting. Always computed from the folder, never trusted from a cache. */
public sealed interface TranscriptionStatus {
    public data object None : TranscriptionStatus

    public data object InProgress : TranscriptionStatus

    public data object Done : TranscriptionStatus

    public data class Failed(val reason: FailureReason) : TranscriptionStatus
}

/** Extra information for the detail screen. */
public sealed interface DetailHint {
    public data object None : DetailHint

    /** A job did not finish. With a committed transcript this is the re-transcription banner. */
    public data class JobFailed(val reason: FailureReason, val resumable: Boolean) : DetailHint

    public data object TranscriptUnreadable : DetailHint
}

public data class Resolved(val status: TranscriptionStatus, val hint: DetailHint)

/** State of `transcript.json` in the folder. */
public enum class TranscriptState {
    MISSING,

    /** The file exists, decodes and validates. */
    VALID,

    /** The file exists but does not decode or validate. */
    UNREADABLE,
}
