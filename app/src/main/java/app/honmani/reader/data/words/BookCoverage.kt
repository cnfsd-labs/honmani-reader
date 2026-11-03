package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.BookWords

/**
 * How much of a book's text the reader really knows: the share of its running words (names left
 * out, as in grading) that [WordKnowledge.isKnown], the same state that marks a word Known in the
 * text, and how many distinct words are neither known nor in Learning. Not an estimate: a new
 * reader knows 0%, and words in Learning are not known yet.
 */
data class BookCoverage(val known: Float, val newWords: Int) {
    companion object {
        fun of(words: BookWords, knowledge: WordKnowledge): BookCoverage? {
            var all = 0L
            var known = 0L
            var newWords = 0
            for (i in 0 until words.size) {
                val uses = words.positions[i].size
                all += uses
                val key = words.keys[i]
                if (knowledge.isKnown(key)) known += uses else if (!knowledge.isLearning(key)) newWords++
            }
            if (all == 0L) return null
            return BookCoverage(known.toFloat() / all, newWords)
        }
    }
}

/** A word of a book that the reader does not know yet, and how often the book uses it. */
data class UnknownWord(val key: String, val uses: Int)

/**
 * The words of a book that are neither known nor in Learning (as [BookCoverage.newWords] counts
 * them): the [words] used most often first (at most [LIMIT]), and how many there are in all
 * ([total]).
 */
data class UnknownWords(val words: List<UnknownWord>, val total: Int) {
    companion object {
        /** A list this long is read before a book; more only slows the sheet down. */
        const val LIMIT = 200

        fun of(words: BookWords, knowledge: WordKnowledge, limit: Int = LIMIT): UnknownWords {
            val unknown = ArrayList<Int>()
            for (i in 0 until words.size) {
                val key = words.keys[i]
                if (!knowledge.isKnown(key) && !knowledge.isLearning(key)) unknown += i
            }
            // Most used first; of equally used words the more common one (a lower rank) first.
            val top = unknown
                .sortedWith(
                    compareByDescending<Int> { words.positions[it].size }
                        .thenBy { words.ranks[it] }
                        .thenBy { words.keys[it] },
                )
                .take(limit)
                .map { UnknownWord(words.keys[it], words.positions[it].size) }
            return UnknownWords(top, unknown.size)
        }
    }
}
