package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read aloud: the speaking speed, remembered between books, and whether it (and the audiobook) keeps playing with the screen off. */
class ReadAloudSettings(private val preferences: SharedPreferences) {
    private val _rate = MutableStateFlow(read())

    /** 1 is the voice's normal speed; one of [RATES]. */
    val rate: StateFlow<Float> = _rate.asStateFlow()

    fun setRate(value: Float) {
        _rate.value = value
        preferences.edit().putFloat(KEY_RATE, value).apply()
    }

    private val _keepPlaying = MutableStateFlow(preferences.getBoolean(KEY_KEEP_PLAYING, true))

    /**
     * Read aloud and the audiobook go on with the screen off or the app in the background (a
     * media notification and lock-screen controls); off, they pause when the app leaves the screen.
     */
    val keepPlaying: StateFlow<Boolean> = _keepPlaying.asStateFlow()

    fun setKeepPlaying(value: Boolean) {
        _keepPlaying.value = value
        preferences.edit().putBoolean(KEY_KEEP_PLAYING, value).apply()
    }

    /** Reads the stored value again (a backup was restored). */
    fun reload() {
        _rate.value = read()
        _keepPlaying.value = preferences.getBoolean(KEY_KEEP_PLAYING, true)
    }

    private fun read(): Float = preferences.getFloat(KEY_RATE, 1f).takeIf { it in RATES } ?: 1f

    companion object {
        const val KEY_RATE = "read_aloud_rate"
        const val KEY_KEEP_PLAYING = "keep_playing_screen_off"
        val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)

        /** The speed after [current] in [RATES], back to the first after the last. */
        fun nextRate(current: Float): Float = RATES[(RATES.indexOf(current) + 1) % RATES.size]
    }
}
