package app.meetingrecorder.models

import androidx.annotation.StringRes
import app.meetingrecorder.R

/**
 * Maps the string keys of the model catalog (`displayNameKey`, `descriptionKey`) to string resources. The
 * catalog lives in `:core`, which knows no Android resources; a map in the app avoids resource reflection
 * (`getIdentifier`), which lint forbids and which hides unused resources. Every catalog key needs an entry here and a
 * string in `values/` and `values-de/`.
 */
object ModelStrings {
    private val resources: Map<String, Int> = mapOf(
        "model_tiny_name" to R.string.model_tiny_name,
        "model_tiny_description" to R.string.model_tiny_description,
        "model_base_compact_name" to R.string.model_base_compact_name,
        "model_base_compact_description" to R.string.model_base_compact_description,
        "model_base_fast_name" to R.string.model_base_fast_name,
        "model_base_fast_description" to R.string.model_base_fast_description,
        "model_small_name" to R.string.model_small_name,
        "model_small_description" to R.string.model_small_description,
    )

    /** The string resource of [key], or null when the key is not a catalog string (for example a file name). */
    @StringRes
    fun res(key: String): Int? = resources[key]
}
