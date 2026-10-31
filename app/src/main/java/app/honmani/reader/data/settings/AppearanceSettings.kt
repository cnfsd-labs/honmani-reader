package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's accent colour. [SYSTEM] takes Material You colours from the wallpaper (Android 12+);
 * [CUSTOM] is [customAccentColor] of the reader's hue. Settings shows them in this order, six a row.
 */
enum class Accent(val color: Long?) {
    // Obsidian's default accent.
    PURPLE(0xFF8A5CF5),
    BLUE(0xFF3B82F6),
    TEAL(0xFF14B8A6),
    GREEN(0xFF22C55E),
    ORANGE(0xFFF97316),
    PINK(0xFFEC4899),
    RED(0xFFEF4444),
    AMBER(0xFFF59E0B),
    INDIGO(0xFF6366F1),
    SLATE(0xFF64748B),
    SYSTEM(null),
    CUSTOM(null),
    ;

    companion object {
        val DEFAULT = PURPLE

        fun fromName(name: String?): Accent = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The custom accent for a [hue] in degrees: saturation and lightness are fixed near the preset
 * colours', so every hue stays readable in the light and the dark theme.
 */
fun customAccentColor(hue: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val chroma = (1f - kotlin.math.abs(2f * CUSTOM_LIGHTNESS - 1f)) * CUSTOM_SATURATION
    val x = chroma * (1f - kotlin.math.abs(h % 2f - 1f))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = CUSTOM_LIGHTNESS - chroma / 2f
    fun channel(value: Float) = ((value + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return (0xFFL shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

private const val CUSTOM_SATURATION = 0.8f
private const val CUSTOM_LIGHTNESS = 0.62f

/** How the app looks. Stored in shared preferences; read once at start, then kept in memory. */
class AppearanceSettings(private val preferences: SharedPreferences) {
    private val _accent = MutableStateFlow(Accent.fromName(preferences.getString(KEY_ACCENT, null)))
    val accent: StateFlow<Accent> = _accent.asStateFlow()

    private val _customHue = MutableStateFlow(preferences.getFloat(KEY_CUSTOM_HUE, DEFAULT_CUSTOM_HUE))

    /** The hue (0–360°) of [Accent.CUSTOM]. */
    val customHue: StateFlow<Float> = _customHue.asStateFlow()

    private val _showDifficulty = MutableStateFlow(preferences.getBoolean(KEY_SHOW_DIFFICULTY, true))

    /** Whether library covers show the book's difficulty in a corner. */
    val showDifficulty: StateFlow<Boolean> = _showDifficulty.asStateFlow()

    private val _showPreviousSentence = MutableStateFlow(preferences.getBoolean(KEY_SHOW_PREVIOUS_SENTENCE, false))

    /** Whether the lookup panel shows the sentence before the tapped one (off until switched on). */
    val showPreviousSentence: StateFlow<Boolean> = _showPreviousSentence.asStateFlow()

    fun setAccent(accent: Accent) {
        _accent.value = accent
        preferences.edit().putString(KEY_ACCENT, accent.name).apply()
    }

    fun setCustomHue(hue: Float) {
        _customHue.value = hue.coerceIn(0f, 360f)
        preferences.edit().putFloat(KEY_CUSTOM_HUE, _customHue.value).apply()
    }

    private val _librarySort = MutableStateFlow(preferences.getString(KEY_LIBRARY_SORT, null))

    /** The library's order, as a `LibrarySort` name; null for the default. */
    val librarySort: StateFlow<String?> = _librarySort.asStateFlow()

    fun setLibrarySort(name: String) {
        _librarySort.value = name
        preferences.edit().putString(KEY_LIBRARY_SORT, name).apply()
    }

    fun setShowDifficulty(show: Boolean) {
        _showDifficulty.value = show
        preferences.edit().putBoolean(KEY_SHOW_DIFFICULTY, show).apply()
    }

    fun setShowPreviousSentence(show: Boolean) {
        _showPreviousSentence.value = show
        preferences.edit().putBoolean(KEY_SHOW_PREVIOUS_SENTENCE, show).apply()
    }

    /** Reads the stored values again (a backup was restored). */
    fun reload() {
        _accent.value = Accent.fromName(preferences.getString(KEY_ACCENT, null))
        _customHue.value = preferences.getFloat(KEY_CUSTOM_HUE, DEFAULT_CUSTOM_HUE)
        _showDifficulty.value = preferences.getBoolean(KEY_SHOW_DIFFICULTY, true)
        _librarySort.value = preferences.getString(KEY_LIBRARY_SORT, null)
        _showPreviousSentence.value = preferences.getBoolean(KEY_SHOW_PREVIOUS_SENTENCE, false)
    }

    companion object {
        const val KEY_ACCENT = "accent"
        const val KEY_SHOW_DIFFICULTY = "show_difficulty"
        const val KEY_SHOW_PREVIOUS_SENTENCE = "show_previous_sentence"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_CUSTOM_HUE = "accent_custom_hue"

        /** A sky blue that none of the presets has (#51C5EC). */
        const val DEFAULT_CUSTOM_HUE = 195f
    }
}
