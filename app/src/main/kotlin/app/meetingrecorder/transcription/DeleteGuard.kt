package app.meetingrecorder.transcription

/** What happened to a delete request. */
sealed interface DeleteResult {
    data object Deleted : DeleteResult

    /** The running transcription did not stop in time; the folder is untouched (FR-020). */
    data object StopTimedOut : DeleteResult

    data object Failed : DeleteResult
}

/**
 * Deleting a meeting while it is being transcribed (FR-020): the job is cancelled first, the delete waits for
 * the stop for at most [STOP_TIMEOUT_MS], and only then is the folder deleted, so no work file or `.part`
 * outlives its folder. If the stop does not finish in time the delete is refused and nothing is touched.
 * Android-free: the folder deletion is injected.
 */
class DeleteGuard(
    private val jobs: JobCanceller,
    private val deleteFolder: (folderName: String) -> Boolean,
) {
    /** True when the confirmation must add the warning that the running transcription will be cancelled. */
    fun needsWarning(folderName: String): Boolean = jobs.isRunningFor(folderName)

    suspend fun delete(folderName: String): DeleteResult {
        if (jobs.isRunningFor(folderName) && !jobs.cancelAndAwait(STOP_TIMEOUT_MS)) return DeleteResult.StopTimedOut
        return if (deleteFolder(folderName)) DeleteResult.Deleted else DeleteResult.Failed
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
