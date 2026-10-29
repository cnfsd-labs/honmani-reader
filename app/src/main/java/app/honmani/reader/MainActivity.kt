package app.honmani.reader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.ui.HonmaniApp
import app.honmani.reader.ui.theme.HonmaniTheme

class MainActivity : ComponentActivity() {
    /**
     * Turns a page (true: the next) when a volume button is pressed; set while a book is open
     * and the reader wants it, so the buttons change the volume everywhere else.
     */
    var volumePageTurn: ((next: Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HonmaniApplication).container
        val appearance = container.appearanceSettings
        // Once per app start (not on rotation): new books from catalogues with automatic import,
        // new versions of installed dictionaries (offered in Dictionaries, not installed), and
        // grading books that have no difficulty yet.
        if (savedInstanceState == null) {
            container.opdsAutoImport.start()
            container.dictionaryUpdateChecker.start()
            container.startGrading()
            container.checkAutoBackupOffer()
            container.openedBooks.receive(intent)
        }
        setContent {
            val accent by appearance.accent.collectAsStateWithLifecycle()
            val customHue by appearance.customHue.collectAsStateWithLifecycle()
            HonmaniTheme(accent = accent, customHue = customHue) {
                HonmaniApp()
            }
        }
    }

    // Volume keys reach the activity: neither the WebView nor Compose uses them.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val next = volumeDirection(keyCode) ?: return super.onKeyDown(keyCode, event)
        val turn = volumePageTurn ?: return super.onKeyDown(keyCode, event)
        // One page per press: holding the button doesn't race through the book.
        if (event.repeatCount == 0) turn(next)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (volumeDirection(keyCode) != null && volumePageTurn != null) true else super.onKeyUp(keyCode, event)

    private fun volumeDirection(keyCode: Int): Boolean? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> true
        KeyEvent.KEYCODE_VOLUME_UP -> false
        else -> null
    }

    /** Another app opened or shared a book while Honmani was running (one task: `singleTask`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as HonmaniApplication).container.openedBooks.receive(intent)
    }
}
