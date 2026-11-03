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
class BookmarkDaoTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var dao: BookmarkDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.bookmarkDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun mark(hash: String, name: String, characters: Long) =
        BookmarkEntity(contentHash = hash, name = name, characters = characters, total = 1_000, createdAt = 5)

    @Test
    fun aBooksBookmarksComeInReadingOrder_andOnlyThatBooks() = runTest {
        dao.put(mark("a", "late", 800))
        dao.put(mark("a", "early", 100))
        dao.put(mark("b", "other", 50))

        assertEquals(listOf("early", "late"), dao.observe("a").first().map { it.name })
        assertEquals(listOf(10, 80), dao.observe("a").first().map { it.percent })
    }

    @Test
    fun renameKeepsThePlace_andDeleteRemovesOne() = runTest {
        val id = dao.put(mark("a", "one", 100))
        dao.put(mark("a", "two", 200))

        dao.rename(id, "renamed")
        assertEquals(listOf("renamed", "two"), dao.observe("a").first().map { it.name })
        assertEquals(100L, dao.observe("a").first().first().characters)

        dao.delete(id)
        assertEquals(listOf("two"), dao.observe("a").first().map { it.name })
    }

    @Test
    fun undoPutsADeletedBookmarkBackWithItsId() = runTest {
        val id = dao.put(mark("a", "one", 100))
        val saved = dao.observe("a").first().single()

        dao.delete(id)
        dao.put(saved)

        assertEquals(listOf(saved), dao.observe("a").first())
    }

    @Test
    fun removingABookClearsItsBookmarksOnly() = runTest {
        dao.put(mark("a", "one", 100))
        dao.put(mark("b", "two", 100))

        dao.deleteForBook("a")

        assertEquals(emptyList<BookmarkEntity>(), dao.observe("a").first())
        assertEquals(1, dao.observe("b").first().size)
    }
}
