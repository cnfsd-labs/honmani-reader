package app.honmani.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.ui.HonmaniApp
import app.honmani.reader.ui.theme.HonmaniTheme

class MainActivity : ComponentActivity() {
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

    /** Another app opened or shared a book while Honmani was running (one task: `singleTask`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as HonmaniApplication).container.openedBooks.receive(intent)
    }
}
