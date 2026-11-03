package app.honmani.reader.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FinishedBookTest {
    private lateinit var database: HonmaniDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), HonmaniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun book(hash: String, progress: Float = 0f, finishedAt: Long? = null) = BookEntity(
        contentHash = hash,
        title = "Book $hash",
        format = BookFormat.EPUB,
        fileName = "$hash.epub",
        addedAt = 0,
        progress = progress,
        finishedAt = finishedAt,
    )

    @Test
    fun aBookInTheLibraryAndRecordedCountsOnce_andOnlyFinishedOnesCount() {
        val library = listOf(book("a", finishedAt = 500), book("b", progress = 1f), book("c", progress = 0.4f))
        val recorded = listOf(FinishedBookEntity("a", "A", 300), FinishedBookEntity("d", "D", 700))

        val merged = FinishedBooks.merge(library, recorded)

        assertEquals(listOf("a", "b", "d"), merged.map { it.contentHash })
        // The earlier date wins; a book read to the end has none.
        assertEquals(listOf<Long?>(300, null, 700), merged.map { it.finishedAt })
    }

    @Test
    fun recordingKeepsTheFirstEntryAndFillsInAMissingDate() = runTest {
        val dao = database.finishedBookDao()

        dao.record(FinishedBookEntity("a", "A", null))
        dao.record(FinishedBookEntity("a", "A again", 900))
        dao.record(FinishedBookEntity("a", "A later", 1_000))

        val all = dao.observeAll().first()
        assertEquals(listOf(FinishedBookEntity("a", "A", 900)), all)

        dao.forget("a")
        assertEquals(emptyList<FinishedBookEntity>(), dao.observeAll().first())
    }

    @Test
    fun deletingABooksReadingLeavesOtherBooks() = runTest {
        val reading = database.readingDao()
        for (id in listOf(1L, 2L)) {
            reading.insertIfMissing(ReadingDayEntity(id, "2026-11-01"))
            reading.add(id, "2026-11-01", 60, 100)
        }

        reading.deleteBook(1)

        assertEquals(listOf(2L), reading.observeAll().first().map { it.bookId })
    }
}
