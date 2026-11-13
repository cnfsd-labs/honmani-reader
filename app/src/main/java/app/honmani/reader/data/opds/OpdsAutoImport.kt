package app.honmani.reader.data.opds

import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.library.RemovedBookEntity
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the automatic import is doing, for the library screen. */
data class AutoImportStatus(
    val catalogTitle: String,
    /** The book being downloaded. */
    val bookTitle: String,
    /** Books added from this catalogue so far in this run. */
    val added: Int,
    /** How much of [bookTitle] is downloaded (0–1), or null while unknown. */
    val progress: Float? = null,
)

/**
 * Downloads new Japanese books from the catalogues that have automatic import on. A run reads a
 * catalogue's newest books first, page by page, and adds every EPUB or text book marked Japanese
 * that is neither in the library nor was removed from it. Once a run has gone through a whole
 * catalogue, later runs stop at the first page that brings nothing new.
 *
 * Books go into the library only; the reader imports one when it is first opened. A run that
 * fails (no connection, wrong login) is logged and tried again on a later start.
 */
class OpdsAutoImport(
    private val repository: OpdsRepository,
    private val client: OpdsClient,
    private val importer: BookImporter,
    private val bookDao: BookDao,
    private val downloadsDir: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow<AutoImportStatus?>(null)
    val status: StateFlow<AutoImportStatus?> = _status.asStateFlow()

    private var job: Job? = null
    private var lastStart = 0L

    /**
     * Starts a run, unless one is running or (without [force], as on app starts) the last one
     * started less than [MIN_INTERVAL_MS] ago. A catalogue just added or switched on forces one.
     */
    fun start(force: Boolean = false) {
        if (job?.isActive == true) return
        if (!force && lastStart != 0L && now() - lastStart < MIN_INTERVAL_MS) return
        lastStart = now()
        job = scope.launch { run() }
    }

    /** Imports from every catalogue with automatic import on; returns how many books were added. */
    suspend fun run(): Int {
        var added = 0
        try {
            for (catalog in repository.autoImportCatalogs()) {
                added += try {
                    importFrom(catalog)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Automatic import from ${catalog.title} failed: $e")
                    0
                }
            }
        } finally {
            _status.value = null
        }
        return added
    }

    private suspend fun importFrom(catalog: OpdsCatalogEntity): Int {
        val server = repository.server(catalog)
        val root = client.feed(server, catalog.url)
        val newest = newestBooksUrl(root, catalog.url)
        if (newest == null) {
            log("Automatic import: ${catalog.title} has no feed of new books")
            return 0
        }
        val known = HashSet<String>().apply {
            addAll(bookDao.sourceIds(BookSource.OPDS))
            addAll(bookDao.removedSourceIds(BookSource.OPDS))
        }
        var added = 0
        var url: String? = newest
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val feed = if (url == catalog.url) root else client.feed(server, url)
            pages++
            val wanted = feed.entries.filter { it.book != null && it.isJapanese && it.id !in known }
            for (entry in wanted) {
                currentCoroutineContext().ensureActive()
                val status = AutoImportStatus(catalog.title, entry.title, added)
                _status.value = status
                if (add(server, entry, status)) added++
                known += entry.id
            }
            // Newest first: after one complete run, a page without new books means the rest is known.
            url = if (wanted.isEmpty() && catalog.autoImportComplete) null else feed.nextUrl
        }
        if (url == null && !catalog.autoImportComplete) repository.setAutoImportComplete(catalog.id, true)
        log("Automatic import from ${catalog.title}: $added added, $pages pages read")
        return added
    }

    /** Downloads and adds one book; true if it is new in the library. */
    private suspend fun add(server: OpdsServer, entry: OpdsEntry, status: AutoImportStatus): Boolean {
        val acquisition = entry.book ?: return false
        withContext(Dispatchers.IO) { downloadsDir.mkdirs() }
        val temp = File(downloadsDir, "${UUID.randomUUID()}.part")
        try {
            var percent = -1
            client.download(server, acquisition.url, temp) { done, total ->
                // Whole percents only: the library redraws for each change.
                val now = if (total > 0) (done * 100 / total).toInt() else -1
                if (now != percent) {
                    percent = now
                    _status.value = status.copy(progress = if (now >= 0) now / 100f else null)
                }
            }
            val name = bookFileName(entry.title, acquisition.type)
            val result = withContext(Dispatchers.IO) {
                temp.inputStream().use { importer.import(it, name, BookSource.OPDS, entry.id) }
            }
            if (result is BookImporter.Result.Added) {
                // Handed to the reader when first opened; nothing here can make the library drop it.
                importer.restoreFor(result.book)
                return true
            }
            // A copy of a book already in the library, or a file the library can't read: don't
            // download it again on every start.
            bookDao.insertRemoved(RemovedBookEntity(BookSource.OPDS, entry.id, now()))
            return false
        } finally {
            withContext(Dispatchers.IO) { temp.delete() }
        }
    }

    companion object {
        /** At most one run per this interval, however often the app is opened. */
        const val MIN_INTERVAL_MS = 10 * 60 * 1000L

        /** A first run reads at most this many pages (Calibre-Web: 60 books each). */
        const val MAX_PAGES = 100

        /**
         * The feed of a catalogue's newest books: the one the start feed links as "new", else
         * Calibre-Web's "Recently added" (`…/opds/new`), else the start feed itself if it lists
         * books.
         */
        internal fun newestBooksUrl(root: OpdsFeed, rootUrl: String): String? {
            root.newestUrl?.let { return it }
            root.entries.firstNotNullOfOrNull { entry ->
                entry.feedUrl?.takeIf { url -> path(url).trimEnd('/').endsWith("/new") }
            }?.let { return it }
            return rootUrl.takeIf { root.entries.any { it.book != null } }
        }

        private fun path(url: String): String = try {
            URI(url).path.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
