package app.honmani.reader.ui.search

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The launcher's "Search" shortcut: the activity passes its intent on, and the tabs open Words
 * with the search field focused once they are on screen.
 */
class SearchRequests {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    val received: Flow<Unit> = requests.receiveAsFlow()

    /** Takes [intent] if it asks for a search; true if it did. */
    fun receive(intent: Intent?): Boolean {
        if (intent?.action != ACTION_SEARCH) return false
        requests.trySend(Unit)
        return true
    }

    companion object {
        const val ACTION_SEARCH = "app.honmani.reader.action.SEARCH"
    }
}
