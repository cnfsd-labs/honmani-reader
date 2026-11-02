package app.honmani.reader.data.settings

import android.content.SharedPreferences
import java.time.LocalDate

/** The last day of reading the Refold export covered, so the next one starts after it. */
class RefoldExportSettings(private val preferences: SharedPreferences) {
    val lastDay: LocalDate?
        get() = preferences.getString(KEY_LAST_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun setLastDay(day: LocalDate) {
        preferences.edit().putString(KEY_LAST_DAY, day.toString()).apply()
    }

    companion object {
        const val KEY_LAST_DAY = "refold_last_export_day"
    }
}
