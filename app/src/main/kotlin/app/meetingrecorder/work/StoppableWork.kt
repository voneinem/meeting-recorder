package app.meetingrecorder.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs [block] (blocking calls inside it) on [dispatcher] and tells [onStop] **at once** when the calling coroutine is cancelled,
 * which is how WorkManager stops a worker (the notification's Cancel action, a quota, the Android 15 timeout).
 * [block] cannot observe a coroutine cancellation, so without this the stop request would arrive only after the
 * whole download or transcription had finished (found by `ModelInstallTest`: Cancel in the notification did not
 * cancel). [onStop] must make [block] return soon; this function then waits for it and rethrows the cancellation.
 */
suspend fun <T> runStoppable(dispatcher: CoroutineDispatcher, onStop: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val work = async(dispatcher) { block() }
    try {
        work.await()
    } catch (cancelled: CancellationException) {
        onStop()
        withContext(NonCancellable) { work.join() }
        throw cancelled
    }
}
