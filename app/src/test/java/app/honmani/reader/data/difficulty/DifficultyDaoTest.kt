package app.honmani.reader.data.difficulty

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.TermEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DifficultyDaoTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var dao: DifficultyDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.difficultyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun term(dictionary: Long, expression: String, sequence: Long) = TermEntity(
        dictionaryId = dictionary, expression = expression, reading = expression, definitionTags = null,
        rules = "", score = 0.0, glossary = Glossary.plain("[]"), sequence = sequence, termTags = null,
    )

    @Test
    fun pagingOneDictionaryAtATime_readsEveryRowOnceInIdOrder() = runTest {
        val a = database.dictionaryDao().insert(DictionaryEntity(title = "A", revision = "1", formatVersion = 3, priority = 0, importedAt = 0))
        val b = database.dictionaryDao().insert(DictionaryEntity(title = "B", revision = "1", formatVersion = 3, priority = 0, importedAt = 0))
        // Interleaved ids: a1 b1 a2 b2 a3.
        database.termDao().insertAll(
            listOf(term(a, "a1", 1), term(b, "b1", 1), term(a, "a2", 2), term(b, "b2", 0), term(a, "a3", 3)),
        )

        suspend fun read(dictionary: Long): List<String> {
            val seen = ArrayList<String>()
            var after = 0L
            while (true) {
                val page = dao.terms(dictionary, after, 2)
                if (page.isEmpty()) break
                page.mapTo(seen) { it.expression }
                after = page.last().id
            }
            return seen
        }

        assertEquals(listOf("a1", "a2", "a3"), read(a))
        assertEquals(listOf("b1", "b2"), read(b))
        // Entries without a sequence (0) are no spelling.
        assertEquals(listOf("b1"), dao.spellings(b, 0, 10).map { it.expression })
    }
}
