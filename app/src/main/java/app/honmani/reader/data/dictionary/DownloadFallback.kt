package app.honmani.reader.data.dictionary

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The publisher's release of the dictionary called [name] is incomplete: it holds no terms,
 * kanji, frequencies or pitch accents, and no complete release to fall back to worked either.
 */
class IncompleteReleaseException(val name: String) :
    Exception("The latest release of $name is incomplete")

/** Checks a freshly downloaded file before it is imported. */
object DownloadCheck {
    private val contentBank = Regex("""(term|term_meta|kanji|kanji_meta)_bank_\d+\.json""")

    /**
     * Whether the Yomitan zip [file] has at least one content bank (`term_bank_*`,
     * `term_meta_bank_*`, `kanji_bank_*`, `kanji_meta_bank_*`). A file that is not a readable zip
     * passes, so the import reports what is wrong with it.
     */
    fun hasContentBank(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { entry ->
                !entry.isDirectory && contentBank.matches(entry.name.substringAfterLast('/'))
            }
        }
    } catch (_: ZipException) {
        true
    } catch (_: IOException) {
        true
    }
}

/**
 * Runs [attempt] (download, check and import) for [url], and once more for [fallbackUrl] when the
 * release at [url] is incomplete: [attempt] throws [IncompleteReleaseException] for a file without
 * content banks, and the importer [NoTermsException]. When there is no fallback, or it is
 * incomplete too, throws [IncompleteReleaseException] for [name]. Other failures pass through.
 */
suspend fun <T> withReleaseFallback(
    name: String,
    url: String,
    fallbackUrl: String?,
    attempt: suspend (url: String) -> T,
): T {
    try {
        return attempt(url)
    } catch (_: IncompleteReleaseException) {
    } catch (_: NoTermsException) {
    }
    if (fallbackUrl == null || fallbackUrl == url) throw IncompleteReleaseException(name)
    try {
        return attempt(fallbackUrl)
    } catch (_: NoTermsException) {
        throw IncompleteReleaseException(name)
    } catch (_: IncompleteReleaseException) {
        throw IncompleteReleaseException(name)
    }
}
