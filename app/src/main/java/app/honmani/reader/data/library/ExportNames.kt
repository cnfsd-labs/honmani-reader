package app.honmani.reader.data.library

/** File names for the library export: the book's title, made safe and unique. */
object ExportNames {
    private val unsafe = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * "Title.epub" for [title]; if that is in [taken] (lower-case names) already, "Title (2).epub"
     * and so on. The chosen name is added to [taken].
     */
    fun unique(title: String, extension: String, taken: MutableSet<String>): String {
        val base = title.replace(unsafe, "_").trim().trim('.').take(100).trim().ifEmpty { "book" }
        var candidate = "$base.$extension"
        var number = 2
        while (candidate.lowercase() in taken) candidate = "$base (${number++}).$extension"
        taken += candidate.lowercase()
        return candidate
    }
}
