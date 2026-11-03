package app.honmani.reader.data.review

import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.WordScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRulesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = ReviewRules.DAY_MS
    private val t0 = at(LocalDateTime.of(2026, 5, 1, 12, 0))
    private val honmani = WordScheduler.HONMANI

    private fun at(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()

    /** A card in review, stability 10 days, last reviewed [t0], added 30 days earlier, one passed review. */
    private fun card(
        state: ReviewState = ReviewState.REVIEW,
        passed: Int = 1,
        stability: Double = 10.0,
        lastReviewAt: Long? = t0,
        addedAt: Long = t0 - 30 * day,
        lastGrade: Int? = Grade.GOOD.value,
        buriedUntil: Long? = null,
        reviewStability: Double = stability,
    ) = ReviewCardEntity(
        wordId = 1,
        state = state,
        due = t0 + 10 * day,
        stability = stability,
        difficulty = 5.0,
        reps = 3,
        passedReviews = passed,
        lastGrade = lastGrade,
        lastReviewAt = lastReviewAt,
        buriedUntil = buriedUntil,
        addedAt = addedAt,
        reviewStability = reviewStability,
    )

    // Stability 10: recall is 0.9 exactly 10 days after the review.
    private val dueish = t0 + 10 * day
    private val fresh = t0 + 2 * day

    @Test
    fun readingCountsOnlyWhenDueIshAfterAPassedReview() {
        assertNotNull(ReviewRules.onRead(card(), honmani, dueish, zone, readToday = false))
        assertNull("recall still high", ReviewRules.onRead(card(), honmani, fresh, zone, readToday = false))
        assertNull("no passed review", ReviewRules.onRead(card(passed = 0), honmani, dueish, zone, readToday = false))
        assertNull("already read today", ReviewRules.onRead(card(), honmani, dueish, zone, readToday = true))
    }

    @Test
    fun readingIsInertForSuspendedNewBuriedAndAnkiCards() {
        assertNull(ReviewRules.onRead(card(state = ReviewState.SUSPENDED), honmani, dueish, zone, false))
        assertNull(ReviewRules.onRead(card(state = ReviewState.NEW), honmani, dueish, zone, false))
        assertNull(ReviewRules.onRead(card(buriedUntil = dueish + day), honmani, dueish, zone, false))
        assertNull(ReviewRules.onRead(card(), WordScheduler.ANKI, dueish, zone, false))
        assertNotNull("a bury that has ended", ReviewRules.onRead(card(buriedUntil = dueish - 1), honmani, dueish, zone, false))
    }

    @Test
    fun aReadIsAHardNotARealReview() {
        val after = ReviewRules.onRead(card(), honmani, dueish, zone, false)!!
        assertEquals(Grade.HARD.value, after.lastGrade)
        assertEquals(1, after.passedReviews)
        assertEquals(4, after.reps)
        assertEquals(0, after.lapses)
        assertTrue(after.stability > 10.0)
        assertEquals(dueish, after.lastReviewAt)
        assertTrue(after.due > dueish)
    }

    @Test
    fun lookupsLapseOnlyFromTheReaderOnDueIshReviewCardsOldEnough() {
        val lapse = ReviewRules.onLookup(card(), honmani, LookupOrigin.READER, dueish, zone)
        assertNotNull(lapse)
        assertEquals(Grade.AGAIN.value, lapse!!.lastGrade)
        assertEquals(1, lapse.lapses)
        assertTrue(lapse.stability < 10.0)
        assertEquals(ReviewRules.startOfDay(dueish, zone, 1), lapse.due)

        assertNull(ReviewRules.onLookup(card(), honmani, LookupOrigin.SEARCH, dueish, zone))
        assertNull(ReviewRules.onLookup(card(), honmani, LookupOrigin.REVIEW, dueish, zone))
        assertNull("not due-ish", ReviewRules.onLookup(card(), honmani, LookupOrigin.READER, fresh, zone))
        assertNull("added within a day", ReviewRules.onLookup(card(addedAt = dueish - day / 2), honmani, LookupOrigin.READER, dueish, zone))
        assertNull(ReviewRules.onLookup(card(state = ReviewState.NEW), honmani, LookupOrigin.READER, dueish, zone))
        assertNull(ReviewRules.onLookup(card(state = ReviewState.SUSPENDED), honmani, LookupOrigin.READER, dueish, zone))
        assertNull(ReviewRules.onLookup(card(), WordScheduler.ANKI, LookupOrigin.READER, dueish, zone))
        assertNull(ReviewRules.onLookup(card(buriedUntil = dueish + 1), honmani, LookupOrigin.READER, dueish, zone))
    }

    @Test
    fun aSecondLookupTheSameDayDoesNotLapseAgain() {
        val lapse = ReviewRules.onLookup(card(), honmani, LookupOrigin.READER, dueish, zone)!!
        // Later the same local day, whatever recall is by then.
        val later = lapse.copy(stability = 0.2)
        assertNull(ReviewRules.onLookup(later, honmani, LookupOrigin.READER, dueish + 60_000, zone))
        // The next day it counts again.
        assertNotNull(ReviewRules.onLookup(later, honmani, LookupOrigin.READER, ReviewRules.startOfDay(dueish, zone, 1) + day, zone))
    }

    @Test
    fun knownByMemoryNeedsStabilityARealPassAndNoFailureSince() {
        assertTrue(ReviewRules.isKnownByMemory(card(stability = 21.0)))
        assertFalse(ReviewRules.isKnownByMemory(card(stability = 20.9)))
        assertFalse("reading alone can't do it", ReviewRules.isKnownByMemory(card(stability = 40.0, passed = 0)))
        assertFalse(ReviewRules.isKnownByMemory(card(stability = 40.0, lastGrade = Grade.AGAIN.value)))
        assertFalse(ReviewRules.isKnownByMemory(card(stability = 40.0, state = ReviewState.SUSPENDED)))
        assertTrue(ReviewRules.isKnownByMemory(card(stability = 15.0), knownAfterDays = 14))
        assertTrue(ReviewRules.isKnownByMemory(card(stability = 30.0, lastGrade = Grade.HARD.value)))
    }

    @Test
    fun knownByMemoryNeedsTheLatestRealReviewToPassAtKnownStability() {
        // stability, stability after the latest real review, latest grade, known after: known?
        val table = listOf(
            Row(21.0, 21.0, Grade.GOOD, 21, true),
            Row(40.0, 21.0, Grade.HARD, 21, true), // reads after a known review keep it known
            Row(40.0, 5.0, Grade.HARD, 21, false), // reads after an early review can't make it known
            Row(40.0, 20.9, Grade.HARD, 21, false),
            Row(40.0, 0.0, Grade.HARD, 21, false), // the latest real review failed
            Row(25.0, 15.0, Grade.HARD, 14, true),
            Row(25.0, 15.0, Grade.HARD, 30, false),
            Row(20.0, 30.0, Grade.GOOD, 21, false), // stability dropped since (a lapse, then reads)
            Row(40.0, 40.0, Grade.AGAIN, 21, false),
        )
        for (row in table) {
            val card = card(stability = row.stability, reviewStability = row.reviewStability, lastGrade = row.grade.value)
            assertEquals(row.toString(), row.known, ReviewRules.isKnownByMemory(card, row.knownAfter))
        }
    }

    private data class Row(val stability: Double, val reviewStability: Double, val grade: Grade, val knownAfter: Int, val known: Boolean)

    @Test
    fun onlyRealReviewsSetTheReviewStability() {
        val early = ReviewRules.grade(card(stability = 3.0, lastReviewAt = t0 - 3 * day), Grade.GOOD, t0, zone, real = true)
        assertEquals(early.stability, early.reviewStability, 0.0)
        var read = early
        var time = t0
        // Daily reads of a due-ish word: stability grows, the review stability stays.
        repeat(30) {
            time += 40 * day
            read = ReviewRules.onRead(read, honmani, time, zone, readToday = false) ?: read
        }
        assertTrue(read.stability >= 21)
        assertEquals(early.reviewStability, read.reviewStability, 0.0)
        assertFalse("reading alone after an early review", ReviewRules.isKnownByMemory(read))
        val passed = ReviewRules.grade(read, Grade.GOOD, time + 40 * day, zone, real = true)
        assertTrue(ReviewRules.isKnownByMemory(passed))
        val failed = ReviewRules.grade(passed, Grade.AGAIN, time + 400 * day, zone, real = true)
        assertEquals(0.0, failed.reviewStability, 0.0)
    }

    @Test
    fun aRealReviewCountsAndUnburies() {
        val buried = card(buriedUntil = dueish + day)
        val after = ReviewRules.grade(buried, Grade.GOOD, dueish, zone, real = true)
        assertEquals(2, after.passedReviews)
        assertNull(after.buriedUntil)
        val evidence = ReviewRules.grade(buried, Grade.HARD, dueish, zone, real = false)
        assertEquals(1, evidence.passedReviews)
        assertEquals(buried.buriedUntil, evidence.buriedUntil)
    }

    @Test
    fun aNewCardGoodGetsTheInitialIntervalAndAgainComesBackInTenMinutesInASession() {
        val new = ReviewCardEntity(wordId = 1, due = t0, addedAt = t0)
        val good = ReviewRules.grade(new, Grade.GOOD, t0, zone, real = true)
        assertEquals(ReviewState.REVIEW, good.state)
        assertEquals(ReviewRules.startOfDay(t0, zone, 3), good.due)
        assertEquals(0, good.lapses)

        val again = ReviewRules.grade(new, Grade.AGAIN, t0, zone, real = true, inSession = true)
        assertEquals(t0 + 10 * 60_000, again.due)
        assertEquals("a first Again is not a lapse", 0, again.lapses)
        assertEquals(0, again.passedReviews)

        val tomorrow = ReviewRules.grade(new, Grade.AGAIN, t0, zone, real = true, inSession = false)
        assertEquals(ReviewRules.startOfDay(t0, zone, 1), tomorrow.due)
    }

    @Test
    fun buryLastsUntilTheNextLocalMidnightAcrossDstChanges() {
        val hour = 3_600_000L
        // Europe/Berlin: clocks go forward on 2026-03-29 (a 23 hour day) and back on 2026-10-25 (25 hours).
        val eveningBefore = at(LocalDateTime.of(2026, 3, 28, 23, 0))
        assertEquals(hour, ReviewRules.buryUntil(eveningBefore, zone) - eveningBefore)
        val shortDay = at(LocalDateTime.of(2026, 3, 29, 0, 30))
        assertEquals(at(LocalDateTime.of(2026, 3, 30, 0, 0)), ReviewRules.buryUntil(shortDay, zone))
        assertEquals(22 * hour + hour / 2, ReviewRules.buryUntil(shortDay, zone) - shortDay)
        val longDay = at(LocalDateTime.of(2026, 10, 25, 0, 30))
        assertEquals(at(LocalDateTime.of(2026, 10, 26, 0, 0)), ReviewRules.buryUntil(longDay, zone))
        assertEquals(24 * hour + hour / 2, ReviewRules.buryUntil(longDay, zone) - longDay)

        // A card buried on the short day is back at midnight, not 24 hours later.
        val buried = card(buriedUntil = ReviewRules.buryUntil(shortDay, zone))
        assertTrue(ReviewRules.isBuried(buried, at(LocalDateTime.of(2026, 3, 29, 23, 59))))
        assertFalse(ReviewRules.isBuried(buried, at(LocalDateTime.of(2026, 3, 30, 0, 0))))
        assertEquals(LocalDate.of(2026, 3, 30).toEpochDay(), ReviewRules.localDay(ReviewRules.buryUntil(shortDay, zone), zone))
    }

    @Test
    fun dueTodayMeansBeforeTheNextLocalMidnight() {
        assertEquals(at(LocalDateTime.of(2026, 5, 2, 0, 0)), ReviewRules.dueCutoff(t0, zone))
    }

    @Test
    fun aDayStartingAt4RunsFrom0400To0400() {
        val night = at(LocalDateTime.of(2026, 5, 2, 3, 0))
        val morning = at(LocalDateTime.of(2026, 5, 2, 4, 0))
        // At 03:00 it is still the 1st: due until 04:00 today, the day started at 04:00 yesterday.
        assertEquals(at(LocalDateTime.of(2026, 5, 2, 4, 0)), ReviewRules.dueCutoff(night, zone, 4))
        assertEquals(at(LocalDateTime.of(2026, 5, 1, 4, 0)), ReviewRules.startOfDay(night, zone, startHour = 4))
        assertEquals(at(LocalDateTime.of(2026, 5, 3, 4, 0)), ReviewRules.dueCutoff(morning, zone, 4))
        assertEquals(ReviewRules.localDay(at(LocalDateTime.of(2026, 5, 1, 23, 0)), zone, 4), ReviewRules.localDay(night, zone, 4))
        assertEquals(ReviewRules.localDay(night, zone, 4) + 1, ReviewRules.localDay(morning, zone, 4))
        // The default is midnight, as before.
        assertEquals(at(LocalDateTime.of(2026, 5, 3, 0, 0)), ReviewRules.dueCutoff(night, zone))
    }

    @Test
    fun aLapseAt0200AndALookupAt0330CountAsTheSameDayWithAStartOf4() {
        val reviewed = at(LocalDateTime.of(2026, 5, 2, 2, 0))
        val lapsed = card(lastReviewAt = reviewed, lastGrade = Grade.AGAIN.value)
        assertTrue(ReviewRules.lapsedToday(lapsed, at(LocalDateTime.of(2026, 5, 2, 3, 30)), zone, 4))
        assertFalse(ReviewRules.lapsedToday(lapsed, at(LocalDateTime.of(2026, 5, 2, 4, 30)), zone, 4))
        // At midnight the same two moments are already the same day too, the 4:30 one as well.
        assertTrue(ReviewRules.lapsedToday(lapsed, at(LocalDateTime.of(2026, 5, 2, 4, 30)), zone))
    }
}
