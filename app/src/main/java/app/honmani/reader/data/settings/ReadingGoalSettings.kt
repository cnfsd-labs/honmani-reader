package app.honmani.reader.data.settings

import android.content.SharedPreferences
import app.honmani.reader.data.stats.ReadingGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** The daily reading goal: off until switched on, then [ReadingGoal.DEFAULT_MINUTES] unless changed. */
class ReadingGoalSettings(private val preferences: SharedPreferences) {
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _minutes = MutableStateFlow(storedMinutes())
    val minutes: StateFlow<Int> = _minutes.asStateFlow()

    /** The goal in minutes, or null while it is off. */
    val goalMinutes: Flow<Int?> = combine(_enabled, _minutes) { on, minutes -> minutes.takeIf { on } }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setMinutes(minutes: Int) {
        _minutes.value = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        preferences.edit().putInt(KEY_MINUTES, _minutes.value).apply()
    }

    /** Reads the stored values again (a backup was restored). */
    fun reload() {
        _enabled.value = preferences.getBoolean(KEY_ENABLED, false)
        _minutes.value = storedMinutes()
    }

    private fun storedMinutes() = preferences.getInt(KEY_MINUTES, ReadingGoal.DEFAULT_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)

    companion object {
        const val KEY_ENABLED = "daily_goal_enabled"
        const val KEY_MINUTES = "daily_goal_minutes"
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 600
    }
}
