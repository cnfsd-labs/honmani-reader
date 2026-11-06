package app.honmani.reader.data.settings

import android.content.SharedPreferences

/**
 * What the library's folder row was left showing: the chosen folder (null: all books) and whether
 * the row is open. Kept in the "device" preferences: folder ids are this phone's, so it is never
 * backed up.
 */
class LibraryFolderView(private val preferences: SharedPreferences) {
    /** The folder chosen last, if any. It may be gone by now: check it against the folders. */
    var chosen: Long?
        get() = preferences.getLong(KEY_CHOSEN, NONE).takeIf { it != NONE }
        set(value) {
            preferences.edit().putLong(KEY_CHOSEN, value ?: NONE).apply()
        }

    /** The row of folder chips is shown. */
    var open: Boolean
        get() = preferences.getBoolean(KEY_OPEN, false)
        set(value) {
            preferences.edit().putBoolean(KEY_OPEN, value).apply()
        }

    private companion object {
        const val KEY_CHOSEN = "library_folder_chosen"
        const val KEY_OPEN = "library_folder_row_open"
        const val NONE = -1L
    }
}
