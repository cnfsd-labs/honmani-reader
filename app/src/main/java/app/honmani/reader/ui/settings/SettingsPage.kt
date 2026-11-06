package app.honmani.reader.ui.settings

import androidx.annotation.StringRes
import app.honmani.reader.R

/**
 * The page Settings shows: the main list, a group of settings under it, or a screen under a group.
 * [parent] is where Back goes; the main list has none.
 */
enum class SettingsPage(@StringRes val title: Int, val parent: SettingsPage?) {
    MAIN(R.string.nav_settings, null),
    READING(R.string.settings_section_reading, MAIN),
    APPEARANCE(R.string.settings_section_appearance, MAIN),
    FLASHCARDS(R.string.settings_section_flashcards, MAIN),
    GOAL(R.string.settings_section_goal, MAIN),
    BOOKS(R.string.settings_section_books, MAIN),
    BACKUP(R.string.settings_section_data, MAIN),
    HELP(R.string.settings_section_help, MAIN),
    ANKI(R.string.settings_anki, FLASHCARDS),
    NETWORK(R.string.network_activity_title, HELP),
    ABOUT(R.string.about_title, HELP),
}
