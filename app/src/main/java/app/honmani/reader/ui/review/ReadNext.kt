package app.honmani.reader.ui.review

import app.honmani.reader.ui.library.LibraryUiState

/** What a review with nothing (more) to do offers next: back to reading. */
sealed interface ReadNext {
    /** The book read last, still being read. */
    data class Continue(val bookId: Long, val title: String) : ReadNext

    /** No book yet: the sample book, as the empty library offers. */
    data object Sample : ReadNext

    /** Books, none being read: the library. */
    data object Library : ReadNext

    companion object {
        /** From the library's state; null while it is still loading. */
        fun of(library: LibraryUiState): ReadNext? {
            val last = library.lastBook
            return when {
                last != null -> Continue(last.id, last.title)
                library.books == null -> null
                library.totalBooks == 0 -> Sample
                else -> Library
            }
        }
    }
}
