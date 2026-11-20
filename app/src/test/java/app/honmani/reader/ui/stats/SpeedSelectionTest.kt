package app.honmani.reader.ui.stats

import app.honmani.reader.data.stats.WeekSpeed
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedSelectionTest {
    private fun weeks(vararg speeds: Long?) = speeds.mapIndexed { i, speed -> WeekSpeed(LocalDate.of(2026, 1, 5).plusWeeks(i.toLong()), speed) }

    @Test
    fun aChoiceSavedBeforeAnyWeekWasMeasured_fallsBackToTheLatestMeasuredWeek() {
        // Saved as -1 while the weeks were empty; the data then came in with the same number of weeks.
        assertEquals(2, speedSelection(weeks(null, 900, 1200, null), -1))
    }

    @Test
    fun aMeasuredChoiceStays_anUnmeasuredOneFallsBack() {
        val weeks = weeks(800, null, 1000)
        assertEquals(0, speedSelection(weeks, 0))
        assertEquals(2, speedSelection(weeks, 1))
        assertEquals(2, speedSelection(weeks, 7))
    }

    @Test
    fun nothingMeasured_showsNothing() {
        assertEquals(-1, speedSelection(weeks(null, null), -1))
    }
}
