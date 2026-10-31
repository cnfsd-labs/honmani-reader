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

    /** A note in [deck] of a note type with [names] as fields. */
    private fun deckNote(deck: String, type: String, names: List<String>, fields: List<String>) {
        val typeId = anki.noteType(type)
        anki.fieldNamesByType[typeId] = names
        anki.decks += deck
        anki.deckContents.getOrPut(deck) { mutableListOf() } += anki.addNote(typeId, fields)
    }

    @Test
    fun findsSavedWordsAmongTheNotesOfADeck_usingTheExpressionField() = runBlocking {
        save("名前", "なまえ")
        save("見当", "けんとう")
        save("猫", "ねこ")
        deckNote("Core", "Basic", listOf("Reading", "Expression"), listOf("なまえ", "名前"))
        deckNote("Core", "Basic", listOf("Reading", "Expression"), listOf("ねこ", "<b>猫</b>[ねこ]"))
        deckNote("Core", "Other", listOf("Back", "Front"), listOf("x", "大きい"))
        words.markLearned(word("猫").id)

        val result = sync.deckWords("Core")

        val found = result as DeckWords.Found
        assertEquals(3, found.notes)
        // 猫 is known already; 見当 is not in the deck.
        assertEquals(listOf("名前"), found.matching.map { it.expression })
    }

    @Test
    fun markingMarksWordsAsKnown_andNoLongerOffers() = runBlocking {
        save("名前", "なまえ")
        deckNote("Core", "Basic", listOf("Front", "Back"), listOf("名前", "name"))
        val found = sync.deckWords("Core") as DeckWords.Found
        assertEquals(false, settings.knownFromAnkiOffered.value)

        sync.markKnown(found.matching)

        assertEquals(app.honmani.reader.data.words.WordStatus.LEARNED, word("名前").status)
        assertEquals(true, settings.knownFromAnkiOffered.value)
    }

    @Test
    fun aDeckThatCannotBeReadIsReportedNotFaked() = runBlocking {
        anki.installed = false
        assertEquals(DeckWords.NotInstalled, sync.deckWords("Core"))
        anki.installed = true
        anki.permission = false
        assertEquals(DeckWords.NoPermission, sync.deckWords("Core"))
        anki.permission = true
        assertTrue(sync.deckWords("Missing") is DeckWords.Failed)
        anki.failure = IllegalStateException("denied")
        assertEquals(DeckWords.Failed("denied"), sync.deckWords("Core"))
    }

    @Test
    fun theWordIsTakenFromAFieldNamedLikeExpression_elseTheFirst() {
        assertEquals(1, AnkiWords.fieldIndex(listOf("Reading", "Expression", "Meaning")))
        assertEquals(2, AnkiWords.fieldIndex(listOf("Id", "Meaning", "単語")))
        assertEquals(0, AnkiWords.fieldIndex(listOf("A", "B")))
    }

    @Test
    fun extractsTheWordFromHtmlAndFurigana() {
        assertEquals("食べる", AnkiWords.extract("食[た]べる"))
        assertEquals("日本語", AnkiWords.extract(" 日本語[にほんご]"))
        assertEquals("取り扱う", AnkiWords.extract("<ruby>取<rt>と</rt></ruby>り<ruby>扱<rt>あつか</rt></ruby>う"))
        assertEquals("猫", AnkiWords.extract("<div><b>猫</b></div><div>ねこ</div>[sound:neko.mp3]"))
        assertEquals("猫", AnkiWords.extract("&nbsp;猫&nbsp;"))
        assertNull(AnkiWords.extract("[sound:a.mp3]"))
        assertNull(AnkiWords.extract("hello"))
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

        val deckContents = mutableMapOf<String, MutableList<Long>>()
        val fieldNamesByType = mutableMapOf<Long, List<String>>()

        override fun deckNotes(deck: String): List<AnkiNote>? {
            failure?.let { throw it }
            return deckContents[deck]?.mapNotNull { notes[it] }
        }

        override fun fieldNames(noteType: Long) = fieldNamesByType[noteType].orEmpty()
    }
}
