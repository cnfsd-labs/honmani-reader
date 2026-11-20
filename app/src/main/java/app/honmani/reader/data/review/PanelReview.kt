package app.honmani.reader.data.review

import app.honmani.reader.data.words.LookupUndo
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus

/** What a review from the lookup panel changed, to take back. [nextInDays]: when the word is due next (1: tomorrow). */
class PanelReviewUndo(val wordId: Long, val review: ReviewUndo, val markedLearned: Boolean, val nextInDays: Int)

/**
 * Reviewing a due word from the lookup panel: a tap on a word the review screen would ask today
 * offers Forgot / Got it there, graded as on the review screen.
 *
 * The tap itself already lapsed the card ([ReviewRules.onLookup]: due tomorrow), so both the due
 * check and the grade use the card as it was before that lookup ([LookupUndo.card]): a deliberate
 * answer replaces the evidence. An Again here is due tomorrow (no session brings it back in ten
 * minutes); the review is logged and counted as any other.
 */
class PanelReview(
    private val reviews: ReviewRepository,
    private val words: WordRepository,
    /** Settings → Flashcards → "Review due words while reading". */
    private val enabled: () -> Boolean,
    private val perDay: () -> Int,
    private val knownAfterDays: () -> Int,
    /** The review screen's scope as a filter ([ReviewFilter]); null for Due. */
    private val scope: suspend () -> ReviewFilter?,
) {
    /** Whether the saved word [expression] read [reading] is due today; [lookup] is what the tap that showed it changed. */
    suspend fun isDue(expression: String, reading: String, lookup: LookupUndo?): Boolean {
        if (!enabled()) return false
        val word = words.find(expression, reading) ?: return false
        val card = before(word.id, lookup) ?: return false
        return reviews.isDueToday(word, card, perDay(), scope)
    }

    /**
     * Grades the word as the review screen's Got it ([gotIt]) or Forgot does, from the card before
     * [lookup]. A Learning word becomes Known when this review makes it known by memory, or when it
     * was Known before the tap moved it back and the reader remembered it. Null if it can't be graded.
     */
    suspend fun grade(expression: String, reading: String, gotIt: Boolean, lookup: LookupUndo?): PanelReviewUndo? {
        val word = words.find(expression, reading) ?: return null
        val before = before(word.id, lookup) ?: return null
        val grade = if (gotIt) Grade.GOOD else Grade.AGAIN
        val review = reviews.record(word.id, grade, inSession = false, from = before) ?: return null
        val after = reviews.card(word.id) ?: return null
        val statusBefore = lookup?.word?.takeIf { it.wordId == word.id }?.before?.status ?: word.status
        val toKnown = word.status == WordStatus.LEARNING &&
            if (statusBefore == WordStatus.LEARNED) grade.passed else ReviewRules.becameKnown(before, after, knownAfterDays())
        if (toKnown) words.markLearned(word.id)
        return PanelReviewUndo(word.id, review, toKnown, reviews.daysUntil(after.due).coerceAtLeast(1))
    }

    /** Takes back [undo]: the card as it was before the review (still lapsed by the tap), the log row, the move to Known. */
    suspend fun undo(undo: PanelReviewUndo) {
        reviews.undo(undo.review)
        if (undo.markedLearned) words.markLearning(undo.wordId)
    }

    private suspend fun before(wordId: Long, lookup: LookupUndo?): ReviewCardEntity? =
        lookup?.card?.takeIf { it.wordId == wordId } ?: reviews.card(wordId)
}
