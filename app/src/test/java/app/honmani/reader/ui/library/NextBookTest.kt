package app.honmani.reader.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextBookTest {
    private fun book(id: Long, progress: Float = 0f, hidden: Boolean = false, finished: Boolean = false) = LibraryBook(
        id = id,
        title = "Book $id",
        author = null,
        cover = null,
        progress = progress,
        status = ReadingStatus.of(progress, if (finished) 1L else null),
        hidden = hidden,
        contentHash = "hash$id",
    )

    @Test
    fun withASkillAndGrades_theBestFitUnfinishedBookIsSuggested() {
        val books = listOf(book(1), book(2, progress = 0.3f), book(3), book(4, finished = true))
        val difficulty = mapOf("hash2" to 80, "hash3" to 42, "hash4" to 40)

        val next = NextBook.choose(books, finishedId = 1, skill = 40) { difficulty[it.contentHash] }

        assertEquals(3L, next?.id)
    }

    @Test
    fun withoutGrades_theMostRecentBookBeingReadIsSuggested_elseTheMostRecentOne() {
        val books = listOf(book(1), book(2), book(3, progress = 0.4f), book(4, progress = 0.2f))

        assertEquals(3L, NextBook.choose(books, finishedId = 1, skill = 40) { null }?.id)
        assertEquals(2L, NextBook.choose(listOf(book(1), book(2), book(5)), finishedId = 1, skill = null) { null }?.id)
    }

    @Test
    fun theFinishedBook_hiddenAndFinishedOnes_areNeverSuggested() {
        val books = listOf(book(1, progress = 0.99f), book(2, hidden = true, progress = 0.5f), book(3, finished = true))

        assertNull(NextBook.choose(books, finishedId = 1, skill = 40) { 40 })
    }
}
