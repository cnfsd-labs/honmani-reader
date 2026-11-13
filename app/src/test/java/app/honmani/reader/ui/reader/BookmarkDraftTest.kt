package app.honmani.reader.ui.reader

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkDraftTest {
    private val scope = SaverScope { true }

    private fun roundTrip(draft: BookmarkDraft?): BookmarkDraft? {
        val saved = with(BookmarkDraft.Saver) { scope.save(draft) }
        return saved?.let { BookmarkDraft.Saver.restore(it) }
    }

    @Test
    fun aDraftBeingNamedSurvivesTheAppBeingStopped() {
        val draft = BookmarkDraft(characters = 12_345, total = 99_999, name = "吾輩は猫である")
        assertEquals(draft, roundTrip(draft))
        assertNull(roundTrip(null))
    }
}
