package app.honmani.reader.ui.words

import app.honmani.reader.data.review.ReviewScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPageStateTest {
    @Test
    fun dueCardsOrNewWordsCanStart() {
        val state = reviewPageState(ReviewScope.Due, due = 3, newWords = 0, starredLearning = 0)
        assertEquals(ReviewPageMode.Due, state.mode)
        assertTrue(state.canStart)
        assertTrue(reviewPageState(ReviewScope.Due, due = 0, newWords = 2, starredLearning = 0).canStart)
    }

    @Test
    fun reviewsOnlyIgnoresNewWords() {
        val state = reviewPageState(ReviewScope.DueOnly, due = 3, newWords = 5, starredLearning = 0)
        assertEquals(ReviewPageMode.Due, state.mode)
        assertTrue(state.canStart)
        // New words alone give nothing to do.
        val none = reviewPageState(ReviewScope.DueOnly, due = 0, newWords = 5, starredLearning = 0)
        assertEquals(ReviewPageMode.Nothing, none.mode)
        assertFalse(none.canStart)
    }

    @Test
    fun nothingDueCannotStart() {
        val state = reviewPageState(ReviewScope.Due, due = 0, newWords = 0, starredLearning = 5)
        assertEquals(ReviewPageMode.Nothing, state.mode)
        assertFalse(state.canStart)
    }

    @Test
    fun starredWithoutStarredWordsSaysSoAndCannotStart() {
        val state = reviewPageState(ReviewScope.Starred, due = 4, newWords = 1, starredLearning = 0)
        assertEquals(ReviewPageMode.NoStarred, state.mode)
        assertFalse(state.canStart)
    }

    @Test
    fun starredWithStarredWordsReviewsAhead() {
        val state = reviewPageState(ReviewScope.Starred, due = 0, newWords = 0, starredLearning = 2)
        assertEquals(ReviewPageMode.Ahead, state.mode)
        assertTrue(state.canStart)
    }

    @Test
    fun noSavedWordsShowsTheEmptyPageWhateverTheChip() {
        for (scope in listOf(ReviewScope.Due, ReviewScope.Starred, ReviewScope.AllLearning)) {
            val state = reviewPageState(scope, due = 0, newWords = 0, starredLearning = 0, hasWords = false)
            assertEquals(ReviewPageMode.NoWords, state.mode)
            assertFalse(state.canStart)
        }
    }
}
