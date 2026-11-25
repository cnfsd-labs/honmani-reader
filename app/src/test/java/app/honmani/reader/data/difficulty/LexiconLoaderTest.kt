package app.honmani.reader.data.difficulty

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.TermEntity
import app.honmani.reader.data.dictionary.TermMetaEntity
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LexiconLoaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: HonmaniDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val terms = DictionaryEntity(id = 1, title = "Words", revision = "1", formatVersion = 3, priority = 0, importedAt = 0)
    private val ranks = DictionaryEntity(id = 2, title = "Jiten", revision = "1", formatVersion = 3, priority = 1, importedAt = 0)

    private fun term(expression: String, reading: String, rules: String) = TermEntity(
        dictionaryId = 1, expression = expression, reading = reading, definitionTags = null,
        rules = rules, score = 0.0, glossary = Glossary.plain("[]"), sequence = 0, termTags = null,
    )

    private fun rank(expression: String, reading: String?, value: Double, display: String?) = TermMetaEntity(
        dictionaryId = 2, expression = expression, reading = reading, kind = TermMetaEntity.Kind.FREQUENCY,
        value = value, display = display, pitches = null,
    )

    /**
     * The app keeps the lexicon it built from the same dictionaries across updates. When this
     * fails because the loader is meant to give something else now, raise
     * [LexiconLoader.VERSION] and put the new pair here.
     */
    @Test
    fun theSameDictionariesGiveTheLexiconOfThisVersion() = runTest {
        database.dictionaryDao().insert(terms)
        database.dictionaryDao().insert(ranks)
        database.termDao().insertAll(
            listOf(
                term("食べる", "たべる", "v1"), term("書く", "かく", "v5"), term("来る", "くる", "vk"),
                term("勉強", "べんきょう", "vs"), term("信ずる", "しんずる", "vz"), term("高い", "たかい", "adj-i"),
                term("ねこ", "ねこ", ""), term("或る", "ある", ""), term("ある", "", "v5"), term("二", "に", "v1 vs x"),
            ),
        )
        database.termMetaDao().insertAll(
            listOf(
                rank("食べる", "たべる", 300.0, "300"), rank("或る", "ある", 615.0, "615㋕"),
                rank("或る", "ある", 53_486.0, "53486"), rank("書く", null, 200.5, null), rank("新語", "", 900.0, null),
            ),
        )

        val file = folder.newFile()
        LexiconLoader(database.difficultyDao()).load(LexiconSources(listOf(terms), ranks)).write(file, "")
        val checksum = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        assertEquals(1 to "0a19d5fe7fadb6dbed3f6083a3749022c19027362a133e5973aaf90dd959c8e2", LexiconLoader.VERSION to checksum)
    }

    @Test
    fun aDictionaryImportedAgainGivesAnotherKey() {
        val sources = LexiconSources(listOf(terms), ranks)

        assertNotEquals(sources.lexiconKey, LexiconSources(listOf(terms.copy(id = 3)), ranks).lexiconKey)
        assertNotEquals(sources.lexiconKey, LexiconSources(listOf(terms), ranks.copy(id = 3)).lexiconKey)
        assertEquals(sources.lexiconKey, LexiconSources(listOf(terms), ranks).lexiconKey)
    }
}
