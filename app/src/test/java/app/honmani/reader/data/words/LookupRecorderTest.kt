package app.honmani.reader.data.words

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.review.Grade
import app.honmani.reader.data.review.ReviewRepository
import app.honmani.reader.data.review.ReviewRules
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LookupRecorderTest {
    @get:org.junit.Rule
    val fixedUpdateClock = app.honmani.reader.data.merge.FixedUpdateClock()

    private val day = ReviewRules.DAY_MS
    private var now = 1_800_000_000_000L
    private lateinit var database: HonmaniDatabase
    private lateinit var words: WordRepository
    private lateinit var reviews: ReviewRepository
    private lateinit var store: WordProgressStore
    private lateinit var recorder: LookupRecorder
    private lateinit var file: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        words = WordRepository(database, now = { now })
        reviews = ReviewRepository(database, now = { now }, zone = { ZoneId.of("Europe/Berlin") })
        file = File.createTempFile("word-progress", ".json").also { it.delete() }
        store = WordProgressStore(file)
        recorder = LookupRecorder(words, reviews, store)
    }

    @After
    fun tearDown() {
        database.close()
        file.delete()
    }

    private fun lookup(expression: String, sentence: String = "${expression}だ。") = LookedUpWord(
        expression = expression, reading = "よみ", gloss = "g", surface = expression, sentence = sentence, surfaceStart = 0,
    )

    private suspend fun events() = words.lookupCount(null).first()

    @Test
    fun aNewWordIsDeletedWithItsLookupEventAndProgress() = runTest {
        val other = recorder.record(lookup("犬"))!!
        val undo = recorder.record(lookup("猫"))!!
        val id = words.find("猫", "よみ")!!.id
        store.save(mapOf(id to WordStep(0, 0, 1, false)))
        assertEquals(2, events())
        recorder.undo(undo)
        assertNull(words.find("猫", "よみ"))
        assertNull(store.load()[id])
        assertEquals(1, events())
        assertNotNull("an older word stays", words.find("犬", "よみ"))
        assertNotNull(other.word)
    }

    @Test
    fun aRepeatedLookupTakesBackOnlyItsOwnChangesAndKeepsTheOlderLookup() = runTest {
        recorder.record(lookup("猫", "猫だ。"))
        val id = words.find("猫", "よみ")!!.id
        words.setStarred(id, true)
        val first = words.find(id)!!
        now += 2 * 60 * 60 * 1000
        val undo = recorder.record(lookup("猫", "猫がいる。"))!!
        assertEquals(2, words.find(id)!!.lookupCount)
        assertEquals(2, words.latestLookups(id).size)
        words.setStarred(id, false)
        words.setStarred(id, true)
        recorder.undo(undo)
        val back = words.find(id)!!
        assertEquals(1, back.lookupCount)
        assertEquals(first.rankKey, back.rankKey, 0.0)
        assertEquals(first.lastSentence, back.lastSentence)
        assertEquals(first.lastLookedUpAt, back.lastLookedUpAt)
        assertTrue("the star is not part of the lookup", back.starred)
        assertEquals(listOf("猫だ。"), words.latestLookups(id).map { it.sentence })
        assertEquals(1, events())
    }

    @Test
    fun aSecondTapInTheSameSentenceChangedNothingToTakeBack() = runTest {
        assertNotNull(recorder.record(lookup("猫")))
        now += 60_000
        assertNull(recorder.record(lookup("猫")))
        assertEquals(1, events())
    }

    @Test
    fun aLearnedMarkComesBackAndTheWordProgressIsWhatItWas() = runTest {
        recorder.record(lookup("猫"))
        val id = words.find("猫", "よみ")!!.id
        words.markLearned(id)
        val learned = WordStep(9, 9, 1, true)
        store.save(mapOf(id to learned))
        now += 3 * 60 * 60 * 1000
        val undo = recorder.record(lookup("猫", "猫が鳴く。"))!!
        assertEquals(WordStatus.LEARNING, words.find(id)!!.status)
        // The tracker had already worked the lookup out: known no more, three steps down.
        store.save(mapOf(id to WordStep(6, 9, 2, false)))
        recorder.undo(undo)
        assertEquals(WordStatus.LEARNED, words.find(id)!!.status)
        assertEquals(learned, store.load()[id])
    }

    @Test
    fun aLapsedCardIsPutBack() = runTest {
        recorder.record(lookup("猫"))
        val id = words.find("猫", "よみ")!!.id
        reviews.record(id, Grade.GOOD)
        now += 5 * day
        val before = reviews.card(id)!!
        val undo = recorder.record(lookup("猫", "猫が鳴く。"))!!
        assertEquals(1, reviews.card(id)!!.lapses)
        assertEquals(before, undo.card)
        recorder.undo(undo)
        assertEquals(before, reviews.card(id))
    }

    @Test
    fun aCardThatDidNotLapseIsLeftAlone() = runTest {
        recorder.record(lookup("猫"))
        val id = words.find("猫", "よみ")!!.id
        reviews.record(id, Grade.GOOD)
        val before = reviews.card(id)!!
        now += 3 * 60 * 60 * 1000
        val undo = recorder.record(lookup("猫", "猫が鳴く。"))!!
        assertNull("not due-ish yet", undo.card)
        recorder.undo(undo)
        assertEquals(before, reviews.card(id))
    }

    @Test
    fun theOldestLookupPushedOutOfTheKeptFewComesBack() = runTest {
        repeat(WordRepository.KEPT_LOOKUPS) {
            now += 2 * 60 * 60 * 1000
            recorder.record(lookup("猫", "猫の$it。"))
        }
        val id = words.find("猫", "よみ")!!.id
        val before = words.latestLookups(id).map { it.sentence }
        assertEquals(WordRepository.KEPT_LOOKUPS, before.size)
        now += 2 * 60 * 60 * 1000
        val undo = recorder.record(lookup("猫", "猫の最後。"))!!
        assertTrue("猫の0。" !in words.latestLookups(id).map { it.sentence })
        recorder.undo(undo)
        assertEquals(before, words.latestLookups(id).map { it.sentence })
        assertEquals(WordRepository.KEPT_LOOKUPS, words.find(id)!!.lookupCount)
    }
}
