package app.honmani.reader.data.library

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookDaoTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.bookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun book(hash: String, addedAt: Long) = BookEntity(
        contentHash = hash,
        title = hash,
        format = BookFormat.EPUB,
        fileName = "$hash.epub",
        addedAt = addedAt,
    )

    @Test
    fun observeAll_recentlyOpenedFirst_thenRecentlyAdded() = runTest {
        val old = dao.insert(book("old", addedAt = 1))
        val new = dao.insert(book("new", addedAt = 2))
        val opened = dao.insert(book("opened", addedAt = 0))
        dao.setLastOpened(opened, 5)

        assertEquals(listOf(opened, new, old), dao.observeAll().first().map { it.id })
    }

    @Test(expected = SQLiteConstraintException::class)
    fun contentHashIsUnique() = runTest {
        dao.insert(book("same", addedAt = 1))
        dao.insert(book("same", addedAt = 2))
    }

    @Test
    fun progressIsSetByTtuId() = runTest {
        val id = dao.insert(book("a", addedAt = 1))
        dao.setTtuBook(id, ttuId = 7, ttuTitle = "A")

        dao.setProgressByTtuId(7, 0.5f)

        assertEquals(0.5f, dao.find(id)!!.progress)
    }

    @Test
    fun progressIsSetForSeveralBooksAtOnce_andUnchangedOnesAreLeftAlone() = runTest {
        val a = dao.insert(book("a", addedAt = 1))
        val b = dao.insert(book("b", addedAt = 2))
        val c = dao.insert(book("c", addedAt = 3))
        dao.setTtuBook(a, ttuId = 1, ttuTitle = "A")
        dao.setTtuBook(b, ttuId = 2, ttuTitle = "B")
        dao.setProgressByTtuId(2, 0.25f)

        dao.setProgressByTtuIds(mapOf(1L to 0.75f, 2L to 0.25f, 99L to 1f))

        assertEquals(0.75f, dao.find(a)!!.progress)
        assertEquals(0.25f, dao.find(b)!!.progress)
        assertEquals(0f, dao.find(c)!!.progress)
    }

    @Test
    fun ttuIdsExcept_listsTheTtuIdsOfTheOtherBooks() = runTest {
        val a = dao.insert(book("a", addedAt = 1))
        val b = dao.insert(book("b", addedAt = 2))
        dao.insert(book("c", addedAt = 3))
        dao.setTtuBook(a, ttuId = 3, ttuTitle = "same title")
        dao.setTtuBook(b, ttuId = 5, ttuTitle = "same title")

        assertEquals(listOf(5L), dao.ttuIdsExcept(a))
        assertEquals(listOf(3L, 5L), dao.ttuIdsExcept(-1).sorted())
    }

    @Test
    fun forgetTtuBookExcept_clearsOtherBooksWithThatTtuId() = runTest {
        val a = dao.insert(book("a", addedAt = 1))
        val b = dao.insert(book("b", addedAt = 2))
        dao.setTtuBook(a, ttuId = 3, ttuTitle = "same title")
        dao.setTtuBook(b, ttuId = 3, ttuTitle = "same title")

        dao.forgetTtuBookExcept(ttuId = 3, keepId = b)

        assertNull(dao.find(a)!!.ttuId)
        assertNull(dao.find(a)!!.ttuTitle)
        assertEquals(3L, dao.find(b)!!.ttuId)
    }
}
