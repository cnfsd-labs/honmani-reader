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
class PronunciationDaoTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var dao: PronunciationDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HonmaniDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = database.pronunciationDao()
    }

    @After
    fun tearDown() = database.close()

    private fun item(hash: String, text: String, reading: String, at: Long = 1) = PronunciationEntity(contentHash = hash, text = text, reading = reading, createdAt = at)

    @Test
    fun aNewReadingForTheSameTextReplacesTheOld_andOnlyThatBookSeesIt() = runTest {
        dao.put(item("a", "雨宮", "あめみや"))
        dao.put(item("a", "雨宮", "うみや", at = 2))
        dao.put(item("b", "雨宮", "x"))
        assertEquals(listOf("うみや"), dao.observe("a").first().map { it.reading })
    }

    @Test
    fun deletingABookRemovesItsList() = runTest {
        dao.put(item("a", "北", "きた"))
        dao.put(item("b", "北", "きた"))
        dao.deleteForBook("a")
        assertEquals(emptyList<PronunciationEntity>(), dao.observe("a").first())
        assertEquals(1, dao.observe("b").first().size)
    }
}
