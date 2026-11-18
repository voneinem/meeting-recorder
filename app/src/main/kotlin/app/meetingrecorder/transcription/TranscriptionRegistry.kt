package app.meetingrecorder.transcription

import app.meetingrecorder.work.JobKind
import app.meetingrecorder.work.JobRegistry
import app.meetingrecorder.work.JobRequest

/** The transcription slot of the [JobRegistry]: what this process has accepted, and whether a folder is live. */
class TranscriptionRegistry(private val registry: JobRegistry) {
    /** Registers the job; false when a transcription is already registered (one at a time, FR-017). */
    fun register(requestId: String, folderName: String, modelId: String): Boolean =
        registry.register(JobRequest(JobKind.TRANSCRIPTION, requestId, folderName, modelId))

    fun claim(requestId: String, token: String): Boolean = registry.claim(requestId, token)

    fun release(requestId: String) = registry.release(requestId)

    fun current(): JobRequest? = registry.current(JobKind.TRANSCRIPTION)

    fun isRunningFor(folderName: String): Boolean = registry.isLive(folderName)

    /** True while the running job uses [modelId]: such a model cannot be deleted (FR-008). */
    fun modelInUse(modelId: String): Boolean = registry.runningTranscriptionModelId() == modelId
}
