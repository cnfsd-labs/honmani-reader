package app.honmani.reader.data.review

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewSessionTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = ReviewRules.DAY_MS
    private var now = LocalDateTime.of(2026, 5, 1, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var database: HonmaniDatabase
    private lateinit var reviews: ReviewRepository
    private lateinit var words: WordRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        reviews = ReviewRepository(database, now = { now }, zone = { zone })
        words = WordRepository(database, now = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Higher [rank] is learned first. */
    private suspend fun word(expression: String, rank: Double = 1.0): Long =
        database.wordDao().insert(
            WordEntity(
                expression = expression, reading = "よみ", gloss = "g", lookupCount = 1, firstLookedUpAt = now,
                lastLookedUpAt = now, rankKey = rank, lastSentence = "", lastSurfaceStart = 0, lastSurfaceEnd = 0,
            ),
        )

    /** A word reviewed once, a month ago: due today. */
    private suspend fun dueWord(expression: String): Long {
        val id = word(expression)
        reviews.introduce(id)
        reviews.record(id, Grade.GOOD)
        now += 40 * day
        return id
    }

    private suspend fun session(newPerDay: Int = 10) = ReviewSession(reviews, words, newPerDay).also { it.start() }

    @Test
    fun dueCardsComeBeforeNewOnes() = runTest {
        val fresh = word("新", rank = 9.0)
        val due = dueWord("旧")
        val session = session()
        assertEquals(2, session.remaining)
        assertEquals(due, session.current!!.word.id)
        session.answer(Grade.GOOD)
        assertEquals(fresh, session.current!!.word.id)
        assertTrue(session.current!!.card.state == ReviewState.NEW)
    }

    @Test
    fun newCardsStopAtTheDailyLimitAcrossSessions() = runTest {
        repeat(5) { word("語$it", rank = it.toDouble()) }
        val first = session(newPerDay = 2)
        assertEquals(2, first.remaining)
        first.answer(Grade.GOOD)
        first.answer(Grade.GOOD)
        assertNull(first.current)
        // Both new words were reviewed today: a second session adds none.
        val second = session(newPerDay = 2)
        assertEquals(0, second.remaining)
        // A bigger limit later the same day lets more in.
        assertEquals(1, session(newPerDay = 3).remaining)
        assertEquals(0, session(newPerDay = 0).remaining)
    }

    @Test
    fun newWordsComeBestRankedFirst() = runTest {
        val low = word("低", rank = 1.0)
        val high = word("高", rank = 5.0)
        val session = session(newPerDay = 5)
        val order = listOf(session.current!!.word.id)
        session.answer(Grade.GOOD)
        assertEquals(listOf(high, low), order + session.current!!.word.id)
    }

    @Test
    fun forgottenCardsComeBackAtTheEnd() = runTest {
        val a = dueWord("一")
        val b = dueWord("二")
        val session = session()
        val first = session.current!!.word.id
        session.answer(Grade.AGAIN)
        assertEquals(2, session.remaining)
        val second = session.current!!.word.id
        assertFalse(second == first)
        session.answer(Grade.GOOD)
        assertEquals(first, session.current!!.word.id)
        assertEquals(setOf(a, b), setOf(first, second))
        assertEquals(1, session.forgotten)
        assertEquals(1, session.remembered)
    }

    @Test
    fun undoTakesBackAnAnswerAndTheLogRow() = runTest {
        val id = dueWord("猫")
        val before = reviews.card(id)!!
        val session = session()
        assertFalse(session.canUndo)
        session.answer(Grade.AGAIN)
        assertEquals(1, session.forgotten)
        assertTrue(session.canUndo)
        assertTrue(session.undo())
        assertEquals(before, reviews.card(id))
        assertEquals(id, session.current!!.word.id)
        assertEquals(1, session.remaining)
        assertEquals(0, session.forgotten)
        assertFalse(session.canUndo)
        assertFalse(session.undo())
        // The grading's log row is gone with it.
        assertEquals(0, reviews.reviewsToday())
    }

    @Test
    fun undoGoesBackStepByStep() = runTest {
        val a = dueWord("一")
        val b = dueWord("二")
        val session = session()
        val first = session.current!!.word.id
        session.answer(Grade.GOOD)
        val second = session.current!!.word.id
        session.answer(Grade.GOOD)
        assertNull(session.current)
        session.undo()
        assertEquals(second, session.current!!.word.id)
        session.undo()
        assertEquals(first, session.current!!.word.id)
        assertEquals(setOf(a, b), setOf(first, second))
        assertEquals(2, session.remaining)
    }

    @Test
    fun buriedCardsLeaveTheQueueUntilTomorrow() = runTest {
        dueWord("猫")
        dueWord("犬")
        val session = session()
        val buried = session.current!!.word.id
        session.bury()
        assertFalse(session.current!!.word.id == buried)
        assertEquals(1, session.remaining)
        assertNotNull(reviews.card(buried)!!.buriedUntil)
        // A new session the same day leaves it out; the next day it is back.
        assertEquals(1, session().remaining)
        now += day
        assertEquals(2, session().remaining)
    }

    @Test
    fun undoingABuryBringsTheCardBack() = runTest {
        dueWord("猫")
        val session = session()
        val id = session.current!!.word.id
        session.bury()
        assertNull(session.current)
        session.undo()
        assertEquals(id, session.current!!.word.id)
        assertNull(reviews.card(id)!!.buriedUntil)
    }

    @Test
    fun suspendedCardsAreNotReviewedAgain() = runTest {
        dueWord("猫")
        val session = session()
        val id = session.current!!.word.id
        session.suspend()
        assertEquals(ReviewState.SUSPENDED, reviews.card(id)!!.state)
        assertEquals(0, session().remaining)
        session.undo()
        assertEquals(ReviewState.REVIEW, reviews.card(id)!!.state)
    }

    @Test
    fun markKnownMovesTheWordToKnownAndUndoBringsItBack() = runTest {
        dueWord("猫")
        val session = session()
        val id = session.current!!.word.id
        session.markKnown()
        assertNull(session.current)
        assertEquals(WordStatus.LEARNED, words.find(id)!!.status)
        session.undo()
        assertEquals(WordStatus.LEARNING, words.find(id)!!.status)
        assertEquals(id, session.current!!.word.id)
    }

    @Test
    fun reviewNowAsksAgainAtTheEndOfTheSession() = runTest {
        dueWord("一")
        dueWord("二")
        val session = session()
        val first = session.current!!.word.id
        session.reviewNow()
        assertEquals(2, session.remaining)
        assertFalse(session.current!!.word.id == first)
        session.undo()
        assertEquals(first, session.current!!.word.id)
        assertEquals(2, session.remaining)
    }

    @Test
    fun removingAWordTakesItsCardAndUndoPutsBoth() = runTest {
        dueWord("猫")
        val session = session()
        val id = session.current!!.word.id
        val card = reviews.card(id)!!
        assertTrue(session.removeWord())
        assertNull(session.current)
        assertNull(words.find(id))
        assertNull(reviews.card(id))
        session.undo()
        assertNotNull(words.find(id))
        assertEquals(card, reviews.card(id))
        assertEquals(id, session.current!!.word.id)
    }

    @Test
    fun editingTheMeaningChangesTheCardOnScreen() = runTest {
        dueWord("猫")
        val session = session()
        session.editMeaning("cat")
        assertEquals("cat", session.current!!.word.gloss)
        assertEquals("cat", words.find(session.current!!.word.id)!!.gloss)
    }

    @Test
    fun aSessionWithNothingDueIsEmpty() = runTest {
        val session = session(newPerDay = 0)
        assertNull(session.current)
        assertEquals(0, session.remaining)
        assertFalse(session.answer(Grade.GOOD))
    }
}
