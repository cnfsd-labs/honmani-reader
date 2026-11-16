package app.honmani.reader.ui.words

import app.honmani.reader.data.words.WordLookupEntity

/** "Open in book" on a saved word: the book of its latest sentence, if that book is still in the library. */
object OpenInBook {
    /** The book to open for a word with these [lookups], given the ids of the books in the library; null: none. */
    fun bookOf(lookups: List<WordLookupEntity>, libraryIds: Set<Long>): Long? =
        lookups
            .filter { it.sentence.isNotBlank() && it.bookId != null && it.bookId in libraryIds }
            .maxByOrNull { it.lookedUpAt }
            ?.bookId
}
