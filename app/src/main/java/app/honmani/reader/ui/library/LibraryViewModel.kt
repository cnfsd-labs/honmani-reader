package app.honmani.reader.ui.library

import app.honmani.reader.diagnostics.logWarning
import app.honmani.reader.readaloud.AudiobookExportResult
import app.honmani.reader.readaloud.AudiobookExporter
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.backup.WaitingBook
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.library.DocumentTree
import app.honmani.reader.data.library.ExportNames
import app.honmani.reader.data.library.FolderResult
import app.honmani.reader.data.library.Folders
import app.honmani.reader.data.settings.LibraryFolderView
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.library.ResolverDocumentTree
import app.honmani.reader.data.library.SampleBook
import app.honmani.reader.data.library.TreeWalker
import app.honmani.reader.data.library.WatchedFolder
import app.honmani.reader.data.library.WatchedFolderScan
import app.honmani.reader.data.opds.AutoImportStatus
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.stats.FinishedBooks
import app.honmani.reader.data.sync.SyncQuestion
import app.honmani.reader.lookup.Romaji
import app.honmani.reader.lookup.TextReading
import app.honmani.reader.ui.reader.TtuBook
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuLibraryException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LibraryBook(
    val id: Long,
    val title: String,
    val author: String?,
    val cover: File?,
    /** Reading progress, 0 to 1. */
    val progress: Float,
    val status: ReadingStatus = ReadingStatus.of(progress, finishedAt = null),
    val hidden: Boolean = false,
    /** Identifies the book's file, and with it its difficulty grade. */
    val contentHash: String = "",
    val format: BookFormat = BookFormat.EPUB,
    /** The folder the book is in; null for none. */
    val folderId: Long? = null,
)

/** A folder with the books in it (not counting hidden ones). */
data class LibraryFolder(val id: Long, val name: String, val count: Int)

data class LibraryUiState(
    /** The books shown (after filter and search); null until the library has loaded. */
    val books: List<LibraryBook>? = null,
    /** All books in the library, before filter and search. */
    val totalBooks: Int = 0,
    /** The folders, by name; empty until the reader makes one, and then the library shows no folder UI. */
    val folders: List<LibraryFolder> = emptyList(),
    /** Only books in this folder; null for all. */
    val folderId: Long? = null,
    /** The row of folder chips is open (it stays closed, and the chosen folder shows as one chip, otherwise). */
    val foldersOpen: Boolean = false,
    /** How many books (not hidden, in the chosen folder) have each status, for the filter buttons. */
    val counts: Map<ReadingStatus, Int> = emptyMap(),
    /** Only books with this status; null for all. */
    val filter: ReadingStatus? = null,
    /** Showing the hidden books (and only those) instead of the library. */
    val showHidden: Boolean = false,
    val hiddenCount: Int = 0,
    /** The book read last, if the reader is still reading it: offered as "Continue reading". */
    val continueBook: LibraryBook? = null,
    /** The last session of [continueBook] played its audiobook, which is still there: the bubble says "Continue listening". */
    val continueListening: Boolean = false,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    /** The reader's skill, once there is one: for [LibrarySort.BEST_FIT]. */
    val skill: Int? = null,
    /** Name of the file being added, if any. */
    val adding: String? = null,
    /** The book being opened, if any. */
    val opening: Long? = null,
    /** A folder import or the library export that is running, if any. */
    val batch: BatchProgress? = null,
)

/** A running folder import or library export: [total] is null while the folder is still searched. */
data class BatchProgress(
    val exporting: Boolean,
    val done: Int = 0,
    val total: Int? = null,
    val current: String = "",
)

/** Something to tell the user once, as a snackbar. */
sealed interface LibraryNotice {
    data class AlreadyInLibrary(val title: String) : LibraryNotice

    data class Unsupported(val fileName: String) : LibraryNotice

    data class Unreadable(val fileName: String) : LibraryNotice

    /** Progress could not be reset; [bookId] lets the snackbar offer a retry. */
    data class ResetFailed(val title: String, val bookId: Long? = null) : LibraryNotice

    /** ッツ Ebook Reader could not import or open the book. */
    data class ReaderFailed(val title: String, val bookId: Long? = null) : LibraryNotice

    data class CopySaved(val title: String) : LibraryNotice

    /** Due words in review read in the book just closed, which count as reviewed. */
    data class PassedWhileReading(val count: Int) : LibraryNotice

    data class CopyFailed(val title: String) : LibraryNotice

    /** The watched folder had new books ([imported] added, [failed] unreadable); sent only if there were any. */
    data class WatchedFolderAdded(val imported: Int, val failed: Int) : LibraryNotice

    /** A folder import ended ([stopped]: cancelled; [incomplete]: too many files or too deep). */
    data class FolderImported(
        val imported: Int,
        val duplicates: Int,
        val failed: Int,
        val stopped: Boolean,
        val incomplete: Boolean,
    ) : LibraryNotice

    /** The library export ended. */
    data class LibraryExported(val exported: Int, val failed: Int, val stopped: Boolean) : LibraryNotice

    /** The chosen folder holds no EPUB or text file. */
    data object NoBooksInFolder : LibraryNotice

    /** The library has no book to export. */
    data object NothingToExport : LibraryNotice

    /** Shared or pasted text had nothing to read. */
    data object TextEmpty : LibraryNotice

    /** Shared or pasted text is longer than [SharedText.MAX_BYTES]. */
    data object TextTooLarge : LibraryNotice

    /** The chosen folder cannot be read or written. */
    data object FolderUnusable : LibraryNotice

    /** An audiobook export ended with [result]. */
    data class AudiobookExport(val title: String, val result: AudiobookExportResult) : LibraryNotice
}

/** The books and what their order depends on. */
private data class Ordering(
    val entities: List<BookEntity>,
    val grades: Map<String, StoredDifficulty>,
    val sort: LibrarySort,
    val skill: Int?,
    val dismissedContinue: String?,
)

/** Which books the library shows: one folder and one reading status (or all), or the hidden ones. */
private data class Shelf(val status: ReadingStatus? = null, val hidden: Boolean = false, val folderId: Long? = null)

/** A file the user picked: its display name and contents. */
class PickedFile(val name: String, val input: InputStream)

class LibraryViewModel(
    private val dao: BookDao,
    private val importer: BookImporter,
    private val ttu: TtuLibrary,
    private val openFile: (Uri) -> PickedFile?,
    private val now: () -> Long = System::currentTimeMillis,
    /** Due words passed while reading since the last book closed, counting again from 0. */
    private val takePassedWhileReading: () -> Int = { 0 },
    /** What the automatic import from book servers is doing, if it runs. */
    val autoImport: StateFlow<AutoImportStatus?> = MutableStateFlow(null),
    /** How a title reads in kana (with the dictionary), for romaji search. */
    private val reading: suspend (String) -> String = { it },
    /** A sample book that comes with the app. */
    private val openSample: (SampleBook) -> PickedFile? = { null },
    /**
     * Takes a newer reading position from progress sync before the book (ttu id) opens, asking
     * (true: go to the other device's place) when it is further back.
     */
    private val syncPosition: suspend (BookEntity, Long, suspend (SyncQuestion) -> Boolean) -> Unit = { _, _, _ -> },
    /** Book grades by content hash, for sorting by difficulty. */
    private val grades: Flow<Map<String, StoredDifficulty>> = flowOf(emptyMap()),
    /** The chosen order (a [LibrarySort] name) and where a new choice is kept. */
    private val sortName: Flow<String?> = flowOf(null),
    private val saveSort: (String) -> Unit = {},
    /** The reader's skill (`Skill.estimate`), for [LibrarySort.BEST_FIT]. */
    private val skill: Flow<Int?> = flowOf(null),
    /** The "Continue reading" bubble swiped away ([continueKey]), and where that is kept. */
    private val dismissedContinue: Flow<String?> = flowOf(null),
    private val saveDismissedContinue: (String) -> Unit = {},
    /** Writes a book's file to a document the reader picked (a content URI). */
    private val writeDocument: suspend (File, Uri) -> Unit = { _, _ -> },
    /**
     * Drops what was worked out for a removed book (grade, words, a restored position waiting for
     * it), by content hash; the statistics, lookups and saved words stay. Off the main thread.
     */
    private val forgetDerivedData: (String) -> Unit = {},
    /** Keeps a finished book that is removed, so the "books finished" counts don't drop. */
    private val recordFinished: suspend (FinishedBookEntity) -> Unit = {},
    /** Deletes a book's reading statistics (its reading days, by book id) and its finished record (by content hash). */
    private val forgetStatistics: suspend (bookId: Long, contentHash: String) -> Unit = { _, _ -> },
    /** The book goes but its statistics stay: they keep its content hash as their name in sync files. */
    private val keepStatistics: (bookId: Long, contentHash: String) -> Unit = { _, _ -> },
    /** Deletes the reader's named bookmarks in a removed book (by content hash). */
    private val forgetBookmarks: suspend (contentHash: String) -> Unit = {},
    /** Deletes the highlights in a removed book (by content hash). */
    private val forgetHighlights: suspend (contentHash: String) -> Unit = {},
    /** Deletes the Read aloud pronunciations of a removed book (by content hash). */
    private val forgetPronunciations: suspend (contentHash: String) -> Unit = {},
    /** The reader's folders; null: no folders. */
    private val folders: Folders? = null,
    /** Opens a folder the user picked (a tree address) for the folder import and the export. */
    private val openTree: (Uri) -> DocumentTree? = { null },
    /** Keeps the chosen folder and the open folder row over a restart; null: kept in memory only. */
    private val folderView: LibraryFolderView? = null,
    /** The folder looked at for new books when the library opens; null: none. */
    private val watchedFolder: WatchedFolder? = null,
    /** The title of a shared or pasted text that has none of its own, by default with today's date. */
    private val textTitle: () -> String = { "Shared text" },
    /** Saves a book's audiobook into a folder the reader picked; null: no audiobooks. */
    private val audiobookExporter: AudiobookExporter? = null,
    /** Whether the book (content hash) should be offered as "Continue listening". */
    private val continueListening: (contentHash: String) -> Boolean = { false },
    /** Ticks when that may have changed. */
    private val listeningChanges: Flow<Int> = flowOf(0),
    /** The next opening of the book (content hash) starts its audiobook. */
    private val armAutoplay: (contentHash: String) -> Unit = {},
) : ViewModel() {
    private val adding = MutableStateFlow<String?>(null)
    private val opening = MutableStateFlow<Long?>(null)
    private val batch = MutableStateFlow<BatchProgress?>(null)
    private var batchJob: Job? = null
    private var watchJob: Job? = null
    private val shelf = MutableStateFlow(Shelf(folderId = folderView?.chosen))
    private val foldersOpen = MutableStateFlow(folderView?.open ?: false)
    private val folderList = folders?.observeAll() ?: flowOf(emptyList())

    private val query = MutableStateFlow("")

    /** Readings of title and author by book id, worked out when a romaji search starts. */
    private val readings = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val searchTerms = combine(query, readings) { query, readings -> query to readings }
    private val addMutex = Mutex()
    private val adder = BookAdder(dao, importer, ttu)
    private val _syncQuestion = MutableStateFlow<Pair<SyncQuestion, CompletableDeferred<Boolean>>?>(null)

    /** Progress sync asks where to continue the book being opened; answered with [answerSync]. */
    val syncQuestion: StateFlow<SyncQuestion?> =
        _syncQuestion.map { it?.first }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _notices = Channel<LibraryNotice>(Channel.BUFFERED)

    /** A book was closed: how many due words passed while reading it, if any. */
    fun bookClosed() {
        val passed = takePassedWhileReading()
        if (passed > 0) _notices.trySend(LibraryNotice.PassedWhileReading(passed))
    }
    val notices: Flow<LibraryNotice> = _notices.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> =
        combine(
            combine(dao.observeAll(), grades, sortName, skill, dismissedContinue) { entities, grades, sort, skill, dismissed ->
                Ordering(entities, grades, LibrarySort.fromName(sort), skill, dismissed)
            },
            combine(adding, batch) { adding, batch -> adding to batch },
            opening,
            combine(shelf, folderList, foldersOpen) { shelf, folders, open -> Triple(shelf, folders, open) },
            searchTerms,
        ) { (entities, grades, sort, skill, dismissed), (adding, batch), opening, (shelf, folders, open), terms ->
            val (query, readings) = terms
            val books = sort.apply(entities.map(::toLibraryBook), skill) { book ->
                grades[book.contentHash]?.difficulty?.score?.takeIf { it > 0 }
            }
            val (hidden, shown) = books.partition { it.hidden }
            // A folder that is gone (deleted) shows all books again.
            val folderId = shelf.folderId?.takeIf { id -> folders.any { it.id == id } }
            val inFolder = if (folderId == null) shown else shown.filter { it.folderId == folderId }
            val onShelf = when {
                shelf.hidden -> hidden
                shelf.status == null -> inFolder
                else -> inFolder.filter { it.status == shelf.status }
            }
            val inFolders = shown.groupingBy { it.folderId }.eachCount()
            LibraryUiState(
                books = onShelf.filter { LibrarySearch.matches(it, query, readings[it.id]) },
                totalBooks = books.size,
                folders = folders.map { LibraryFolder(it.id, it.name, inFolders[it.id] ?: 0) },
                folderId = folderId,
                foldersOpen = open,
                counts = inFolder.groupingBy { it.status }.eachCount(),
                filter = shelf.status,
                showHidden = shelf.hidden,
                hiddenCount = hidden.size,
                continueBook = entities
                    .filter { !it.hidden && it.lastOpenedAt != null }
                    .maxByOrNull { it.lastOpenedAt!! }
                    ?.takeIf { continueKey(it) != dismissed }
                    ?.let(::toLibraryBook)
                    ?.takeIf { it.status == ReadingStatus.READING },
                query = query,
                sort = sort,
                skill = skill,
                adding = adding,
                opening = opening,
                batch = batch,
            )
        }.let { base ->
            combine(base, listeningChanges) { state, _ ->
                state.copy(continueListening = state.continueBook?.let { continueListening(it.contentHash) } == true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // The hidden shelf closes when its last book is unhidden or removed.
        viewModelScope.launch {
            dao.observeAll().collect { books ->
                if (shelf.value.hidden && books.none { it.hidden }) shelf.value = shelf.value.copy(hidden = false)
            }
        }
        viewModelScope.launch {
            folderList.collect { all ->
                // The chosen folder was deleted: back to all books.
                val chosen = shelf.value.folderId
                if (chosen != null && all.none { it.id == chosen }) selectFolder(null)
            }
        }
        viewModelScope.launch {
            ttu.progress.collect { books ->
                dao.setProgressByTtuIds(books.associate { it.id to it.progress })
            }
        }
        viewModelScope.launch {
            ttu.finished.collect { ttuId -> dao.setFinishedByTtuId(ttuId, now()) }
        }
    }

    /** Adds the picked files one after another, and hands each new book to the reader. */
    fun add(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) addMutex.withLock { addOne(uri) }
        }
    }

    /**
     * Adds books another app opened or shared with Honmani; a single one is opened as well (also
     * if it is in the library already), as "Open with" asks for.
     */
    fun addOpened(uris: List<Uri>) {
        viewModelScope.launch {
            val books = uris.map { uri -> addMutex.withLock { addOne(uri) } }
            books.singleOrNull()?.let { open(it.id) }
        }
    }

    /**
     * Adds [text] (shared or pasted) as a text book titled [title], or after its first line if
     * that is null, and opens it (also if it is in the library already).
     */
    fun addText(text: String?, title: String?) {
        viewModelScope.launch {
            when (val prepared = SharedText.prepare(text, title, textTitle())) {
                SharedText.Prepared.Empty -> _notices.send(LibraryNotice.TextEmpty)
                SharedText.Prepared.TooLarge -> _notices.send(LibraryNotice.TextTooLarge)
                is SharedText.Prepared.Ready -> addAndOpen(prepared.fileName, prepared.bytes)
            }
        }
    }

    /**
     * Adds a file the app downloaded (a free book from the catalogue) as if it had been picked,
     * named [fileName], and opens the book (also if it is in the library already, which is told).
     */
    fun addDownloaded(fileName: String, bytes: ByteArray) {
        viewModelScope.launch { addAndOpen(fileName, bytes) }
    }

    private suspend fun addAndOpen(fileName: String, bytes: ByteArray) {
        val book = addMutex.withLock { add(PickedFile(fileName, ByteArrayInputStream(bytes))) }
        book?.let { open(it.id) }
    }

    /** Adds a sample book and opens it (also if it is in the library already). */
    fun addSample(sample: SampleBook = SampleBook.FIRST) {
        viewModelScope.launch {
            val book = addMutex.withLock {
                val picked = withContext(Dispatchers.IO) { openSample(sample) } ?: return@launch
                add(picked)
            }
            book?.let { open(it.id) }
        }
    }

    /** Adds the file at [uri]; returns the book if it is in the library now. */
    private suspend fun addOne(uri: Uri): BookEntity? {
        val fallbackName = uri.lastPathSegment.orEmpty()
        val picked = withContext(Dispatchers.IO) { openFile(uri) }
        if (picked == null) {
            _notices.send(LibraryNotice.Unreadable(fallbackName))
            return null
        }
        return add(picked)
    }

    /** Adds [picked]; returns the book if it is in the library now. */
    private suspend fun add(picked: PickedFile): BookEntity? {
        adding.value = picked.name
        try {
            val result = addQuietly(picked)
            val notice = when (result) {
                is BookAdder.Result.Added -> null
                is BookAdder.Result.AlreadyInLibrary -> LibraryNotice.AlreadyInLibrary(result.book.title)
                BookAdder.Result.Unsupported -> LibraryNotice.Unsupported(picked.name)
                BookAdder.Result.Unreadable -> LibraryNotice.Unreadable(picked.name)
                is BookAdder.Result.ReaderFailed -> LibraryNotice.ReaderFailed(result.title)
            }
            notice?.let { _notices.send(it) }
            return when (result) {
                is BookAdder.Result.Added -> result.book
                is BookAdder.Result.AlreadyInLibrary -> result.book
                else -> null
            }
        } finally {
            adding.value = null
        }
    }

    private suspend fun addQuietly(picked: PickedFile, source: BookSource = BookSource.FILE): BookAdder.Result =
        picked.input.use { adder.add(it, picked.name, source) }

    /**
     * Adds the books that are new in the watched folder since the last look (called when the
     * library opens). Quiet unless something was added; an unreadable folder only sets the state
     * the settings row shows.
     */
    fun scanWatchedFolder() {
        val watched = watchedFolder ?: return
        if (watchJob?.isActive == true) return
        val folder = watched.folder ?: return
        watchJob = viewModelScope.launch {
            val documents = if (watched.isPermitted()) {
                try {
                    openTree(Uri.parse(folder))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            if (documents == null) {
                watched.setProblem(true)
                return@launch
            }
            val removedHashes = dao.removedSourceIds(BookSource.FOLDER).toSet()
            val scan = withContext(Dispatchers.IO) {
                val settledBefore = System.currentTimeMillis() - WatchedFolderScan.SETTLE_MILLIS
                WatchedFolderScan.find(documents, watched.seen, removedHashes, settledBefore) { entry ->
                    try {
                        openFile(documents.uriOf(entry.id))?.input?.use(WatchedFolderScan::sha256)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (scan.files.isEmpty() && scan.unreadableFolders > 0) {
                watched.setProblem(true)
                return@launch
            }
            watched.setProblem(false)
            var imported = 0
            var failed = 0
            try {
                for (file in scan.files) {
                    val result = addMutex.withLock {
                        val picked = withContext(Dispatchers.IO) { openFile(documents.uriOf(file.id)) }
                        picked?.let { addQuietly(it, BookSource.FOLDER) }
                    }
                    when (result) {
                        is BookAdder.Result.Added -> imported++
                        is BookAdder.Result.AlreadyInLibrary -> Unit
                        else -> failed++
                    }
                    // A file that could not be opened or handed to the reader is tried again next time.
                    if (result != null && result !is BookAdder.Result.ReaderFailed) watched.seen.mark(file)
                }
            } finally {
                watched.seen.save()
            }
            if (imported > 0 || failed > 0) _notices.trySend(LibraryNotice.WatchedFolderAdded(imported, failed))
        }
    }

    /**
     * Adds every EPUB and text file in the folder [tree] and its subfolders, one after another,
     * with a progress row and one summary at the end. Does nothing while another batch runs.
     */
    fun importFolder(tree: Uri) {
        if (batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            batch.value = BatchProgress(exporting = false)
            var imported = 0
            var duplicates = 0
            var failed = 0
            var incomplete = false
            var stopped = false
            var report = false
            try {
                val documents = openTree(tree)
                if (documents == null) {
                    _notices.send(LibraryNotice.FolderUnusable)
                    return@launch
                }
                val scan = withContext(Dispatchers.IO) { TreeWalker.findBooks(documents) }
                incomplete = scan.incomplete
                failed += scan.unreadableFolders
                if (scan.files.isEmpty()) {
                    _notices.send(if (failed > 0) LibraryNotice.FolderUnusable else LibraryNotice.NoBooksInFolder)
                    return@launch
                }
                report = true
                for ((index, file) in scan.files.withIndex()) {
                    batch.value = BatchProgress(exporting = false, done = index, total = scan.files.size, current = file.name)
                    val result = addMutex.withLock {
                        val picked = withContext(Dispatchers.IO) { openFile(documents.uriOf(file.id)) }
                        picked?.let { addQuietly(it) }
                    }
                    when (result) {
                        is BookAdder.Result.Added -> imported++
                        is BookAdder.Result.AlreadyInLibrary -> duplicates++
                        else -> failed++
                    }
                }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } finally {
                batch.value = null
                if (report) {
                    _notices.trySend(LibraryNotice.FolderImported(imported, duplicates, failed, stopped, incomplete))
                }
            }
        }
    }

    /** Writes every library book's own file into the folder [tree], named by title. */
    fun exportLibrary(tree: Uri) {
        if (batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            batch.value = BatchProgress(exporting = true)
            var exported = 0
            var failed = 0
            var stopped = false
            var report = false
            try {
                val documents = openTree(tree)
                if (documents == null) {
                    _notices.send(LibraryNotice.FolderUnusable)
                    return@launch
                }
                val books = dao.observeAll().first()
                if (books.isEmpty()) {
                    _notices.send(LibraryNotice.NothingToExport)
                    return@launch
                }
                val taken = withContext(Dispatchers.IO) { documents.children(documents.rootId) }
                    ?.mapTo(mutableSetOf()) { it.name.lowercase() }
                if (taken == null) {
                    _notices.send(LibraryNotice.FolderUnusable)
                    return@launch
                }
                report = true
                for ((index, book) in books.withIndex()) {
                    val name = ExportNames.unique(book.title, book.format.extension, taken)
                    batch.value = BatchProgress(exporting = true, done = index, total = books.size, current = name)
                    val mime = if (book.format == BookFormat.TXT) "text/plain" else "application/epub+zip"
                    val target = withContext(Dispatchers.IO) { documents.createFile(documents.rootId, mime, name) }
                    if (target == null) {
                        failed++
                        continue
                    }
                    try {
                        writeDocument(importer.bookFile(book), target)
                        exported++
                    } catch (e: CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) { documents.delete(target) }
                        throw e
                    } catch (e: Exception) {
                        logWarning("Honmani.library", "Exporting a book failed", e)
                        withContext(Dispatchers.IO) { documents.delete(target) }
                        failed++
                    }
                }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } finally {
                batch.value = null
                if (report) _notices.trySend(LibraryNotice.LibraryExported(exported, failed, stopped))
            }
        }
    }

    /** Stops the running folder import or export; what was done stays. */
    fun cancelBatch() {
        batchJob?.cancel()
    }

    /** Opens the book; [listen]: its audiobook starts playing (only after the reader chose "Continue listening"). */
    fun open(id: Long, listen: Boolean = false) {
        if (opening.value != null) return
        opening.value = id
        viewModelScope.launch {
            try {
                val book = dao.find(id) ?: return@launch
                val known = book.ttuId?.let { ttuId -> book.ttuTitle?.let { TtuBook(ttuId, it) } }
                val ttuBook = try {
                    adder.ensureInReader(book, known)
                } catch (_: TtuLibraryException) {
                    _notices.send(LibraryNotice.ReaderFailed(book.title, book.id))
                    return@launch
                }
                if (ttuBook != known) adder.link(book, ttuBook)
                syncPosition(book, ttuBook.id) { question ->
                    val answer = CompletableDeferred<Boolean>()
                    _syncQuestion.value = question to answer
                    try {
                        answer.await()
                    } finally {
                        _syncQuestion.value = null
                    }
                }
                if (listen) armAutoplay(book.contentHash)
                ttu.open(ttuBook.id)
                dao.setLastOpened(book.id, now())
            } catch (_: TtuLibraryException) {
                _notices.send(LibraryNotice.ReaderFailed(dao.find(id)?.title.orEmpty(), id))
            } finally {
                opening.value = null
            }
        }
    }

    /** True: continue at the other device's place; false: stay at this phone's. */
    fun answerSync(goThere: Boolean) {
        _syncQuestion.value?.second?.complete(goThere)
    }

    /** Shows only books with [status]; null shows all. */
    fun setFilter(status: ReadingStatus?) {
        shelf.value = Shelf(status = status, folderId = shelf.value.folderId)
    }

    /** Shows only the books in folder [id]; null shows all. */
    fun selectFolder(id: Long?) {
        shelf.value = shelf.value.copy(folderId = id)
        folderView?.chosen = id
    }

    /** Opens or closes the row of folder chips. */
    fun setFoldersOpen(open: Boolean) {
        foldersOpen.value = open
        folderView?.open = open
    }

    /** Makes a folder, and puts book [bookId] in it if there is one; [done] gets the outcome (a refused name, say). */
    fun createFolder(name: String, bookId: Long? = null, done: (FolderResult) -> Unit = {}) {
        val folders = folders ?: return
        viewModelScope.launch {
            done(if (bookId == null) folders.create(name) else folders.createFor(bookId, name))
        }
    }

    fun renameFolder(id: Long, name: String, done: (FolderResult) -> Unit = {}) {
        val folders = folders ?: return
        viewModelScope.launch { done(folders.rename(id, name)) }
    }

    /** Deletes the folder; its books stay in the library, in no folder. */
    fun deleteFolder(id: Long) {
        val folders = folders ?: return
        viewModelScope.launch { folders.delete(id) }
    }

    /** Puts the book in a folder; null takes it out of any. */
    fun moveToFolder(bookId: Long, folderId: Long?) {
        val folders = folders ?: return
        viewModelScope.launch { folders.move(bookId, folderId) }
    }

    /** Shows the hidden books instead of the library, or goes back to all books. */
    fun showHidden(show: Boolean) {
        shelf.value = Shelf(hidden = show, folderId = shelf.value.folderId)
    }

    fun setHidden(id: Long, hidden: Boolean) {
        viewModelScope.launch { dao.setHidden(id, hidden) }
    }

    fun setSort(sort: LibrarySort) = saveSort(sort.name)

    /** Hides the "Continue reading" bubble until a book is opened again. */
    fun dismissContinue() {
        val id = uiState.value.continueBook?.id ?: return
        viewModelScope.launch {
            dao.observeAll().first().firstOrNull { it.id == id }?.let { saveDismissedContinue(continueKey(it)) }
        }
    }

    fun search(text: String) {
        query.value = text
        if (Romaji.isRomaji(text)) readTitles()
    }

    private var readingJob: Job? = null

    /** Works out the readings of titles that don't have one yet (once per book). */
    private fun readTitles() {
        if (readingJob?.isActive == true) return
        readingJob = viewModelScope.launch {
            val books = dao.observeAll().first().filter { it.id !in readings.value }
            for (book in books) {
                val text = listOfNotNull(book.title, book.author).joinToString(" ")
                val kana = try {
                    reading(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWarning("Honmani.library", "Reading of a book title failed", e)
                    text
                }
                readings.update { it + (book.id to kana) }
            }
        }
    }

    fun markFinished(id: Long) {
        viewModelScope.launch { dao.setFinished(id, now()) }
    }

    /** Starts the book over: ttu forgets its reading position (and that it was finished). */
    fun resetProgress(id: Long) {
        viewModelScope.launch {
            val book = dao.find(id) ?: return@launch
            try {
                book.ttuId?.let { ttu.resetProgress(it) }
                dao.clearProgress(book.id)
            } catch (_: TtuLibraryException) {
                _notices.send(LibraryNotice.ResetFailed(book.title, book.id))
            }
        }
    }

    /** Saves a copy of the book's file (as added: EPUB or text) where the reader picked. */
    fun saveCopy(id: Long, uri: Uri) {
        viewModelScope.launch {
            val book = dao.find(id) ?: return@launch
            val notice = try {
                writeDocument(importer.bookFile(book), uri)
                LibraryNotice.CopySaved(book.title)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.library", "Saving a copy of a book failed", e)
                LibraryNotice.CopyFailed(book.title)
            }
            _notices.send(notice)
        }
    }

    /** Whether the book has an audiobook that can be exported (a finished generated one, or a paired recording). */
    suspend fun audiobookExportable(contentHash: String): Boolean {
        val exporter = audiobookExporter ?: return false
        return contentHash.isNotEmpty() && withContext(Dispatchers.IO) { exporter.canExport(contentHash) }
    }

    /** Saves the book's audiobook (audio and subtitles) into the folder [tree] the reader picked. */
    fun exportAudiobook(id: Long, tree: Uri) {
        val exporter = audiobookExporter ?: return
        viewModelScope.launch {
            val book = dao.find(id) ?: return@launch
            val result = exporter.export(book.contentHash, book.title, tree)
            _notices.send(LibraryNotice.AudiobookExport(book.title, result))
        }
    }

    /**
     * Removes a book. Its reading time, characters and the finished count stay in the statistics,
     * unless [deleteStatistics]: then its reading days and finished record go too.
     */
    fun remove(id: Long, deleteStatistics: Boolean = false) {
        viewModelScope.launch {
            val book = dao.find(id) ?: return@launch
            book.ttuId?.let { ttuId ->
                try {
                    ttu.remove(ttuId)
                } catch (_: TtuLibraryException) {
                    // The reader keeps an orphaned copy; harmless, and gone if it is reset.
                }
            }
            if (deleteStatistics) {
                forgetStatistics(book.id, book.contentHash)
            } else {
                keepStatistics(book.id, book.contentHash)
                if (FinishedBooks.isFinished(book)) recordFinished(FinishedBookEntity(book.contentHash, book.title, book.finishedAt))
            }
            dao.delete(book.id)
            forgetBookmarks(book.contentHash)
            forgetHighlights(book.contentHash)
            forgetPronunciations(book.contentHash)
            // A book from the watched folder stays removed, by its content hash.
            if (book.source == BookSource.FOLDER) {
                dao.insertRemoved(RemovedBookEntity(BookSource.FOLDER, book.contentHash, now()))
            }
            // A book from a catalogue stays removed: automatic imports skip it from now on.
            book.sourceId?.takeIf { book.source == BookSource.OPDS }?.let {
                dao.insertRemoved(RemovedBookEntity(book.source, it, now()))
            }
            withContext(Dispatchers.IO) {
                importer.deleteFiles(book)
                forgetDerivedData(book.contentHash)
            }
        }
    }

    private fun toLibraryBook(book: BookEntity) = LibraryBook(
        id = book.id,
        title = book.title,
        author = book.author,
        cover = importer.coverFile(book),
        progress = book.progress,
        status = ReadingStatus.of(book.progress, book.finishedAt),
        hidden = book.hidden,
        contentHash = book.contentHash,
        format = book.format,
        folderId = book.folderId,
    )

    companion object {
        fun fileUrl(book: BookEntity) = TtuLibrary.BOOK_FILES_PATH + book.fileName

        /** ttu takes a text file's title from its name, and picks its importer by extension. */
        fun readerFileName(book: BookEntity): String = copyFileName(book.title, book.format)

        /** The book's title as a file name with its format's extension. */
        fun copyFileName(title: String, format: BookFormat): String {
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100)
            return "${safeTitle.ifBlank { "book" }}.${format.extension}"
        }

        /** Identifies one opening of a book: opening it (or another) again brings the bubble back. */
        fun continueKey(book: BookEntity): String = "${book.id}:${book.lastOpenedAt}"

        val Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as HonmaniApplication
                val container = application.container
                LibraryViewModel(
                    dao = container.bookDao,
                    importer = container.bookImporter,
                    ttu = container.ttuLibrary,
                    openFile = { uri -> openPickedFile(application.contentResolver, uri) },
                    takePassedWhileReading = container.readingPasses::take,
                    openSample = { sample ->
                        runCatching { PickedFile(sample.fileName, application.assets.open(sample.asset)) }.getOrNull()
                    },
                    autoImport = container.opdsAutoImport.status,
                    grades = container.difficultyStore.grades,
                    folders = container.folders,
                    folderView = container.libraryFolderView,
                    watchedFolder = container.watchedFolder,
                    audiobookExporter = container.audiobookExporter,
                    continueListening = { hash ->
                        container.sessionModes.continueListening(
                            hash,
                            container.audiobookStore.pairing(hash) != null,
                            container.audiobookStore.position(hash),
                        )
                    },
                    listeningChanges = container.sessionModes.changes,
                    armAutoplay = { hash -> container.sessionModes.armAutoplay(hash, android.os.SystemClock.elapsedRealtime()) },
                    textTitle = {
                        application.getString(R.string.shared_text_title, java.time.LocalDate.now().toString())
                    },
                    openTree = { uri ->
                        try {
                            ResolverDocumentTree(application.contentResolver, uri)
                        } catch (_: IllegalArgumentException) {
                            // Not a tree address.
                            null
                        }
                    },
                    sortName = container.appearanceSettings.librarySort,
                    saveSort = container.appearanceSettings::setLibrarySort,
                    skill = container.skillTracker.estimate.map { it.current }.distinctUntilChanged(),
                    dismissedContinue = container.dismissedContinue,
                    saveDismissedContinue = container::dismissContinue,
                    writeDocument = { file, uri ->
                        withContext(Dispatchers.IO) {
                            val out = application.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("Cannot write $uri")
                            out.use { output -> file.inputStream().use { it.copyTo(output) } }
                        }
                    },
                    recordFinished = container.finishedBookDao::record,
                    forgetStatistics = { bookId, hash ->
                        container.readingDao.deleteBook(bookId)
                        container.finishedBookDao.forget(hash)
                    },
                    keepStatistics = { bookId, hash -> container.restoredBooks.addWaiting(listOf(WaitingBook(hash, bookId))) },
                    forgetBookmarks = container.bookmarkDao::deleteForBook,
                    forgetHighlights = container.highlightDao::deleteForBook,
                    forgetPronunciations = container.pronunciationDao::deleteForBook,
                    forgetDerivedData = { hash ->
                        container.difficultyStore.forget(hash)
                        container.audiobookStore.forget(hash)
                        // A generated audiobook: stop the job, then delete generated/<hash>/ (and its pairing).
                        container.audiobookScheduler.cancel(hash)
                        container.audiobookMaker.delete(hash)
                        container.restoredBooks.forgetPosition(hash)
                    },
                    syncPosition = { book, ttuId, ask ->
                        val ttu = container.ttuLibrary
                        // A restored backup's position, if ttu's own is behind it (ttu forgot the
                        // book when Honmani was installed again). It keeps the time it was read,
                        // so the sync below still takes the server's position if that is newer.
                        val restored = container.restoredBooks
                        withContext(Dispatchers.IO) { restored.takePosition(book.contentHash) }?.let { position ->
                            try {
                                val (characters, _) = ttu.bookmark(ttuId)
                                if (characters == null || characters < position.characters) {
                                    ttu.setBookmark(ttuId, position.characters, position.progress.toDouble(), position.savedAt)
                                }
                            } catch (_: TtuLibraryException) {
                                withContext(Dispatchers.IO) { restored.setPosition(book.contentHash, position) }
                            }
                        }
                        // Sync is a bonus: the book opens where it was if the reader page can't answer.
                        val pulled = try {
                            container.progressSync.pull(
                                book,
                                ttuId,
                                bookmark = { ttu.bookmark(ttuId) },
                                setBookmark = { characters, progress -> ttu.setBookmark(ttuId, characters, progress) },
                                ask = ask,
                            )
                        } catch (_: TtuLibraryException) {
                            null
                        }
                        if (pulled != null) {
                            val text = application.getString(R.string.sync_continued, pulled.percent, pulled.device)
                            Toast.makeText(application, text, Toast.LENGTH_LONG).show()
                        }
                    },
                    // Lookups off the main thread: a romaji search reads every title in the library.
                    reading = { text ->
                        val lookup = container.dictionaryLookup
                        withContext(Dispatchers.Default) {
                            TextReading.of(text) { part ->
                                lookup.lookup(part).entries.firstOrNull()?.let { entry ->
                                    entry.source.length to TextReading.readingOf(
                                        entry.source,
                                        entry.term.expression,
                                        entry.term.reading,
                                        entry.matchedExpression,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }

        /** Opens a file the user picked; null if its app won't give it (its errors must not close Honmani). */
        internal fun openPickedFile(resolver: ContentResolver, uri: Uri): PickedFile? = try {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment
                ?: "book"
            resolver.openInputStream(uri)?.let { PickedFile(name, it) }
        } catch (e: Exception) {
            logWarning("Honmani.library", "Could not open the picked file", e)
            // SecurityException, FileNotFoundException, or whatever the other app's provider throws
            // (IllegalArgumentException for an address it doesn't know, say).
            null
        }
    }
}
