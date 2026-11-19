package app.honmani.reader.ui.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOverlaysTest {
    private val scope = SaverScope { true }

    @Test
    fun afterProcessDeathOnlyOverlaysWithoutABookComeBack() {
        val draft = mutableStateOf<BookmarkDraft?>(BookmarkDraft(characters = 10, total = 100, name = "x"))
        val open = ReaderOverlays(draft).apply {
            readerSettings = true
            dictionariesShown = true
            reviewShown = true
            openedFromMenu = true
            contentsFromMenu = true
            sessionShown = true
            searchShown = true
            bookmarksShown = true
            highlightsShown = true
            pronunciationsShown = true
            audiobookStep = AudiobookStep.entries.first()
            makeShown = true
        }
        val saver = ReaderOverlays.saver(draft)
        val saved = with(saver) { scope.save(open) }!!
        // The WebView starts at ttu's library after process death: a book's overlays start closed.
        val restored = saver.restore(saved)!!

        assertTrue(restored.readerSettings)
        assertTrue(restored.dictionariesShown)
        assertTrue(restored.reviewShown)
        assertFalse(restored.openedFromMenu)
        assertFalse(restored.contentsFromMenu)
        assertFalse(restored.sessionShown)
        assertFalse(restored.searchShown)
        assertFalse(restored.bookmarksShown)
        assertFalse(restored.highlightsShown)
        assertFalse(restored.pronunciationsShown)
        assertNull(restored.audiobookStep)
        assertFalse(restored.makeShown)
        assertNull(restored.bookmarkDraft)
        assertEquals(null, draft.value)
    }
}
