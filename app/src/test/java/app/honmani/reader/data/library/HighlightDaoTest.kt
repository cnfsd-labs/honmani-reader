package app.honmani.reader.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HighlightDaoTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var dao: HighlightDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.highlightDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun mark(hash: String, start: Long, end: Long, color: Int = 0) =
        HighlightEntity(contentHash = hash, start = start, end = end, color = color, text = "t$start", total = 1_000, createdAt = 5)

    @Test
    fun aBooksHighlightsComeInReadingOrder_andOnlyThatBooks() = runTest {
        dao.put(mark("a", 800, 810))
        dao.put(mark("a", 100, 130))
        dao.put(mark("b", 50, 60))

        val marks = dao.observe("a").first()
        assertEquals(listOf(100L, 800L), marks.map { it.start })
        assertEquals(listOf(10, 80), marks.map { it.percent })
    }

    @Test
    fun changingTheColourKeepsThePassage_andDeleteRemovesOne() = runTest {
        val id = dao.put(mark("a", 100, 120))
        dao.put(mark("a", 200, 220))

        dao.setColor(id, 3)
        val first = dao.observe("a").first().first()
        assertEquals(3, first.color)
        assertEquals(100L to 120L, first.start to first.end)

        dao.delete(id)
        assertEquals(listOf(200L), dao.observe("a").first().map { it.start })
    }

    @Test
    fun undoPutsADeletedHighlightBackWithItsId() = runTest {
        val id = dao.put(mark("a", 100, 120, color = 2))
        val saved = dao.observe("a").first().single()

        dao.delete(id)
        dao.put(saved)

        assertEquals(listOf(saved), dao.observe("a").first())
    }

    @Test
    fun removingABookClearsItsHighlightsOnly() = runTest {
        dao.put(mark("a", 100, 120))
        dao.put(mark("b", 100, 120))

        dao.deleteForBook("a")

        assertEquals(emptyList<HighlightEntity>(), dao.observe("a").first())
        assertEquals(1, dao.observe("b").first().size)
    }
}
