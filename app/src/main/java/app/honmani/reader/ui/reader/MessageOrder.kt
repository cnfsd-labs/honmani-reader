package app.honmani.reader.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles the page's messages in the order they came although some (pictures) are parsed off the
 * main thread first: a Dismiss sent after a picture must not be handled before it. Main thread only;
 * [scope] runs on it.
 */
internal class MessageOrder(private val scope: CoroutineScope) {
    private var last: Job? = null

    /** A message that needs [parse] first: handled once parsed and every earlier message was. */
    fun <T : Any> later(parse: suspend () -> T?, handle: (T) -> Unit) {
        val previous = last
        last = scope.launch {
            val message = parse()
            previous?.join()
            message?.let(handle)
        }
    }

    /** A message ready now: handled at once unless an earlier one is still being parsed. */
    fun now(handle: () -> Unit) {
        val previous = last
        if (previous == null || previous.isCompleted) {
            last = null
            handle()
        } else {
            last = scope.launch {
                previous.join()
                handle()
            }
        }
    }
}
