package app.honmani.reader.ui.library

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Book files other apps hand to Honmani: "Open with" from a file manager or a browser's
 * downloads, or "Share". The activity passes them on as they come; the library adds them (and
 * opens a single one) once it is on screen, so none are lost while the app starts.
 */
class OpenedBooks {
    private val files = Channel<List<Uri>>(Channel.UNLIMITED)

    val received: Flow<List<Uri>> = files.receiveAsFlow()

    /** Takes the book files of [intent], if it has any; true if it had. */
    fun receive(intent: Intent?): Boolean {
        val uris = bookUris(intent)
        if (uris.isEmpty()) return false
        files.trySend(uris)
        return true
    }

    companion object {
        /** The files of an "Open with" (view) or "Share" (send) intent; empty for anything else. */
        fun bookUris(intent: Intent?): List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.filter { it.scheme == "content" }
    }
}
