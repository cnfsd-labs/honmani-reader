package app.honmani.reader.ui.words

import app.honmani.reader.data.words.WordLookupEntity

/**
 * "Open in book" on a saved word: the place of its latest lookup with a known position in a book
 * still in the library; else (lookups saved before positions were kept, or in a book where the page
 * could not measure one) the book of its latest sentence, opened where the reader left it.
 */
object OpenInBook {
    /** A book to open and, if known, ttu's character count to open it at. */
    data class Place(val bookId: Long, val position: Long?)

    /** The place to open for a word with these [lookups], given the ids of the books in the library; null: none. */
    fun placeOf(lookups: List<WordLookupEntity>, libraryIds: Set<Long>): Place? {
        val inLibrary = lookups.filter { it.sentence.isNotBlank() && it.bookId != null && it.bookId in libraryIds }
        inLibrary.filter { it.position != null }.maxByOrNull { it.lookedUpAt }
            ?.let { return Place(it.bookId!!, it.position) }
        return inLibrary.maxByOrNull { it.lookedUpAt }?.let { Place(it.bookId!!, null) }
    }
}
