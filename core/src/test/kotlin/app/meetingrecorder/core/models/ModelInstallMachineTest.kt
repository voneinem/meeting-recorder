package app.meetingrecorder.core.models

import app.meetingrecorder.core.models.InstallEffect.DELETE_INSTALLED
import app.meetingrecorder.core.models.InstallEffect.DELETE_PARTIAL
import app.meetingrecorder.core.models.InstallEffect.KEEP_PARTIAL
import app.meetingrecorder.core.models.InstallEffect.WRITE_SIDECAR
import app.meetingrecorder.core.models.ModelInstallState.Failed
import app.meetingrecorder.core.models.ModelInstallState.Installed
import app.meetingrecorder.core.models.ModelInstallState.NotInstalled
import app.meetingrecorder.core.models.ModelInstallState.Paused
import app.meetingrecorder.core.models.ModelInstallState.Transferring
import app.meetingrecorder.core.models.ModelInstallState.Verifying
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One test per transition of the state machine in data-model.md. */
class ModelInstallMachineTest {

    private fun reduce(state: ModelInstallState, event: InstallEvent) = ModelInstallMachine.reduce(state, event)

    private val downloading = Transferring(TransferKind.DOWNLOAD, 100, 1_000)
    private val importing = Transferring(TransferKind.IMPORT, 100, 1_000)

    @Test
    fun `NotInstalled to Transferring on download or import`() {
        assertEquals(InstallTransition(Transferring(TransferKind.DOWNLOAD, 0, 1_000)), reduce(NotInstalled, InstallEvent.Start(TransferKind.DOWNLOAD, 1_000)))
        assertEquals(InstallTransition(Transferring(TransferKind.IMPORT, 0, 500)), reduce(NotInstalled, InstallEvent.Start(TransferKind.IMPORT, 500)))
    }

    @Test
    fun `progress moves forward within the size`() {
        assertEquals(InstallTransition(downloading.copy(bytesDone = 400)), reduce(downloading, InstallEvent.Progress(400)))
        assertTrue(reduce(downloading, InstallEvent.Progress(50)).rejected) // never backwards
        assertTrue(reduce(downloading, InstallEvent.Progress(1_001)).rejected) // never beyond the size
    }

    @Test
    fun `Transferring to Verifying when the size is reached`() {
        assertEquals(InstallTransition(Verifying), reduce(downloading.copy(bytesDone = 1_000), InstallEvent.SizeReached))
        assertTrue(reduce(downloading, InstallEvent.SizeReached).rejected)
    }

    @Test
    fun `Verifying to Installed on a matching hash writes the sidecar`() {
        assertEquals(InstallTransition(Installed, listOf(WRITE_SIDECAR)), reduce(Verifying, InstallEvent.HashOk))
    }

    @Test
    fun `cancel returns to NotInstalled and deletes the partial files`() {
        assertEquals(InstallTransition(NotInstalled, listOf(DELETE_PARTIAL)), reduce(downloading, InstallEvent.Cancel))
        assertEquals(InstallTransition(NotInstalled, listOf(DELETE_PARTIAL)), reduce(importing, InstallEvent.Cancel))
    }

    @Test
    fun `network loss, a killed process or a recording pause a download and keep the partial files`() {
        assertEquals(InstallTransition(Paused(100, 1_000), listOf(KEEP_PARTIAL)), reduce(downloading, InstallEvent.Interrupted))
    }

    @Test
    fun `an interrupted import is cancelled instead`() {
        assertEquals(InstallTransition(NotInstalled, listOf(DELETE_PARTIAL)), reduce(importing, InstallEvent.Interrupted))
    }

    @Test
    fun `Paused resumes where it stopped`() {
        assertEquals(InstallTransition(Transferring(TransferKind.DOWNLOAD, 100, 1_000)), reduce(Paused(100, 1_000), InstallEvent.Resume))
    }

    @Test
    fun `deleting the partial files of a paused download returns to NotInstalled`() {
        assertEquals(InstallTransition(NotInstalled, listOf(DELETE_PARTIAL)), reduce(Paused(100, 1_000), InstallEvent.DeletePartial))
    }

    @Test
    fun `a hash mismatch fails with checksum, deletes the files and can be dismissed`() {
        val failed = reduce(Verifying, InstallEvent.HashMismatch)
        assertEquals(InstallTransition(Failed(InstallFailure.CHECKSUM), listOf(DELETE_PARTIAL)), failed)
        assertEquals(InstallTransition(NotInstalled), reduce(failed.state, InstallEvent.Dismiss))
        // A new attempt may start straight from the failure.
        assertEquals(Transferring(TransferKind.DOWNLOAD, 0, 10), reduce(failed.state, InstallEvent.Start(TransferKind.DOWNLOAD, 10)).state)
    }

    @Test
    fun `an installed model can be deleted when it is not in use`() {
        assertEquals(InstallTransition(NotInstalled, listOf(DELETE_INSTALLED)), reduce(Installed, InstallEvent.Delete(inUse = false)))
        assertTrue(reduce(Installed, InstallEvent.Delete(inUse = true)).rejected)
        assertEquals(Installed, reduce(Installed, InstallEvent.Delete(inUse = true)).state)
    }

    @Test
    fun `events that do not fit the state are rejected and change nothing`() {
        assertTrue(reduce(NotInstalled, InstallEvent.Resume).rejected)
        assertTrue(reduce(NotInstalled, InstallEvent.Cancel).rejected)
        assertTrue(reduce(Installed, InstallEvent.Start(TransferKind.DOWNLOAD, 1)).rejected)
        assertTrue(reduce(Verifying, InstallEvent.Cancel).rejected)
        assertTrue(reduce(Paused(1, 2), InstallEvent.HashOk).rejected)
        assertEquals(Installed, reduce(Installed, InstallEvent.Cancel).state)
    }

    @Test
    fun `only one install job at a time`() {
        assertTrue(ModelInstallMachine.mayStart(listOf(NotInstalled, Installed, Paused(1, 2), Failed(InstallFailure.CHECKSUM))))
        assertTrue(!ModelInstallMachine.mayStart(listOf(NotInstalled, downloading)))
        assertTrue(!ModelInstallMachine.mayStart(listOf(Verifying)))
        assertTrue(ModelInstallMachine.mayStart(emptyList()))
    }
}
