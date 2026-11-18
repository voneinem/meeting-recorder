package app.meetingrecorder.core.models

public enum class ModelKind {
    /** A speech recognition model, offered to the user. */
    SPEECH,

    /** The voice-activity model: downloaded together with a speech model, never offered on its own. */
    VAD,
}

/**
 * One downloadable model (contracts/model-catalog.md). [displayNameKey] and [descriptionKey] are
 * names of string resources. [sizeBytes] and [sha256] describe the whole file at the pinned [url].
 */
public data class ModelCatalogEntry(
    val id: String,
    val kind: ModelKind,
    val displayNameKey: String,
    val descriptionKey: String,
    val languages: ModelLanguages,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val sourceName: String,
    val license: String,
    val approxMemoryMb: Int,
    val vadCompanionId: String? = null,
    val recommended: Boolean = false,
)
