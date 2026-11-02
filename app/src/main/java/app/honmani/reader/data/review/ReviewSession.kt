package app.honmani.reader.data.review

import app.honmani.reader.data.words.DeletedWord
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus

/**
 * One go at the review queue: the cards due today, then up to [newPerDay] minus today's first
 * reviews new ones. Every answer is saved at once, so leaving the session keeps what was done.
 * A card graded Again goes to the end of the queue; every action can be taken back, last first.
 * Not thread safe: call it from one coroutine at a time.
 */
class ReviewSession(
    private val reviews: ReviewRepository,
    private val words: WordRepository,
    private val newPerDay: Int,
    private val knownAfterDays: Int = ReviewRules.DEFAULT_KNOWN_AFTER_DAYS,
) {
    private class Action(
        val item: CardWithWord,
        /** The card as it was; restored on undo. */
        val before: ReviewCardEntity,
        /** Set for a grading, which also took a log row. */
        val review: ReviewUndo? = null,
        /** An Again went to the end of the queue. */
        val requeued: Boolean = false,
        /** The word was Learning and got marked known. */
        val wasLearning: Boolean = false,
        val grade: Grade? = null,
        /** The word was removed with its lookups; undo puts it back. */
        val deleted: DeletedWord? = null,
    )

    private val queue = ArrayDeque<CardWithWord>()
    private val actions = ArrayDeque<Action>()

    /** The card to ask, if any is left. */
    val current: CardWithWord? get() = queue.firstOrNull()

    /** Cards still to ask, an Again counted again. */
    val remaining: Int get() = queue.size

    val canUndo: Boolean get() = actions.isNotEmpty()

    /** Gradings in this session that stand (not taken back). */
    val forgotten: Int get() = actions.count { it.grade == Grade.AGAIN }
    val remembered: Int get() = actions.count { it.grade != null && it.grade != Grade.AGAIN }

    /** Builds the queue: due cards (most overdue first), then new cards. */
    suspend fun start() {
        queue.clear()
        actions.clear()
        val due = reviews.dueCards(Int.MAX_VALUE)
        val left = newPerDay - reviews.newWordsToday()
        queue.addAll(due)
        queue.addAll(reviews.introduceNew(left).filter { fresh -> due.none { it.word.id == fresh.word.id } })
    }

    /** Grades the current card; an Again comes back at the end. False if there is none or it can't be graded. */
    suspend fun answer(grade: Grade): Boolean {
        val item = current ?: return false
        queue.removeFirst()
        val undo = reviews.record(item.word.id, grade)
        if (undo == null) return false
        val requeue = grade == Grade.AGAIN
        val after = reviews.card(item.word.id)
        if (requeue) after?.let { queue.addLast(item.copy(card = it)) }
        // A pass that makes the memory long enough moves the word to Known; a later lapse keeps it there.
        val becameKnown = item.word.status == WordStatus.LEARNING && after != null &&
            !ReviewRules.isKnownByMemory(undo.before, knownAfterDays) && ReviewRules.isKnownByMemory(after, knownAfterDays)
        if (becameKnown) words.markLearned(item.word.id)
        actions.addLast(Action(item, undo.before, review = undo, requeued = requeue, wasLearning = becameKnown, grade = grade))
        return true
    }

    /** Hides the current card until tomorrow. */
    suspend fun bury() = leave { reviews.bury(it.word.id) }

    /** "Don't review": the card keeps its memory and leaves the queue. */
    suspend fun suspend() = leave { reviews.suspend(it.word.id) }

    /** Marks the current word known: its card counts as stable and the word moves to Known. */
    suspend fun markKnown() {
        val item = current ?: return
        val learning = item.word.status == WordStatus.LEARNING
        leave(wasLearning = learning) {
            reviews.setKnown(it.word.id, knownAfterDays)
            words.markLearned(it.word.id)
        }
    }

    /** Asks the current card again later in this session, whatever its schedule. */
    suspend fun reviewNow() {
        val item = current ?: return
        queue.removeFirst()
        val before = reviews.card(item.word.id) ?: item.card
        reviews.reviewNow(item.word.id)
        queue.addLast(item)
        actions.addLast(Action(item, before, requeued = true))
    }

    /** Takes the current word off the word list, with its card; [undo] puts both back. False if there is none. */
    suspend fun removeWord(): Boolean {
        val item = current ?: return false
        val before = reviews.card(item.word.id) ?: item.card
        val deleted = words.delete(item.word.id) ?: return false
        queue.removeFirst()
        actions.addLast(Action(item, before, deleted = deleted))
        return true
    }

    /** Replaces the meaning of the current word. */
    suspend fun editMeaning(gloss: String) {
        val item = current ?: return
        words.setGloss(item.word.id, gloss)
        val word: WordEntity = words.find(item.word.id) ?: return
        queue[0] = item.copy(word = word)
    }

    /** Takes back the last action; false if there is none. */
    suspend fun undo(): Boolean {
        val action = actions.removeLastOrNull() ?: return false
        if (action.requeued && queue.isNotEmpty() && queue.last().word.id == action.item.word.id) queue.removeLast()
        action.deleted?.let { words.restore(it) }
        if (action.review != null) reviews.undo(action.review) else reviews.restore(action.before)
        if (action.wasLearning) words.markLearning(action.item.word.id)
        queue.addFirst(action.item.copy(card = action.before))
        return true
    }

    private suspend fun leave(wasLearning: Boolean = false, change: suspend (CardWithWord) -> Unit) {
        val item = current ?: return
        queue.removeFirst()
        val before = reviews.card(item.word.id) ?: item.card
        change(item)
        actions.addLast(Action(item, before, wasLearning = wasLearning))
    }
}
