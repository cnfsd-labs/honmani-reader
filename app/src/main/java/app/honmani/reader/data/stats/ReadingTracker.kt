package app.honmani.reader.data.stats

import app.honmani.reader.data.time.DayClock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where [ReadingTracker] keeps what it counted, by ttu's book id. */
interface ReadingStore {
    /**
     * Adds reading to the book's row for [day] (`yyyy-MM-dd`); unknown books are ignored. [listenSeconds]
     * are the part of [seconds] with audio playing.
     */
    suspend fun add(ttuId: Long, day: String, seconds: Long, characters: Long, listenSeconds: Long = 0)

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
 * app is in front (and, if the user chose so, no lookup panel is open). A stretch without any
 * activity in the book (touch, page turn, lookup: see `reading-activity.js`) counts for at most [IDLE_MS], so a phone left on the
 * table doesn't keep counting.
 *
 * **Listening**: the part of that time with audio playing (an audiobook or Read aloud, see
 * [setAudioPlaying]) is counted again on the side, as a subset of the time, never added to it.
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
    /** The hour a day starts at ([DayClock]); 0 is midnight. */
    private val startHour: () -> Int = { 0 },
    private val log: (String) -> Unit = {},
) {
    private val dayClock = DayClock(zone, startHour, clock)
    private val lock = Any()
    private val writes = Mutex()

    private var book: Long? = null
    private var visible = false
    private var foreground = false
    private var lookupOpen = false
    private var countLookupTime = true
    private var audioPlaying = false

    /** Start of the time not counted yet, while active. */
    private var mark: Long? = null

    /**
     * Milliseconds counted since the book's reading position last changed, by day: what its
     * characters may use, and the days they are shared between.
     */
    private val sinceReport = HashMap<Long, Map<String, Long>>()

    /** Time of page turns that kept the position, waiting for the turn that moves it. */
    private val carried = HashMap<Long, Map<String, Long>>()

    /** Milliseconds counted but not written yet, by book and day. */
    private val pending = HashMap<Pair<Long, String>, Long>()

    /** The part of [pending] with audio playing (never more than it). */
    private val pendingListen = HashMap<Pair<Long, String>, Long>()

    /** ttu opened book [ttuId] (null: none is open). */
    fun setBook(ttuId: Long?) = change { book = ttuId }

    /** Whether the reader is on screen (not covered by the library or the tabs). */
    fun setVisible(value: Boolean) = change { visible = value }

    /** Whether the app is in front. */
    fun setForeground(value: Boolean) = change { foreground = value }

    /** Whether the lookup panel is open (its touches are native: the book gets no activity). */
    fun setLookupOpen(value: Boolean) = change { lookupOpen = value }

    /**
     * Whether time counts while the lookup panel is open (the default). Off: the timer pauses
     * when the panel opens and carries on when it closes.
     */
    fun setCountLookupTime(value: Boolean) = change { countLookupTime = value }

    /**
     * Whether audio plays (an audiobook, or Read aloud speaking). Time counted meanwhile is also
     * listening time. Must go false when it stops, pauses or the book is left.
     */
    fun setAudioPlaying(value: Boolean) = change { audioPlaying = value }

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
        val onThisPage = synchronized(lock) { sinceReport.remove(ttuId).orEmpty() }
        scope.launch {
            writes.withLock {
                val known = store.position(ttuId)
                if (known == null) {
                    // Nothing to measure the page against (a new or restored book): counting
                    // from 0 would credit the whole distance to the start. Just remember it.
                    synchronized(lock) { carried.remove(ttuId) }
                    safely { store.setPosition(ttuId, characters) }
                    return@withLock
                }
                val before = known
                // ttu saves the same position again when a page turn stays in one paragraph:
                // the time keeps adding up until the position moves.
                if (characters == before) {
                    synchronized(lock) { carried[ttuId] = carried[ttuId].orEmpty().sumWith(onThisPage) }
                    return@withLock
                }
                val byDay = onThisPage.sumWith(synchronized(lock) { carried.remove(ttuId).orEmpty() })
                val seconds = byDay.values.sum() / 1_000
                val read = if (seconds >= MIN_PAGE_SECONDS) {
                    (characters - before).coerceIn(0L, seconds * MAX_CHARACTERS_PER_SECOND)
                } else {
                    0L
                }
                if (read > 0) {
                    addCharacters(ttuId, read, byDay)
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
                    val byDay = synchronized(lock) { sinceReport.remove(ttuId).orEmpty().sumWith(carried.remove(ttuId).orEmpty()) }
                    val seconds = byDay.values.sum() / 1_000
                    val allowed = seconds * MAX_CHARACTERS_PER_SECOND + SLACK_CHARACTERS
                    val read = (characters - before).coerceIn(0L, allowed)
                    if (read > 0) addCharacters(ttuId, read, byDay)
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
            val due = HashMap<Pair<Long, String>, Pair<Long, Long>>()
            for ((key, whole) in seconds) {
                val rest = pending.getValue(key) - whole * 1_000
                if (rest > 0) pending[key] = rest else pending.remove(key)
                val listenMillis = pendingListen[key] ?: 0L
                val listenWhole = minOf(listenMillis / 1_000, whole)
                // The part with audio never outgrows the time it is part of.
                val listenRest = minOf(listenMillis - listenWhole * 1_000, rest)
                if (listenRest > 0) pendingListen[key] = listenRest else pendingListen.remove(key)
                due[key] = whole to listenWhole
            }
            due
        }
        if (due.isEmpty()) return
        scope.launch {
            writes.withLock {
                for ((key, counted) in due) safely { store.add(key.first, key.second, counted.first, 0, counted.second) }
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

    private fun active() = book != null && visible && foreground && (countLookupTime || !lookupOpen)

    /** Counts the time since [mark], at most [IDLE_MS]; under [lock]. */
    private fun count(now: Long) {
        val from = mark ?: return
        val book = book ?: return
        val millis = (now - from).coerceIn(0L, IDLE_MS)
        if (millis == 0L) return
        // The stretch ran from the mark; the part before the next day starts belongs to the day before.
        val end = from + millis
        val startDay = date(from)
        val nextStart = dayClock.startOfDay(from, 1)
        if (end <= nextStart) {
            countOn(book, startDay.toString(), millis)
        } else {
            countOn(book, startDay.toString(), nextStart - from)
            countOn(book, date(end).toString(), end - nextStart)
        }
    }

    private fun countOn(book: Long, day: String, millis: Long) {
        if (millis <= 0) return
        val key = book to day
        pending[key] = (pending[key] ?: 0L) + millis
        if (audioPlaying) pendingListen[key] = (pendingListen[key] ?: 0L) + millis
        // Kept in milliseconds: the characters' allowance floors only the total.
        sinceReport[book] = sinceReport[book].orEmpty().let { it + (day to (it[day] ?: 0L) + millis) }
    }

    /**
     * Credits [read] characters to the days the time went to, in proportion to that time; a page
     * read on one day goes to that day (not the day of the write), with no time known to today.
     */
    private suspend fun addCharacters(ttuId: Long, read: Long, byDay: Map<String, Long>) {
        val days = byDay.toSortedMap().filterValues { it > 0 }
        val total = days.values.sum()
        if (days.size < 2) return safely { store.add(ttuId, days.keys.firstOrNull() ?: day(clock()), 0, read) }
        var left = read
        val last = days.keys.last()
        for ((day, millis) in days) {
            val share = if (day == last) left else read * millis / total
            left -= share
            if (share > 0) safely { store.add(ttuId, day, 0, share) }
        }
    }

    private fun flushIfDue() {
        val due = synchronized(lock) { pending.values.sum() >= FLUSH_MS }
        if (due) flush()
    }

    private fun date(millis: Long): LocalDate = dayClock.date(millis)

    private fun day(millis: Long): String = date(millis).toString()

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

/** Both maps' times together: a day in both gets the sum (`Map.plus` would keep only one). */
private fun Map<String, Long>.sumWith(other: Map<String, Long>): Map<String, Long> =
    other.entries.fold(this) { sum, (day, millis) -> sum + (day to (sum[day] ?: 0L) + millis) }
