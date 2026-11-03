package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Reading: whether the reading timer keeps running while the lookup panel is open (it does by default). */
class ReadingTimeSettings(private val preferences: SharedPreferences) {
    private val _countDuringLookup = MutableStateFlow(preferences.getBoolean(KEY_COUNT_DURING_LOOKUP, true))

    /** Time counts while the lookup panel is open; off: the timer pauses until it closes. */
    val countDuringLookup: StateFlow<Boolean> = _countDuringLookup.asStateFlow()

    fun setCountDuringLookup(value: Boolean) {
        _countDuringLookup.value = value
        preferences.edit().putBoolean(KEY_COUNT_DURING_LOOKUP, value).apply()
    }

    /** Reads the stored value again (a backup was restored). */
    fun reload() {
        _countDuringLookup.value = preferences.getBoolean(KEY_COUNT_DURING_LOOKUP, true)
    }

    companion object {
        const val KEY_COUNT_DURING_LOOKUP = "count_time_during_lookup"
    }
}
