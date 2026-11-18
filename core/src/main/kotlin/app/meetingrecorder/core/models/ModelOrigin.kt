package app.meetingrecorder.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a speech recognition model file came from. */
@Serializable
public enum class ModelOrigin {
    /** Downloaded from the pinned catalog address, or recognised as a catalog entry by its hash. */
    @SerialName("catalog") CATALOG,

    /** Picked by the user from a file. */
    @SerialName("imported") IMPORTED,
}
