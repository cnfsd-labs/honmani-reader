package app.honmani.reader.data.sync

import android.util.Log
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A position taken from another device when a book opened. */
data class PulledPosition(val percent: Int, val device: String)

/** Where the reader was ([characters] of [total], [percent] %) before [ProgressSync.pull] moved the book. */
data class PlaceBefore(val characters: Long, val total: Long, val percent: Int, val pulled: PulledPosition)

/**
 * [device] saved an earlier place in the book ([theirs] %) after this phone's ([ours] %): the
 * reader may have gone back on purpose there, or opened the book there before this phone's last
 * pages were synced.
 */
data class SyncQuestion(val device: String, val theirs: Int, val ours: Int)

/**
 * Reading progress sync with KOReader's sync server (KOReader on an e-reader, Calibre-Web
 * Automated, or another Honmani). Only EPUBs: the document id is the file's partial MD5, so a book
 * matches when both devices have the same file.
 *
 * - When a book opens ([pull], before ttu loads it), a newer position from another device is
 *   taken if it is further on; if it is further back, the reader is asked (as KOReader does).
 * - Page turns are sent a little later, together ([onPosition]), and at once when the reader
 *   leaves the book or the app ([flush]). What couldn't be sent (no network, away from a home
 *   server) is kept in [unsent] and sent when a network comes back or the app starts
 *   ([resend]), unless another device has saved a position since: further on, it wins; further
 *   back, the reader chooses when the book opens next.
 *
 * Failures only delay a sync: reading never waits more than [PULL_TIMEOUT_MS] for it.
 */
class ProgressSync(
    private val settings: KoSyncSettings,
    private val client: KoSyncClient,
    private val books: BookDao,
    private val bookFile: (BookEntity) -> File,
    private val scope: CoroutineScope,
    /** How this phone is named on the server ("Honmani (Pixel 8)"). */
    private val device: String,
    private val unsent: UnsentPositions,
    private val pushDelayMs: Long = PUSH_DELAY_MS,
    private val pullTimeoutMs: Long = PULL_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
    /** Records the last success and failure for Settings and the reader menu. */
    private val status: KoSyncStatus? = null,
    /**
     * Where book files are parsed: placing a position re-reads one chapter (up to 32 MB), which
     * must not block the caller (the main thread, when a book opens) or [pull]'s timeout.
     */
    private val parsing: CoroutineContext = Dispatchers.Default,
) {
    private class Document(val id: String, val positions: EpubPositions, private val parsing: CoroutineContext) {
        val total get() = positions.total.coerceAtLeast(1)

        suspend fun characters(remote: RemoteProgress): Long = withContext(parsing) {
            (positions.charactersAt(remote.progress)?.toLong() ?: (remote.percentage * total).roundToLong())
                .coerceIn(0, total.toLong())
        }

        suspend fun xpointerAt(characters: Long): String? = withContext(parsing) { positions.xpointerAt(characters.toInt()) }

        fun percent(characters: Long) = (characters * 100.0 / total).roundToInt()
    }

    /** What [pull] found on the server. */
    private sealed interface Found {
        data object None : Found

        /** [percentage]: the server's, 0..1. */
        class Ahead(val document: Document, val characters: Long, val device: String, val percentage: Double, val local: Long? = null) : Found

        /** [at]: when the server stored it, in milliseconds. */
        class Behind(val document: Document, val characters: Long, val device: String, val local: Long, val at: Long) : Found
    }

    private val documents = object : LinkedHashMap<Long, Document>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Document>?) = size > MAX_DOCUMENTS
    }
    private val documentLock = Mutex()
    private val pushLock = Mutex()

    /** Characters at the last position synced per ttu book, so unchanged positions aren't sent. */
    private val synced = HashMap<Long, Long>()

    /**
     * Positions just taken from the server per ttu book: ttu then saves the start of the page
     * that holds it, a little earlier, which must not go back as if the reader had turned back.
     * Only that first save is skipped.
     */
    private val pulledTo = HashMap<Long, Long>()

    /** Per ttu book, this phone's place before [pull] moved it, until the reader takes it ([takePlaceBefore]). */
    private val placesBefore = HashMap<Long, PlaceBefore>()
    private var pending: Pair<Long, Long>? = null
    private var pushJob: Job? = null

    /** When [flush] last ran ([System.nanoTime]): positions saved shortly after it go at once. */
    private var flushedAt: Long? = null

    /** Whether [pushJob] is still in its delay: only then may [flush] replace it. */
    private var waiting = false

    init {
        // Positions belong to the account they were read with: signing out or into another
        // account drops them. An account whose login can no longer be decrypted keeps them
        // until the reader signs in to it again.
        scope.launch {
            if (settings.signedInAs.value == null) unsent.clear() else unsent.all()
            settings.signedInAs.drop(1).collect {
                unsent.clear()
                status?.clear()
            }
        }
    }

    /**
     * Before [book] opens in ttu (as [ttuId]): takes the server's position if it is newer, from
     * another device and further on, writing it with [setBookmark] (characters, progress). If it
     * is newer but further back, [ask] decides (true: go there). Returns what was taken, or null
     * if the book stays where it was.
     */
    suspend fun pull(
        book: BookEntity,
        ttuId: Long,
        bookmark: suspend () -> Pair<Long?, Long>,
        setBookmark: suspend (Long, Double) -> Unit,
        ask: suspend (SyncQuestion) -> Boolean = { false },
    ): PulledPosition? {
        val found = lookup(book, ttuId, bookmark) ?: return null
        return when (found) {
            Found.None -> {
                resend()
                null
            }
            is Found.Ahead -> take(found.document, ttuId, found.characters, found.device, setBookmark, from = found.local)
            is Found.Behind -> {
                val question = SyncQuestion(found.device, found.document.percent(found.characters), found.document.percent(found.local))
                if (ask(question)) {
                    take(found.document, ttuId, found.characters, found.device, setBookmark, from = found.local)
                } else {
                    // This phone's place stays, and goes to the server for the other device.
                    found.document.xpointerAt(found.local)?.let { xpointer ->
                        // A file write: off the caller's (main) thread, like the parse.
                        withContext(Dispatchers.IO) { unsent.put(
                            UnsentPosition(
                                ttuId = ttuId,
                                document = found.document.id,
                                progress = xpointer,
                                percentage = found.local.toDouble() / found.document.total,
                                characters = found.local,
                                // The reader chose it over the other device's.
                                readAt = maxOf(now(), found.at + 1),
                            ),
                        ) }
                    }
                    resend()
                    null
                }
            }
        }
    }

    /**
     * Once, when [book] was just downloaded from a book server and handed to ttu as [ttuId]: a
     * position another device saved for the same file is written with [setBookmark] and shown in
     * the library; one at the end ([FINISHED_PERCENTAGE], KOReader has no "finished" flag) also
     * marks the book finished. Failures are only logged and recorded: the import never waits
     * more than [PULL_TIMEOUT_MS] for it. Returns what was taken.
     */
    suspend fun pullOnImport(book: BookEntity, ttuId: Long, setBookmark: suspend (Long, Double) -> Unit): PulledPosition? {
        // A new book has no position of its own yet.
        val found = lookup(book, ttuId) { null to 0L } as? Found.Ahead ?: return null
        val pulled = take(found.document, ttuId, found.characters, found.device, setBookmark)
        books.setProgressByTtuId(ttuId, (found.characters.toDouble() / found.document.total).toFloat())
        if (found.percentage >= FINISHED_PERCENTAGE) books.setFinished(book.id, now())
        return pulled
    }

    /**
     * Once, when [book] was imported from a book server in the background, before ttu holds it:
     * the library shows the progress another device saved for the same file, and one at the end
     * marks the book finished. Nothing goes to ttu: when the book first opens, [pull] takes the
     * position (ttu has none of its own then). Failures are only logged and recorded.
     */
    suspend fun progressOnImport(book: BookEntity) {
        val found = lookup(book, null) { null to 0L } as? Found.Ahead ?: return
        books.setProgress(book.id, (found.characters.toDouble() / found.document.total).toFloat())
        if (found.percentage >= FINISHED_PERCENTAGE) books.setFinished(book.id, now())
    }

    /** The server's position for [book], or null if sync is off, the book isn't an EPUB, or it failed or took too long. */
    private suspend fun lookup(book: BookEntity, ttuId: Long?, bookmark: suspend () -> Pair<Long?, Long>): Found? {
        val account = settings.account.value ?: return null
        if (book.format != BookFormat.EPUB) return null
        // The request blocks in HttpURLConnection, which a timeout cannot interrupt: it runs on
        // its own and is left behind when the deadline passes (its answer is dropped).
        val waiting = AtomicBoolean()
        val lookup = CoroutineScope(currentCoroutineContext().minusKey(Job)).async {
            find(account, book, ttuId, bookmark, waiting)
        }
        return try {
            val found = withTimeoutOrNull(pullTimeoutMs) { lookup.await() }
            // Reading the book slowly, before or after the request, is no server problem.
            if (found == null && waiting.get()) {
                Log.i(TAG, "No progress sync for this book: no answer in $pullTimeoutMs ms")
                status?.failed(KoSyncException(KoSyncException.Reason.NETWORK, "No answer in time"), ttuId)
            }
            found
        } catch (e: IOException) {
            Log.i(TAG, "No progress sync for this book: ${e.message}")
            status?.failed(e, ttuId)
            null
        } finally {
            lookup.cancel()
        }
    }

    private suspend fun find(
        account: KoSyncAccount,
        book: BookEntity,
        ttuId: Long?,
        bookmark: suspend () -> Pair<Long?, Long>,
        waiting: AtomicBoolean,
    ): Found {
        val document = document(book) ?: return Found.None
        waiting.set(true)
        val remote = client.progress(account, document.id)
        waiting.set(false)
        status?.succeeded()
        val (local, savedAt) = bookmark()
        if (ttuId != null) local?.let { synchronized(synced) { synced[ttuId] = it } }
        if (remote == null || remote.deviceId == settings.deviceId) return Found.None
        val characters = document.characters(remote)
        val remoteAt = remote.timestamp?.let { it * 1000 }
        val newer = when {
            local == null -> true
            remoteAt != null -> remoteAt > savedAt
            // An old server without times: only ever forwards.
            else -> characters > local
        }
        if (!newer || (local != null && abs(characters - local) < MIN_CHANGE)) {
            // A position held back for this question is settled: the server is no further back.
            unsent.find(document.id)?.takeIf { it.held }?.let(unsent::remove)
            return Found.None
        }
        return if (local == null || characters > local || remoteAt == null) {
            Found.Ahead(document, characters, remote.device, remote.percentage, local)
        } else {
            Found.Behind(document, characters, remote.device, local, remoteAt)
        }
    }

    private suspend fun take(
        document: Document,
        ttuId: Long,
        characters: Long,
        device: String,
        setBookmark: suspend (Long, Double) -> Unit,
        from: Long? = null,
    ): PulledPosition {
        val pulled = PulledPosition(document.percent(characters), device)
        setBookmark(characters, characters.toDouble() / document.total)
        synchronized(synced) {
            synced[ttuId] = characters
            pulledTo[ttuId] = characters
            if (from != null) placesBefore[ttuId] = PlaceBefore(from, document.total.toLong(), document.percent(from), pulled)
            else placesBefore.remove(ttuId)
        }
        unsent.forget(document.id)
        return pulled
    }

    /**
     * This phone's place in book [ttuId] before the last [pull] moved it (null if it had none, or
     * it was taken already): the reader offers the way back to it.
     */
    fun takePlaceBefore(ttuId: Long): PlaceBefore? = synchronized(synced) { placesBefore.remove(ttuId) }

    /** Like [takePlaceBefore], but leaves it for the reader. */
    fun placeBefore(ttuId: Long): PlaceBefore? = synchronized(synced) { placesBefore[ttuId] }

    /**
     * The reader jumped to [characters] of book [ttuId] (slider, bookmark, search hit, the way
     * back): ttu saves the page that holds it as soon as the book reloads. That first save is
     * where the reader looked, not reading, so it isn't sent: the server keeps the place the
     * reader came from (the furthest one, as far as this phone knows) until a page is turned
     * there. Without it a glance at the end of the book, or at the start, would replace the
     * server's position, and the other device would ask about a backward jump nobody read to.
     */
    fun onJump(ttuId: Long, characters: Long) {
        synchronized(synced) { pulledTo[ttuId] = characters }
    }

    /** ttu saved a reading position (a page turn); sent after [pushDelayMs], with later ones. */
    fun onPosition(ttuId: Long, characters: Long) {
        if (settings.account.value == null) return
        // The first save after a jump is ttu opening the page that holds the pulled position.
        val pageStart = synchronized(synced) {
            pulledTo.remove(ttuId)?.let { pulled -> characters in (pulled - PAGE_CHARACTERS)..pulled } == true
        }
        if (pageStart) return
        synchronized(this) {
            pending = ttuId to characters
            if (pushJob?.isActive == true) return
            // Just after [flush] (ttu answers its progress request later) a position goes at
            // once: the reader left, and the process may be gone before a delay ends.
            schedulePush(nextDelay())
        }
    }

    private fun justFlushed() = flushedAt?.let { System.nanoTime() - it < FLUSH_GRACE_NS } == true

    /** Under the lock. */
    private fun nextDelay() = if (justFlushed()) 0 else pushDelayMs

    /** Sends [pending] after [wait]; under the lock. A position that arrived meanwhile follows. */
    private fun schedulePush(wait: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (wait > 0) delay(wait)
            synchronized(this@ProgressSync) {
                // [flush] cancelled the wait just as it ended: its own job sends.
                if (!isActive) return@launch
                waiting = false
            }
            push()
        }
        pushJob = job
        waiting = wait > 0
        job.invokeOnCompletion { finished ->
            // A position turned in while this one was being sent had no job to wait for it.
            if (finished == null) {
                synchronized(this) {
                    if (pushJob === job) {
                        pushJob = null
                        if (pending != null) schedulePush(nextDelay())
                    }
                }
            }
        }
        job.start()
    }

    /** Sends the latest position now: the reader left the book or the app. */
    fun flush() {
        synchronized(this) {
            flushedAt = System.nanoTime()
            if (pending == null) return
            // A job on its way to the server takes the position, or sends it after (no delay now).
            if (pushJob?.isActive == true && !waiting) return
            pushJob?.cancel()
            schedulePush(0)
        }
    }

    /** Returns once no position is waiting to be sent or on its way; tests use it instead of sleeping. */
    internal suspend fun idle() {
        while (true) (synchronized(this) { pushJob } ?: break).join()
        pushLock.withLock { }
    }

    /** Sends the positions that couldn't be sent before: a network is back, or the app started. */
    fun resend(): Job? {
        if (settings.account.value == null) return null
        return scope.launch { pushLock.withLock { settings.account.value?.let { settle(it) } } }
    }

    /**
     * "Sync now" after a failure: the current position at once, then what couldn't be sent before.
     * Returns false if nothing was waiting to be sent (the failure came from a [pull]): the caller
     * then pulls again (the reader) or [checkServer]s (Settings). Returns once the send is done
     * (sent or failed), so the caller can show it running; the send itself is not cancelled with the caller.
     */
    suspend fun syncNow(): Boolean {
        flush()
        val waiting = synchronized(this) { pushJob != null } ||
            withContext(Dispatchers.IO) { unsent.raw() != null || unsent.all().any { !it.held } }
        if (waiting) resend()?.join()
        return waiting
    }

    /** Checks the login with the server: a success clears the last failure, a failure replaces it. */
    suspend fun checkServer() {
        val account = settings.account.value ?: return
        try {
            client.authorize(account)
            status?.succeeded()
        } catch (e: IOException) {
            Log.i(TAG, "Sync server check failed: ${e.message}")
            status?.failed(e, status.state.value.failure?.ttuId)
        }
    }

    private suspend fun push() = pushLock.withLock {
        val (ttuId, characters) = synchronized(this) { pending.also { pending = null } } ?: return@withLock
        val account = settings.account.value ?: return@withLock
        // Before the book is parsed (slow): a process kill meanwhile must not lose the position.
        unsent.putRaw(RawPosition(ttuId, characters, now()))
        settle(account)
    }

    /** Turns the raw position into an unsent one, then sends what [unsent] holds. */
    private suspend fun settle(account: KoSyncAccount) {
        unsent.raw()?.let { record(it) }
        sendUnsent(account)
    }

    private suspend fun record(raw: RawPosition) {
        val (ttuId, characters) = raw.ttuId to raw.characters
        if (synchronized(synced) { synced[ttuId] } != characters) {
            try {
                val book = books.findByTtuId(ttuId)?.takeIf { it.format == BookFormat.EPUB }
                val document = book?.let { document(it) }
                val xpointer = document?.xpointerAt(characters)
                if (document != null && xpointer != null) {
                    val percentage = characters.toDouble() / document.total
                    unsent.put(UnsentPosition(ttuId, document.id, xpointer, percentage.coerceIn(0.0, 1.0), characters, raw.readAt))
                }
            } catch (e: IOException) {
                // Kept in the raw file: the next resend tries again.
                Log.i(TAG, "Progress not sent: ${e.message}")
                return
            }
        }
        unsent.removeRaw(raw)
    }

    /** Sends what [unsent] holds, until the server can't be reached. */
    private suspend fun sendUnsent(account: KoSyncAccount) {
        for (position in unsent.all().filterNot { it.held }) {
            try {
                send(account, position)
            } catch (e: IOException) {
                Log.i(TAG, "Progress not sent, kept for later: ${e.message}")
                status?.failed(e, position.ttuId)
                return
            }
        }
    }

    private suspend fun send(account: KoSyncAccount, position: UnsentPosition) {
        val remote = client.progress(account, position.document)
        status?.succeeded()
        val remoteAt = remote?.timestamp?.let { it * 1000 }
        // Another device saved a position after this one was read, or (an old server without
        // times) at some point: only ever forwards there.
        if (remote != null && remote.deviceId != settings.deviceId && (remoteAt == null || remoteAt > position.readAt)) {
            val theirs = books.findByTtuId(position.ttuId)
                ?.takeIf { it.format == BookFormat.EPUB }
                ?.let { document(it) }
                ?.takeIf { it.id == position.document }
                ?.characters(remote)
            val further = if (theirs != null) {
                theirs > position.characters - MIN_CHANGE
            } else {
                remote.percentage > position.percentage - PERCENTAGE_SLACK
            }
            if (further) {
                unsent.remove(position)
                return
            }
            if (remoteAt != null) {
                unsent.hold(position)
                return
            }
        }
        client.update(account, position.document, position.progress, position.percentage, device, settings.deviceId)
        status?.succeeded()
        unsent.remove(position)
        synchronized(synced) { synced[position.ttuId] = position.characters }
    }

    /** The book's sync id and positions, worked out once per book (it reads the whole file). */
    private suspend fun document(book: BookEntity): Document? = documentLock.withLock {
        documents[book.id] ?: withContext(parsing) {
            val file = bookFile(book).takeIf { it.isFile } ?: return@withContext null
            val positions = EpubPositions.read(file) ?: return@withContext null
            Document(PartialMd5.of(file), positions, parsing)
        }?.also { documents[book.id] = it }
    }

    private companion object {
        const val TAG = "Honmani.sync"
        const val PULL_TIMEOUT_MS = 4_000L
        const val PUSH_DELAY_MS = 15_000L

        /** How long after [flush] a saved position still counts as part of leaving. */
        const val FLUSH_GRACE_NS = 10_000_000_000L
        const val MAX_DOCUMENTS = 3

        /** KOReader's last page is often at 0.998 rather than 1. */
        const val FINISHED_PERCENTAGE = 0.99

        /** Positions closer than this count as the same (a few characters' rounding). */
        const val MIN_CHANGE = 10

        /** More characters than a phone page holds, even in small print. */
        const val PAGE_CHARACTERS = 1_500L

        /** [MIN_CHANGE] for books whose file is gone: a server's percentage against ours. */
        const val PERCENTAGE_SLACK = 0.001
    }
}
