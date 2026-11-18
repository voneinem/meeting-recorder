package app.meetingrecorder.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which languages a speech recognition model can transcribe. */
@Serializable
public enum class ModelLanguages {
    @SerialName("multilingual") MULTILINGUAL,
    @SerialName("english-only") ENGLISH_ONLY,
}
