package app.honmani.reader.data.settings

import android.content.SharedPreferences
import app.honmani.reader.data.time.DayClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → "Day starts at": the hour after midnight when a reading and review day begins
 * ([DayClock]); 0 is midnight. A change applies from then on; stored days keep their day.
 */
class DaySettings(private val preferences: SharedPreferences) {
    private val _startHour = MutableStateFlow(stored())
    val startHour: StateFlow<Int> = _startHour.asStateFlow()

    fun setStartHour(value: Int) {
        _startHour.value = value.coerceIn(0, DayClock.MAX_START_HOUR)
        preferences.edit().putInt(KEY_START_HOUR, _startHour.value).apply()
    }

    /** Reads the stored value again (a backup was restored). */
    fun reload() {
        _startHour.value = stored()
    }

    private fun stored() = preferences.getInt(KEY_START_HOUR, DEFAULT_START_HOUR).coerceIn(0, DayClock.MAX_START_HOUR)

    companion object {
        const val KEY_START_HOUR = "day_start_hour"
        const val DEFAULT_START_HOUR = 0
        val CHOICES = (0..DayClock.MAX_START_HOUR).toList()
    }
}
