package app.honmani.reader.ui.words

import app.honmani.reader.data.words.WordLookupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenInBookTest {
    private fun lookup(bookId: Long?, at: Long, sentence: String = "猫が鳴いた。", position: Long? = null) =
        WordLookupEntity(
            wordId = 1, surface = "猫", sentence = sentence, surfaceStart = 0, surfaceEnd = 1, bookId = bookId, lookedUpAt = at,
            position = position,
        )

    @Test
    fun opensTheBookOfTheLatestSentenceInTheLibrary() {
        val lookups = listOf(lookup(1, at = 10), lookup(2, at = 30), lookup(3, at = 20))
        assertEquals(OpenInBook.Place(2, null), OpenInBook.placeOf(lookups, setOf(1, 2, 3)))
        // The latest book was removed: the next latest still in the library.
        assertEquals(OpenInBook.Place(3, null), OpenInBook.placeOf(lookups, setOf(1, 3)))
    }

    @Test
    fun opensAtTheLatestLookupWithAPlace() {
        val lookups = listOf(lookup(1, at = 10, position = 100), lookup(2, at = 20, position = 200), lookup(3, at = 30))
        assertEquals(OpenInBook.Place(2, 200), OpenInBook.placeOf(lookups, setOf(1, 2, 3)))
        // Its book was removed: the next one with a place.
        assertEquals(OpenInBook.Place(1, 100), OpenInBook.placeOf(lookups, setOf(1, 3)))
        // None with a place left: the latest book, where the reader left it.
        assertEquals(OpenInBook.Place(3, null), OpenInBook.placeOf(lookups, setOf(3)))
    }

    @Test
    fun nothingWithoutABookInTheLibrary() {
        assertNull(OpenInBook.placeOf(emptyList(), setOf(1)))
        assertNull(OpenInBook.placeOf(listOf(lookup(null, at = 10, position = 5)), setOf(1)))
        assertNull(OpenInBook.placeOf(listOf(lookup(4, at = 10, position = 5)), setOf(1)))
        assertNull(OpenInBook.placeOf(listOf(lookup(1, at = 10, sentence = " ", position = 5)), setOf(1)))
    }
}
