package app.honmani.reader.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.anki.CloudVoiceInfo
import app.honmani.reader.anki.GoogleCloudVoice
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.ui.anki.label
import kotlinx.coroutines.CoroutineScope

/** What the Google key check says. */
sealed interface KeyCheck {
    data object Checking : KeyCheck

    data class Works(val voices: List<CloudVoiceInfo>) : KeyCheck

    /** [canRetry]: the failure was not the key's fault (offline, quota, server), so checking again may help. */
    data class Failed(val message: Int, val canRetry: Boolean = false) : KeyCheck
}

/**
 * The Voice settings' Google key: saving, removing and checking it. A new check replaces one still
 * running; its late result is dropped.
 */
class GoogleKeyCheck(
    private val settings: AnkiSettings,
    scope: CoroutineScope,
    private val voices: suspend (String) -> List<CloudVoiceInfo> = { GoogleCloudVoice.voices(it) },
) {
    var state by mutableStateOf<KeyCheck?>(null)
        private set

    private val checks = LatestOnly(scope)

    /** Checks the saved key by listing its voices. */
    fun check() {
        val key = settings.googleKey() ?: run {
            // It was saved but can no longer be read on this phone: it has to be entered again.
            if (settings.hasGoogleKey.value) state = KeyCheck.Failed(R.string.google_key_unreadable)
            return
        }
        state = KeyCheck.Checking
        checks.launch(
            work = {
                try {
                    KeyCheck.Works(voices(key))
                } catch (e: VoiceException) {
                    KeyCheck.Failed(e.problem.label(), canRetry = e.problem !in KEY_PROBLEMS)
                }
            },
            onResult = { state = it },
        )
    }

    /** Saves [input] and checks it; false if the phone could not encrypt it. */
    fun save(input: String): Boolean {
        if (!settings.setGoogleKey(input)) {
            state = KeyCheck.Failed(R.string.error_key_storage)
            return false
        }
        check()
        return true
    }

    /** Removes the saved key; a check still running is dropped and no result is shown. */
    fun remove() {
        settings.setGoogleKey("")
        checks.launch(work = {}, onResult = {})
        state = null
    }

    private companion object {
        /** Failures that checking the same key again can't fix. */
        val KEY_PROBLEMS = setOf(VoiceProblem.KEY_REJECTED, VoiceProblem.NO_KEY)
    }
}
