package app.honmani.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomAccentTest {
    private fun next(mode: AccentRandom, current: Accent = Accent.PURPLE, lastDay: Long? = null, today: Long = 10, newLaunch: Boolean = false, roll: (Int) -> Int = { 0 }) =
        RandomAccent.next(mode, current, lastDay, today, newLaunch, roll)

    @Test
    fun choicesAreTheNamedColoursOnly() {
        assertFalse(Accent.SYSTEM in RandomAccent.CHOICES)
        assertFalse(Accent.CUSTOM in RandomAccent.CHOICES)
        assertEquals(10, RandomAccent.CHOICES.size)
    }

    @Test
    fun offNeverPicks() {
        assertNull(next(AccentRandom.OFF, newLaunch = true, lastDay = null))
    }

    @Test
    fun everyLaunchPicksOnlyOnALaunch() {
        assertNull(next(AccentRandom.LAUNCH, newLaunch = false))
        assertTrue(next(AccentRandom.LAUNCH, newLaunch = true) != null)
    }

    @Test
    fun everyDayPicksWhenTheDayChanged() {
        assertNull(next(AccentRandom.DAILY, lastDay = 10, today = 10, newLaunch = true))
        assertTrue(next(AccentRandom.DAILY, lastDay = 9, today = 10) != null)
        assertTrue(next(AccentRandom.DAILY, lastDay = null, today = 10) != null)
    }

    @Test
    fun neverTheSameColourTwice_whateverTheRoll() {
        for (current in RandomAccent.CHOICES) {
            for (roll in 0 until RandomAccent.CHOICES.size - 1) {
                val picked = next(AccentRandom.LAUNCH, current = current, newLaunch = true, roll = { roll })!!
                assertTrue(picked != current && picked in RandomAccent.CHOICES)
            }
        }
    }

    @Test
    fun fromSystemOrCustomAnyNamedColourCanFollow() {
        val picked = RandomAccent.CHOICES.indices.map { roll ->
            next(AccentRandom.LAUNCH, current = Accent.SYSTEM, newLaunch = true, roll = { roll })
        }
        assertEquals(RandomAccent.CHOICES, picked)
    }
}
