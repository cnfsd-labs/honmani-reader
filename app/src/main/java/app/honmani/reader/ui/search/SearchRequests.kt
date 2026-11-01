package app.honmani.reader.ui.search

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The launcher's "Search" and "Review" shortcuts: the activity passes its intent on, and the tabs
 * open Words with the search field focused, or the review screen, once they are on screen.
 */
class SearchRequests {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    private val reviews = Channel<Unit>(Channel.CONFLATED)

    val received: Flow<Unit> = requests.receiveAsFlow()

    val reviewReceived: Flow<Unit> = reviews.receiveAsFlow()

    /** Takes [intent] if it asks for a search or a review; true if it did. */
    fun receive(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_SEARCH -> requests.trySend(Unit)
            ACTION_REVIEW -> reviews.trySend(Unit)
            else -> return false
        }
        return true
    }

    companion object {
        const val ACTION_SEARCH = "app.honmani.reader.action.SEARCH"
        const val ACTION_REVIEW = "app.honmani.reader.action.REVIEW"
    }
}
