package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Highlighting: the extent (selection, sentence, paragraph) the reader chose last, kept between
 * lookups. Stored by name; the lookup panel reads it with `HighlightExtent.initial`.
 */
class HighlightSettings(private val preferences: SharedPreferences) {
    private val _extent = MutableStateFlow(read())

    /** The name of the extent last chosen by a tap on its chip, or null if none was. */
    val extent: StateFlow<String?> = _extent.asStateFlow()

    fun setExtent(name: String) {
        _extent.value = name
        preferences.edit().putString(KEY_EXTENT, name).apply()
    }

    /** Reads the stored value again (a backup was restored). */
    fun reload() {
        _extent.value = read()
    }

    private fun read(): String? = preferences.getString(KEY_EXTENT, null)

    companion object {
        const val KEY_EXTENT = "highlight_extent"
    }
}
