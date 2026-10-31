package app.honmani.reader.ui.opds

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.opds.OpdsCatalog
import app.honmani.reader.data.opds.OpdsClient
import app.honmani.reader.data.opds.OpdsEntry
import app.honmani.reader.data.opds.OpdsException
import app.honmani.reader.data.opds.OpdsRepository
import app.honmani.reader.data.opds.OpdsSearch
import app.honmani.reader.data.opds.OpdsServer
import app.honmani.reader.data.opds.bookFileName
import app.honmani.reader.data.sync.CatalogSync
import app.honmani.reader.data.sync.KoSyncException
import app.honmani.reader.ui.library.BookAdder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One feed page on the browsing stack, with the pages loaded so far appended. */
data class CatalogPage(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String?,
    val search: OpdsSearch?,
    val loadingMore: Boolean = false,
)

data class BrowseState(
    val catalog: OpdsCatalog,
    /** The feeds opened so far; the last one is shown. */
    val pages: List<CatalogPage> = emptyList(),
    val loading: Boolean = true,
    val error: OpdsException.Reason? = null,
) {
    val page: CatalogPage? get() = pages.lastOrNull()

    /** The search of the current feed, or of the catalogue's start feed. */
    val search: OpdsSearch? get() = page?.search ?: pages.firstOrNull()?.search
}

sealed interface DownloadState {
    /** [progress] from 0 to 1, or null while the size is unknown. */
    data class Running(val progress: Float?) : DownloadState

    data class Failed(val reason: DownloadFailure) : DownloadState
}

enum class DownloadFailure { NETWORK, UNAUTHORIZED, SERVER, UNREADABLE, READER, HOTSPOT }

/** The catalogue dialog: adding a catalogue ([id] null) or changing one. */
data class CatalogEditor(
    val id: Long? = null,
    val address: String = "",
    val username: String = "",
    /** Whether a password is stored; the field stays empty and keeps it unless typed over. */
    val hasPassword: Boolean = false,
    /** Download new Japanese books automatically; on for new catalogues. */
    val autoImport: Boolean = true,
    /** Reading progress sync runs on this server with this login (Calibre-Web Automated). */
    val syncProgress: Boolean = false,
    /** The server sync runs on now, if another one: turning it on here replaces it. */
    val otherSyncServer: String? = null,
    val checking: Boolean = false,
    val error: OpdsException.Reason? = null,
    /** Turning sync on failed (the server has none, say); nothing was saved. */
    val syncError: KoSyncException.Reason? = null,
)

data class OpdsUiState(
    val catalogs: List<OpdsCatalog> = emptyList(),
    val browsing: BrowseState? = null,
    val editor: CatalogEditor? = null,
    /** Downloads by entry id. */
    val downloads: Map<String, DownloadState> = emptyMap(),
    /** Entry ids of the books already in the library. */
    val inLibrary: Set<String> = emptySet(),
)

/** Book catalogues (OPDS): the list, browsing one, and downloading books into the library. */
class OpdsViewModel(
    private val repository: OpdsRepository,
    private val client: OpdsClient,
    private val adder: BookAdder,
    bookDao: BookDao,
    private val downloadsDir: File,
    /** Starts an automatic import now (after a catalogue with it was saved). */
    private val startAutoImport: () -> Unit = {},
    /** Reading progress sync with a catalogue's login; null leaves sync out of the dialog. */
    private val catalogSync: CatalogSync? = null,
) : ViewModel() {
    private val browsing = MutableStateFlow<BrowseState?>(null)
    private val editor = MutableStateFlow<CatalogEditor?>(null)
    private val downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** Entries added in this session that matched a book already in the library. */
    private val matched = MutableStateFlow<Set<String>>(emptySet())
    private var server: OpdsServer? = null
    private var loadJob: Job? = null

    /** The last feed request, for retrying it. */
    private var lastRequest: (suspend () -> Unit)? = null
    private val thumbnails = LruCache<String, ImageBitmap>(THUMBNAIL_CACHE_SIZE)

    val uiState: StateFlow<OpdsUiState> = combine(
        repository.catalogs,
        combine(browsing, editor) { browsing, editor -> browsing to editor },
        downloads,
        bookDao.observeSourceIds(BookSource.OPDS),
        matched,
    ) { catalogs, (browsing, editor), downloads, sourceIds, matched ->
        OpdsUiState(catalogs, browsing, editor, downloads, sourceIds.toSet() + matched)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpdsUiState())

    fun openCatalog(catalog: OpdsCatalog) {
        loadJob?.cancel()
        browsing.value = BrowseState(catalog)
        loadJob = viewModelScope.launch {
            val server = repository.server(catalog.id)
            if (server == null) {
                browsing.value = null
                return@launch
            }
            this@OpdsViewModel.server = server
            request { push(server.url, catalog.title, server) }
        }
    }

    fun openFeed(url: String, title: String) {
        val server = server ?: return
        loadJob?.cancel()
        browsing.update { it?.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { request { push(url, title, server) } }
    }

    fun search(terms: String) {
        val state = browsing.value ?: return
        val search = state.search ?: return
        val server = server ?: return
        if (terms.isBlank()) return
        loadJob?.cancel()
        browsing.value = state.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            request {
                val url = client.searchUrl(server, search, terms)
                push(url, "“${terms.trim()}”", server)
            }
        }
    }

    /** Loads the current feed's next page, if it has one and none is loading. */
    fun loadMore() {
        val state = browsing.value ?: return
        val page = state.page ?: return
        val next = page.nextUrl ?: return
        val server = server ?: return
        if (page.loadingMore || state.loading) return
        replaceTop { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val feed = client.feed(server, next)
                // The page may no longer be on top (an entry was opened meanwhile): it still gets its entries.
                replacePage(page.url) { top ->
                    val known = top.entries.mapTo(HashSet()) { it.id }
                    top.copy(
                        entries = top.entries + feed.entries.filter { it.id !in known },
                        nextUrl = feed.nextUrl?.takeIf { it != next },
                        loadingMore = false,
                    )
                }
            } catch (_: OpdsException) {
                // Scrolling to the end again retries.
                replacePage(page.url) { it.copy(loadingMore = false) }
            }
        }
    }

    fun retry() {
        val state = browsing.value ?: return
        val again = lastRequest
        if (server == null || again == null) {
            openCatalog(state.catalog)
            return
        }
        loadJob?.cancel()
        browsing.value = state.copy(loading = true, error = null)
        loadJob = viewModelScope.launch { request(again) }
    }

    /** Back: the previous feed, or out of the catalogue. Returns false when there was nothing to close. */
    fun back(): Boolean {
        val state = browsing.value ?: return false
        loadJob?.cancel()
        when {
            state.loading || state.error != null -> {
                // Leave the feed that is loading or failed; stay on the one below.
                if (state.pages.isEmpty()) close() else browsing.value = state.copy(loading = false, error = null)
            }
            state.pages.size > 1 -> browsing.value = state.copy(pages = state.pages.dropLast(1))
            else -> close()
        }
        return true
    }

    fun close() {
        loadJob?.cancel()
        browsing.value = null
        server = null
        lastRequest = null
    }

    /** Runs a feed request; a failure is shown with a retry button. */
    private suspend fun request(block: suspend () -> Unit) {
        lastRequest = block
        try {
            block()
        } catch (e: OpdsException) {
            browsing.update { it?.copy(loading = false, error = e.reason) }
        }
    }

    private suspend fun push(url: String, title: String, server: OpdsServer) {
        val feed = client.feed(server, url)
        val page = CatalogPage(
            url = url,
            // The title of the entry the user tapped (or the search terms); the feed's own otherwise.
            title = title.ifBlank { feed.title },
            entries = feed.entries,
            nextUrl = feed.nextUrl?.takeIf { it != url },
            search = feed.search,
        )
        browsing.update { state -> state?.copy(pages = state.pages + page, loading = false, error = null) }
    }

    /** Changes the page loaded from [url], wherever it is on the stack; nothing if it was left. */
    private fun replacePage(url: String, transform: (CatalogPage) -> CatalogPage) {
        browsing.update { state ->
            val index = state?.pages?.indexOfLast { it.url == url } ?: -1
            if (state == null || index < 0) return@update state
            state.copy(pages = state.pages.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    private fun replaceTop(transform: (CatalogPage) -> CatalogPage) {
        browsing.update { state ->
            val top = state?.page ?: return@update state
            state.copy(pages = state.pages.dropLast(1) + transform(top))
        }
    }

    /** A cover thumbnail of the open catalogue, loaded with its login; null if there is none. */
    suspend fun thumbnail(url: String): ImageBitmap? {
        thumbnails.get(url)?.let { return it }
        val server = server ?: return null
        return try {
            val bytes = client.bytes(server, url, OpdsClient.MAX_THUMBNAIL_BYTES)
            withContext(Dispatchers.Default) { decodeThumbnail(bytes) }?.also { thumbnails.put(url, it) }
        } catch (_: OpdsException) {
            null
        }
    }

    /** Downloads the entry's book and adds it to the library. */
    fun download(entry: OpdsEntry) {
        val acquisition = entry.book ?: return
        val server = server ?: return
        if (downloads.value[entry.id] is DownloadState.Running) return
        setDownload(entry.id, DownloadState.Running(null))
        viewModelScope.launch {
            downloadsDir.mkdirs()
            val temp = File(downloadsDir, "${UUID.randomUUID()}.part")
            try {
                var reported = -1
                client.download(server, acquisition.url, temp) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (percent != reported) {
                        reported = percent
                        setDownload(entry.id, DownloadState.Running(if (percent >= 0) percent / 100f else null))
                    }
                }
                val name = fileName(entry.title, acquisition.type)
                // On the main thread: handing the book to the reader drives the WebView. The import
                // itself moves to a background thread.
                val result = temp.inputStream().use { adder.add(it, name, BookSource.OPDS, entry.id) }
                when (result) {
                    is BookAdder.Result.Added -> setDownload(entry.id, null)
                    is BookAdder.Result.AlreadyInLibrary -> {
                        matched.update { it + entry.id }
                        setDownload(entry.id, null)
                    }
                    BookAdder.Result.Unsupported, BookAdder.Result.Unreadable ->
                        setDownload(entry.id, DownloadState.Failed(DownloadFailure.UNREADABLE))
                    is BookAdder.Result.ReaderFailed ->
                        setDownload(entry.id, DownloadState.Failed(DownloadFailure.READER))
                }
            } catch (e: OpdsException) {
                val failure = when (e.reason) {
                    OpdsException.Reason.UNAUTHORIZED -> DownloadFailure.UNAUTHORIZED
                    OpdsException.Reason.NETWORK, OpdsException.Reason.CERTIFICATE -> DownloadFailure.NETWORK
                    OpdsException.Reason.HOTSPOT -> DownloadFailure.HOTSPOT
                    else -> DownloadFailure.SERVER
                }
                setDownload(entry.id, DownloadState.Failed(failure))
            } finally {
                withContext(Dispatchers.IO) { temp.delete() }
            }
        }
    }

    private fun setDownload(entryId: String, state: DownloadState?) {
        downloads.update { if (state == null) it - entryId else it + (entryId to state) }
    }

    fun startAdding() {
        editor.value = CatalogEditor(otherSyncServer = catalogSync?.otherServer("", null))
    }

    fun startEditing(catalog: OpdsCatalog) {
        editor.value = CatalogEditor(
            id = catalog.id,
            address = catalog.url,
            username = catalog.username.orEmpty(),
            hasPassword = catalog.hasPassword,
            autoImport = catalog.autoImport,
            syncProgress = catalogSync?.isOn(catalog.url, catalog.username) == true,
            otherSyncServer = catalogSync?.otherServer(catalog.url, catalog.username),
        )
    }

    fun dismissEditor() {
        editor.value = null
    }

    /**
     * Checks the catalogue and saves it; a new one opens at once. An empty [password] while editing
     * keeps the stored password.
     */
    fun saveCatalog(address: String, username: String, password: String, autoImport: Boolean, syncProgress: Boolean = false) {
        val current = editor.value ?: return
        if (current.checking) return
        editor.value = current.copy(address = address, username = username, checking = true, error = null, syncError = null)
        viewModelScope.launch {
            val user = username.trim().ifEmpty { null }
            val keepPassword = current.id != null && current.hasPassword && password.isEmpty() && user != null
            val loginPassword = if (keepPassword) {
                repository.server(current.id)?.password
            } else {
                password.takeIf { user != null }
            }
            try {
                val (url, feed) = repository.connect(address, user, loginPassword)
                if (!updateSync(syncProgress, url, user, loginPassword, password.isNotEmpty())) return@launch
                val title = feed.title.ifBlank { url.substringAfter("://").substringBefore('/') }
                val id = repository.save(
                    id = current.id,
                    title = title,
                    url = url,
                    username = user,
                    password = if (keepPassword) null else password,
                    autoImport = autoImport,
                )
                editor.value = null
                if (autoImport) startAutoImport()
                // With automatic import on, the books come by themselves: stay in the library,
                // which shows them arriving. Without it, open the catalogue to pick books.
                if (current.id == null && !autoImport) {
                    openCatalog(OpdsCatalog(id, title, url, user, password.isNotEmpty(), autoImport))
                }
            } catch (e: OpdsException) {
                editor.update { it?.copy(checking = false, error = e.reason) }
            }
        }
    }

    /**
     * Turns sync on or off for the server of [url] as the switch says. False if turning it on
     * failed: the dialog stays open with the reason and nothing is saved.
     */
    private suspend fun updateSync(on: Boolean, url: String, user: String?, password: String?, newPassword: Boolean): Boolean {
        val sync = catalogSync ?: return true
        if (!on) {
            sync.turnOff(url, user)
            return true
        }
        if (sync.isOn(url, user) && !newPassword) return true
        return try {
            if (user == null || password == null) throw KoSyncException(KoSyncException.Reason.UNAUTHORIZED, "No login")
            sync.turnOn(url, user, password)
            true
        } catch (e: KoSyncException) {
            editor.update { it?.copy(checking = false, syncError = e.reason) }
            false
        }
    }

    fun deleteCatalog(id: Long) {
        editor.value = null
        if (browsing.value?.catalog?.id == id) close()
        viewModelScope.launch { repository.delete(id) }
    }

    companion object {
        private const val THUMBNAIL_CACHE_SIZE = 120
        private const val THUMBNAIL_MAX_SIDE = 320

        /** The name the library sees: the entry's title (TXT books take their title from it). */
        internal fun fileName(title: String, type: String): String = bookFileName(title, type)

        private fun decodeThumbnail(bytes: ByteArray): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                OpdsViewModel(
                    repository = container.opdsRepository,
                    client = container.opdsClient,
                    adder = BookAdder(container.bookDao, container.bookImporter, container.ttuLibrary),
                    bookDao = container.bookDao,
                    downloadsDir = container.downloadsDir,
                    startAutoImport = { container.opdsAutoImport.start(force = true) },
                    catalogSync = CatalogSync(container.koSyncSettings, container.koSyncClient),
                )
            }
        }
    }
}
