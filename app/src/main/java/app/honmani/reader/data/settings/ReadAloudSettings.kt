package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read aloud: the speaking speed, remembered between books. */
class ReadAloudSettings(private val preferences: SharedPreferences) {
    private val _rate = MutableStateFlow(read())

    /** 1 is the voice's normal speed; one of [RATES]. */
    val rate: StateFlow<Float> = _rate.asStateFlow()

    fun setRate(value: Float) {
        _rate.value = value
        preferences.edit().putFloat(KEY_RATE, value).apply()
    }

    /** Reads the stored value again (a backup was restored). */
    fun reload() {
        _rate.value = read()
    }

    private fun read(): Float = preferences.getFloat(KEY_RATE, 1f).takeIf { it in RATES } ?: 1f

    companion object {
        const val KEY_RATE = "read_aloud_rate"
        val RATES = listOf(0.75f, 1f, 1.25f, 1.5f)

        /** The speed after [current] in [RATES], back to the first after the last. */
        fun nextRate(current: Float): Float = RATES[(RATES.indexOf(current) + 1) % RATES.size]
    }
}
