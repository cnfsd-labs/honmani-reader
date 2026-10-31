package app.honmani.reader.data.backup

import app.honmani.reader.data.words.WordMarksMode
import java.net.URI

/** A book server in a backup file, as shown before restoring: its name and the host it points at. */
data class BackupCatalogInfo(val title: String, val host: String)

/** What a backup file holds, for the reader to look at before restoring it. */
data class BackupContents(
    /** Books of the library at backup time (not the ones kept only for their statistics). */
    val books: Int,
    val words: Int,
    /** Days of reading statistics. */
    val readingDays: Int,
    val catalogs: List<BackupCatalogInfo>,
    val hasReaderSettings: Boolean,
)

fun BackupFile.contents() = BackupContents(
    books = books.count { it.book != null },
    words = words.size,
    readingDays = readingDays.map { it.day }.distinct().size,
    catalogs = catalogs.map { BackupCatalogInfo(it.title, hostOf(it.url)) },
    hasReaderSettings = !readerSettings.isNullOrEmpty(),
)

/** This backup without its book servers: nothing in them is added, so nothing is fetched. */
fun BackupFile.withoutCatalogs() = copy(catalogs = emptyList())

private fun hostOf(url: String): String =
    runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url.trim().take(80)

/**
 * ttu's reader settings from a backup file that are safe to write: a value of the wrong type or
 * outside a sane range could leave the reader blank or unusable, so it is dropped (ttu's default
 * or the phone's own value stays).
 */
object ReaderSettingsCheck {
    private val themes = setOf("auto", "light-theme", "dark-theme", "ecru-theme", "water-theme", "gray-theme", "black-theme")
    private val flags = setOf("hideFurigana", "showCharacterCounter", "showPercentage", "enableReaderWakeLock", "honmaniProgressBar", "honmaniVolumeKeys", "honmaniSelectionLookup")
    private val badFontChars = Regex("""[\"'\\;{}<>()\u0000-\u001f]""")

    fun valid(values: Map<String, String?>): Map<String, String> = buildMap {
        for ((key, value) in values) {
            if (value != null && isValid(key, value)) put(key, value)
        }
    }

    fun isValid(key: String, value: String): Boolean = when (key) {
        "theme" -> value in themes
        "fontFamilyGroupOne" -> value.isNotBlank() && value.length <= 100 && !badFontChars.containsMatchIn(value)
        "fontSize" -> value.toFiniteDouble()?.let { it in 8.0..120.0 } == true
        "lineHeight" -> value.toFiniteDouble()?.let { it in 1.0..4.0 } == true
        "writingMode" -> value == "vertical-rl" || value == "horizontal-tb"
        "viewMode" -> value == "paginated" || value == "continuous"
        in flags -> value == "0" || value == "1"
        "honmaniWordMarks" -> WordMarksMode.entries.any { it.storage == value }
        else -> false
    }

    private fun String.toFiniteDouble() = toDoubleOrNull()?.takeIf { it.isFinite() }
}
