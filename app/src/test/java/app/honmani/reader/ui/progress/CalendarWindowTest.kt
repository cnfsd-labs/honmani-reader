package app.honmani.reader.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarWindowTest {
    @Test
    fun aNarrowPhoneShowsSeveralMonthsWithoutScrolling() {
        // 360 dp wide minus the screen and card padding (72 dp).
        val weeks = CalendarWindow.weeks(width = 288f, minCell = 12f, gap = 3f, max = 53)
        assertEquals(19, weeks)
        // The columns, with their gaps, fit the width.
        assertTrue(weeks * 12f + (weeks - 1) * 3f <= 288f)
    }

    @Test
    fun neverMoreThanTheYearOrFewerThanAMonth() {
        assertEquals(53, CalendarWindow.weeks(width = 2000f, minCell = 12f, gap = 3f, max = 53))
        assertEquals(4, CalendarWindow.weeks(width = 10f, minCell = 12f, gap = 3f, max = 53))
    }
}
