package app.honmani.reader.data.settings

import android.content.SharedPreferences

/**
 * The one-time hint, on the first book that opens, that the reader menu is behind a swipe up or a
 * tap on the bottom edge. Kept in the "device" preferences: it is about this phone's first use
 * and is never backed up.
 */
class ReaderMenuHint(private val preferences: SharedPreferences) {
    /** True the first time it is asked, ever; the hint counts as shown from then on. */
    @Synchronized
    fun take(): Boolean {
        if (preferences.getBoolean(KEY_SHOWN, false)) return false
        preferences.edit().putBoolean(KEY_SHOWN, true).apply()
        return true
    }

    private companion object {
        const val KEY_SHOWN = "reader_menu_hint_shown"
    }
}
