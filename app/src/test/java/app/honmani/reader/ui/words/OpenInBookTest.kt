package app.honmani.reader.ui.words

import app.honmani.reader.data.words.WordLookupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenInBookTest {
    private fun lookup(bookId: Long?, at: Long, sentence: String = "猫が鳴いた。") =
        WordLookupEntity(wordId = 1, surface = "猫", sentence = sentence, surfaceStart = 0, surfaceEnd = 1, bookId = bookId, lookedUpAt = at)

    @Test
    fun opensTheBookOfTheLatestSentenceInTheLibrary() {
        val lookups = listOf(lookup(1, at = 10), lookup(2, at = 30), lookup(3, at = 20))
        assertEquals(2L, OpenInBook.bookOf(lookups, setOf(1, 2, 3)))
        // The latest book was removed: the next latest still in the library.
        assertEquals(3L, OpenInBook.bookOf(lookups, setOf(1, 3)))
    }

    @Test
    fun nothingWithoutABookInTheLibrary() {
        assertNull(OpenInBook.bookOf(emptyList(), setOf(1)))
        assertNull(OpenInBook.bookOf(listOf(lookup(null, at = 10)), setOf(1)))
        assertNull(OpenInBook.bookOf(listOf(lookup(4, at = 10)), setOf(1)))
        assertNull(OpenInBook.bookOf(listOf(lookup(1, at = 10, sentence = " ")), setOf(1)))
    }
}
