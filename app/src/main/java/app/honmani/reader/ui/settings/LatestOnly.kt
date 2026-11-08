package app.honmani.reader.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs one piece of work at a time: [launch] cancels the one before, and a result that arrives
 * after a newer [launch] (work that does not stop when cancelled) is dropped.
 */
class LatestOnly(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0

    /** Runs [work], then [onResult] with its result, unless another [launch] came first. */
    fun <T> launch(work: suspend () -> T, onResult: (T) -> Unit) {
        job?.cancel()
        val mine = ++generation
        job = scope.launch {
            val result = work()
            if (mine == generation) onResult(result)
        }
    }
}
