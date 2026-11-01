package app.honmani.reader.data.review

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewRepositoryTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = ReviewRules.DAY_MS
    private var now = LocalDateTime.of(2026, 5, 1, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var database: HonmaniDatabase
    private lateinit var repository: ReviewRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ReviewRepository(database, now = { now }, zone = { zone })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun word(expression: String, scheduler: WordScheduler = WordScheduler.HONMANI): Long =
        database.wordDao().insert(
            WordEntity(
                expression = expression, reading = "よみ", gloss = "g", lookupCount = 1, firstLookedUpAt = now,
                lastLookedUpAt = now, rankKey = 1.0, lastSentence = "", lastSurfaceStart = 0, lastSurfaceEnd = 0,
                scheduler = scheduler,
            ),
        )

    @Test
    fun introduceMakesANewCardOnceAndNotForAnkiWords() = runTest {
        val id = word("猫")
        val anki = word("犬", WordScheduler.ANKI)
        assertTrue(repository.introduce(id))
        assertFalse(repository.introduce(id))
        assertFalse(repository.introduce(anki))
        assertEquals(listOf(id), repository.newCards(10).map { it.word.id })
        assertEquals(0, repository.dueCount())
    }

    @Test
    fun aGoodReviewMakesTheCardDueLaterAndUndoRestoresIt() = runTest {
        val id = word("猫")
        repository.introduce(id)
        val undo = repository.record(id, Grade.GOOD)!!
        val card = repository.card(id)!!
        assertEquals(ReviewState.REVIEW, card.state)
        assertEquals(ReviewRules.startOfDay(now, zone, 3), card.due)
        assertEquals(1, repository.reviewsToday())
        assertEquals(1, repository.newWordsToday())
        assertTrue(repository.newCards(10).isEmpty())

        repository.undo(undo)
        assertEquals(ReviewState.NEW, repository.card(id)!!.state)
        assertEquals(0, repository.reviewsToday())
        assertEquals(1, repository.newCards(10).size)
    }

    @Test
    fun dueCardsComeMostOverdueFirstAndSkipBuriedSuspendedAndAnki() = runTest {
        val a = word("一")
        val b = word("二")
        val c = word("三")
        val d = word("四")
        for (id in listOf(a, b, c, d)) repository.record(id, Grade.GOOD)
        now += 3 * day
        assertEquals(4, repository.dueCount())
        assertEquals(4, repository.observeDueCount().first())
        repository.bury(b)
        repository.suspend(c)
        database.wordDao().setAnkiNote(d, 99)
        assertEquals(listOf(a), repository.dueCards(10).map { it.word.id })
        assertEquals(1, repository.dueCount())

        // Buried until the next local midnight; suspended stays out until unsuspended.
        now = ReviewRules.startOfDay(now, zone, 1)
        assertEquals(setOf(a, b), repository.dueCards(10).map { it.word.id }.toSet())
        repository.unsuspend(c)
        assertEquals(setOf(a, b, c), repository.dueCards(10).map { it.word.id }.toSet())
    }

    @Test
    fun lookingUpAnAnkiWordOrChangingItsNoteMovesTheScheduler() = runTest {
        val id = word("猫")
        database.wordDao().setAnkiNote(id, 5)
        assertEquals(WordScheduler.ANKI, database.wordDao().find(id)!!.scheduler)
        database.wordDao().setAnkiNote(id, null)
        assertEquals(WordScheduler.HONMANI, database.wordDao().find(id)!!.scheduler)
    }

    @Test
    fun readingMovesADueWordOncePerDay() = runTest {
        val id = word("猫")
        repository.record(id, Grade.GOOD)
        now += 2 * day
        assertTrue("not due-ish yet", repository.onRead(listOf(id)).isEmpty())
        now += 3 * day
        assertEquals(listOf(id), repository.onRead(listOf(id)))
        val after = repository.card(id)!!
        assertEquals(Grade.HARD.value, after.lastGrade)
        assertEquals(1, after.passedReviews)
        assertEquals(1, database.reviewDao().readsOn(id, ReviewRules.localDay(now, zone)))
        assertEquals("only one real review in the log", 1, database.reviewDao().reviewsSince(0))
        assertTrue(repository.onRead(listOf(id)).isEmpty())
    }

    @Test
    fun aReaderLookupLapsesADueWordButASearchDoesNot() = runTest {
        val id = word("猫")
        repository.record(id, Grade.GOOD)
        now += 5 * day
        assertFalse(repository.onLookup(id, LookupOrigin.SEARCH))
        assertTrue(repository.onLookup(id, LookupOrigin.READER))
        assertEquals(1, repository.card(id)!!.lapses)
        assertFalse(repository.onLookup(id, LookupOrigin.READER))
        assertFalse(repository.onLookup(id + 1, LookupOrigin.READER))
    }

    @Test
    fun setKnownMakesTheCardKnownAndResetStartsOver() = runTest {
        val id = word("猫")
        assertTrue(repository.setKnown(id))
        assertTrue(ReviewRules.isKnownByMemory(repository.card(id)!!))
        repository.reset(id)
        val card = repository.card(id)!!
        assertEquals(ReviewState.NEW, card.state)
        assertEquals(0, card.reps)
        repository.reviewNow(id)
        repository.remove(id)
        assertNull(repository.card(id))
    }

    @Test
    fun deletingAWordRemovesItsCardAndKeepsTheLog() = runTest {
        val id = word("猫")
        repository.record(id, Grade.GOOD)
        database.wordDao().delete(id)
        assertNull(repository.card(id))
        assertEquals(1, database.reviewDao().reviewsSince(0))
    }
}
