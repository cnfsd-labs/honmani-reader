package app.honmani.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageCycleTest {
    @Test
    fun goesToTheNextPageAndWrapsFromTheLastToTheFirst() {
        assertEquals(1, nextPageInCycle(0, 3))
        assertEquals(2, nextPageInCycle(1, 3))
        assertEquals(0, nextPageInCycle(2, 3))
    }

    @Test
    fun aSinglePageStaysPut() {
        assertEquals(0, nextPageInCycle(0, 1))
        assertEquals(0, nextPageInCycle(0, 0))
    }
}
