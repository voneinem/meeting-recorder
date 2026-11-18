package app.meetingrecorder.models

import app.meetingrecorder.core.models.InstallEffect
import app.meetingrecorder.core.models.InstallEvent
import app.meetingrecorder.core.models.InstallTransition
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelFiles
import app.meetingrecorder.core.models.ModelInstallMachine
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The install state of every model the user can see: each speech entry of the catalog and every installed
 * model (imported ones included). The initial state is derived from the files on disk (installed; paused when
 * a `.part` and a matching `.part.json` exist; otherwise not installed); events go through
 * [ModelInstallMachine] and its file effects are carried out here. The writes of a finished transfer
 * (`.bin` and sidecar) are done by the downloader and importer themselves, before they report success.
 * The voice-activity model is auxiliary and never listed.
 */
class ModelStateStore(
    private val repository: ModelRepository,
    private val catalog: () -> List<ModelCatalogEntry>,
) {
    private val lock = Any()
    private val _states = MutableStateFlow(derive())
    val states: StateFlow<Map<String, ModelInstallState>> = _states.asStateFlow()

    fun stateOf(id: String): ModelInstallState = _states.value[id] ?: ModelInstallState.NotInstalled

    /** Re-reads the disk, for example after an import or a repair. Running transfers keep their state. */
    fun reload() {
        synchronized(lock) {
            val fresh = derive()
            val running = _states.value.filterValues { it is ModelInstallState.Transferring || it == ModelInstallState.Verifying }
            _states.value = fresh + running
        }
    }

    /** One install job at a time: false while another model is transferring or verifying. */
    fun mayStart(): Boolean = ModelInstallMachine.mayStart(_states.value.values)

    /** Applies [event] to [id]; a rejected event changes nothing. Returns the transition that was applied. */
    fun apply(id: String, event: InstallEvent): InstallTransition = synchronized(lock) {
        val transition = ModelInstallMachine.reduce(stateOf(id), event)
        if (transition.rejected) return transition
        transition.effects.forEach { perform(id, it) }
        _states.value = _states.value + (id to transition.state)
        transition
    }

    private fun perform(id: String, effect: InstallEffect) {
        when (effect) {
            InstallEffect.DELETE_PARTIAL -> {
                repository.partFile(id).delete()
                java.io.File(repository.directory, ModelFiles.partStateName(id)).delete()
            }
            InstallEffect.DELETE_INSTALLED -> repository.delete(id)
            InstallEffect.KEEP_PARTIAL, InstallEffect.WRITE_SIDECAR -> Unit // already on disk
        }
    }

    private fun derive(): Map<String, ModelInstallState> {
        val entries = catalog()
        val vadIds = entries.filter { it.kind == ModelKind.VAD }.map { it.id }.toSet()
        val result = linkedMapOf<String, ModelInstallState>()
        for (entry in entries.filter { it.kind == ModelKind.SPEECH }) {
            result[entry.id] = when {
                repository.find(entry.id) != null -> ModelInstallState.Installed
                else -> pausedState(entry) ?: ModelInstallState.NotInstalled
            }
        }
        for (model in repository.installed()) {
            if (model.id !in vadIds) result.putIfAbsent(model.id, ModelInstallState.Installed)
        }
        return result
    }

    private fun pausedState(entry: ModelCatalogEntry): ModelInstallState? {
        val state = repository.partState(entry.id)
        val part = repository.partFile(entry.id)
        val resumable = ModelFiles.isResumable(
            partLength = part.takeIf { it.isFile }?.length(),
            state = state,
            url = entry.url,
            expectedSize = entry.sizeBytes,
            expectedSha256 = entry.sha256,
        )
        return if (resumable && state != null && state.bytesDone > 0) ModelInstallState.Paused(state.bytesDone, entry.sizeBytes) else null
    }
}
