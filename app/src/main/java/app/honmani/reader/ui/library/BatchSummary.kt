package app.honmani.reader.ui.library

/** The one-line summary after a folder import or the library export. */
object BatchSummary {
    /**
     * "Imported 12 books, 3 already in the library, 1 failed.": each (count, wording) pair with a
     * count above 0, in order. Empty when every count is 0.
     */
    fun sentence(counts: List<Pair<Int, (Int) -> String>>): String {
        val parts = counts.filter { it.first > 0 }.map { (count, wording) -> wording(count) }
        return if (parts.isEmpty()) "" else parts.joinToString(", ") + "."
    }
}
