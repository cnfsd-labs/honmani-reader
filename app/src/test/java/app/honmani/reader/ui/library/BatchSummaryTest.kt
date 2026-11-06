package app.honmani.reader.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchSummaryTest {
    private val imported: (Int) -> String = { "Imported $it ${if (it == 1) "book" else "books"}" }
    private val duplicates: (Int) -> String = { "$it already in the library" }
    private val failed: (Int) -> String = { "$it failed" }

    @Test
    fun listsOnlyWhatHappened() {
        assertEquals(
            "Imported 12 books, 3 already in the library, 1 failed.",
            BatchSummary.sentence(listOf(12 to imported, 3 to duplicates, 1 to failed)),
        )
        assertEquals("Imported 1 book.", BatchSummary.sentence(listOf(1 to imported, 0 to duplicates, 0 to failed)))
        assertEquals("3 already in the library, 1 failed.", BatchSummary.sentence(listOf(0 to imported, 3 to duplicates, 1 to failed)))
    }

    @Test
    fun isEmptyWhenNothingHappened() {
        assertEquals("", BatchSummary.sentence(listOf(0 to imported, 0 to duplicates)))
    }
}
