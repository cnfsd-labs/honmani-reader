package app.honmani.reader.data.settings

import android.content.SharedPreferences

/**
 * The one-time hint, the first time the reader turns on edge taps (Reader settings), where the
 * page-turn zones are and that the rest of the page still looks words up. Kept in the "device" preferences: it is about this phone's first use
 * and is never backed up.
 */
class EdgeTapsHint(private val preferences: SharedPreferences) {
    /** True the first time it is asked, ever; the hint counts as shown from then on. */
    @Synchronized
    fun take(): Boolean {
        if (preferences.getBoolean(KEY_SHOWN, false)) return false
        preferences.edit().putBoolean(KEY_SHOWN, true).apply()
        return true
    }

    private companion object {
        const val KEY_SHOWN = "edge_taps_hint_shown"
    }
}
