package app.honmani.reader.data.settings

import app.honmani.reader.data.words.WordMarksMode
import java.util.Locale

/** The reader's colours: ttu's themes Honmani offers, or following the system's dark mode. */
enum class ReaderTheme(val storage: String) {
    AUTO("auto"),
    LIGHT("light-theme"),
    SEPIA("ecru-theme"),
    GRAY("gray-theme"),
    BLACK("black-theme"),
}

/** The fonts every phone has; downloaded ones are stored by their family name. */
enum class ReaderFont(val storage: String) {
    /** Mincho, the usual book typeface. */
    SERIF("serif"),

    /** Gothic. */
    SANS("sans-serif"),
}

/**
 * The reader settings Honmani shows on its own screen, kept where ッツ Ebook Reader keeps them
 * (its localStorage keys, see `assets/honmani/reader-settings.js`). Missing values are ttu's
 * defaults, or Honmani's where `ttu-defaults.js` sets its own (text size 28).
 */
data class ReaderSettings(
    /** Null: one of ttu's other themes, picked in its full settings. */
    val theme: ReaderTheme? = ReaderTheme.AUTO,
    /** The font family as ttu stores it: a [ReaderFont]'s, a downloaded font's or one from ttu's settings. */
    val font: String = ReaderFont.SERIF.storage,
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val vertical: Boolean = true,
    val paginated: Boolean = true,
    val furigana: Boolean = true,
    /** ttu's character counter and percentage at the bottom of the page. */
    val showProgress: Boolean = true,
    /** Honmani's thin bar along the bottom of the page (`book-frame.js`). */
    val progressBar: Boolean = true,
    /** Which words get a tint in the book (`word-marks.js`). */
    val wordMarks: WordMarksMode = WordMarksMode.DEFAULT,
    val keepScreenOn: Boolean = false,
    /** The phone's volume buttons turn pages in a book (down: next, up: previous). */
    val volumeKeys: Boolean = true,
) {
    /** The values to store, as ttu writes them. */
    fun toStorage(): Map<String, String> = buildMap {
        theme?.let { put(THEME, it.storage) }
        put(FONT, font)
        put(FONT_SIZE, fontSize.toString())
        put(LINE_HEIGHT, String.format(Locale.ROOT, "%.2f", lineHeight).trimEnd('0').trimEnd('.'))
        put(WRITING_MODE, if (vertical) VERTICAL else HORIZONTAL)
        put(VIEW_MODE, if (paginated) PAGINATED else CONTINUOUS)
        put(HIDE_FURIGANA, flag(!furigana))
        put(CHARACTER_COUNTER, flag(showProgress))
        put(PERCENTAGE, flag(showProgress))
        put(PROGRESS_BAR, flag(progressBar))
        put(WORD_MARKS, wordMarks.storage)
        put(WAKE_LOCK, flag(keepScreenOn))
        put(VOLUME_KEYS, flag(volumeKeys))
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 28
        const val DEFAULT_LINE_HEIGHT = 1.65f
        val FONT_SIZES = 16..48
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.4f

        private const val THEME = "theme"
        private const val FONT = "fontFamilyGroupOne"
        private const val FONT_SIZE = "fontSize"
        private const val LINE_HEIGHT = "lineHeight"
        private const val WRITING_MODE = "writingMode"
        private const val VIEW_MODE = "viewMode"
        private const val HIDE_FURIGANA = "hideFurigana"
        private const val CHARACTER_COUNTER = "showCharacterCounter"
        private const val PERCENTAGE = "showPercentage"
        private const val WAKE_LOCK = "enableReaderWakeLock"
        private const val PROGRESS_BAR = "honmaniProgressBar"
        private const val WORD_MARKS = "honmaniWordMarks"
        private const val VOLUME_KEYS = "honmaniVolumeKeys"
        private const val VERTICAL = "vertical-rl"
        private const val HORIZONTAL = "horizontal-tb"
        private const val PAGINATED = "paginated"
        private const val CONTINUOUS = "continuous"

        private fun flag(value: Boolean) = if (value) "1" else "0"

        /** The settings from ttu's stored values ([key] to value, null for unset). */
        fun fromStorage(values: Map<String, String?>): ReaderSettings {
            fun flag(key: String, default: Boolean) = values[key]?.let { it == "1" } ?: default
            val defaults = ReaderSettings()
            return ReaderSettings(
                theme = values[THEME]?.let { stored -> ReaderTheme.entries.firstOrNull { it.storage == stored } }
                    ?: if (values[THEME] == null) ReaderTheme.AUTO else null,
                font = values[FONT]?.takeIf { it.isNotBlank() } ?: defaults.font,
                fontSize = values[FONT_SIZE]?.toDoubleOrNull()?.toInt() ?: defaults.fontSize,
                lineHeight = values[LINE_HEIGHT]?.toFloatOrNull() ?: defaults.lineHeight,
                vertical = values[WRITING_MODE]?.let { it != HORIZONTAL } ?: defaults.vertical,
                paginated = values[VIEW_MODE]?.let { it != CONTINUOUS } ?: defaults.paginated,
                furigana = !flag(HIDE_FURIGANA, !defaults.furigana),
                showProgress = flag(CHARACTER_COUNTER, defaults.showProgress) || flag(PERCENTAGE, defaults.showProgress),
                progressBar = flag(PROGRESS_BAR, defaults.progressBar),
                wordMarks = WordMarksMode.fromStorage(values[WORD_MARKS]),
                keepScreenOn = flag(WAKE_LOCK, defaults.keepScreenOn),
                volumeKeys = flag(VOLUME_KEYS, defaults.volumeKeys),
            )
        }
    }
}
