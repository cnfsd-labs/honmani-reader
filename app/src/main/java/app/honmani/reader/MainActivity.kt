package app.honmani.reader

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.ui.HonmaniApp
import app.honmani.reader.ui.search.SearchRequests
import app.honmani.reader.ui.reader.LocalExampleSentences
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
        // A new start of the app (not a rotation) may pick a new random accent, before the first frame.
        if (savedInstanceState == null) appearance.refreshRandomAccent(container.dayClock.today().toEpochDay(), newLaunch = true)
        // Once per app start (not on rotation): new books from catalogues with automatic import,
        // new versions of installed dictionaries (offered in Dictionaries, not installed), and
        // grading books that have no difficulty yet.
        if (savedInstanceState == null) {
            container.opdsAutoImport.start()
            container.dictionaryUpdateChecker.start()
            container.startGrading()
            container.startGlossIndex()
            container.checkAutoBackupOffer()
            container.sweepLeftovers()
            container.openedBooks.receive(intent)
            container.searchRequests.receive(intent)
            addSearchShortcut()
            addReviewShortcut()
        }
        setContent {
            val accent by appearance.accent.collectAsStateWithLifecycle()
            val customHue by appearance.customHue.collectAsStateWithLifecycle()
            val eInk by appearance.eInk.collectAsStateWithLifecycle()
            val animations by appearance.animations.collectAsStateWithLifecycle()
            HonmaniTheme(accent = accent, customHue = customHue, eInk = eInk, animations = animations) {
                CompositionLocalProvider(LocalExampleSentences provides container.exampleSentences) {
                    HonmaniApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A day that changed while the app was in the background gives "every day" a new colour.
        val container = (application as HonmaniApplication).container
        container.appearanceSettings.refreshRandomAccent(container.dayClock.today().toEpochDay(), newLaunch = false)
        // Whatever the reader's other phones wrote into the sync folder (if turned on) comes in.
        (application as HonmaniApplication).container.syncWithFolder()
    }

    override fun onStop() {
        super.onStop()
        // Whatever screen was open, the automatic backup (if anything changed) and the sync file
        // (if the reader turned it on) are brought up to date.
        val container = (application as HonmaniApplication).container
        if (!isChangingConfigurations) container.saveAutoBackup()
        container.saveSyncFile()
    }

    /**
     * Dark mode, font size and language switch (by hand or at sunset) without recreating the
     * activity: that would drop the open book and its lookup panel. Compose follows the system theme by itself; the system
     * bars' icon colours are chosen when edge-to-edge is set up, so set it up again.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
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
        val container = (application as HonmaniApplication).container
        container.openedBooks.receive(intent)
        container.searchRequests.receive(intent)
    }

    /** "Search" in the launcher's menu of the app icon: Words with the search field focused. */
    private fun addSearchShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "search")
            .setShortLabel(getString(R.string.shortcut_search))
            .setLongLabel(getString(R.string.shortcut_search_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_search))
            .setIntent(Intent(this, MainActivity::class.java).setAction(SearchRequests.ACTION_SEARCH))
            .build()
        try {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (e: RuntimeException) {
            // A launcher's rate limit or a missing shortcut service: the app works without it.
            android.util.Log.w("Honmani", "Adding the search shortcut failed", e)
        }
    }

    /** "Review" in the launcher's menu of the app icon: the review screen. */
    private fun addReviewShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "review")
            .setShortLabel(getString(R.string.shortcut_review))
            .setLongLabel(getString(R.string.shortcut_review_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_review))
            .setIntent(Intent(this, MainActivity::class.java).setAction(SearchRequests.ACTION_REVIEW))
            .build()
        try {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (e: RuntimeException) {
            android.util.Log.w("Honmani", "Adding the review shortcut failed", e)
        }
    }
}
