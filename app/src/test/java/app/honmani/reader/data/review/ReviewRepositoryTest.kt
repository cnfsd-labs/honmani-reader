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
import kotlinx.coroutines.flow.flowOf
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
    fun newCardsComeUpInTheOrderOfTheBookBeingRead() = runTest {
        val scores = mapOf("五" to 3.0, "犬" to 1.0)
        val ordered = ReviewRepository(database, now = { now }, zone = { zone }, relevance = { { scores[it] ?: 0.0 } })
        val cat = word("猫")
        word("五")
        word("犬")
        word("鳥")
        assertTrue(ordered.introduce(cat))
        assertEquals(listOf("五", "犬", "猫"), ordered.introduceNew(3).map { it.word.expression })
        // Without a book: introduced first, then the best ranked.
        assertEquals("猫", repository.introduceNew(1).single().word.expression)
    }

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

    /** Five words reviewed one day apart, then 60 days on: all due, the first one the most overdue. */
    private suspend fun fiveDueWords(): List<Long> {
        val ids = listOf("一", "二", "三", "四", "五").map { word(it) }
        for (id in ids) {
            repository.record(id, Grade.GOOD)
            now += day
        }
        now += 60 * day
        return ids
    }

    @Test
    fun theDailyLimitShowsTheMostOverdueAndCountsTodaysReviewsAgainstIt() = runTest {
        val ids = fiveDueWords()
        assertEquals(ids, repository.dueToday(ReviewLimit.NO_LIMIT).map { it.id })
        assertEquals(ids.take(2), repository.dueToday(2).map { it.id })
        assertEquals(ids.take(2), repository.observeDueToday(flowOf(2)).first().map { it.id })
        assertEquals(setOf("一", "二"), repository.dueExpressions(2))
        assertEquals(ids.take(2), repository.dueCardsToday(2).map { it.word.id })
        assertEquals(5, repository.dueCards(Int.MAX_VALUE).size)

        // A card answered today counts: one left of two after one review, none after two.
        repository.record(ids[0], Grade.GOOD)
        assertEquals(listOf(ids[1]), repository.dueToday(2).map { it.id })
        assertEquals(listOf(ids[1]), repository.dueCardsToday(2).map { it.word.id })
        repository.record(ids[1], Grade.GOOD)
        assertEquals(emptyList<Long>(), repository.dueToday(2).map { it.id })
        assertEquals(emptyList<Long>(), repository.dueCardsToday(2).map { it.word.id })
        // A bigger limit shows the rest: the three still due, minus none beyond its room.
        assertEquals(ids.drop(2), repository.dueToday(5).map { it.id })
        assertEquals(ids.slice(2..3), repository.dueToday(4).map { it.id })

        // An Again comes back today whatever the limit; tomorrow the count starts anew and the
        // longest overdue come first.
        repository.record(ids[2], Grade.AGAIN)
        assertEquals(listOf(ids[2]), repository.dueToday(2).map { it.id })
        now = ReviewRules.startOfDay(now, zone, 1)
        assertEquals(listOf(ids[3], ids[4]), repository.dueToday(2).map { it.id })
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
        assertEquals(4, repository.observeDueToday(flowOf(ReviewLimit.NO_LIMIT)).first().size)
        repository.bury(b)
        repository.suspend(c)
        database.wordDao().setAnkiNote(d, 99)
        assertEquals(listOf(a), repository.dueCards(10).map { it.word.id })
        assertEquals(1, repository.dueCount())
        // The due set the book marks: how the due words are written, and only those.
        assertEquals(setOf("一"), repository.dueExpressions(ReviewLimit.NO_LIMIT))
        assertEquals(listOf("一"), repository.observeDueToday(flowOf(ReviewLimit.NO_LIMIT)).first().map { it.expression })

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
    fun wordsMetInABookPassOncePerDayWhenDueIsh() = runTest {
        val cat = word("猫")
        val dog = word("犬")
        val drink = word("飲む")
        val fresh = word("五")
        val anki = word("食べる", WordScheduler.ANKI)
        listOf(cat, dog, drink).forEach { repository.record(it, Grade.GOOD) }
        repository.introduce(fresh)
        now += 2 * day
        val keys = setOf("猫", "犬", "呑む", "五", "食べる", "走る")
        assertTrue("not due-ish yet (R > 0.9)", repository.onReadKeys(keys).isEmpty())
        now += 3 * day
        val dueBefore = repository.card(cat)!!.due
        // 飲む counts through its other spelling 呑む; a new card and an Anki word don't move.
        val passed = repository.onReadKeys(keys) { if (it == "飲む") listOf("呑む") else emptyList() }
        assertEquals(setOf(cat, dog, drink), passed.toSet())
        assertTrue("moves later", repository.card(cat)!!.due > dueBefore)
        assertTrue("once a day", repository.onReadKeys(keys).isEmpty())
        assertTrue(repository.onReadKeys(emptySet()).isEmpty())
        assertEquals(0, repository.card(fresh)!!.reps)
        assertNull(repository.card(anki))
    }

    @Test
    fun aReaderLookupByWordMakesItDueAndReadingCantMakeItKnown() = runTest {
        val id = word("猫")
        repository.record(id, Grade.GOOD)
        now += 5 * day
        assertFalse(repository.onLookup("猫", "よみ", LookupOrigin.REVIEW))
        assertFalse(repository.onLookup("猫", "ねこ", LookupOrigin.READER))
        assertTrue(repository.onLookup("猫", "よみ", LookupOrigin.READER))
        val lapsed = repository.card(id)!!
        assertEquals(Grade.AGAIN.value, lapsed.lastGrade)
        assertEquals(ReviewRules.startOfDay(now, zone, 1), lapsed.due)
        now = lapsed.due
        assertEquals(1, repository.dueCount())
        // Months of reading after one early review: stable, but not known without a review.
        repeat(40) {
            now += 30 * day
            repository.onRead(listOf(id))
        }
        val read = repository.card(id)!!
        assertTrue(read.stability >= ReviewRules.DEFAULT_KNOWN_AFTER_DAYS)
        assertFalse(ReviewRules.isKnownByMemory(read))
        now += 30 * day
        repository.record(id, Grade.GOOD)
        assertTrue(ReviewRules.isKnownByMemory(repository.card(id)!!))
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
    fun aCardStableForAYearIsRetiredUntilALookupLapsesIt() = runTest {
        val id = word("猫")
        val stable = ReviewCardEntity(
            wordId = id, state = ReviewState.REVIEW, due = now - 100 * day, stability = 400.0, difficulty = 5.0, reps = 5,
            passedReviews = 5, lastGrade = Grade.GOOD.value, lastReviewAt = now - 500 * day, reviewStability = 400.0,
            addedAt = now - 900 * day,
        )
        database.reviewDao().upsert(stable)
        val short = word("犬")
        database.reviewDao().upsert(stable.copy(wordId = short, stability = ReviewRules.RETIRE_AFTER_DAYS - 1.0))
        // Only the card just under a year is in the queue, in every query.
        assertEquals(1, repository.dueCount())
        assertEquals(listOf(short), repository.dueCards(10).map { it.word.id })
        assertEquals(listOf(short), repository.dueToday(ReviewLimit.NO_LIMIT).map { it.id })
        assertEquals(setOf("犬"), repository.dueExpressions(ReviewLimit.NO_LIMIT))
        assertEquals(listOf(short), repository.observeDueToday(flowOf(ReviewLimit.NO_LIMIT)).first().map { it.id })
        assertEquals(listOf(short), repository.dueCardsToday(ReviewLimit.NO_LIMIT).map { it.word.id })
        // A reader lookup lapse drops the stability: the card is back the next day.
        assertTrue(repository.onLookup(id, LookupOrigin.READER))
        assertTrue(repository.card(id)!!.stability < ReviewRules.RETIRE_AFTER_DAYS)
        now = repository.card(id)!!.due
        assertEquals(setOf(id, short), repository.dueCards(10).map { it.word.id }.toSet())
        assertEquals(2, repository.dueCount())
        assertEquals(setOf("猫", "犬"), repository.dueExpressions(ReviewLimit.NO_LIMIT))
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

    @Test
    fun theDailyCountsAndLimitRollOverAtTheDayStartHour() = runTest {
        val shifted = ReviewRepository(database, now = { now }, zone = { zone }, startHour = { 4 })
        val plain = ReviewRepository(database, now = { now }, zone = { zone })
        val id = word("猫")
        shifted.introduce(id)
        now = LocalDateTime.of(2026, 5, 1, 23, 0).atZone(zone).toInstant().toEpochMilli()
        shifted.record(id, Grade.GOOD)
        assertEquals(1, shifted.newWordsToday())
        assertEquals(1, shifted.reviewsToday())

        // 03:00 the next morning: still the same day with a start of 4, a new one at midnight.
        now = LocalDateTime.of(2026, 5, 2, 3, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(1, shifted.newWordsToday())
        assertEquals(1, shifted.reviewsToday())
        assertEquals(0, plain.newWordsToday())
        assertEquals(0, plain.reviewsToday())

        now = LocalDateTime.of(2026, 5, 2, 4, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(0, shifted.newWordsToday())
        assertEquals(0, shifted.reviewsToday())
    }

    @Test
    fun cardsBecomeDueAtTheDayStartHour() = runTest {
        val shifted = ReviewRepository(database, now = { now }, zone = { zone }, startHour = { 4 })
        val id = word("猫")
        shifted.introduce(id)
        shifted.record(id, Grade.GOOD) // 12:00 on the 1st: due three days on, at 04:00
        val due = shifted.card(id)!!.due
        assertEquals(LocalDateTime.of(2026, 5, 4, 4, 0).atZone(zone).toInstant().toEpochMilli(), due)

        now = due - 60_000L // 03:59 on the 4th: still the 3rd
        assertEquals(0, shifted.dueCount())
        now = due // 04:00: the 4th has begun
        assertEquals(1, shifted.dueCount())
    }
}
