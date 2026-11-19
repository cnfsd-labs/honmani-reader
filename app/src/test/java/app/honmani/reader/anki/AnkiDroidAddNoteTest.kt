package app.honmani.reader.anki

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class AnkiDroidAddNoteTest {
    /** AnkiDroid's provider that adds the note but fails to move its cards. */
    private class DeckMoveFails : ContentProvider() {
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri = Uri.withAppendedPath(uri, "42")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf("ord")).apply { addRow(arrayOf<Any>("0")) }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
            throw IllegalStateException("no deck")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    @Test
    fun noteIsAddedEvenIfItsCardsCannotMoveToTheDeck() {
        ShadowContentResolver.registerProviderInternal(AnkiDroid.AUTHORITY, DeckMoveFails())
        val anki = AnkiDroid(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(42L, anki.addNote(1L, 2L, listOf("語", "ご"), emptySet()))
    }
}
