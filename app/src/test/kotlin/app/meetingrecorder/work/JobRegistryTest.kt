package app.meetingrecorder.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobRegistryTest {
    private val token = "token-of-this-process"
    private val registry = JobRegistry(token)

    private fun register(requestId: String = "r1", folder: String? = "2026-11-16_10-00-00", kind: JobKind = JobKind.TRANSCRIPTION) =
        registry.register(JobRequest(kind, requestId, folder, "small-q5_1"))

    @Test
    fun aRegisteredRequestCanBeClaimedWithTheTokenOfThisProcess() {
        assertTrue(register())
        assertTrue(registry.claim("r1", token))
    }

    @Test
    fun aRequestCanBeClaimedOnlyOnce() {
        register()
        assertTrue(registry.claim("r1", token))
        assertFalse(registry.claim("r1", token))
    }

    @Test
    fun aRequestThatThisProcessNeverRegisteredIsRefused() {
        // WorkManager re-running old work after a kill: the new process has an empty registry.
        assertFalse(registry.claim("old-request", token))
    }

    @Test
    fun aTokenFromAnotherProcessStartIsRefusedAndDoesNotUseUpTheClaim() {
        register()
        assertFalse(registry.claim("r1", "token-of-an-earlier-process"))
        assertTrue(registry.claim("r1", token))
    }

    @Test
    fun releaseFreesTheSlot() {
        register("r1")
        assertFalse(register("r2"))
        registry.release("r1")
        assertTrue(register("r2"))
        assertFalse(registry.claim("r1", token), "a released request cannot be claimed any more")
    }

    @Test
    fun releaseOfAnUnknownRequestChangesNothing() {
        register("r1")
        registry.release("other")
        assertEquals("r1", registry.current(JobKind.TRANSCRIPTION)?.requestId)
    }

    @Test
    fun onlyOneJobPerKindIsRegisteredButKindsAreIndependent() {
        assertTrue(register("t1", kind = JobKind.TRANSCRIPTION))
        assertFalse(register("t2", kind = JobKind.TRANSCRIPTION))
        assertTrue(register("m1", folder = null, kind = JobKind.MODEL_INSTALL))
        assertFalse(register("m2", folder = null, kind = JobKind.MODEL_INSTALL))
    }

    @Test
    fun liveMeansTheRegistryHoldsTheFolder() {
        assertFalse(registry.isLive("2026-11-16_10-00-00"))
        register()
        assertTrue(registry.isLive("2026-11-16_10-00-00"))
        assertFalse(registry.isLive("2026-11-16_11-00-00"))
        registry.release("r1")
        assertFalse(registry.isLive("2026-11-16_10-00-00"))
    }

    @Test
    fun aModelInstallIsNotALiveMeeting() {
        register("m1", folder = null, kind = JobKind.MODEL_INSTALL)
        assertFalse(registry.isLive("2026-11-16_10-00-00"))
    }

    @Test
    fun theRunningModelIsKnownWhileATranscriptionHoldsIt() {
        assertNull(registry.runningTranscriptionModelId())
        register()
        assertEquals("small-q5_1", registry.runningTranscriptionModelId())
        registry.release("r1")
        assertNull(registry.runningTranscriptionModelId())
    }

    @Test
    fun theLaunchTokenIsRandomPerCall() {
        assertTrue(LaunchToken.newToken().length >= 32)
        assertTrue(LaunchToken.newToken() != LaunchToken.newToken())
    }
}
