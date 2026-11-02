package app.honmani.reader.data.review

import androidx.room.withTransaction
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.WordScheduler
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** A new card, or a Learning word that could become one ([card] null). */
internal class NewCandidate(val wordId: Long, val expression: String, val card: CardWithWord?)

/** [candidates] by [score] of their word, highest first; equal ones keep their order. */
internal fun orderNew(candidates: List<NewCandidate>, score: (String) -> Double): List<NewCandidate> =
    candidates.map { it to score(it.expression) }.sortedByDescending { it.second }.map { it.first }

/** What it takes to take back a review: the card as it was and the log row written. */
class ReviewUndo(val before: ReviewCardEntity, val logId: Long)

/**
 * Reviews of saved words: the queue, grading with undo, the controls on a card and the evidence
 * from reading and lookups ([ReviewRules]). No UI yet. A [zone] provider makes "today" follow the
 * device's current zone.
 */
class ReviewRepository(
    private val database: HonmaniDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** How relevant a word (by how it is written) is to the book being read ([Relevance]); null without one. */
    private val relevance: suspend () -> ((String) -> Double)? = { null },
) {
    private val dao = database.reviewDao()
    private val words = database.wordDao()

    /** Cards due today (before the next local midnight), not buried. Observed when subscribed: re-subscribe after midnight. */
    fun observeDueCount(): Flow<Int> = now().let { dao.observeDueCount(ReviewRules.dueCutoff(it, zone()), it) }

    /** How the words with a card due today are written (the book marks them). Re-subscribe after midnight. */
    fun observeDueExpressions(): Flow<Set<String>> =
        now().let { time -> dao.observeDueExpressions(ReviewRules.dueCutoff(time, zone()), time).map { it.toSet() } }

    suspend fun dueExpressions(): Set<String> =
        now().let { dao.dueExpressions(ReviewRules.dueCutoff(it, zone()), it).toSet() }

    suspend fun dueCount(): Int = now().let { dao.dueCount(ReviewRules.dueCutoff(it, zone()), it) }

    /** Due cards, most overdue first. A card graded Again in a session comes back after 10 minutes: ask again then. */
    suspend fun dueCards(limit: Int): List<CardWithWord> =
        now().let { dao.dueCards(ReviewRules.dueCutoff(it, zone()), it, limit) }

    /** Cards introduced but never reviewed, oldest first. */
    suspend fun newCards(limit: Int): List<CardWithWord> = dao.newCards(now(), limit)

    /**
     * New cards a session may show today, for the card in Words: what is left of [newPerDay]
     * after today's first reviews, at most the introduced new cards plus the Learning words that
     * could still be introduced. Like [observeDueCount], re-subscribe after midnight.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeNewAvailable(newPerDay: Flow<Int>): Flow<Int> = newPerDay.flatMapLatest { limit ->
        flow {
            val time = now()
            emitAll(
                combine(
                    dao.observeNewCardCount(time),
                    dao.observeUnintroducedCount(),
                    dao.observeFirstReviewsSince(ReviewRules.startOfDay(time, zone())),
                ) { existing, fresh, done -> minOf(maxOf(limit - done, 0), existing + fresh) },
            )
        }
    }

    /**
     * Up to [limit] new cards for a session: cards introduced but not reviewed yet, oldest
     * first, then Learning words without a card (best ranked first), which are introduced here.
     * With a book being read, the words that come up soonest and most often in it go first
     * ([Relevance]); the others keep that order.
     */
    suspend fun introduceNew(limit: Int, filter: ReviewFilter? = null): List<CardWithWord> {
        if (limit <= 0) return emptyList()
        val score = filter?.score ?: relevance()
        return database.withTransaction {
            if (score == null && filter == null) {
                val existing = dao.newCards(now(), limit)
                val fresh = dao.unintroducedIds(limit - existing.size).mapNotNull { id ->
                    if (introduce(id)) dao.cardWithWord(id) else null
                }
                return@withTransaction existing + fresh
            }
            val candidates = (
                dao.newCards(now(), Int.MAX_VALUE).map { NewCandidate(it.word.id, it.word.expression, it) } +
                    dao.unintroducedWords().filter { filter == null || filter.accepts(it) }.map { NewCandidate(it.id, it.expression, null) }
                ).filter { filter == null || it.card == null || filter.accepts(it.card.word) }
            orderNew(candidates, score ?: { 0.0 }).take(limit).mapNotNull { candidate ->
                candidate.card ?: if (introduce(candidate.wordId)) dao.cardWithWord(candidate.wordId) else null
            }
        }
    }

    /** Cards of Learning words that are not due today, soonest due first: a scope's early reviews. */
    suspend fun notDueLearningCards(): List<CardWithWord> =
        now().let { dao.notDueLearningCards(ReviewRules.dueCutoff(it, zone()), it) }

    /** Puts a card back as it was (takes back a bury, a suspension or a change of memory). */
    suspend fun restore(card: ReviewCardEntity) {
        if (words.find(card.wordId) != null) dao.upsert(card)
    }

    /** Reviews done since the start of today, and new words among them. */
    suspend fun reviewsToday(): Int = dao.reviewsSince(ReviewRules.startOfDay(now(), zone()))

    suspend fun newWordsToday(): Int = dao.firstReviewsSince(ReviewRules.startOfDay(now(), zone()))

    suspend fun card(wordId: Long): ReviewCardEntity? = dao.find(wordId)

    /** Today's reviews, retention and time for the Progress tab, again after each review. */
    fun observeSummary(): Flow<ReviewSummary> {
        val time = now()
        return combine(
            dao.observeLogsSince(time - (ReviewStats.RETENTION_DAYS + 1) * ReviewRules.DAY_MS),
            dao.observeAll(),
            dao.observeFirstReviewsSince(ReviewRules.startOfDay(time, zone())),
        ) { logs, cards, firstReviews ->
            ReviewStats.summary(
                logs, cards.count { it.state == ReviewState.REVIEW }, cards.isNotEmpty(), firstReviews, now(), zone(),
            )
        }
    }

    /** Every card, again after each change. */
    fun observeCards(): Flow<List<ReviewCardEntity>> = dao.observeAll()

    /** Puts a word into review as a new card; false if it is Anki's, unknown or already there. */
    suspend fun introduce(wordId: Long): Boolean = database.withTransaction {
        val word = words.find(wordId) ?: return@withTransaction false
        if (word.scheduler != WordScheduler.HONMANI) return@withTransaction false
        val time = now()
        dao.insertIfMissing(ReviewCardEntity(wordId = wordId, due = time, addedAt = time)) != -1L
    }

    /**
     * A real review on the review screen: grades the card (a new one is introduced first), logs it
     * and returns what [undo] needs; null for an unknown or Anki word, or a suspended card.
     */
    suspend fun record(wordId: Long, grade: Grade, inSession: Boolean = true): ReviewUndo? = database.withTransaction {
        val word = words.find(wordId) ?: return@withTransaction null
        if (word.scheduler != WordScheduler.HONMANI) return@withTransaction null
        val time = now()
        val before = dao.find(wordId) ?: ReviewCardEntity(wordId = wordId, due = time, addedAt = time)
        if (before.state == ReviewState.SUSPENDED) return@withTransaction null
        val after = ReviewRules.grade(before, grade, time, zone(), real = true, inSession = inSession)
        dao.upsert(after)
        val elapsed = before.lastReviewAt?.let { (time - it) / ReviewRules.DAY_MS.toDouble() } ?: 0.0
        val logId = dao.insertLog(ReviewLogEntity(wordId = wordId, at = time, grade = grade.value, elapsedDays = elapsed))
        ReviewUndo(before, logId)
    }

    /** Takes back a review made by [record]: the card as it was, without the log row. */
    suspend fun undo(undo: ReviewUndo) = database.withTransaction {
        dao.deleteLog(undo.logId)
        if (words.find(undo.before.wordId) != null) dao.upsert(undo.before)
    }

    /** Hides a card until tomorrow (the next local midnight). */
    suspend fun bury(wordId: Long) = change(wordId) { it.copy(buriedUntil = ReviewRules.buryUntil(now(), zone())) }

    /** "Don't review": the card keeps its memory but leaves the queue and ignores reading. */
    suspend fun suspend(wordId: Long) = change(wordId) { it.copy(state = ReviewState.SUSPENDED) }

    suspend fun unsuspend(wordId: Long) = change(wordId) {
        if (it.state == ReviewState.SUSPENDED) it.copy(state = if (it.reps > 0) ReviewState.REVIEW else ReviewState.NEW) else it
    }

    /** Due now, whatever the schedule says (pull forward) and unburied. */
    suspend fun reviewNow(wordId: Long) = change(wordId) { it.copy(due = now(), buriedUntil = null) }

    /**
     * Marks a word known by hand: its card (made if missing) gets at least [knownAfterDays] of
     * stability, counts as passed and is next due after that long.
     */
    suspend fun setKnown(wordId: Long, knownAfterDays: Int = ReviewRules.DEFAULT_KNOWN_AFTER_DAYS): Boolean = database.withTransaction {
        val word = words.find(wordId) ?: return@withTransaction false
        if (word.scheduler != WordScheduler.HONMANI) return@withTransaction false
        val time = now()
        val card = dao.find(wordId) ?: ReviewCardEntity(wordId = wordId, due = time, addedAt = time)
        val stability = maxOf(card.stability, knownAfterDays.toDouble())
        dao.upsert(
            card.copy(
                state = ReviewState.REVIEW,
                stability = stability,
                difficulty = if (card.difficulty > 0) card.difficulty else 5.0,
                due = ReviewRules.startOfDay(time, zone(), Fsrs.interval(stability)),
                passedReviews = maxOf(card.passedReviews, 1),
                reviewStability = stability,
                lastGrade = Grade.GOOD.value,
                lastReviewAt = time,
                buriedUntil = null,
            ),
        )
        true
    }

    /** Forgets what the card knew: a new card again (the review log stays). */
    suspend fun reset(wordId: Long) = change(wordId) {
        val time = now()
        ReviewCardEntity(wordId = wordId, due = time, addedAt = time)
    }

    /** Takes a word out of review for good (its card goes; the log stays). */
    suspend fun remove(wordId: Long) = dao.delete(wordId)

    /**
     * The words were met in a book without a lookup. Each due-ish card of a word that passed a
     * real review gets a Hard, once per local day ([ReviewRules.onRead]). Returns the words that moved.
     */
    suspend fun onRead(wordIds: Collection<Long>): List<Long> = database.withTransaction {
        val time = now()
        val day = ReviewRules.localDay(time, zone())
        wordIds.filter { id ->
            val card = dao.find(id) ?: return@filter false
            val word = words.find(id) ?: return@filter false
            val after = ReviewRules.onRead(card, word.scheduler, time, zone(), readToday = dao.readsOn(id, day) > 0)
            // The read is counted only when it moved a card, so a read before the card was due-ish
            // doesn't use up the day.
            if (after == null) return@filter false
            dao.insertReadDay(ReadDayEntity(id, day, 0))
            dao.incrementReadDay(id, day)
            dao.upsert(after)
            true
        }
    }

    /**
     * [onRead] for the words met in a stretch of a book: [keys] are their dictionary forms, and a
     * word in review also counts as read when one of its [variantsOf] spellings was met.
     */
    suspend fun onReadKeys(keys: Set<String>, variantsOf: (String) -> Collection<String> = { emptyList() }): List<Long> {
        if (keys.isEmpty()) return emptyList()
        val ids = dao.reviewWords()
            .filter { word -> word.expression in keys || variantsOf(word.expression).any { it in keys } }
            .map { it.id }
        return if (ids.isEmpty()) emptyList() else onRead(ids)
    }

    /** [onLookup] for the saved word [expression] read [reading], if there is one. */
    suspend fun onLookup(expression: String, reading: String, origin: LookupOrigin): Boolean {
        val word = words.find(expression, reading) ?: return false
        return onLookup(word.id, origin)
    }

    /** A lookup of a saved word: an Again if [ReviewRules.onLookup] counts it. True if the card moved. */
    suspend fun onLookup(wordId: Long, origin: LookupOrigin): Boolean = database.withTransaction {
        val card = dao.find(wordId) ?: return@withTransaction false
        val word = words.find(wordId) ?: return@withTransaction false
        val after = ReviewRules.onLookup(card, word.scheduler, origin, now(), zone()) ?: return@withTransaction false
        dao.upsert(after)
        true
    }

    private suspend fun change(wordId: Long, transform: (ReviewCardEntity) -> ReviewCardEntity) =
        database.withTransaction {
            val card = dao.find(wordId) ?: return@withTransaction
            dao.upsert(transform(card))
        }
}
