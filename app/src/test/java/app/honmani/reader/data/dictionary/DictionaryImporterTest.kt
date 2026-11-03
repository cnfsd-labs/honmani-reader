package app.honmani.reader.data.dictionary

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.yomitan.InvalidDictionaryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictionaryImporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var importer: DictionaryImporter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        importer = DictionaryImporter(database, clock = { 1_000L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importsTermsAndTags() = runTest {
        val progress = mutableListOf<ImportProgress>()

        val dictionary = importer.import(writeZip(temp.root, sampleDictionary())) { progress += it }

        assertEquals("Test Dictionary", dictionary.title)
        assertEquals(DictionaryEntity.Status.READY, dictionary.status)
        assertEquals(4, dictionary.termCount)
        assertEquals(1_000L, dictionary.importedAt)
        assertEquals(listOf(dictionary), database.dictionaryDao().observeReady().first())

        val terms = database.termDao().allForDictionary(dictionary.id)
        assertEquals(listOf("猫", "食べる", "ねこ", "読む"), terms.map { it.expression })
        assertEquals(listOf("ねこ", "たべる", "ねこ", "よむ"), terms.map { it.reading })
        val preset = database.termDao().glossaryPreset(dictionary.id)
        assertEquals("""["to eat"]""", GlossaryPacker.unpack(terms[1].glossary, preset))
        assertEquals("v1", terms[1].rules)

        val tags = database.tagDao().allForDictionary(dictionary.id)
        assertEquals(listOf("P", "n"), tags.map { it.name })

        // The first bank is reported before the second: 0%, part way, 100%.
        assertEquals(0, progress.first().percent)
        assertEquals(100, progress.last().percent)
        assertTrue(progress.size >= 3)
        assertEquals(progress.map { it.percent }.sorted(), progress.map { it.percent })
        assertTrue(progress.all { it.title == "Test Dictionary" })
    }

    @Test
    fun sameTitleIsRejected_andTheInstalledDictionaryIsKept() = runTest {
        val first = importer.import(writeZip(temp.root, sampleDictionary(), "a.zip"))

        try {
            importer.import(writeZip(temp.root, sampleDictionary(), "b.zip"))
            fail("expected DictionaryAlreadyInstalledException")
        } catch (e: DictionaryAlreadyInstalledException) {
            assertEquals("Test Dictionary", e.title)
        }

        assertEquals(4, database.termDao().countForDictionary(first.id))
    }

    @Test
    fun aNewDictionaryGoesLast() = runTest {
        val first = importer.import(writeZip(temp.root, sampleDictionary("B first"), "a.zip"))
        val second = importer.import(writeZip(temp.root, sampleDictionary("A second"), "b.zip"))

        assertEquals(listOf(0, 1), listOf(first.priority, second.priority))
        assertEquals(listOf("B first", "A second"), database.dictionaryDao().observeReady().first().map { it.title })
    }

    @Test
    fun failedImportLeavesNothingBehind() = runTest {
        val files = sampleDictionary() + ("term_bank_2.json" to """[["壊","こわ","","",0,["broken"]]]""")

        try {
            importer.import(writeZip(temp.root, files))
            fail("expected InvalidDictionaryException")
        } catch (e: InvalidDictionaryException) {
            assertEquals("term_bank_2.json, entry 1: expected 8 fields, found 6", e.message)
        }

        assertNull(database.dictionaryDao().findByTitle("Test Dictionary"))
        assertTrue(database.dictionaryDao().observeReady().first().isEmpty())
        val terms = database.query("SELECT COUNT(*) FROM term", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, terms)
    }

    @Test
    fun frequencyAndPitchDictionariesAreImported() = runTest {
        val files = mapOf(
            "index.json" to index("Jiten", extra = ""","frequencyMode":"rank-based""""),
            "term_meta_bank_1.json" to """[
                ["猫","freq",{"value":1200,"displayValue":"1200㋕"}],
                ["乃","freq",{"reading":"の","frequency":{"value":1,"displayValue":"1㋕"}}],
                ["猫","pitch",{"reading":"ねこ","pitches":[{"position":1}]}],
                ["猫","ipa",{"reading":"ねこ","transcriptions":[]}]
            ]""",
        )
        val progress = mutableListOf<ImportProgress>()

        val dictionary = importer.import(writeZip(temp.root, files)) { progress += it }

        assertEquals(0, dictionary.termCount)
        assertEquals(3, dictionary.metaCount)
        assertEquals("rank-based", dictionary.frequencyMode)
        val rows = database.termMetaDao().find(listOf("猫", "乃")).sortedBy { it.id }
        assertEquals(
            listOf(
                Triple("猫", null, "1200㋕"),
                Triple("乃", "の", "1㋕"),
            ),
            rows.filter { it.kind == TermMetaEntity.Kind.FREQUENCY }.map { Triple(it.expression, it.reading, it.display) },
        )
        assertEquals(1.0, rows[1].value!!, 0.0)
        val pitch = rows.single { it.kind == TermMetaEntity.Kind.PITCH }
        assertEquals("ねこ" to "1", pitch.reading to pitch.pitches)
        assertEquals(listOf(0, 100), progress.map { it.percent }.let { listOf(it.first(), it.last()) })
    }

    @Test
    fun kanjiumAccentsAreImportedFromTheirTextList() = runTest {
        val file = temp.newFile("accents.txt").apply {
            writeText("１\tひと\t2\nああ\t\t0\n２つ\tふたつ\t(副)0,(名)3\n壊れた行\n")
        }

        val dictionary = importer.import(file, DictionaryFileFormat.KANJIUM_ACCENTS)

        assertEquals(KanjiumAccents.TITLE, dictionary.title)
        assertEquals(3, dictionary.metaCount)
        val rows = database.termMetaDao().find(listOf("ああ", "２つ")).associateBy { it.expression }
        assertEquals("ああ" to "0", rows.getValue("ああ").reading to rows.getValue("ああ").pitches)
        assertEquals("ふたつ" to "0,3", rows.getValue("２つ").reading to rows.getValue("２つ").pitches)
    }

    @Test
    fun dictionaryWithoutTermsIsRejected() = runTest {
        val tagsOnly = mapOf(
            "index.json" to index("Tags"),
            "tag_bank_1.json" to """[["n","partOfSpeech",-3,"noun",0]]""",
        )

        try {
            importer.import(writeZip(temp.root, tagsOnly))
            fail("expected NoTermsException")
        } catch (e: NoTermsException) {
            assertEquals("Tags", e.title)
        }
        assertNull(database.dictionaryDao().findByTitle("Tags"))
    }

    @Test
    fun kanjiDictionariesAreImported() = runTest {
        val kanjidic = mapOf(
            "index.json" to index("KANJIDIC [2026-289]"),
            "kanji_bank_1.json" to """[
                ["食","ショク ジキ","く.う た.べる","jouyou",["eat","food"],{"strokes":"9","grade":"2","freq":"328"}],
                ["猫","ビョウ","ねこ","",["cat"],{}]
            ]""",
            "tag_bank_1.json" to """[["strokes","misc",0,"Stroke count",0]]""",
        )

        val dictionary = importer.import(writeZip(temp.root, kanjidic))

        assertEquals(2, dictionary.kanjiCount)
        assertEquals(0, dictionary.termCount)
        val rows = database.kanjiDao().find(listOf("食"))
        assertEquals(1, rows.size)
        assertEquals("ショク ジキ", rows[0].onyomi)
        assertEquals("く.う た.べる", rows[0].kunyomi)
        assertEquals("""["eat","food"]""", rows[0].meanings)
        assertEquals("""{"strokes":"9","grade":"2","freq":"328"}""", rows[0].stats)
        assertEquals(2, database.dictionaryDao().findByTitle("KANJIDIC [2026-289]")?.kanjiCount)
    }

    @Test
    fun malformedKanjiAreReported() = runTest {
        val broken = mapOf(
            "index.json" to index("KANJIDIC"),
            "kanji_bank_1.json" to """[["食","ショク","た.べる","jouyou","eat",{}]]""",
        )

        try {
            importer.import(writeZip(temp.root, broken))
            fail("expected InvalidDictionaryException")
        } catch (e: InvalidDictionaryException) {
            assertEquals("kanji_bank_1.json, entry 1: field 5 is not an array", e.message)
        }
    }

    @Test
    fun anUpdateTakesThePlaceOfTheInstalledVersion() = runTest {
        val dao = database.dictionaryDao()
        val old = importer.import(writeZip(temp.root, sampleDictionary(), "old.zip"))
        dao.setEnabledAndPriority(old.id, enabled = false, priority = 3)
        // Same title, as Jiten keeps it; a new revision.
        val newer = sampleDictionary() + ("index.json" to index(extra = ""","isUpdatable":true""").replace("2026.10", "2026.11"))

        val updated = importer.import(writeZip(temp.root, newer, "new.zip"), replaces = old.id)

        assertEquals("2026.11", updated.revision)
        assertNull(dao.findById(old.id))
        assertEquals(0, database.termDao().countForDictionary(old.id))
        val installed = dao.observeReady().first().single()
        assertEquals(updated.id, installed.id)
        assertEquals(false, installed.enabled)
        assertEquals(3, installed.priority)
        assertEquals(4, database.termDao().countForDictionary(updated.id))
    }

    @Test
    fun aFailedUpdateKeepsTheInstalledVersion() = runTest {
        val old = importer.import(writeZip(temp.root, sampleDictionary(), "old.zip"))
        val broken = sampleDictionary() + ("term_bank_2.json" to """[["壊","こわ","","",0,["broken"]]]""")

        try {
            importer.import(writeZip(temp.root, broken, "new.zip"), replaces = old.id)
            fail("expected InvalidDictionaryException")
        } catch (_: InvalidDictionaryException) {
        }

        assertEquals(listOf(old), database.dictionaryDao().observeReady().first())
        assertEquals(4, database.termDao().countForDictionary(old.id))
    }

    @Test
    fun leftoversOfAnInterruptedImportAreRemoved() = runTest {
        database.dictionaryDao().insert(
            DictionaryEntity(
                title = "Test Dictionary",
                revision = "old",
                formatVersion = 3,
                importedAt = 0,
                status = DictionaryEntity.Status.IMPORTING,
            ),
        )

        val dictionary = importer.import(writeZip(temp.root, sampleDictionary()))

        assertEquals("2026.10", dictionary.revision)
        assertEquals(listOf(dictionary), database.dictionaryDao().observeReady().first())
    }

    /** [count] terms "w<first>".."w<first+count-1>" with sequence = the number, as one term bank. */
    private fun bank(first: Int, count: Int) =
        (first until first + count).joinToString(",", "[", "]") { """["w$it","r$it","","",$it,["gloss $it"],$it,""]""" }

    @Test
    fun rowsKeepFileOrder_acrossManyBanksAndChunks() = runTest {
        // More banks than readers run at once; bank 10 sorts after bank 9; bank 3 has more than one chunk.
        val sizes = mapOf(1 to 3, 2 to 1, 3 to 5_001, 4 to 2, 5 to 4, 6 to 1, 7 to 3, 8 to 2, 9 to 2, 10 to 3)
        var first = 0
        val files = mutableMapOf("index.json" to index())
        for ((n, size) in sizes) {
            files["term_bank_$n.json"] = bank(first, size)
            first += size
        }

        val dictionary = importer.import(writeZip(temp.root, files))

        assertEquals(first, dictionary.termCount)
        val terms = database.termDao().allForDictionary(dictionary.id)
        assertEquals((0 until first).map { "w$it" }, terms.map { it.expression })
        val preset = database.termDao().glossaryPreset(dictionary.id)
        assertEquals(listOf("[\"gloss 0\"]", "[\"gloss 1\"]"), listOf(0, 1).map { GlossaryPacker.unpack(terms[it].glossary, preset) })
        assertEquals("""["gloss ${first - 1}"]""", GlossaryPacker.unpack(terms.last().glossary, preset))
    }

    @Test
    fun theFirstErrorInFileOrderIsReported_andNothingIsLeft() = runTest {
        val files = mutableMapOf("index.json" to index())
        for (n in 1..8) files["term_bank_$n.json"] = bank(n * 10, 3)
        files["term_bank_7.json"] = """[["x","","","",0,["a"]]]"""
        files["term_bank_4.json"] = """[["y","","","",0,["a"]]]"""

        try {
            importer.import(writeZip(temp.root, files))
            fail("expected InvalidDictionaryException")
        } catch (e: InvalidDictionaryException) {
            assertEquals("term_bank_4.json, entry 1: expected 8 fields, found 6", e.message)
        }

        assertNull(database.dictionaryDao().findByTitle("Test Dictionary"))
        for (table in listOf("dictionary", "term", "tag", "glossary_preset")) {
            val rows = database.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
            assertEquals(table, 0, rows)
        }
    }

    @Test
    fun anEmptyFirstBankIsSkippedWhenChoosingThePreset() = runTest {
        val files = mapOf(
            "index.json" to index(),
            "term_bank_1.json" to "[]",
            "term_bank_2.json" to bank(0, 3),
            "term_bank_3.json" to bank(3, 3),
        )

        val dictionary = importer.import(writeZip(temp.root, files))

        val terms = database.termDao().allForDictionary(dictionary.id)
        assertEquals((0 until 6).map { "w$it" }, terms.map { it.expression })
        val expected = GlossaryPacker.preset((0 until 3).map { """["gloss $it"]""" })
        assertTrue(expected.contentEquals(database.termDao().glossaryPreset(dictionary.id)))
    }

    @Test
    fun aCancelledImportLeavesNothingBehind() = runTest {
        val files = mutableMapOf("index.json" to index())
        for (n in 1..12) files["term_bank_$n.json"] = bank(n * 6_000, 6_000)
        val zip = writeZip(temp.root, files)

        val job = launch(Dispatchers.Default) { importer.import(zip) }
        // Cancel as soon as something has been written, so the cancel lands mid-import.
        withContext(Dispatchers.Default) { withTimeout(60_000) {
            while (database.query("SELECT COUNT(*) FROM term", null).use { it.moveToFirst(); it.getInt(0) } == 0) delay(5)
        } }
        job.cancelAndJoin()

        assertNull(database.dictionaryDao().findByTitle("Test Dictionary"))
        assertEquals(0, database.query("SELECT COUNT(*) FROM term", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, database.query("SELECT COUNT(*) FROM dictionary", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
