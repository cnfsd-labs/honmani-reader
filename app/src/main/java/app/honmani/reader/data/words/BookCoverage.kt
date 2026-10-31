package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.BookWords

/**
 * How much of a book's text the reader already knows: the share of its running words (names left
 * out, as in grading) that [WordKnowledge.knows], and how many distinct words would be new.
 */
data class BookCoverage(val known: Float, val newWords: Int) {
    companion object {
        /** [vocabulary]: the reader's estimated vocabulary as a frequency rank (`VocabularyEstimate.words`). */
        fun of(words: BookWords, knowledge: WordKnowledge, vocabulary: Int): BookCoverage? {
            var all = 0L
            var known = 0L
            var newWords = 0
            for (i in 0 until words.size) {
                val uses = words.positions[i].size
                all += uses
                if (knowledge.knows(words.keys[i], words.ranks[i], vocabulary)) known += uses else newWords++
            }
            if (all == 0L) return null
            return BookCoverage(known.toFloat() / all, newWords)
        }
    }
}

/** A word of a book that the reader does not know yet, and how often the book uses it. */
data class UnknownWord(val key: String, val uses: Int)

/**
 * The words of a book the reader does not know yet ([WordKnowledge.knows], as [BookCoverage]
 * counts them): the [words] used most often first (at most [LIMIT]), and how many there are in
 * all ([total]).
 */
data class UnknownWords(val words: List<UnknownWord>, val total: Int) {
    companion object {
        /** A list this long is read before a book; more only slows the sheet down. */
        const val LIMIT = 200

        fun of(words: BookWords, knowledge: WordKnowledge, vocabulary: Int, limit: Int = LIMIT): UnknownWords {
            val unknown = ArrayList<Int>()
            for (i in 0 until words.size) {
                if (!knowledge.knows(words.keys[i], words.ranks[i], vocabulary)) unknown += i
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
