package app.honmani.reader.ui.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The loaded reader page, as [TtuLibrary] drives it. Implemented by the reader WebView. */
interface TtuPage {
    fun evaluate(script: String)

    /** Loads [path] on the reader origin. */
    fun load(path: String)
}

/** A book as ttu stores it. */
data class TtuBook(val id: Long, val title: String)

/** A book's reading position: [progress] 0 to 1, [characters] read up to it (if ttu says). */
data class TtuProgress(val id: Long, val progress: Float, val characters: Long? = null)

class TtuLibraryException(message: String) : Exception(message)

/**
 * Honmani's side of `assets/honmani/library-bridge.js`: hands library books to ッツ Ebook Reader
 * (ttu), opens them, removes them and reads reading progress. Commands run one at a time and
 * need a loaded reader page; the reader WebView [attach]es it when a page has loaded.
 */
class TtuLibrary(
    private val pageTimeoutMs: Long = PAGE_TIMEOUT_MS,
    private val importTimeoutMs: Long = IMPORT_TIMEOUT_MS,
) {
    private val page = MutableStateFlow<TtuPage?>(null)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<LibraryMessage>>()
    private val requestIds = AtomicLong()
    private val mutex = Mutex()

    private val _progress = MutableSharedFlow<List<TtuProgress>>(extraBufferCapacity = 4)

    /** Progress reports, after [requestProgress]. */
    val progress: SharedFlow<List<TtuProgress>> = _progress.asSharedFlow()

    private val _finished = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** ttu ids of books whose end the reader reached. */
    val finished: SharedFlow<Long> = _finished.asSharedFlow()

    /** A reader page has loaded; commands can run. */
    fun attach(page: TtuPage) {
        this.page.value = page
        accentScript?.let(page::evaluate)
    }

    /** The page is going away: its scripts will never answer pending commands. */
    fun detach() {
        page.value = null
        val failed = pending.values.toList()
        pending.clear()
        failed.forEach { it.completeExceptionally(TtuLibraryException("The reader page reloaded")) }
    }

    /**
     * Makes sure ttu holds the book: the one it stored as [known], if still there, or a fresh
     * import of the file at [url] (a path on the reader origin) named [fileName].
     * ttu replaces a book of the same title under its old id, so when it holds one of [keepIds]
     * (other library books) under the new book's title, the new book is stored as a separate book
     * under its title plus [titleSuffix].
     */
    suspend fun ensure(
        known: TtuBook?,
        url: String,
        fileName: String,
        keepIds: List<Long> = emptyList(),
        titleSuffix: String = "",
    ): TtuBook {
        val reply = command(importTimeoutMs, "ensure") { requestId ->
            put("requestId", requestId)
            put("ttuId", known?.id)
            put("ttuTitle", known?.title)
            put("url", url)
            put("fileName", fileName)
            put("keepIds", JsonArray(keepIds.map(::JsonPrimitive)))
            put("titleSuffix", titleSuffix)
        }
        return when (reply) {
            is LibraryMessage.Ready -> TtuBook(reply.id, reply.title)
            is LibraryMessage.Failed -> throw TtuLibraryException(reply.message)
            else -> throw TtuLibraryException("Unexpected reply")
        }
    }

    /** Deletes the book from ttu, if it is there. */
    suspend fun remove(ttuId: Long) {
        command(pageTimeoutMs, "remove") { requestId ->
            put("requestId", requestId)
            put("ttuId", ttuId)
        }
    }

    /** Deletes ttu's reading position for the book, so it opens at the start. */
    suspend fun resetProgress(ttuId: Long) {
        command(pageTimeoutMs, "resetProgress") { requestId ->
            put("requestId", requestId)
            put("ttuId", ttuId)
        }
    }

    /** ttu's saved reading position for the book: characters before it (null if none) and when (ms). */
    suspend fun bookmark(ttuId: Long): Pair<Long?, Long> {
        val reply = command(pageTimeoutMs, "bookmark") { requestId ->
            put("requestId", requestId)
            put("ttuId", ttuId)
        }
        return (reply as? LibraryMessage.Bookmark)?.let { it.characters to it.modified }
            ?: throw TtuLibraryException("Unexpected reply")
    }

    /**
     * Saves a reading position for the book, which ttu opens it at next; [modified] (ms) is when
     * it was read (progress sync compares it with the server's), now if null.
     */
    suspend fun setBookmark(ttuId: Long, characters: Long, progress: Double, modified: Long? = null) {
        command(pageTimeoutMs, "setBookmark") { requestId ->
            put("requestId", requestId)
            put("ttuId", ttuId)
            put("characters", characters)
            put("progress", progress)
            modified?.let { put("modified", it) }
        }
    }

    /** Opens the book in ttu's reader, inside the running page (see `library-bridge.js`). */
    suspend fun open(ttuId: Long) {
        mutex.withLock {
            awaitPage().evaluate("window.honmaniLibrary && honmaniLibrary.open({\"ttuId\":$ttuId});")
        }
    }

    /**
     * Shows another ttu page, such as its settings, inside the running page (no page load, so
     * the previous page never shows through). Ignored while a command runs or without a page.
     */
    fun show(path: String) {
        if (mutex.isLocked) return
        val target = JsonPrimitive(path).toString()
        page.value?.evaluate(
            "window.honmaniLibrary ? honmaniLibrary.navigate({\"path\":$target}) : location.assign($target);",
        )
    }

    /** Opens ttu's table of contents, as its button in the reader bar does (`reader-menu.js`). */
    fun openContents() {
        page.value?.evaluate("window.honmaniReaderMenu && honmaniReaderMenu.openContents();")
    }

    /**
     * Opens the book at a search hit (`book-search.js`): saves the hit's reading position as
     * ttu's bookmark and reloads the page, where ttu restores it in either reading mode. After
     * the reload the page marks the hit for a moment, found by the letters [before] it and the
     * [match] itself. The page counts as gone at once, as with [reload].
     */
    suspend fun openHit(ttuId: Long, characters: Long, progress: Double, before: String, match: String) {
        setBookmark(ttuId, characters, progress)
        val current = page.value ?: return
        detach()
        val hit = Json.encodeToString(buildJsonObject {
            put("before", before)
            put("match", match)
        })
        current.evaluate("window.honmaniSearch ? honmaniSearch.reload($hit) : location.reload();")
    }

    /** The app's accent colours (`#rrggbb`), for ttu's settings; kept for later pages. */
    fun setAccent(accent: String, onAccent: String) {
        accentScript = "window.honmaniSetAccent && honmaniSetAccent(${JsonPrimitive(accent)}, ${JsonPrimitive(onAccent)});"
        accentScript?.let { page.value?.evaluate(it) }
    }

    private var accentScript: String? = null

    /** ttu's stored reader settings (see `reader-settings.js`). */
    suspend fun readSettings(): Map<String, String?> {
        val reply = command(pageTimeoutMs, "read", target = "honmaniReaderSettings") { requestId -> put("requestId", requestId) }
        return (reply as? LibraryMessage.Settings)?.values ?: throw TtuLibraryException("Unexpected reply")
    }

    /** Stores reader settings for ttu; they apply after [reload]. Does nothing without a page. */
    fun writeSettings(values: Map<String, String>) {
        val json = Json.encodeToString(buildJsonObject { put("settings", buildJsonObject { values.forEach { (key, value) -> put(key, value) } }) })
        page.value?.evaluate("window.honmaniReaderSettings && honmaniReaderSettings.write($json);")
    }

    /**
     * Lists Honmani's downloaded fonts ([family] to file name) in ttu's user fonts; they apply
     * after [reload]. Does nothing without a page.
     */
    fun writeFonts(fonts: Map<String, String>) {
        val list = buildJsonArray {
            fonts.forEach { (family, fileName) ->
                add(buildJsonObject {
                    put("name", family)
                    put("fileName", fileName)
                })
            }
        }
        val json = Json.encodeToString(buildJsonObject { put("fonts", list) })
        page.value?.evaluate("window.honmaniReaderSettings && honmaniReaderSettings.fonts($json);")
    }

    /**
     * Reloads the reader page, or loads [path] instead, so ttu reads its settings again. The page
     * counts as gone at once: commands sent before the WebView reports the new load would reach
     * the old page and be lost with it (a book tapped right after closing the settings didn't
     * open). They wait for the new page instead.
     */
    fun reload(path: String? = null) {
        val current = page.value ?: return
        detach()
        if (path != null) {
            current.load(path)
        } else {
            current.evaluate("window.honmaniReaderSettings ? honmaniReaderSettings.reload() : location.reload();")
        }
    }

    /** Asks for reading progress, answered on [progress]; does nothing without a page. */
    fun requestProgress() {
        page.value?.evaluate("window.honmaniLibrary && honmaniLibrary.progress();")
    }

    /** A message from the bridge (on the main thread). */
    fun onMessage(message: LibraryMessage) {
        when (message) {
            is LibraryMessage.Progress -> _progress.tryEmit(message.books)
            is LibraryMessage.Finished -> _finished.tryEmit(message.id)
            is LibraryMessage.Reply -> pending.remove(message.requestId)?.complete(message)
        }
    }

    private suspend fun command(
        timeoutMs: Long,
        name: String,
        target: String = "honmaniLibrary",
        arguments: JsonObjectBuilder.(requestId: Long) -> Unit,
    ): LibraryMessage = mutex.withLock {
        val page = awaitPage()
        val requestId = requestIds.incrementAndGet()
        val reply = CompletableDeferred<LibraryMessage>()
        pending[requestId] = reply
        try {
            val json = Json.encodeToString(buildJsonObject { arguments(requestId) })
            page.evaluate("window.$target && $target.$name($json);")
            withTimeout(timeoutMs) { reply.await() }
        } catch (_: TimeoutCancellationException) {
            throw TtuLibraryException("The reader did not answer")
        } finally {
            pending.remove(requestId)
        }
    }

    private suspend fun awaitPage(): TtuPage = try {
        withTimeout(pageTimeoutMs) { page.filterNotNull().first() }
    } catch (_: TimeoutCancellationException) {
        throw TtuLibraryException("The reader is not ready")
    }

    companion object {
        /** ttu's reading page. */
        const val BOOK_PATH = "/b"

        /** Where the app serves library files on the reader origin (see BookPathHandler). */
        const val BOOK_FILES_PATH = "/honmani-books/"

        /** Where the app serves downloaded reader fonts (see FontPathHandler, reader-settings.js). */
        const val FONT_FILES_PATH = "/honmani-fonts/"

        private const val PAGE_TIMEOUT_MS = 20_000L

        /** A little longer than the bridge's own waits (15 s for ttu's input plus 180 s to import, library-bridge.js). */
        private const val IMPORT_TIMEOUT_MS = 210_000L
    }
}
