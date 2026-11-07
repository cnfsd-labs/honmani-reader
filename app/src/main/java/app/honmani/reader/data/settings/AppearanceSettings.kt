package app.honmani.reader.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

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

/** How often the accent colour changes by itself (Settings → Appearance); off by default. */
enum class AccentRandom {
    OFF,
    DAILY,
    LAUNCH,
    ;

    companion object {
        fun fromName(name: String?): AccentRandom = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** The pure rule of the random accent: when it is due and which colour it takes. */
object RandomAccent {
    /** The colours a pick may take: the named ones, not System and not Custom. */
    val CHOICES: List<Accent> = Accent.entries.filter { it.color != null }

    /**
     * The next accent, or null when none is due. Every launch is due on a new start of the app
     * ([newLaunch]), every day when the day of the last pick ([lastDay], null if never) is not
     * [today]. Never the [current] colour again; [roll] gives a number below its argument.
     */
    fun next(mode: AccentRandom, current: Accent, lastDay: Long?, today: Long, newLaunch: Boolean, roll: (Int) -> Int): Accent? {
        val due = when (mode) {
            AccentRandom.OFF -> false
            AccentRandom.DAILY -> lastDay != today
            AccentRandom.LAUNCH -> newLaunch
        }
        if (!due) return null
        val options = CHOICES.filter { it != current }
        return options[roll(options.size)]
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

/**
 * How the app looks. Stored in shared preferences; read once at start, then kept in memory.
 * The random accent's mode and the colour it picked are settings (backed up, like the accent they
 * replace); the day of its last pick is tied to this phone's clock and lives in [devicePreferences].
 */
class AppearanceSettings(
    private val preferences: SharedPreferences,
    private val devicePreferences: SharedPreferences,
) {
    private val _accent = MutableStateFlow(Accent.fromName(preferences.getString(KEY_ACCENT, null)))
    val accent: StateFlow<Accent> = _accent.asStateFlow()

    private val _randomAccent = MutableStateFlow(AccentRandom.fromName(preferences.getString(KEY_ACCENT_RANDOM, null)))

    /** Whether the accent changes by itself, and how often. */
    val randomAccent: StateFlow<AccentRandom> = _randomAccent.asStateFlow()

    private val _customHue = MutableStateFlow(preferences.getFloat(KEY_CUSTOM_HUE, DEFAULT_CUSTOM_HUE))

    /** The hue (0–360°) of [Accent.CUSTOM]. */
    val customHue: StateFlow<Float> = _customHue.asStateFlow()

    private val _showDifficulty = MutableStateFlow(preferences.getBoolean(KEY_SHOW_DIFFICULTY, true))

    /** Whether library covers show the book's difficulty in a corner. */
    val showDifficulty: StateFlow<Boolean> = _showDifficulty.asStateFlow()

    private val _showPreviousSentence = MutableStateFlow(preferences.getBoolean(KEY_SHOW_PREVIOUS_SENTENCE, false))

    /** Whether the lookup panel shows the sentence before the tapped one (off until switched on). */
    val showPreviousSentence: StateFlow<Boolean> = _showPreviousSentence.asStateFlow()

    private val _showWordFrequency = MutableStateFlow(preferences.getBoolean(KEY_SHOW_WORD_FREQUENCY, true))

    /**
     * Whether the Words list shows how common each word is. On by default; nothing shows
     * without a frequency dictionary.
     */
    val showWordFrequency: StateFlow<Boolean> = _showWordFrequency.asStateFlow()

    private val _showExamples = MutableStateFlow(preferences.getBoolean(KEY_SHOW_EXAMPLES, false))

    /**
     * Whether the lookup panel shows example sentences (Tatoeba, an optional download). Off until
     * switched on; backed up, but the file is not.
     */
    val showExamples: StateFlow<Boolean> = _showExamples.asStateFlow()

    private val _showPitch = MutableStateFlow(preferences.getBoolean(KEY_SHOW_PITCH, true))

    /**
     * Whether the lookup panel draws the pitch accent over the reading (on by default). Only the
     * display: the pitch dictionaries stay installed and Anki cards still carry the pitch.
     */
    val showPitch: StateFlow<Boolean> = _showPitch.asStateFlow()

    /** The reader chose [accent] by hand: the random accent turns off. */
    fun setAccent(accent: Accent) {
        _accent.value = accent
        _randomAccent.value = AccentRandom.OFF
        preferences.edit().putString(KEY_ACCENT, accent.name).putString(KEY_ACCENT_RANDOM, AccentRandom.OFF.name).apply()
    }

    /** Sets how often the accent changes; a random mode picks a colour now, on day [today]. */
    fun setRandomAccent(mode: AccentRandom, today: Long, roll: (Int) -> Int = Random::nextInt) {
        if (mode == _randomAccent.value) return
        _randomAccent.value = mode
        preferences.edit().putString(KEY_ACCENT_RANDOM, mode.name).apply()
        refreshRandomAccent(today, newLaunch = true, roll)
    }

    /** Picks a new accent if the mode says one is due ([RandomAccent.next]); [today] is the day by `DayClock`. */
    fun refreshRandomAccent(today: Long, newLaunch: Boolean, roll: (Int) -> Int = Random::nextInt) {
        val lastDay = if (devicePreferences.contains(KEY_ACCENT_RANDOM_DAY)) devicePreferences.getLong(KEY_ACCENT_RANDOM_DAY, 0L) else null
        val next = RandomAccent.next(_randomAccent.value, _accent.value, lastDay, today, newLaunch, roll) ?: return
        _accent.value = next
        preferences.edit().putString(KEY_ACCENT, next.name).apply()
        devicePreferences.edit().putLong(KEY_ACCENT_RANDOM_DAY, today).apply()
    }

    fun setCustomHue(hue: Float) {
        _customHue.value = hue.coerceIn(0f, 360f)
        preferences.edit().putFloat(KEY_CUSTOM_HUE, _customHue.value).apply()
    }

    private val _librarySort = MutableStateFlow(preferences.getString(KEY_LIBRARY_SORT, null))

    /** The library's order, as a `LibrarySort` name; null for the default. */
    val librarySort: StateFlow<String?> = _librarySort.asStateFlow()

    private val _sounds = MutableStateFlow(preferences.getBoolean(KEY_SOUNDS, false))

    /** Whether short interface sounds play (reviews, milestones); off until switched on. */
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()

    private val _animations = MutableStateFlow(preferences.getBoolean(KEY_ANIMATIONS, true))

    /** Whether the interface animates (on by default); E-ink mode and the phone's own setting still turn it off. */
    val animations: StateFlow<Boolean> = _animations.asStateFlow()

    fun setAnimations(on: Boolean) {
        _animations.value = on
        preferences.edit().putBoolean(KEY_ANIMATIONS, on).apply()
    }

    private val _eInk = MutableStateFlow(devicePreferences.getBoolean(KEY_EINK, false))

    /**
     * Whether the reader's E-ink mode is on, mirrored from ttu's storage (where the setting lives)
     * so the interface can drop its animations too. Tied to ttu's copy on this phone, so not backed up.
     */
    val eInk: StateFlow<Boolean> = _eInk.asStateFlow()

    fun setEInk(on: Boolean) {
        if (on == _eInk.value) return
        _eInk.value = on
        devicePreferences.edit().putBoolean(KEY_EINK, on).apply()
    }

    fun setSounds(on: Boolean) {
        _sounds.value = on
        preferences.edit().putBoolean(KEY_SOUNDS, on).apply()
    }

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

    fun setShowWordFrequency(show: Boolean) {
        _showWordFrequency.value = show
        preferences.edit().putBoolean(KEY_SHOW_WORD_FREQUENCY, show).apply()
    }

    fun setShowExamples(show: Boolean) {
        _showExamples.value = show
        preferences.edit().putBoolean(KEY_SHOW_EXAMPLES, show).apply()
    }

    fun setShowPitch(show: Boolean) {
        _showPitch.value = show
        preferences.edit().putBoolean(KEY_SHOW_PITCH, show).apply()
    }

    /** Reads the stored values again (a backup was restored). */
    fun reload() {
        _showPitch.value = preferences.getBoolean(KEY_SHOW_PITCH, true)
        _accent.value = Accent.fromName(preferences.getString(KEY_ACCENT, null))
        _randomAccent.value = AccentRandom.fromName(preferences.getString(KEY_ACCENT_RANDOM, null))
        _customHue.value = preferences.getFloat(KEY_CUSTOM_HUE, DEFAULT_CUSTOM_HUE)
        _showDifficulty.value = preferences.getBoolean(KEY_SHOW_DIFFICULTY, true)
        _librarySort.value = preferences.getString(KEY_LIBRARY_SORT, null)
        _showPreviousSentence.value = preferences.getBoolean(KEY_SHOW_PREVIOUS_SENTENCE, false)
        _showWordFrequency.value = preferences.getBoolean(KEY_SHOW_WORD_FREQUENCY, true)
        _showExamples.value = preferences.getBoolean(KEY_SHOW_EXAMPLES, false)
        _sounds.value = preferences.getBoolean(KEY_SOUNDS, false)
        _animations.value = preferences.getBoolean(KEY_ANIMATIONS, true)
    }

    companion object {
        const val KEY_ACCENT = "accent"
        const val KEY_ACCENT_RANDOM = "accent_random"

        /** In the device preferences: the day (epoch day) the random accent last picked. */
        const val KEY_ACCENT_RANDOM_DAY = "accent_random_day"
        const val KEY_SHOW_DIFFICULTY = "show_difficulty"
        const val KEY_SHOW_PREVIOUS_SENTENCE = "show_previous_sentence"
        const val KEY_SHOW_WORD_FREQUENCY = "show_word_frequency"
        const val KEY_SHOW_EXAMPLES = "show_examples"
        const val KEY_SHOW_PITCH = "show_pitch_accent"
        const val KEY_SOUNDS = "ui_sounds"
        const val KEY_ANIMATIONS = "ui_animations"
        const val KEY_EINK = "eink_mirror"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_CUSTOM_HUE = "accent_custom_hue"

        /** A sky blue that none of the presets has (#51C5EC). */
        const val DEFAULT_CUSTOM_HUE = 195f
    }
}
