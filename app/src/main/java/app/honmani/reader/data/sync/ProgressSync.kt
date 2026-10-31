package app.honmani.reader.data.sync

import android.util.Log
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import java.io.File
import java.io.IOException
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A position taken from another device when a book opened. */
data class PulledPosition(val percent: Int, val device: String)

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
) {
    private class Document(val id: String, val positions: EpubPositions) {
        val total get() = positions.total.coerceAtLeast(1)

        fun characters(remote: RemoteProgress): Long =
            (positions.charactersAt(remote.progress)?.toLong() ?: (remote.percentage * total).roundToLong())
                .coerceIn(0, total.toLong())

        fun percent(characters: Long) = (characters * 100.0 / total).roundToInt()
    }

    /** What [pull] found on the server. */
    private sealed interface Found {
        data object None : Found

        class Ahead(val document: Document, val characters: Long, val device: String) : Found

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
    private var pending: Pair<Long, Long>? = null
    private var pushJob: Job? = null

    init {
        // Positions belong to the account they were read with: signing out or into another
        // account drops them.
        scope.launch {
            if (settings.account.value == null) unsent.clear() else unsent.all()
            settings.account.map { it?.server to it?.username }.distinctUntilChanged().drop(1).collect { unsent.clear() }
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
        val account = settings.account.value ?: return null
        if (book.format != BookFormat.EPUB) return null
        // The request blocks in HttpURLConnection, which a timeout cannot interrupt: it runs on
        // its own and is left behind when the deadline passes (its answer is dropped).
        val lookup = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { find(account, book, ttuId, bookmark) }
        val found = try {
            withTimeoutOrNull(pullTimeoutMs) { lookup.await() }
        } catch (e: IOException) {
            Log.i(TAG, "No progress sync for this book: ${e.message}")
            null
        } finally {
            lookup.cancel()
        } ?: return null
        return when (found) {
            Found.None -> {
                resend()
                null
            }
            is Found.Ahead -> take(found.document, ttuId, found.characters, found.device, setBookmark)
            is Found.Behind -> {
                val question = SyncQuestion(found.device, found.document.percent(found.characters), found.document.percent(found.local))
                if (ask(question)) {
                    take(found.document, ttuId, found.characters, found.device, setBookmark)
                } else {
                    // This phone's place stays, and goes to the server for the other device.
                    found.document.positions.xpointerAt(found.local.toInt())?.let { xpointer ->
                        unsent.put(
                            UnsentPosition(
                                ttuId = ttuId,
                                document = found.document.id,
                                progress = xpointer,
                                percentage = found.local.toDouble() / found.document.total,
                                characters = found.local,
                                // The reader chose it over the other device's.
                                readAt = maxOf(now(), found.at + 1),
                            ),
                        )
                    }
                    resend()
                    null
                }
            }
        }
    }

    private suspend fun find(
        account: KoSyncAccount,
        book: BookEntity,
        ttuId: Long,
        bookmark: suspend () -> Pair<Long?, Long>,
    ): Found {
        val document = document(book) ?: return Found.None
        val remote = client.progress(account, document.id)
        val (local, savedAt) = bookmark()
        local?.let { synchronized(synced) { synced[ttuId] = it } }
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
            Found.Ahead(document, characters, remote.device)
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
    ): PulledPosition {
        setBookmark(characters, characters.toDouble() / document.total)
        synchronized(synced) {
            synced[ttuId] = characters
            pulledTo[ttuId] = characters
        }
        unsent.forget(document.id)
        return PulledPosition(document.percent(characters), device)
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
            schedulePush(pushDelayMs)
        }
    }

    /** Sends [pending] after [wait]; under the lock. A position that arrived meanwhile follows. */
    private fun schedulePush(wait: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (wait > 0) delay(wait)
            push()
        }
        pushJob = job
        job.invokeOnCompletion { finished ->
            // A position turned in while this one was being sent had no job to wait for it.
            if (finished == null) {
                synchronized(this) {
                    if (pushJob === job) {
                        pushJob = null
                        if (pending != null) schedulePush(pushDelayMs)
                    }
                }
            }
        }
        job.start()
    }

    /** Sends the latest position now: the reader left the book or the app. */
    fun flush() {
        synchronized(this) {
            if (pending == null) return
            pushJob?.cancel()
            schedulePush(0)
        }
    }

    /** Sends the positions that couldn't be sent before: a network is back, or the app started. */
    fun resend() {
        if (settings.account.value == null) return
        scope.launch { pushLock.withLock { settings.account.value?.let { sendUnsent(it) } } }
    }

    private suspend fun push() = pushLock.withLock {
        val (ttuId, characters) = synchronized(this) { pending.also { pending = null } } ?: return@withLock
        val account = settings.account.value ?: return@withLock
        if (synchronized(synced) { synced[ttuId] } != characters) {
            try {
                val book = books.findByTtuId(ttuId)?.takeIf { it.format == BookFormat.EPUB } ?: return@withLock
                val document = document(book) ?: return@withLock
                val xpointer = document.positions.xpointerAt(characters.toInt()) ?: return@withLock
                val percentage = characters.toDouble() / document.total
                unsent.put(UnsentPosition(ttuId, document.id, xpointer, percentage.coerceIn(0.0, 1.0), characters, now()))
            } catch (e: IOException) {
                Log.i(TAG, "Progress not sent: ${e.message}")
            }
        }
        sendUnsent(account)
    }

    /** Sends what [unsent] holds, until the server can't be reached. */
    private suspend fun sendUnsent(account: KoSyncAccount) {
        for (position in unsent.all().filterNot { it.held }) {
            try {
                send(account, position)
            } catch (e: IOException) {
                Log.i(TAG, "Progress not sent, kept for later: ${e.message}")
                return
            }
        }
    }

    private suspend fun send(account: KoSyncAccount, position: UnsentPosition) {
        val remote = client.progress(account, position.document)
        val remoteAt = remote?.timestamp?.let { it * 1000 }
        if (remote != null && remote.deviceId != settings.deviceId && remoteAt != null && remoteAt > position.readAt) {
            // Another device saved a position after this one was read.
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
            if (further) unsent.remove(position) else unsent.hold(position)
            return
        }
        client.update(account, position.document, position.progress, position.percentage, device, settings.deviceId)
        unsent.remove(position)
        synchronized(synced) { synced[position.ttuId] = position.characters }
    }

    /** The book's sync id and positions, worked out once per book (it reads the whole file). */
    private suspend fun document(book: BookEntity): Document? = documentLock.withLock {
        documents[book.id] ?: withContext(Dispatchers.Default) {
            val file = bookFile(book).takeIf { it.isFile } ?: return@withContext null
            val positions = EpubPositions.read(file) ?: return@withContext null
            Document(PartialMd5.of(file), positions)
        }?.also { documents[book.id] = it }
    }

    private companion object {
        const val TAG = "Honmani.sync"
        const val PULL_TIMEOUT_MS = 4_000L
        const val PUSH_DELAY_MS = 15_000L
        const val MAX_DOCUMENTS = 3

        /** Positions closer than this count as the same (a few characters' rounding). */
        const val MIN_CHANGE = 10

        /** More characters than a phone page holds, even in small print. */
        const val PAGE_CHARACTERS = 1_500L

        /** [MIN_CHANGE] for books whose file is gone: a server's percentage against ours. */
        const val PERCENTAGE_SLACK = 0.001
    }
}
