package app.honmani.reader.ui.review

import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.review.ReviewScope

/** The chip chosen on the Review page, the library's books for "A book…", and what a tap on a chip does. */
class ReviewScopeChoice(
    val scope: ReviewScope = ReviewScope.Due,
    val books: List<BookEntity> = emptyList(),
    val onChange: (ReviewScope) -> Unit = {},
)
