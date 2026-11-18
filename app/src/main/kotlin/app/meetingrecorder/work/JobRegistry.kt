package app.meetingrecorder.work

/** The two kinds of background job; at most one of each runs at a time. */
enum class JobKind(val uniqueWorkName: String) {
    TRANSCRIPTION("transcription"),
    MODEL_INSTALL("model-install"),
}

/** A job this process accepted. [folderName] is null for a model install. */
data class JobRequest(val kind: JobKind, val requestId: String, val folderName: String?, val modelId: String)

/**
 * In-memory, thread-safe record of the jobs this process has accepted (contracts/transcription-service.md,
 * "Liveness registry and the launch token"). WorkManager persists work and may re-run it after the process
 * died; a worker therefore first [claim]s its request and does nothing when this process never registered it.
 * "Live" means the registry holds the folder: after a process death it is empty, so any `in-progress` job
 * block found in a folder is stale by definition.
 */
class JobRegistry(private val launchToken: String) {
    private class Slot(val request: JobRequest, var claimed: Boolean = false)

    private val lock = Any()
    private val slots = mutableMapOf<JobKind, Slot>()

    /** Registers [request]; false when a job of the same kind is already registered. */
    fun register(request: JobRequest): Boolean = synchronized(lock) {
        if (slots.containsKey(request.kind)) return false
        slots[request.kind] = Slot(request)
        true
    }

    /**
     * True only for a request this process registered, with the token of this process start, and only
     * once. A refused claim leaves the slot untouched.
     */
    fun claim(requestId: String, token: String): Boolean = synchronized(lock) {
        if (token != launchToken) return false
        val slot = slots.values.firstOrNull { it.request.requestId == requestId } ?: return false
        if (slot.claimed) return false
        slot.claimed = true
        true
    }

    /** Frees the slot of [requestId]; unknown ids change nothing. */
    fun release(requestId: String) {
        synchronized(lock) { slots.entries.removeAll { it.value.request.requestId == requestId } }
    }

    fun current(kind: JobKind): JobRequest? = synchronized(lock) { slots[kind]?.request }

    /** True while a transcription registered for [folderName] is held. */
    fun isLive(folderName: String): Boolean = synchronized(lock) { slots[JobKind.TRANSCRIPTION]?.request?.folderName == folderName }

    /** The model of the running transcription, so that deleting it can be refused (FR-008). */
    fun runningTranscriptionModelId(): String? = synchronized(lock) { slots[JobKind.TRANSCRIPTION]?.request?.modelId }
}
