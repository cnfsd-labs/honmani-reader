package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** The last dictionary searches, newest first, kept on this phone only. */
class SearchHistory(private val preferences: SharedPreferences) {
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** Puts [query] first (once); the oldest drop out beyond [MAX_ENTRIES]. */
    fun add(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        save((listOf(text) + _entries.value.filter { it != text }).take(MAX_ENTRIES))
    }

    fun remove(query: String) = save(_entries.value - query)

    fun clear() = save(emptyList())

    private fun save(entries: List<String>) {
        _entries.value = entries
        preferences.edit().putString(KEY, JSONArray(entries).toString()).apply()
    }

    private fun load(): List<String> = try {
        val array = JSONArray(preferences.getString(KEY, null) ?: "[]")
        List(array.length()) { array.getString(it) }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    companion object {
        const val MAX_ENTRIES = 20
        private const val KEY = "search_history"
    }
}
