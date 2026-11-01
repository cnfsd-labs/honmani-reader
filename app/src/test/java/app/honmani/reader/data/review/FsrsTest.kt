package app.honmani.reader.data.review

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected intervals come from the published FSRS-6 formulas with the default weights, worked
 * out separately (a small script) for reviews made exactly on the due day.
 */
class FsrsTest {
    private fun intervals(vararg grades: Grade): List<Int> {
        var memory: Memory? = null
        var last = 0
        return grades.map { grade ->
            val m = memory
            memory = if (m == null) Fsrs.first(grade) else Fsrs.next(m, grade, last.toDouble())
            Fsrs.interval(memory.stability).also { last = it }
        }
    }

    private fun assertWithin(expected: List<Int>, actual: List<Int>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            // One day, or 2% on the long intervals (accumulated rounding).
            val allowed = maxOf(1, (e * 0.02).toInt())
            assertTrue("step $i: expected $e, got $a", abs(e - a) <= allowed)
        }
    }

    @Test
    fun goodGoodGoodGood() = assertWithin(listOf(3, 11, 37, 112), intervals(Grade.GOOD, Grade.GOOD, Grade.GOOD, Grade.GOOD))

    @Test
    fun againThenGood() = assertWithin(listOf(1, 2, 5), intervals(Grade.AGAIN, Grade.GOOD, Grade.GOOD))

    @Test
    fun goodThenHardThenGood() = assertWithin(listOf(3, 5, 15), intervals(Grade.GOOD, Grade.HARD, Grade.GOOD))

    @Test
    fun againAgainGood() = assertWithin(listOf(1, 1, 1), intervals(Grade.AGAIN, Grade.AGAIN, Grade.GOOD))

    @Test
    fun theFirstGoodReviewUsesTheInitialStability() {
        val memory = Fsrs.first(Grade.GOOD)
        assertEquals(3.2602, memory.stability, 1e-9)
        assertEquals(4.8846, memory.difficulty, 1e-3)
    }

    @Test
    fun recallIsNinetyPercentAtTheStabilityAndFallsOverTime() {
        assertEquals(0.9, Fsrs.retrievability(10.0, 10.0), 1e-9)
        assertEquals(1.0, Fsrs.retrievability(10.0, 0.0), 1e-9)
        assertTrue(Fsrs.retrievability(10.0, 20.0) < Fsrs.retrievability(10.0, 10.0))
        assertEquals(10, Fsrs.interval(10.0))
        assertEquals(1, Fsrs.interval(0.2))
    }

    @Test
    fun hardGainsLessThanGoodAndEasyMore() {
        val memory = Memory(10.0, 5.0)
        val hard = Fsrs.next(memory, Grade.HARD, 10.0).stability
        val good = Fsrs.next(memory, Grade.GOOD, 10.0).stability
        val easy = Fsrs.next(memory, Grade.EASY, 10.0).stability
        assertTrue(hard in 10.0..good)
        assertTrue(easy > good)
    }

    @Test
    fun forgettingLowersStabilityAndRaisesDifficulty() {
        val memory = Memory(30.0, 5.0)
        val after = Fsrs.next(memory, Grade.AGAIN, 30.0)
        assertTrue(after.stability < 30.0)
        assertTrue(after.difficulty > 5.0)
    }

    @Test
    fun aSameDayGoodNeverShrinksStability() {
        val memory = Memory(2.0, 5.0)
        assertTrue(Fsrs.next(memory, Grade.GOOD, 0.01).stability >= 2.0)
    }
}
