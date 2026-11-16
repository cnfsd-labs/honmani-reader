package app.honmani.reader.ui.review

import app.honmani.reader.ui.library.LibraryBook
import app.honmani.reader.ui.library.LibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadNextTest {
    private val book = LibraryBook(id = 7, title = "こころ", author = null, cover = null, progress = 0.4f)

    @Test
    fun theBookReadLastComesFirst() {
        assertEquals(ReadNext.Continue(7, "こころ"), ReadNext.of(LibraryUiState(books = listOf(book), totalBooks = 1, lastBook = book)))
    }

    @Test
    fun withoutABookBeingRead_theSampleOrTheLibrary() {
        assertEquals(ReadNext.Sample, ReadNext.of(LibraryUiState(books = emptyList(), totalBooks = 0)))
        assertEquals(ReadNext.Library, ReadNext.of(LibraryUiState(books = listOf(book), totalBooks = 1)))
    }

    @Test
    fun nothingWhileTheLibraryLoads() {
        assertNull(ReadNext.of(LibraryUiState()))
    }
}
