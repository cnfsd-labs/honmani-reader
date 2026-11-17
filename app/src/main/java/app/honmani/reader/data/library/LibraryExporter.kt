package app.honmani.reader.data.library

import android.net.Uri
import app.honmani.reader.diagnostics.logWarning
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export library": writes every library book's own file into a folder the reader picked, named
 * by title. One for the app, so the Library and Settings → Back up and restore share its progress
 * and only one export runs at a time; it outlives the screen that started it.
 */
class LibraryExporter(
    private val scope: CoroutineScope,
    /** Every book in the library. */
    private val books: suspend () -> List<BookEntity>,
    /** A book's own file in app storage. */
    private val bookFile: (BookEntity) -> File,
    /** Opens a folder the reader picked (a tree address); null: not usable. */
    private val openTree: (Uri) -> DocumentTree?,
    /** Writes a book's file to a document in that folder. */
    private val writeDocument: suspend (File, Uri) -> Unit,
) {
    /** A running export: [total] null while it gets ready. */
    data class Progress(val done: Int = 0, val total: Int? = null, val current: String = "")

    sealed interface Result {
        data class Exported(val exported: Int, val failed: Int, val stopped: Boolean) : Result
        data object NothingToExport : Result
        data object FolderUnusable : Result
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _lastResult = MutableStateFlow<Result?>(null)

    /** How the last export ended, for as long as the app runs; null: none yet, or one is running. */
    val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * Starts exporting into [tree]; false (and nothing happens) while an export runs. [onDone]
     * gets the result, or null for an export stopped before it began.
     */
    @Synchronized
    fun start(tree: Uri, onDone: (Result?) -> Unit = {}): Boolean {
        if (running) return false
        _lastResult.value = null
        _progress.value = Progress()
        job = scope.launch {
            var result: Result? = null
            try {
                result = export(tree) { result = it }
            } finally {
                _progress.value = null
                _lastResult.value = result
                onDone(result)
            }
        }
        return true
    }

    /** Stops the running export; the books written so far stay. */
    fun cancel() {
        job?.cancel()
    }

    /** [partial] keeps the counts up to date, so a cancelled export still reports them. */
    private suspend fun export(tree: Uri, partial: (Result) -> Unit): Result {
        val documents = withContext(Dispatchers.IO) { openTree(tree) } ?: return Result.FolderUnusable
        val books = books()
        if (books.isEmpty()) return Result.NothingToExport
        val entries = withContext(Dispatchers.IO) { documents.children(documents.rootId) } ?: return Result.FolderUnusable
        val taken = entries.mapTo(mutableSetOf()) { it.name.lowercase() }
        var exported = 0
        var failed = 0
        partial(Result.Exported(0, 0, stopped = true))
        for ((index, book) in books.withIndex()) {
            // Exported before into this folder: keep that file rather than adding "Title (2)".
            val file = bookFile(book)
            val already = withContext(Dispatchers.IO) {
                ExportNames.previousExport(book.title, book.format.extension, file.length(), entries)
                    ?.let { entry -> documents.open(entry.id) }
                    ?.let { input -> runCatching { ExportNames.sameBytes(file, input) }.getOrDefault(false) } == true
            }
            if (already) {
                exported++
                partial(Result.Exported(exported, failed, stopped = true))
                continue
            }
            val name = ExportNames.unique(book.title, book.format.extension, taken)
            _progress.value = Progress(done = index, total = books.size, current = name)
            val mime = if (book.format == BookFormat.TXT) "text/plain" else "application/epub+zip"
            val target = withContext(Dispatchers.IO) { documents.createFile(documents.rootId, mime, name) }
            if (target == null) {
                failed++
            } else {
                try {
                    writeDocument(file, target)
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
            partial(Result.Exported(exported, failed, stopped = true))
        }
        return Result.Exported(exported, failed, stopped = false)
    }
}
