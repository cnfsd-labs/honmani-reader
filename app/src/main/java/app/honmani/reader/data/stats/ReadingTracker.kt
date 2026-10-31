package app.honmani.reader.data.stats

import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where [ReadingTracker] keeps what it counted, by ttu's book id. */
interface ReadingStore {
    /** Adds reading to the book's row for [day] (`yyyy-MM-dd`); unknown books are ignored. */
    suspend fun add(ttuId: Long, day: String, seconds: Long, characters: Long)

    /** Characters read up to the book's reading position when last seen; null if never seen. */
    suspend fun position(ttuId: Long): Long?

    suspend fun setPosition(ttuId: Long, characters: Long)

    /** The part of the book from [from] to [to] (0 to 1) was read, page by page. */
    suspend fun addRead(ttuId: Long, from: Float, to: Float) {}
}

/**
 * Counts reading time and characters read, per book and day.
 *
 * **Time** counts while a book is open, the reader shows it (not the library or tabs) and the
 * app is in front. A stretch without any activity in the book (touch, page turn, lookup: see
 * `reading-activity.js`) counts for at most [IDLE_MS], so a phone left on the table doesn't
 * keep counting.
 *
 * **Characters** come from ttu's bookmark, which holds the characters read up to the reading
 * position and is saved on every page turn (`reading-activity.js` reports each one). Turning
 * the page forward counts the characters of the page left, if it was on screen for at least
 * [MIN_PAGE_SECONDS] (pages flicked through are not read), and up to what fits the time it was
 * on screen ([MAX_CHARACTERS_PER_SECOND]): a jump forward through the table of contents is not
 * reading. Going back counts nothing. ttu's reports of all positions (from the library) count
 * what page turns missed, with a little allowance for reading before the app last stopped.
 * A page turn in a book without a stored position only stores it.
 *
 * **Stretches read**: the part of the book of each page counted is passed on ([ReadingStore.addRead]),
 * for the words read (`WordKnowledge`); ttu's reports of all positions add none.
 *
 * Calls come from the main thread and from collectors of ttu's reports; state is guarded by a
 * lock, writes go through [store] one at a time.
 */
class ReadingTracker(
    private val store: ReadingStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val writes = Mutex()

    private var book: Long? = null
    private var visible = false
    private var foreground = false

    /** Start of the time not counted yet, while active. */
    private var mark: Long? = null

    /** Time counted since the book's reading position last changed: what its characters may use. */
    private val secondsSinceReport = HashMap<Long, Long>()

    /** Time of page turns that kept the position, waiting for the turn that moves it. */
    private val carriedSeconds = HashMap<Long, Long>()

    /** Milliseconds counted but not written yet, by book and day. */
    private val pending = HashMap<Pair<Long, String>, Long>()

    /** ttu opened book [ttuId] (null: none is open). */
    fun setBook(ttuId: Long?) = change { book = ttuId }

    /** Whether the reader is on screen (not covered by the library or the tabs). */
    fun setVisible(value: Boolean) = change { visible = value }

    /** Whether the app is in front. */
    fun setForeground(value: Boolean) = change { foreground = value }

    /** The reader touched or scrolled the book. */
    fun onActivity() {
        synchronized(lock) {
            if (!active()) return
            val now = clock()
            count(now)
            mark = now
        }
        flushIfDue()
    }

    /**
     * The page turned in book [ttuId]: [characters] read up to the new reading position, which is
     * at [progress] (0 to 1) in the book, if known.
     */
    fun onPage(ttuId: Long, characters: Long, progress: Float? = null) {
        synchronized(lock) {
            // The time on the page left counts up to now.
            if (active() && book == ttuId) {
                val now = clock()
                count(now)
                mark = now
            }
        }
        // The time up to this page turn is taken now, before a later page adds to it.
        val onThisPage = synchronized(lock) { secondsSinceReport.remove(ttuId) ?: 0L }
        scope.launch {
            writes.withLock {
                val known = store.position(ttuId)
                if (known == null) {
                    // Nothing to measure the page against (a new or restored book): counting
                    // from 0 would credit the whole distance to the start. Just remember it.
                    synchronized(lock) { carriedSeconds.remove(ttuId) }
                    safely { store.setPosition(ttuId, characters) }
                    return@withLock
                }
                val before = known
                // ttu saves the same position again when a page turn stays in one paragraph:
                // the time keeps adding up until the position moves.
                if (characters == before) {
                    synchronized(lock) { carriedSeconds.merge(ttuId, onThisPage, Long::plus) }
                    return@withLock
                }
                val seconds = onThisPage + synchronized(lock) { carriedSeconds.remove(ttuId) ?: 0L }
                val read = if (seconds >= MIN_PAGE_SECONDS) {
                    (characters - before).coerceIn(0L, seconds * MAX_CHARACTERS_PER_SECOND)
                } else {
                    0L
                }
                if (read > 0) {
                    safely { store.add(ttuId, day(clock()), 0, read) }
                    // The book's length from the new position: characters at progress.
                    if (progress != null && progress > 0f && characters > 0) {
                        val total = characters / progress.toDouble()
                        safely { store.addRead(ttuId, (before / total).toFloat(), ((before + read) / total).toFloat()) }
                    }
                }
                safely { store.setPosition(ttuId, characters) }
            }
        }
    }

    /** ttu reported reading positions: characters read up to each book's position. */
    fun onPositions(positions: Map<Long, Long>) {
        if (positions.isEmpty()) return
        scope.launch {
            writes.withLock {
                for ((ttuId, characters) in positions) {
                    val before = store.position(ttuId) ?: 0L
                    if (characters == before) continue
                    val seconds = synchronized(lock) {
                        (secondsSinceReport.remove(ttuId) ?: 0L) + (carriedSeconds.remove(ttuId) ?: 0L)
                    }
                    val allowed = seconds * MAX_CHARACTERS_PER_SECOND + SLACK_CHARACTERS
                    val read = (characters - before).coerceIn(0L, allowed)
                    if (read > 0) safely { store.add(ttuId, day(clock()), 0, read) }
                    safely { store.setPosition(ttuId, characters) }
                }
            }
        }
    }

    /** Writes what was counted (when the app goes to the background, say). */
    fun flush() {
        val due = synchronized(lock) {
            // Whole seconds are written; the rest of a second waits for the next flush.
            val seconds = pending.mapValues { (_, millis) -> millis / 1_000 }.filterValues { it > 0 }
            for ((key, whole) in seconds) {
                val rest = pending.getValue(key) - whole * 1_000
                if (rest > 0) pending[key] = rest else pending.remove(key)
            }
            seconds
        }
        if (due.isEmpty()) return
        scope.launch {
            writes.withLock {
                for ((key, seconds) in due) safely { store.add(key.first, key.second, seconds, 0) }
            }
        }
    }

    private inline fun change(update: () -> Unit) {
        var stopped = false
        synchronized(lock) {
            val wasActive = active()
            val wasBook = book
            if (wasActive) {
                count(clock())
                mark = null
            }
            update()
            if (active()) {
                if (mark == null || book != wasBook) mark = clock()
            } else {
                mark = null
                stopped = wasActive
            }
        }
        if (stopped) flush()
    }

    private fun active() = book != null && visible && foreground

    /** Counts the time since [mark], at most [IDLE_MS]; under [lock]. */
    private fun count(now: Long) {
        val from = mark ?: return
        val book = book ?: return
        val millis = (now - from).coerceIn(0L, IDLE_MS)
        if (millis == 0L) return
        val key = book to day(now)
        pending[key] = (pending[key] ?: 0L) + millis
        // Whole seconds for the characters' allowance; the rest waits in the next stretch.
        secondsSinceReport[book] = (secondsSinceReport[book] ?: 0L) + millis / 1_000
    }

    private fun flushIfDue() {
        val due = synchronized(lock) { pending.values.sum() >= FLUSH_MS }
        if (due) flush()
    }

    private fun day(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate().toString()

    /** Statistics never get in the way of reading. */
    private suspend fun safely(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Saving reading statistics failed: $e")
        }
    }

    companion object {
        /** A stretch without activity counts for at most this long. */
        const val IDLE_MS = 5 * 60_000L

        /** Counted time is written at least this often while reading. */
        const val FLUSH_MS = 60_000L

        /** A page on screen for less than this was flicked past, not read. */
        const val MIN_PAGE_SECONDS = 5L

        /** Faster than anyone reads Japanese (about 1,800 characters a minute). */
        const val MAX_CHARACTERS_PER_SECOND = 30L

        /** Allowance for characters read before the app last stopped (time not counted then). */
        const val SLACK_CHARACTERS = 500L
    }
}
