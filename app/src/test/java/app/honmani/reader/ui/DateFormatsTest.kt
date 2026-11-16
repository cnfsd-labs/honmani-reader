package app.honmani.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DateFormatsTest {
    @Test
    fun `dates use the locale's medium style`() {
        val day = LocalDate.of(2026, 11, 16)
        assertEquals("Nov 16, 2026", day.format(mediumDateFormat(Locale.US)))
        assertEquals("2026/11/16", day.format(mediumDateFormat(Locale.JAPAN)))
    }
}
