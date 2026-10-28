package app.honmani.reader.ui.lookup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.MainActivity
import app.honmani.reader.ui.theme.HonmaniTheme

/**
 * Looks up words outside a book, over whatever was on screen: text selected in another app
 * ("Honmani" in its selection menu), text shared to Honmani, or a word typed in (from Words).
 * A text file shared to Honmani is a book: it goes on to the library.
 */
class LookupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedFile(intent)?.let { file ->
            forwardBook(file)
            finish()
            return
        }
        val text = textOf(intent)
        val appearance = (application as HonmaniApplication).container.appearanceSettings
        setContent {
            val accent by appearance.accent.collectAsStateWithLifecycle()
            val customHue by appearance.customHue.collectAsStateWithLifecycle()
            HonmaniTheme(accent = accent, customHue = customHue) {
                LookupScreen(initialText = text, onDone = ::finish)
            }
        }
    }

    private fun sharedFile(intent: Intent?): Uri? =
        intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }

    /** Hands a shared text file to the library, with this activity's permission to read it. */
    private fun forwardBook(file: Uri) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(intent.type ?: "text/plain")
                .putExtra(Intent.EXTRA_STREAM, file)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    companion object {
        /** The text to look up: selected in another app, shared, or none (type a word). */
        fun textOf(intent: Intent?): String {
            val text = when (intent?.action) {
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                else -> null
            }
            return text?.toString()?.trim()?.take(MAX_TEXT).orEmpty()
        }

        /** Like the sentence a tap in a book sends. */
        const val MAX_TEXT = 400
    }
}
