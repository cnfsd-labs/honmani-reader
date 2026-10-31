package app.honmani.reader.anki

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.opds.CredentialCipher
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.WordRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnkiSyncTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var words: WordRepository
    private lateinit var settings: AnkiSettings
    private val anki = FakeAnki()
    private lateinit var sync: AnkiSync

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java).allowMainThreadQueries().build()
        words = WordRepository(database)
        val cipher = object : CredentialCipher {
            override fun encrypt(plain: String) = plain

            override fun decrypt(encrypted: String) = encrypted
        }
        settings = AnkiSettings(context.getSharedPreferences("anki-sync-test", Context.MODE_PRIVATE), cipher)
        sync = AnkiSync(anki, settings, words)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun save(expression: String, reading: String) {
        words.record(
            LookedUpWord(expression = expression, reading = reading, gloss = "g", surface = expression, sentence = expression, surfaceStart = 0),
        )
    }

    private suspend fun word(expression: String) = words.all.first().single { it.expression == expression }

    /** A Honmani card for the word, as the exporter writes its first two fields. */
    private fun card(expression: String, reading: String): Long {
        val type = anki.honmaniType()
        return anki.addNote(type, listOf(AnkiCard.escape(expression), AnkiCard.escape(reading)))
    }

    @Test
    fun clearsTheMarkOfACardDeletedInAnki_andKeepsOthers() = runBlocking {
        save("名前", "なまえ")
        save("見当", "けんとう")
        val kept = card("名前", "なまえ")
        val deleted = card("見当", "けんとう")
        words.setAnkiNote(word("名前").id, kept)
        words.setAnkiNote(word("見当").id, deleted)
        anki.notes.remove(deleted)

        val result = sync.check()

        assertEquals(AnkiCheck.Done(cleared = 1, marked = 0), result)
        assertEquals(kept, word("名前").ankiNoteId)
        assertNull(word("見当").ankiNoteId)
    }

    @Test
    fun marksAWordWhoseCardIsInAnkiButWasNotMarked() = runBlocking {
        save("名前", "なまえ")
        save("見当", "けんとう")
        val id = card("名前", "なまえ")

        val result = sync.check()

        assertEquals(AnkiCheck.Done(cleared = 0, marked = 1), result)
        assertEquals(id, word("名前").ankiNoteId)
        assertNull(word("見当").ankiNoteId)
    }

    @Test
    fun aMarkedCardInAnotherNoteTypeStaysMarked() = runBlocking {
        save("名前", "なまえ")
        val other = anki.addNote(anki.noteType("Older Honmani"), listOf("名前", "なまえ"))
        words.setAnkiNote(word("名前").id, other)

        val result = sync.check()

        assertEquals(AnkiCheck.Done(cleared = 0, marked = 0), result)
        assertEquals(other, word("名前").ankiNoteId)
    }

    @Test
    fun saysWhenAnkiDroidIsMissingOrNotAllowed() = runBlocking {
        anki.installed = false
        assertEquals(AnkiCheck.NotInstalled, sync.check())
        anki.installed = true
        anki.permission = false
        assertEquals(AnkiCheck.NoPermission, sync.check())
    }

    @Test
    fun anAnkiDroidThatThrowsOrHasNoCollectionChangesNothing() = runBlocking {
        save("名前", "なまえ")
        words.setAnkiNote(word("名前").id, 99L)

        anki.decks.clear()
        assertTrue(sync.check() is AnkiCheck.Failed)

        anki.decks += "Default"
        anki.failure = IllegalStateException("provider gone")
        assertEquals(AnkiCheck.Failed("provider gone"), sync.check())
        assertEquals(99L, word("名前").ankiNoteId)
    }

    private class FakeAnki : AnkiApi {
        var installed = true
        var permission = true
        var failure: Exception? = null
        val decks = mutableListOf("Default")
        val notes = linkedMapOf<Long, AnkiNote>()
        private val types = linkedMapOf<Long, String>()
        private var nextId = 1L

        fun noteType(name: String): Long = types.entries.firstOrNull { it.value == name }?.key
            ?: (500L + types.size).also { types[it] = name }

        fun honmaniType() = noteType(AnkiCard.NOTE_TYPE)

        fun addNote(type: Long, fields: List<String>): Long {
            val id = nextId++
            notes[id] = AnkiNote(id, type, fields)
            return id
        }

        override fun packageName() = if (installed) "com.ichi2.anki" else null

        override fun hasPermission() = permission

        override fun noteTypeExists(id: Long) = id in types

        override fun findNoteType(name: String, fields: List<String>) = types.entries.firstOrNull { it.value == name }?.key

        override fun addNoteType(name: String, fields: List<String>, cardName: String, front: String, back: String, css: String) =
            noteType(name)

        override fun cardFront(noteType: Long): String? = null

        override fun setCard(noteType: Long, cardName: String, front: String, back: String) = true

        override fun deck(name: String) = 1L

        override fun deckNames(): List<String> = decks

        override fun findNote(noteType: Long, first: String, second: String): Long? = null

        override fun addNote(noteType: Long, deck: Long, fields: List<String>, tags: Set<String>) = addNote(noteType, fields)

        override fun addSound(file: File, preferredName: String): String? = null

        override fun existingNotes(ids: Collection<Long>): Set<Long> {
            failure?.let { throw it }
            return ids.filter { it in notes }.toSet()
        }

        override fun notes(noteType: Long) = notes.values.filter { it.noteType == noteType }
    }
}
