package app.honmani.reader.data.settings

import android.content.SharedPreferences

/**
 * The "swipe to answer" line under a revealed review card: shown in the first [SESSIONS] finished
 * sessions, and never again once the reader has answered with a swipe. Kept in the "device"
 * preferences: it is about this phone's first use and is never backed up.
 */
class ReviewSwipeHint(private val preferences: SharedPreferences) {
    /** True while the hint still shows. */
    val shown: Boolean
        @Synchronized get() = !preferences.getBoolean(KEY_SWIPED, false) && preferences.getInt(KEY_SESSIONS, 0) < SESSIONS

    /** A session ended with every card answered. */
    @Synchronized
    fun sessionFinished() {
        val done = preferences.getInt(KEY_SESSIONS, 0)
        if (done < SESSIONS) preferences.edit().putInt(KEY_SESSIONS, done + 1).apply()
    }

    /** The reader answered with a swipe: they know it. */
    @Synchronized
    fun swiped() {
        if (!preferences.getBoolean(KEY_SWIPED, false)) preferences.edit().putBoolean(KEY_SWIPED, true).apply()
    }

    companion object {
        const val SESSIONS = 3
        private const val KEY_SESSIONS = "review_swipe_hint_sessions"
        private const val KEY_SWIPED = "review_swiped"
    }
}
