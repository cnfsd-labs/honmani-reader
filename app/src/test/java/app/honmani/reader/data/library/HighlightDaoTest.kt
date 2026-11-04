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
    fun aNewHighlightReplacesTheOnesItOverlaps_inThatBookOnly() = runTest {
        dao.put(mark("a", 0, 10))
        dao.put(mark("a", 10, 20))
        dao.put(mark("a", 30, 40))
        dao.put(mark("b", 12, 18))

        val saved = dao.saveReplacingOverlaps(mark("a", 5, 12, color = 3))

        // [0,10) and [10,20) share characters with [5,12); [30,40) and the other book do not.
        assertEquals(listOf(0L, 10L), saved.replaced.map { it.start })
        assertEquals(listOf(5L, 30L), dao.observe("a").first().map { it.start })
        assertEquals(1, dao.observe("b").first().size)
    }

    @Test
    fun highlightsThatOnlyTouch_areNotReplaced() = runTest {
        dao.put(mark("a", 0, 10))

        val saved = dao.saveReplacingOverlaps(mark("a", 10, 20))

        assertEquals(emptyList<HighlightEntity>(), saved.replaced)
        assertEquals(listOf(0L, 10L), dao.observe("a").first().map { it.start })
    }

    @Test
    fun undoingASave_bringsBackWhatItReplaced_withTheirIds() = runTest {
        dao.put(mark("a", 0, 10, color = 1))
        dao.put(mark("a", 20, 30, color = 2))
        val before = dao.observe("a").first()

        val saved = dao.saveReplacingOverlaps(mark("a", 5, 25, color = 0))
        assertEquals(1, dao.observe("a").first().size)
        dao.undoSave(saved)

        assertEquals(before, dao.observe("a").first())
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
