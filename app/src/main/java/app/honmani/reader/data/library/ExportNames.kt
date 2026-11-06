package app.honmani.reader.data.library

/** File names for the library export: the book's title, made safe and unique. */
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
        val base = title.replace(unsafe, "_").trim().trim('.').take(100).dropLastWhile { it.isHighSurrogate() }.takeBytes(MAX_BASE_BYTES).trim().ifEmpty { "book" }
        var candidate = "$base.$extension"
        var number = 2
        while (candidate.lowercase() in taken) candidate = "$base (${number++}).$extension"
        taken += candidate.lowercase()
        return candidate
    }
}
