package app.honmani.reader.data.review

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewLimitTest {
    private val due = listOf(1L, 2L, 3L, 4L, 5L)

    private fun apply(limit: Int, vararg done: ReviewedToday) = ReviewLimit.apply(due, { it }, limit, done.toList())

    @Test
    fun noLimitShowsEverythingDue() {
        assertEquals(due, apply(ReviewLimit.NO_LIMIT))
        assertEquals(due, apply(ReviewLimit.NO_LIMIT, ReviewedToday(9, carried = true)))
    }

    @Test
    fun theLimitKeepsTheFirstMostOverdueAndTheRestCarryOver() {
        assertEquals(listOf(1L, 2L), apply(2))
        assertEquals(due, apply(5))
        assertEquals(due, apply(50))
    }

    @Test
    fun reviewsDoneTodayUseUpTheLimit() {
        assertEquals(listOf(1L), apply(2, ReviewedToday(9, carried = true)))
        assertEquals(emptyList<Long>(), apply(2, ReviewedToday(8, carried = true), ReviewedToday(9, carried = true)))
        assertEquals(emptyList<Long>(), apply(1, ReviewedToday(8, carried = true), ReviewedToday(9, carried = true)))
    }

    @Test
    fun newCardsDoNotUseUpTheLimit() {
        assertEquals(listOf(1L, 2L), apply(2, ReviewedToday(9, carried = false)))
    }

    @Test
    fun aCardReviewedTodayThatIsDueAgainIsAlwaysKept() {
        // 3 and 5 were answered Again today: they stay, the budget (2 - 1 done = 1) goes to the others.
        assertEquals(listOf(1L, 3L, 5L), apply(2, ReviewedToday(3, carried = true), ReviewedToday(5, carried = false)))
        assertEquals(listOf(3L), apply(1, ReviewedToday(3, carried = true)))
    }
}
