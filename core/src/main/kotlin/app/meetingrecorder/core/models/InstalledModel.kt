package app.meetingrecorder.core.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The sidecar `<id>.json` of an installed model. [sha256] is the identity that `transcript.json` and
 * the work file refer to; [sizeBytes] must equal the length of `<id>.bin`, else the model is damaged.
 */
@Serializable
public data class InstalledModel(
    val id: String,
    val displayName: String,
    val origin: ModelOrigin,
    val sha256: String,
    val sizeBytes: Long,
    val languages: ModelLanguages,
    /** ISO-8601, informational. */
    val installedAt: String,
) {
    public companion object {
        private const val IMPORTED_PREFIX = "imported-"
        private const val IMPORTED_HEX = 12

        /** The id of an imported model: `imported-<first 12 hex of sha256>`. */
        public fun importedId(sha256: String): String = IMPORTED_PREFIX + sha256.take(IMPORTED_HEX)
    }
}

/** The state file `<id>.part.json` of a transfer in progress. [bytesDone] never exceeds [expectedSize]. */
@Serializable
public data class PartState(
    val url: String,
    val expectedSha256: String,
    val expectedSize: Long,
    val etag: String? = null,
    val bytesDone: Long,
) {
    init {
        require(bytesDone in 0..expectedSize) { "bytesDone $bytesDone is outside 0..$expectedSize" }
    }
}

/** File names of a model and the rules that turn a listing of files into "installed" and "resumable". */
public object ModelFiles {

    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    public fun binName(id: String): String = "$id.bin"

    public fun sidecarName(id: String): String = "$id.json"

    public fun partName(id: String): String = "$id.part"

    public fun partStateName(id: String): String = "$id.part.json"

    public fun encodeSidecar(model: InstalledModel): String = json.encodeToString(InstalledModel.serializer(), model) + "\n"

    /** The sidecar, or `null` when the text does not decode or breaks a rule (so the model counts as not installed). */
    public fun decodeSidecar(text: String): InstalledModel? {
        val model = try {
            json.decodeFromString(InstalledModel.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val valid = model.id.isNotEmpty() && model.displayName.isNotEmpty() && sha256Pattern.matches(model.sha256) && model.sizeBytes > 0
        return model.takeIf { valid }
    }

    /**
     * A model is installed iff `<id>.bin` and a valid `<id>.json` exist and the length of the file
     * equals `sizeBytes`. [binLength] is `null` when the file is missing, [sidecarText] when the sidecar is.
     */
    public fun installedModel(id: String, binLength: Long?, sidecarText: String?): InstalledModel? {
        if (binLength == null || sidecarText == null) return null
        val model = decodeSidecar(sidecarText) ?: return null
        return model.takeIf { it.id == id && it.sizeBytes == binLength }
    }

    public fun encodePartState(state: PartState): String = json.encodeToString(PartState.serializer(), state) + "\n"

    public fun decodePartState(text: String): PartState? =
        try {
            json.decodeFromString(PartState.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null // bytesDone > expectedSize
        }

    /**
     * A paused transfer can continue only with a `.part` file and a `.part.json` for the same url,
     * size and hash, and a `.part` file that holds at least the bytes the state claims.
     */
    public fun isResumable(
        partLength: Long?,
        state: PartState?,
        url: String,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean =
        partLength != null &&
            state != null &&
            state.url == url &&
            state.expectedSize == expectedSize &&
            state.expectedSha256 == expectedSha256 &&
            partLength >= state.bytesDone
}
