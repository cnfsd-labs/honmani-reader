package app.honmani.reader.data.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun leavingABookOffersTheSessionFromFiveLookups() {
        assertFalse(ReadingSession.offersReview(10 * minute, lookupsInSession = 4, lookupsThisVisit = 4, offeredStart = null))
        assertTrue(ReadingSession.offersReview(10 * minute, lookupsInSession = 5, lookupsThisVisit = 5, offeredStart = null))
        // Earlier lookups of the same session count too, as long as this visit had one.
        assertTrue(ReadingSession.offersReview(10 * minute, lookupsInSession = 7, lookupsThisVisit = 1, offeredStart = null))
        assertFalse(ReadingSession.offersReview(10 * minute, lookupsInSession = 7, lookupsThisVisit = 0, offeredStart = null))
    }

    @Test
    fun aSessionIsOfferedOnce() {
        assertFalse(ReadingSession.offersReview(10 * minute, lookupsInSession = 9, lookupsThisVisit = 3, offeredStart = 10 * minute))
        // A new session can be offered again.
        assertTrue(ReadingSession.offersReview(90 * minute, lookupsInSession = 9, lookupsThisVisit = 9, offeredStart = 10 * minute))
        assertFalse(ReadingSession.offersReview(null, lookupsInSession = 0, lookupsThisVisit = 0, offeredStart = null))
    }
}
