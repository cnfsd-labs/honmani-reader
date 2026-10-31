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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnkiExporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var words: WordRepository
    private lateinit var settings: AnkiSettings
    private val anki = FakeAnki()
    private val voice = FakeVoice()
    private val plainCipher = object : CredentialCipher {
        override fun encrypt(plain: String) = plain

        override fun decrypt(encrypted: String) = encrypted
    }
    private lateinit var exporter: AnkiExporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java).allowMainThreadQueries().build()
        words = WordRepository(database)
        settings = AnkiSettings(context.getSharedPreferences("anki-test", Context.MODE_PRIVATE), plainCipher)
        settings.setAudio(CardAudio.PHONE)
        exporter = AnkiExporter(
            anki = anki,
            settings = settings,
            words = words,
            phoneVoice = { voice },
            audioDir = folder.root,
            details = { _, _ ->
                WordDetails(listOf(listOf("name", "full name"), listOf("reputation")), listOf(0), "Jiten #600")
            },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun savedWord(): app.honmani.reader.data.words.WordEntity {
        words.record(
            LookedUpWord(
                expression = "名前",
                reading = "なまえ",
                gloss = "name",
                surface = "名前",
                sentence = "名前はまだ無い。",
                surfaceStart = 0,
                bookTitle = "吾輩は猫である",
            ),
        )
        return words.learning.first().single()
    }

    @Test
    fun addsACardWithSentenceMeaningsAndAudio_andRemembersIt() = runBlocking {
        val word = savedWord()

        val result = exporter.add(word)

        assertTrue(result is AnkiResult.Added)
        val note = anki.notes.single()
        assertEquals(setOf(AnkiCard.TAG), note.tags)
        assertEquals("名前", note.fields[0])
        assertEquals("<b>名前</b>はまだ無い。", note.fields[4])
        assertEquals("<ol><li>name; full name</li><li>reputation</li></ol>", note.fields[3])
        assertEquals("吾輩は猫である", note.fields[5])
        assertTrue(note.fields[6].startsWith("[sound:honmani_"))
        assertTrue(note.fields[7].startsWith("[sound:honmani_"))
        assertEquals("な<span class=\"h\">ま</span><span class=\"h\">え</span>", note.fields[8])
        assertEquals("Jiten #600", note.fields[9])
        // The word is spoken from its reading; the sentence as written.
        assertEquals(listOf("なまえ", "名前はまだ無い。"), voice.spoken)
        assertEquals(1, anki.noteTypesAdded)
        assertEquals((result as AnkiResult.Added).noteId, words.find(word.id)?.ankiNoteId)
        // Audio files are removed once AnkiDroid has its copies.
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theSentenceIsSpokenAtTheChosenSpeed_theWordAtNormalSpeed() = runBlocking {
        assertEquals(SentenceSpeed.NORMAL, settings.sentenceSpeed.value)
        settings.setSentenceSpeed(SentenceSpeed.SLOW)

        exporter.add(savedWord())

        assertEquals(listOf("なまえ", "名前はまだ無い。"), voice.spoken)
        assertEquals(listOf(1.0, 0.7), voice.rates)
    }

    @Test
    fun theSentenceSpeedIsRemembered() {
        settings.setSentenceSpeed(SentenceSpeed.SLOWER)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val reopened = AnkiSettings(context.getSharedPreferences("anki-test", Context.MODE_PRIVATE), plainCipher)
        assertEquals(SentenceSpeed.SLOWER, reopened.sentenceSpeed.value)
    }

    @Test
    fun cardsGoToTheChosenDeck() = runBlocking {
        settings.setDeck("日本語::Words")

        exporter.add(savedWord())

        assertEquals(anki.deckId("日本語::Words"), anki.notes.single().deck)
        settings.setDeck("  ")
        assertEquals(AnkiCard.DECK, settings.deck.value)
    }

    @Test
    fun wordsBecomeATextFileForAnkiImport_withoutAudio() = runBlocking {
        val text = exporter.textFile(listOf(savedWord()))

        val lines = text.lines()
        assertEquals("#deck column:9", lines[3])
        assertEquals("#columns:Word\tReading\tFurigana\tMeaning\tSentence\tBook\tPitch\tFrequency\tDeck\tTags", lines[5])
        val fields = lines[6].split('\t')
        assertEquals(10, fields.size)
        assertEquals("\"Honmani\"", fields[8])
        assertEquals("\"名前\"", fields[0])
        assertEquals("\"<b>名前</b>はまだ無い。\"", fields[4])
        assertEquals(emptyList<String>(), voice.spoken)
        assertTrue(anki.notes.isEmpty())
    }

    @Test
    fun wordsBecomeAnAnkiPackage_withNoteTypeDeckAndAudio() = runBlocking {
        settings.setDeck("日本語::Honmani")
        val out = java.io.ByteArrayOutputStream()
        val progress = mutableListOf<Int>()

        val problem = exporter.ankiPackage(listOf(savedWord()), out) { progress += it }

        assertEquals(null, problem)
        assertEquals(listOf(1), progress)
        assertEquals(listOf("なまえ", "名前はまだ無い。"), voice.spoken)
        val entries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) entries[(zip.nextEntry ?: break).name] = zip.readBytes()
        }
        assertEquals(setOf("collection.anki2", "media", "0", "1"), entries.keys)
        val media = kotlinx.serialization.json.Json.parseToJsonElement(entries.getValue("media").decodeToString())
            as kotlinx.serialization.json.JsonObject
        val names = (0..1).map { (media.getValue(it.toString()) as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals("なまえ", entries.getValue("0").decodeToString())

        val file = folder.newFile("collection.anki2").apply { writeBytes(entries.getValue("collection.anki2")) }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db.rawQuery("SELECT models, decks FROM col", null).use {
                    it.moveToFirst()
                    assertTrue("\"name\":\"Honmani\"" in it.getString(0))
                    assertTrue("\"name\":\"日本語::Honmani\"" in it.getString(1))
                }
                db.rawQuery("SELECT n.flds, n.tags, n.mid, c.did FROM notes n JOIN cards c ON c.nid = n.id", null).use {
                    assertEquals(1, it.count)
                    it.moveToFirst()
                    val fields = it.getString(0).split('\u001f')
                    assertEquals(AnkiCard.FIELDS.size, fields.size)
                    assertEquals("名前", fields[0])
                    assertEquals("<b>名前</b>はまだ無い。", fields[4])
                    assertEquals("[sound:${names[0]}]", fields[6])
                    assertEquals("[sound:${names[1]}]", fields[7])
                    assertEquals(" honmani ", it.getString(1))
                    assertEquals(AnkiPackage.NOTE_TYPE_ID, it.getLong(2))
                    assertEquals(AnkiPackage.deckId("日本語::Honmani"), it.getLong(3))
                }
            }
        // Nothing is added to AnkiDroid, and nothing is left behind.
        assertTrue(anki.notes.isEmpty())
        assertEquals(emptyList<String>(), folder.root.resolve("package").list()?.toList().orEmpty())
    }

    @Test
    fun aVoiceProblemStillSavesThePackage_withoutAudio() = runBlocking {
        voice.problem = VoiceProblem.NO_JAPANESE
        val out = java.io.ByteArrayOutputStream()

        assertEquals(VoiceProblem.NO_JAPANESE, exporter.ankiPackage(listOf(savedWord()), out))
        assertTrue(out.size() > 0)
    }

    @Test
    fun aWordAlreadyInAnkiIsNotAddedAgain() = runBlocking {
        val word = savedWord()
        exporter.add(word)

        assertEquals(AnkiResult.AlreadyInAnki, exporter.add(word))

        assertEquals(1, anki.notes.size)
        assertEquals(1, anki.noteTypesAdded)
    }

    @Test
    fun withoutAnkiDroidOrPermissionNothingIsAdded() = runBlocking {
        val word = savedWord()
        anki.permission = false
        assertEquals(AnkiResult.NoPermission, exporter.add(word))
        anki.installed = false
        assertEquals(AnkiResult.NotInstalled, exporter.add(word))
        assertTrue(anki.notes.isEmpty())
    }

    @Test
    fun aVoiceProblemStillAddsTheCard_withoutAudio() = runBlocking {
        val word = savedWord()
        voice.problem = VoiceProblem.NO_JAPANESE

        val result = exporter.add(word)

        assertEquals(VoiceProblem.NO_JAPANESE, (result as AnkiResult.Added).audioProblem)
        assertEquals("", anki.notes.single().fields[6])
    }

    @Test
    fun audioOffMakesNoAudio() = runBlocking {
        settings.setAudio(CardAudio.OFF)

        exporter.add(savedWord())

        assertTrue(voice.spoken.isEmpty())
        assertEquals("", anki.notes.single().fields[7])
    }

    @Test
    fun googleWithoutAKeyUsesThePhoneVoice() = runBlocking {
        settings.setAudio(CardAudio.GOOGLE)

        exporter.add(savedWord())

        assertEquals(listOf("なまえ", "名前はまだ無い。"), voice.spoken)
    }

    @Test
    fun aPlaceholderCardIsReplacedOnce_andTheNoteTypeKept() = runBlocking {
        exporter.add(savedWord())
        val noteType = anki.notes.single().noteType
        assertTrue(anki.fronts[noteType]!!.contains("{{Sentence}}"))
        // The reader edits the card in Anki afterwards: Honmani leaves it alone.
        anki.fronts[noteType] = "{{Word}}"
        exporter.add(savedWord().copy(expression = "猫", reading = "ねこ"))

        assertEquals("{{Word}}", anki.fronts[noteType])
        assertEquals(1, anki.noteTypesAdded)
    }

    @Test
    fun errorsFromAnkiDroidAreReported_notThrown() = runBlocking {
        anki.failure = IllegalStateException("collection closed")

        assertEquals(AnkiResult.Failed("collection closed"), exporter.add(savedWord()))
    }

    @Test
    fun suggestedWordsAreLookedUpAgain_andNotInAnki() = runBlocking {
        val word = savedWord()
        assertTrue(words.forAnki.first().isEmpty())
        words.record(
            LookedUpWord("名前", "なまえ", "name", "名前", "君の名前は。", 2),
        )
        assertEquals(listOf("名前"), words.forAnki.first().map { it.expression })
        assertEquals(1, words.counts.first().forAnki)

        exporter.add(word)

        assertTrue(words.forAnki.first().isEmpty())
    }

    private class FakeVoice : CardVoice {
        val spoken = mutableListOf<String>()
        val rates = mutableListOf<Double>()
        var problem: VoiceProblem? = null

        override val extension = "wav"

        override suspend fun speak(text: String, out: File, rate: Double) {
            problem?.let { throw VoiceException(it) }
            spoken += text
            rates += rate
            out.writeText(text)
        }
    }

    private class FakeAnki : AnkiApi {
        data class Note(val noteType: Long, val deck: Long, val fields: List<String>, val tags: Set<String>)

        var installed = true
        var permission = true
        val notes = mutableListOf<Note>()
        var noteTypesAdded = 0
        private val noteTypes = mutableMapOf<Long, String>()
        private val decks = mutableMapOf<String, Long>()

        override fun packageName() = if (installed) "com.ichi2.anki" else null

        override fun hasPermission() = permission

        override fun noteTypeExists(id: Long) = id in noteTypes

        override fun findNoteType(name: String, fields: List<String>) =
            noteTypes.entries.firstOrNull { it.value == name }?.key

        override fun addNoteType(
            name: String,
            fields: List<String>,
            cardName: String,
            front: String,
            back: String,
            css: String,
        ): Long {
            noteTypesAdded++
            val id = 1000L + noteTypes.size
            noteTypes[id] = name
            return id
        }

        val fronts = mutableMapOf<Long, String>()

        override fun cardFront(noteType: Long) = fronts[noteType]

        override fun setCard(noteType: Long, cardName: String, front: String, back: String): Boolean {
            fronts[noteType] = front
            return true
        }

        override fun deck(name: String) = decks.getOrPut(name) { 2000L + decks.size }

        override fun deckNames() = decks.keys.toList()

        fun deckId(name: String) = decks[name]

        override fun findNote(noteType: Long, first: String, second: String): Long? {
            val i = notes.indexOfFirst { it.noteType == noteType && it.fields[0] == first && it.fields[1] == second }
            return if (i < 0) null else i + 1L
        }

        var failure: Exception? = null

        override fun addNote(noteType: Long, deck: Long, fields: List<String>, tags: Set<String>): Long {
            failure?.let { throw it }
            notes += Note(noteType, deck, fields, tags)
            return notes.size.toLong()
        }

        override fun existingNotes(ids: Collection<Long>) = ids.filter { it in 1..notes.size }.toSet()

        override fun notes(noteType: Long) =
            notes.mapIndexed { i, note -> AnkiNote(i + 1L, note.noteType, note.fields) }.filter { it.noteType == noteType }

        override fun deckNotes(deck: String): List<AnkiNote>? = null

        override fun fieldNames(noteType: Long): List<String> = emptyList()

        override fun addSound(file: File, preferredName: String): String {
            check(file.isFile)
            return "[sound:$preferredName.${file.extension}]"
        }
    }
}
