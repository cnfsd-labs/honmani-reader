package app.honmani.reader.ui.reader

/**
 * Lengths to try when the reader makes the matched text shorter or longer by one character in
 * the lookup panel. All lengths are in UTF-16 units of the tapped text, as the lookup's
 * `source` is, and never end inside a surrogate pair.
 */
internal object MatchLength {
    /** The length one character shorter than [length] in [text], or null if that would be empty. */
    fun shorter(text: String, length: Int): Int? {
        val end = length.coerceAtMost(text.length)
        if (end <= 0) return null
        val shorter = text.offsetByCodePoints(end, -1)
        return shorter.takeIf { it > 0 }
    }

    /**
     * The lengths longer than [length], up to [limit], at which the text can end, shortest
     * first: one character longer, then further if that finds no longer match.
     */
    fun longer(text: String, length: Int, limit: Int): List<Int> {
        val max = minOf(limit, text.length)
        val lengths = mutableListOf<Int>()
        var next = length.coerceAtLeast(0)
        while (next < max) {
            next = text.offsetByCodePoints(next, 1)
            if (next <= max) lengths += next
        }
        return lengths
    }
}
