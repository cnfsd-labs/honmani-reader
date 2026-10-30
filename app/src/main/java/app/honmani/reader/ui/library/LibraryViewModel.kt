package app.honmani.reader.ui.library

import app.honmani.reader.diagnostics.logWarning
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
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.library.SampleBook
import app.honmani.reader.data.opds.AutoImportStatus
import app.honmani.reader.data.sync.SyncQuestion
import app.honmani.reader.lookup.Romaji
import app.honmani.reader.lookup.TextReading
import app.honmani.reader.ui.reader.TtuBook
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuLibraryException
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
)

data class LibraryUiState(
    /** The books shown (after filter and search); null until the library has loaded. */
    val books: List<LibraryBook>? = null,
    /** All books in the library, before filter and search. */
    val totalBooks: Int = 0,
    /** How many books (not hidden) have each status, for the filter buttons. */
    val counts: Map<ReadingStatus, Int> = emptyMap(),
    /** Only books with this status; null for all. */
    val filter: ReadingStatus? = null,
    /** Showing the hidden books (and only those) instead of the library. */
    val showHidden: Boolean = false,
    val hiddenCount: Int = 0,
    /** The book read last, if the reader is still reading it: offered as "Continue reading". */
    val continueBook: LibraryBook? = null,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    /** The reader's skill, once there is one: for [LibrarySort.BEST_FIT]. */
    val skill: Int? = null,
    /** Name of the file being added, if any. */
    val adding: String? = null,
    /** The book being opened, if any. */
    val opening: Long? = null,
)

/** Something to tell the user once, as a snackbar. */
sealed interface LibraryNotice {
    data class AlreadyInLibrary(val title: String) : LibraryNotice

    data class Unsupported(val fileName: String) : LibraryNotice

    data class Unreadable(val fileName: String) : LibraryNotice

    data class ResetFailed(val title: String) : LibraryNotice

    /** ッツ Ebook Reader could not import or open the book. */
    data class ReaderFailed(val title: String) : LibraryNotice

    data class CopySaved(val title: String) : LibraryNotice

    data class CopyFailed(val title: String) : LibraryNotice
}

/** The books and what their order depends on. */
private data class Ordering(
    val entities: List<BookEntity>,
    val grades: Map<String, StoredDifficulty>,
    val sort: LibrarySort,
    val skill: Int?,
    val dismissedContinue: String?,
)

/** Which books the library shows: one reading status (or all), or the hidden ones. */
private data class Shelf(val status: ReadingStatus? = null, val hidden: Boolean = false)

/** A file the user picked: its display name and contents. */
class PickedFile(val name: String, val input: InputStream)

class LibraryViewModel(
    private val dao: BookDao,
    private val importer: BookImporter,
    private val ttu: TtuLibrary,
    private val openFile: (Uri) -> PickedFile?,
    private val now: () -> Long = System::currentTimeMillis,
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
) : ViewModel() {
    private val adding = MutableStateFlow<String?>(null)
    private val opening = MutableStateFlow<Long?>(null)
    private val shelf = MutableStateFlow(Shelf())

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
    val notices: Flow<LibraryNotice> = _notices.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> =
        combine(
            combine(dao.observeAll(), grades, sortName, skill, dismissedContinue) { entities, grades, sort, skill, dismissed ->
                Ordering(entities, grades, LibrarySort.fromName(sort), skill, dismissed)
            },
            adding,
            opening,
            shelf,
            searchTerms,
        ) { (entities, grades, sort, skill, dismissed), adding, opening, shelf, terms ->
            val (query, readings) = terms
            val books = sort.apply(entities.map(::toLibraryBook), skill) { book ->
                grades[book.contentHash]?.difficulty?.score?.takeIf { it > 0 }
            }
            val (hidden, shown) = books.partition { it.hidden }
            val onShelf = when {
                shelf.hidden -> hidden
                shelf.status == null -> shown
                else -> shown.filter { it.status == shelf.status }
            }
            LibraryUiState(
                books = onShelf.filter { LibrarySearch.matches(it, query, readings[it.id]) },
                totalBooks = books.size,
                counts = shown.groupingBy { it.status }.eachCount(),
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // The hidden shelf closes when its last book is unhidden or removed.
        viewModelScope.launch {
            dao.observeAll().collect { books ->
                if (shelf.value.hidden && books.none { it.hidden }) shelf.value = Shelf()
            }
        }
        viewModelScope.launch {
            ttu.progress.collect { books ->
                books.forEach { dao.setProgressByTtuId(it.id, it.progress) }
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
            val result = picked.input.use { adder.add(it, picked.name) }
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

    fun open(id: Long) {
        if (opening.value != null) return
        opening.value = id
        viewModelScope.launch {
            try {
                val book = dao.find(id) ?: return@launch
                val known = book.ttuId?.let { ttuId -> book.ttuTitle?.let { TtuBook(ttuId, it) } }
                val ttuBook = try {
                    ttu.ensure(known, fileUrl(book), readerFileName(book))
                } catch (_: TtuLibraryException) {
                    _notices.send(LibraryNotice.ReaderFailed(book.title))
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
                ttu.open(ttuBook.id)
                dao.setLastOpened(book.id, now())
            } catch (_: TtuLibraryException) {
                _notices.send(LibraryNotice.ReaderFailed(dao.find(id)?.title.orEmpty()))
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
        shelf.value = Shelf(status = status)
    }

    /** Shows the hidden books instead of the library, or goes back to all books. */
    fun showHidden(show: Boolean) {
        shelf.value = Shelf(hidden = show)
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
                _notices.send(LibraryNotice.ResetFailed(book.title))
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

    fun remove(id: Long) {
        viewModelScope.launch {
            val book = dao.find(id) ?: return@launch
            book.ttuId?.let { ttuId ->
                try {
                    ttu.remove(ttuId)
                } catch (_: TtuLibraryException) {
                    // The reader keeps an orphaned copy; harmless, and gone if it is reset.
                }
            }
            dao.delete(book.id)
            // A book from a catalogue stays removed: automatic imports skip it from now on.
            book.sourceId?.takeIf { book.source == BookSource.OPDS }?.let {
                dao.insertRemoved(RemovedBookEntity(book.source, it, now()))
            }
            withContext(Dispatchers.IO) { importer.deleteFiles(book) }
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
                    openSample = { sample ->
                        runCatching { PickedFile(sample.fileName, application.assets.open(sample.asset)) }.getOrNull()
                    },
                    autoImport = container.opdsAutoImport.status,
                    grades = container.difficultyStore.grades,
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
