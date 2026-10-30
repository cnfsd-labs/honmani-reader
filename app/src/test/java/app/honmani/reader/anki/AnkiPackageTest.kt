package app.honmani.reader.anki

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The .apkg writer on its own: the zip, the collection's schema and the ids Anki matches on. */
@RunWith(RobolectricTestRunner::class)
class AnkiPackageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private fun fields(word: String, reading: String = "よみ") =
        AnkiCard.FIELDS.map { "" }.toMutableList().also {
            it[0] = word
            it[1] = reading
        }

    private fun write(notes: List<AnkiPackage.Note>, deck: String = "Honmani"): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        AnkiPackage.write(notes, deck, folder.newFolder(), out, now)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) entries[(zip.nextEntry ?: break).name] = zip.readBytes()
        }
        return entries
    }

    private fun <T> Map<String, ByteArray>.collection(block: (SQLiteDatabase) -> T): T {
        val file = folder.newFile().apply { writeBytes(getValue("collection.anki2")) }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
    }

    @Test
    fun theOutputIsAZipWithTheCollectionTheMediaMapAndTheAudio() {
        val audio = folder.newFile("name.mp3").apply { writeText("audio-bytes") }
        val entries = write(listOf(AnkiPackage.Note(fields("名前"), listOf(audio))))

        assertEquals(listOf("collection.anki2", "media", "0"), entries.keys.toList())
        assertTrue(entries.getValue("collection.anki2").decodeToString(endIndex = 15).startsWith("SQLite format 3"))
        val media = Json.parseToJsonElement(entries.getValue("media").decodeToString()) as JsonObject
        assertEquals("name.mp3", media.getValue("0").jsonPrimitive.content)
        assertEquals("audio-bytes", entries.getValue("0").decodeToString())
    }

    @Test
    fun withoutAudioTheMediaMapIsEmpty() {
        val entries = write(listOf(AnkiPackage.Note(fields("名前"))))

        assertEquals(listOf("collection.anki2", "media"), entries.keys.toList())
        assertEquals("{}", entries.getValue("media").decodeToString())
    }

    @Test
    fun theSameAudioFileIsStoredOnce() {
        val audio = folder.newFile("shared.mp3").apply { writeText("x") }
        val entries = write(
            listOf(AnkiPackage.Note(fields("a"), listOf(audio)), AnkiPackage.Note(fields("b"), listOf(audio))),
        )

        assertEquals(setOf("collection.anki2", "media", "0"), entries.keys)
    }

    @Test
    fun theCollectionIsSchema11WithTheFixedNoteTypeAndTheDeck() {
        val entries = write(listOf(AnkiPackage.Note(fields("名前"))), deck = "日本語::Honmani")

        entries.collection { db ->
            db.rawQuery("SELECT ver, models, decks, conf FROM col", null).use {
                assertEquals(1, it.count)
                it.moveToFirst()
                assertEquals(11, it.getInt(0))
                val models = Json.parseToJsonElement(it.getString(1)).jsonObject
                assertEquals(setOf(AnkiPackage.NOTE_TYPE_ID.toString()), models.keys)
                val type = models.getValue(AnkiPackage.NOTE_TYPE_ID.toString()).jsonObject
                assertEquals(AnkiPackage.NOTE_TYPE_ID.toString(), type.getValue("id").jsonPrimitive.content)
                assertEquals(AnkiCard.NOTE_TYPE, type.getValue("name").jsonPrimitive.content)
                val decks = Json.parseToJsonElement(it.getString(2)).jsonObject
                val deckId = AnkiPackage.deckId("日本語::Honmani").toString()
                assertEquals("日本語::Honmani", decks.getValue(deckId).jsonObject.getValue("name").jsonPrimitive.content)
                assertTrue("\"curModel\":${AnkiPackage.NOTE_TYPE_ID}" in it.getString(3))
            }
            db.rawQuery("SELECT mid FROM notes", null).use {
                it.moveToFirst()
                assertEquals(AnkiPackage.NOTE_TYPE_ID, it.getLong(0))
            }
        }
    }

    @Test
    fun aNotesGuidComesFromItsWordAndReadingOnly() {
        val first = fields("名前", "なまえ")
        val sameWord = fields("名前", "なまえ").also { it[3] = "another meaning" }
        val otherReading = fields("名前", "めい")
        val otherWord = fields("犬", "なまえ")

        assertEquals(AnkiPackage.guid(first), AnkiPackage.guid(sameWord))
        assertNotEquals(AnkiPackage.guid(first), AnkiPackage.guid(otherReading))
        assertNotEquals(AnkiPackage.guid(first), AnkiPackage.guid(otherWord))
        assertTrue(AnkiPackage.guid(first).matches(Regex("honmani-[0-9a-f]{16}")))

        val entries = write(listOf(AnkiPackage.Note(first), AnkiPackage.Note(otherWord)))
        entries.collection { db ->
            db.rawQuery("SELECT guid FROM notes ORDER BY id", null).use {
                val guids = generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList()
                assertEquals(listOf(AnkiPackage.guid(first), AnkiPackage.guid(otherWord)), guids)
            }
        }
    }

    @Test
    fun theSameInputGivesTheSameContent() {
        val audio = folder.newFile("name.mp3").apply { writeText("audio") }
        val notes = listOf(AnkiPackage.Note(fields("名前"), listOf(audio)), AnkiPackage.Note(fields("犬")))

        val one = write(notes)
        val two = write(notes)

        assertEquals(one.keys, two.keys)
        for (name in one.keys) assertTrue("$name differs", one.getValue(name).contentEquals(two.getValue(name)))
        assertFalse(one.getValue("collection.anki2").isEmpty())
    }

    @Test
    fun theWorkDirectoryKeepsNoDatabaseAfterwards() {
        val work: File = folder.newFolder("work")
        AnkiPackage.write(listOf(AnkiPackage.Note(fields("名前"))), "Honmani", work, ByteArrayOutputStream(), now)

        assertEquals(emptyList<String>(), work.list()?.toList().orEmpty())
    }
}
