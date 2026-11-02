package app.honmani.reader.data.settings

import android.content.SharedPreferences
import java.time.LocalDate

/** The day of the last Refold export, only shown in the export dialog. */
class RefoldExportSettings(private val preferences: SharedPreferences) {
    val lastExport: LocalDate?
        get() = preferences.getString(KEY_LAST_EXPORT, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun setLastExport(day: LocalDate) {
        preferences.edit().putString(KEY_LAST_EXPORT, day.toString()).apply()
    }

    companion object {
        const val KEY_LAST_EXPORT = "refold_last_export"
    }
}
