package app.honmani.reader.data.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.export.RefoldExport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingDaoTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var dao: ReadingDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.readingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun add(book: Long, day: String, seconds: Long, characters: Long) {
        dao.insertIfMissing(ReadingDayEntity(book, day))
        dao.add(book, day, seconds, characters)
    }

    @Test
    fun readingAddsUpPerBookAndDay() = runTest {
        add(1, "2026-10-17", 60, 0)
        add(1, "2026-10-17", 30, 500)
        add(1, "2026-10-16", 120, 1_000)
        add(2, "2026-10-17", 10, 0)

        assertEquals(
            listOf(ReadingDayEntity(1, "2026-10-16", 120, 1_000), ReadingDayEntity(1, "2026-10-17", 90, 500)),
            dao.observeBook(1).first(),
        )
        assertEquals(3, dao.observeAll().first().size)
    }

    @Test
    fun theRefoldMarkIsSetPerDayAndBookAndNeverLowered() = runTest {
        add(1, "2026-10-17", 60, 0)
        add(1, "2026-10-18", 30, 0)
        add(2, "2026-10-17", 10, 0)

        dao.markRefoldExported(listOf(RefoldExport.Mark(1, "2026-10-17", 60), RefoldExport.Mark(2, "2026-10-17", 10)))
        dao.markRefoldExported(listOf(RefoldExport.Mark(1, "2026-10-17", 20)))

        val marks = dao.observeAll().first().associate { (it.bookId to it.day) to it.refoldSeconds }
        assertEquals(mapOf((1L to "2026-10-17") to 60L, (1L to "2026-10-18") to 0L, (2L to "2026-10-17") to 10L), marks)
        // Reading added afterwards leaves the mark where it was.
        dao.add(1, "2026-10-17", 5, 0)
        assertEquals(60L, dao.observeBook(1).first().first().refoldSeconds)
    }
}
