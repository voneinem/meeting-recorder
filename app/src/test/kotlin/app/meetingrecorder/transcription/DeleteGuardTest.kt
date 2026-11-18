package app.meetingrecorder.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DeleteGuardTest {
    private val log = mutableListOf<String>()

    private class FakeCanceller(
        private val log: MutableList<String>,
        var running: Boolean,
        private val stopsInTime: Boolean = true,
    ) : JobCanceller {
        override fun isRunningFor(folderName: String) = running && folderName == "m1"

        override suspend fun cancelAndAwait(timeoutMs: Long): Boolean {
            log += "cancel($timeoutMs)"
            if (stopsInTime) running = false
            return stopsInTime
        }
    }

    private fun guard(canceller: JobCanceller, deleteResult: Boolean = true) = DeleteGuard(canceller) { folder ->
        log += "delete($folder)"
        deleteResult
    }

    @Test
    fun aMeetingThatIsNotTranscribedIsDeletedAtOnce() = runBlocking {
        val result = guard(FakeCanceller(log, running = false)).delete("m1")

        assertEquals(DeleteResult.Deleted, result)
        assertEquals(listOf("delete(m1)"), log)
    }

    @Test
    fun aRunningTranscriptionIsCancelledBeforeTheDeleteAndTheOrderIsKept() = runBlocking {
        val result = guard(FakeCanceller(log, running = true)).delete("m1")

        assertEquals(DeleteResult.Deleted, result)
        assertEquals(listOf("cancel(${DeleteGuard.STOP_TIMEOUT_MS})", "delete(m1)"), log)
    }

    @Test
    fun theStopTimeoutIsFiveSeconds() {
        assertEquals(5_000L, DeleteGuard.STOP_TIMEOUT_MS)
    }

    @Test
    fun aStopThatDoesNotFinishInTimeRefusesTheDeleteAndLeavesTheFolder() = runBlocking {
        val result = guard(FakeCanceller(log, running = true, stopsInTime = false)).delete("m1")

        assertEquals(DeleteResult.StopTimedOut, result)
        assertTrue(log.none { it.startsWith("delete") }, "the folder must not be touched")
    }

    @Test
    fun aFailingDeleteIsReported() = runBlocking {
        assertEquals(DeleteResult.Failed, guard(FakeCanceller(log, running = false), deleteResult = false).delete("m1"))
    }

    @Test
    fun theWarningIsShownOnlyForAMeetingWithARunningJob() {
        val canceller = FakeCanceller(log, running = true)
        assertTrue(guard(canceller).needsWarning("m1"))
        assertFalse(guard(canceller).needsWarning("m2"))
    }

    @Test
    fun renamingARunningMeetingStaysAllowed() {
        // A rename only changes the title through MeetingMetadataStore, under the lock the job also uses;
        // the guard has no say in it: there is no function that could refuse it.
        assertTrue(DeleteGuard::class.java.declaredMethods.none { it.name.contains("rename", ignoreCase = true) })
    }
}
