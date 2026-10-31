package app.honmani.reader.data.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingSessionTest {
    private val minute = 60_000L

    @Test
    fun noLookupsMeansNoSession() {
        assertNull(ReadingSession.start(emptyList()))
    }

    @Test
    fun aSingleLookupStartsTheSession() {
        assertEquals(5_000L, ReadingSession.start(listOf(5_000L)))
    }

    @Test
    fun lookupsCloserThanTheGapBelongTogether() {
        val times = listOf(100 * minute, 90 * minute, 70 * minute, 45 * minute)
        assertEquals(45 * minute, ReadingSession.start(times))
    }

    @Test
    fun aPauseOfHalfAnHourEndsTheSession() {
        // 100 -> 70 is exactly the gap: the older lookups are an earlier session.
        assertEquals(100 * minute, ReadingSession.start(listOf(100 * minute, 70 * minute, 69 * minute)))
        assertEquals(70 * minute, ReadingSession.start(listOf(100 * minute, 90 * minute, 70 * minute, 20 * minute)))
    }

    @Test
    fun theLatestSessionStaysTheLatestHoursAfterItsEnd() {
        assertEquals(10 * minute, ReadingSession.start(listOf(20 * minute, 10 * minute)))
    }
}
