package app.honmani.reader.data.review

import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.WordScheduler
import java.time.Instant
import java.time.ZoneId

/**
 * When reading and lookups count as evidence about a word's memory, and how a grade changes a
 * card. Pure functions of the card and the clock, so the rules are testable at any time and zone.
 * Days are local calendar days of [ZoneId], so a day is 23 or 25 hours long when the clock changes.
 *
 * Evidence: reading a due-ish word without looking it up is a pass with a small gain (Hard);
 * looking it up in the reader is a lapse (Again). Both only touch cards of Honmani's own
 * scheduler that are in review, not buried; reading needs one real passed review first.
 */
object ReviewRules {
    /** A card is "due-ish" for evidence once recall has fallen to this. */
    const val DUE_ISH = 0.9

    const val DAY_MS = 86_400_000L
    const val AGAIN_DELAY_MS = 10 * 60_000L
    const val DEFAULT_KNOWN_AFTER_DAYS = 21

    fun retrievability(card: ReviewCardEntity, now: Long): Double {
        val last = card.lastReviewAt ?: return 1.0
        if (card.stability <= 0) return 1.0
        return Fsrs.retrievability(card.stability, (now - last) / DAY_MS.toDouble())
    }

    /**
     * [card] after a [grade] at [now]. A [real] review (review screen) is logged by the caller,
     * counts towards [ReviewCardEntity.passedReviews] and unburies; evidence doesn't. An Again
     * [inSession] comes back in 10 minutes, otherwise tomorrow; any other grade comes after the
     * FSRS interval (at least a day).
     */
    fun grade(
        card: ReviewCardEntity,
        grade: Grade,
        now: Long,
        zone: ZoneId,
        real: Boolean,
        inSession: Boolean = false,
    ): ReviewCardEntity {
        val reviewed = card.stability > 0 && card.lastReviewAt != null
        val memory = if (reviewed) {
            Fsrs.next(
                Memory(card.stability, card.difficulty),
                grade,
                (now - card.lastReviewAt!!) / DAY_MS.toDouble(),
            )
        } else {
            Fsrs.first(grade)
        }
        val due = if (grade == Grade.AGAIN) {
            if (inSession) now + AGAIN_DELAY_MS else startOfDay(now, zone, 1)
        } else {
            startOfDay(now, zone, Fsrs.interval(memory.stability))
        }
        return card.copy(
            state = if (card.state == ReviewState.SUSPENDED) card.state else ReviewState.REVIEW,
            due = due,
            stability = memory.stability,
            difficulty = memory.difficulty,
            reps = card.reps + 1,
            lapses = card.lapses + if (grade == Grade.AGAIN && reviewed) 1 else 0,
            passedReviews = card.passedReviews + if (real && grade.passed) 1 else 0,
            reviewStability = if (!real) card.reviewStability else if (grade.passed) memory.stability else 0.0,
            lastGrade = grade.value,
            lastReviewAt = now,
            buriedUntil = if (real) null else card.buriedUntil,
        )
    }

    /**
     * Reading [card]'s word in a book without a lookup: the card after a Hard, or null if it
     * doesn't count. [readToday]: a read was already counted for the word on this local day.
     */
    fun onRead(card: ReviewCardEntity, scheduler: WordScheduler, now: Long, zone: ZoneId, readToday: Boolean): ReviewCardEntity? {
        if (!active(card, scheduler, now)) return null
        if (card.passedReviews < 1 || readToday) return null
        if (retrievability(card, now) > DUE_ISH) return null
        return grade(card, Grade.HARD, now, zone, real = false)
    }

    /**
     * A lookup of [card]'s word: the card after an Again (due tomorrow), or null if it doesn't
     * count: not from the reader, not due-ish, added within a day, or already lapsed today.
     */
    fun onLookup(card: ReviewCardEntity, scheduler: WordScheduler, origin: LookupOrigin, now: Long, zone: ZoneId): ReviewCardEntity? {
        if (origin != LookupOrigin.READER || !active(card, scheduler, now)) return null
        if (now - card.addedAt <= DAY_MS) return null
        if (retrievability(card, now) > DUE_ISH) return null
        if (lapsedToday(card, now, zone)) return null
        return grade(card, Grade.AGAIN, now, zone, real = false)
    }

    /** Whether a lookup already counted as a lapse today (the latest grading was an Again today). */
    fun lapsedToday(card: ReviewCardEntity, now: Long, zone: ZoneId): Boolean {
        val last = card.lastReviewAt ?: return false
        return card.lastGrade == Grade.AGAIN.value && localDay(last, zone) == localDay(now, zone)
    }

    /** In review, Honmani's own, and not buried: the only cards evidence reaches. */
    private fun active(card: ReviewCardEntity, scheduler: WordScheduler, now: Long) =
        card.state == ReviewState.REVIEW && scheduler == WordScheduler.HONMANI && !isBuried(card, now)

    /** How far [card] is towards known by memory, 0 to 1: its stability against [knownAfterDays]. */
    fun memory(card: ReviewCardEntity, knownAfterDays: Int = DEFAULT_KNOWN_AFTER_DAYS): Float =
        if (isKnownByMemory(card, knownAfterDays)) 1f else (card.stability / knownAfterDays).toFloat().coerceIn(0f, 0.99f)

    fun isBuried(card: ReviewCardEntity, now: Long) = (card.buriedUntil ?: 0L) > now

    /**
     * Counts as known by memory: in review, stable for [knownAfterDays] or more, the latest real
     * review passed at that stability already, and not failed since (the latest grading, even from
     * a lookup, passed). Reading can't get a card here, only keep it: it needs a real review at
     * [knownAfterDays] (or [ReviewRepository.setKnown]).
     */
    fun isKnownByMemory(card: ReviewCardEntity, knownAfterDays: Int = DEFAULT_KNOWN_AFTER_DAYS): Boolean =
        card.state == ReviewState.REVIEW &&
            card.stability >= knownAfterDays &&
            card.reviewStability >= knownAfterDays &&
            card.passedReviews >= 1 &&
            card.lastGrade != Grade.AGAIN.value

    /** The next local midnight after [now]. */
    fun buryUntil(now: Long, zone: ZoneId): Long = startOfDay(now, zone, 1)

    fun localDay(time: Long, zone: ZoneId): Long = Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toEpochDay()

    /** Midnight of the local day [plusDays] after the day of [time]. */
    fun startOfDay(time: Long, zone: ZoneId, plusDays: Int = 0): Long =
        Instant.ofEpochMilli(time).atZone(zone).toLocalDate().plusDays(plusDays.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** Due the same day or earlier than the end of the local day of [now]. */
    fun dueCutoff(now: Long, zone: ZoneId): Long = startOfDay(now, zone, 1)
}
