package app.honmani.reader.data.review

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.LookupRecorder
import app.honmani.reader.data.words.WordProgressStore
import app.honmani.reader.data.words.WordRepository
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PanelReviewTest {
    @get:org.junit.Rule
    val fixedUpdateClock = app.honmani.reader.data.merge.FixedUpdateClock()

    private val day = ReviewRules.DAY_MS
    private val zone = ZoneId.of("Europe/Berlin")
    private var now = 1_800_000_000_000L
    private var enabled = true
    private var perDay = ReviewLimit.NO_LIMIT
    private var filter: ReviewFilter? = null
    private lateinit var database: HonmaniDatabase
    private lateinit var words: WordRepository
    private lateinit var reviews: ReviewRepository
    private lateinit var recorder: LookupRecorder
    private lateinit var panel: PanelReview
    private lateinit var file: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HonmaniDatabase::class.java)
            .allowMainThreadQueries().build()
        words = WordRepository(database, now = { now })
        reviews = ReviewRepository(database, now = { now }, zone = { zone })
        file = File.createTempFile("word-progress", ".json").also { it.delete() }
        recorder = LookupRecorder(words, reviews, WordProgressStore(file))
        panel = PanelReview(reviews, words, { enabled }, { perDay }, { ReviewRules.DEFAULT_KNOWN_AFTER_DAYS }, { filter })
    }

    @After
    fun tearDown() {
        database.close()
        file.delete()
    }

    private fun lookup(expression: String, origin: LookupOrigin) = LookedUpWord(
        expression = expression, reading = "よみ", gloss = "g", surface = expression, sentence = "${expression}だ。", surfaceStart = 0, origin = origin,
    )

    /** A word reviewed once (Good), [days] ago. */
    private suspend fun reviewed(expression: String, days: Int = 30): Long {
        recorder.record(lookup(expression, LookupOrigin.SEARCH))
        val id = words.find(expression, "よみ")!!.id
        now -= days * day
        reviews.record(id, Grade.GOOD)
        now += days * day
        return id
    }

    private suspend fun dueIds() = reviews.dueCards(Int.MAX_VALUE).map { it.word.id }.toSet()

    @Test
    fun isDue_agreesWithTheQueue() = runTest {
        val due = reviewed("犬")
        val fresh = reviewed("猫", days = 0)
        val buried = reviewed("鳥").also { reviews.bury(it) }
        val suspended = reviewed("魚").also { reviews.suspend(it) }
        val ignored = reviewed("馬").also { words.setIgnored(it, true) }
        val retired = reviewed("牛").also { id -> reviews.restore(reviews.card(id)!!.copy(stability = 400.0, due = now - day)) }
        val cutoff = ReviewRules.dueCutoff(now, zone)
        val inMemory = listOf(due, fresh, buried, suspended, ignored, retired)
            .filter { ReviewRules.isDue(reviews.card(it)!!, words.find(it)!!, cutoff, now) }.toSet()
        assertEquals(setOf(due), dueIds())
        assertEquals(dueIds(), inMemory)
    }

    @Test
    fun aWordTheTapLapsedIsStillDue_andGotItGradesTheCardBeforeTheTap() = runTest {
        val id = reviewed("犬")
        val tap = recorder.record(lookup("犬", LookupOrigin.READER))
        assertNotNull(tap?.card)
        val before = tap!!.card!!
        val lapsed = reviews.card(id)!!
        assertFalse("the tap lapsed it out of today's queue", id in dueIds())
        assertTrue(panel.isDue("犬", "よみ", tap))

        val undo = panel.grade("犬", "よみ", gotIt = true, lookup = tap)!!

        val expected = ReviewRules.grade(before, Grade.GOOD, now, zone, real = true, inSession = true)
        assertEquals(expected, reviews.card(id))
        assertEquals(1, reviews.reviewsToday())
        assertEquals(reviews.daysUntil(expected.due), undo.nextInDays)

        panel.undo(undo)
        assertEquals("undo returns to the lapsed card", lapsed, reviews.card(id))
        assertEquals(0, reviews.reviewsToday())
    }

    @Test
    fun forgot_keepsTheSameMemory_andIsDueTomorrow() = runTest {
        val id = reviewed("犬")
        val tap = recorder.record(lookup("犬", LookupOrigin.READER))!!
        val undo = panel.grade("犬", "よみ", gotIt = false, lookup = tap)!!
        val card = reviews.card(id)!!
        val expected = ReviewRules.grade(tap.card!!, Grade.AGAIN, now, zone, real = true, inSession = true)
        assertEquals(expected.stability, card.stability, 0.0)
        assertEquals(expected.difficulty, card.difficulty, 0.0)
        assertEquals(ReviewRules.startOfDay(now, zone, 1), card.due)
        assertEquals(1, undo.nextInDays)
        assertEquals(1, reviews.reviewsToday())
    }

    @Test
    fun notOffered_whenOff_outOfScope_overTheDailyLimit_orNotDue() = runTest {
        reviewed("犬")
        reviewed("猫", days = 0)
        assertFalse("not due yet", panel.isDue("猫", "よみ", null))
        assertTrue(panel.isDue("犬", "よみ", null))
        enabled = false
        assertFalse(panel.isDue("犬", "よみ", null))
        enabled = true
        filter = ReviewFilter { false }
        assertFalse(panel.isDue("犬", "よみ", null))
        filter = null
        // One carried review today uses up a limit of one.
        val other = reviewed("鳥")
        reviews.record(other, Grade.GOOD)
        perDay = 1
        assertFalse(panel.isDue("犬", "よみ", null))
        perDay = 2
        assertTrue(panel.isDue("犬", "よみ", null))
    }
}
