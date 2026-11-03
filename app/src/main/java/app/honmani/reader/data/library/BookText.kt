package app.honmani.reader.data.library

import app.honmani.reader.data.sync.EpubPositions

/** A match in a book: [characters] is ttu's reading position of its first letter (see [BookText]). */
data class SearchHit(
    val characters: Int,
    /** Where in the book it is, 0 to 100. */
    val percent: Int,
    /** The text in front of the match on the same line, at most [BookText.CONTEXT] characters. */
    val before: String,
    val match: String,
    val after: String,
) {
    /** The last few letters before the match, without blanks: the reader finds the match on its page by them. */
    val hint: String get() = before.filterNot { it.isWhitespace() }.takeLast(BookText.HINT)
}

/** The hits of a search; [more] if the book has further ones past the [BookText.MAX_HITS] listed. */
data class SearchResults(val hits: List<SearchHit>, val more: Boolean)

/**
 * A book's text for searching: one string per document (chapter), furigana left out, lines
 * separated by a line break so a match never runs from one paragraph into the next. Every hit
 * comes with ttu's character count before it (`EpubPositions`: only Japanese letters, digits and
 * Latin letters count), which is the reading position ttu opens the book at.
 */
class BookText(private val documents: List<String>) {
    /** Characters before each document, as ttu counts them. */
    private val starts: IntArray
    /** Characters in the whole book, as ttu counts them. */
    val total: Int

    init {
        var sum = 0
        starts = IntArray(documents.size) { i ->
            sum.also { sum += EpubPositions.count(documents[i]) }
        }
        total = sum
    }

    /**
     * Where [query] (blanks around it ignored, upper and lower case alike) is in the book, in
     * reading order: at most [maxHits] hits, matches never overlap.
     */
    fun find(query: String, maxHits: Int = MAX_HITS): SearchResults {
        val needle = query.trim()
        if (needle.isEmpty()) return SearchResults(emptyList(), more = false)
        val hits = ArrayList<SearchHit>()
        for ((index, text) in documents.withIndex()) {
            var counted = starts[index]
            var countedTo = 0
            var from = 0
            while (true) {
                val at = text.indexOf(needle, from, ignoreCase = true)
                if (at < 0) break
                if (hits.size == maxHits) return SearchResults(hits, more = true)
                counted += EpubPositions.count(text.substring(countedTo, at))
                countedTo = at
                val end = at + needle.length
                hits += SearchHit(
                    characters = counted,
                    percent = if (total == 0) 0 else (counted * 100L / total).toInt(),
                    before = text.substring(lineStart(text, at), at),
                    match = text.substring(at, end),
                    after = text.substring(end, lineEnd(text, end)),
                )
                from = end
            }
        }
        return SearchResults(hits, more = false)
    }

    private fun lineStart(text: String, at: Int): Int {
        var start = maxOf(text.lastIndexOf('\n', at - 1) + 1, at - CONTEXT)
        // Not half a surrogate pair.
        if (start in 1 until at && text[start].isLowSurrogate()) start++
        return start
    }

    private fun lineEnd(text: String, from: Int): Int {
        val newline = text.indexOf('\n', from).let { if (it < 0) text.length else it }
        var end = minOf(newline, from + CONTEXT)
        if (end in (from + 1) until text.length && text[end - 1].isHighSurrogate()) end--
        return end
    }

    companion object {
        /** Characters of context on each side of a match. */
        const val CONTEXT = 20

        /** The context letters the reader matches on its page to mark the hit. */
        const val HINT = 6

        /** A search of a very common letter stops here; the list stays quick. */
        const val MAX_HITS = 300
    }
}
