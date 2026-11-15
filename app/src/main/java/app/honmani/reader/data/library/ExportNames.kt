package app.honmani.reader.data.library

import java.io.File
import java.io.InputStream

/**
 * File names for the library export: the book's title, made safe and unique; a book already
 * exported to the folder (same name and bytes) is recognised so it is not written twice.
 */
object ExportNames {
    private val unsafe = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** File names are limited to 255 bytes; leaves room for " (99)" and the extension. */
    private const val MAX_BASE_BYTES = 200

    /** The longest start of this string that fits [max] UTF-8 bytes, never ending in half a surrogate pair. */
    private fun String.takeBytes(max: Int): String {
        var bytes = 0
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > max) break
            bytes += size
            i += Character.charCount(cp)
        }
        return substring(0, i)
    }

    /**
     * "Title.epub" for [title]; if that is in [taken] (lower-case names) already, "Title (2).epub"
     * and so on. The chosen name is added to [taken].
     */
    fun unique(title: String, extension: String, taken: MutableSet<String>): String {
        val base = base(title)
        var candidate = "$base.$extension"
        var number = 2
        while (candidate.lowercase() in taken) candidate = "$base (${number++}).$extension"
        taken += candidate.lowercase()
        return candidate
    }

    private fun base(title: String): String =
        title.replace(unsafe, "_").trim().trim('.').take(100).dropLastWhile { it.isHighSurrogate() }.takeBytes(MAX_BASE_BYTES).trim().ifEmpty { "book" }

    /**
     * The file in [entries] that may be this book from an earlier export: the plain name
     * "Title.epub" (any case) and [size] bytes (or a size the provider does not report). The
     * caller still compares the bytes ([sameBytes]).
     */
    fun previousExport(title: String, extension: String, size: Long, entries: List<TreeEntry>): TreeEntry? {
        val plain = "${base(title)}.$extension".lowercase()
        return entries.firstOrNull { !it.isFolder && it.name.lowercase() == plain && (it.size < 0 || it.size == size) }
    }

    /** Whether [other] holds exactly the bytes of [file]; closes [other]. */
    fun sameBytes(file: File, other: InputStream): Boolean {
        other.buffered().use { theirs ->
            file.inputStream().buffered().use { ours ->
                while (true) {
                    val a = ours.read()
                    if (a != theirs.read()) return false
                    if (a < 0) return true
                }
            }
        }
    }
}
