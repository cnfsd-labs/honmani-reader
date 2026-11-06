package app.honmani.reader.data.review

import app.honmani.reader.data.words.WordEntity

/** What a review session draws from (the chips on the Review card); [Due] by default. */
sealed interface ReviewScope {
    /** The cards due today, then new words up to the day's limit. */
    data object Due : ReviewScope

    /** The cards due today only: no new words are started. */
    data object DueOnly : ReviewScope

    /** Words that come up in the book being read. */
    data object ThisBook : ReviewScope

    /** Words that come up in a book of the library. */
    data class Book(val id: Long, val title: String) : ReviewScope

    data object Starred : ReviewScope

    /** Every word in Learning. */
    data object AllLearning : ReviewScope
}

/**
 * The words a scope other than [ReviewScope.Due] and [ReviewScope.DueOnly] takes ([accepts]), and how new cards are
 * ordered ([score] of a word by how it is written; null: as without a scope).
 */
class ReviewFilter(val score: ((String) -> Double)? = null, val accepts: (WordEntity) -> Boolean)
