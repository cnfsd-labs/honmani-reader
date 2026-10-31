package app.honmani.reader.data.library

import app.honmani.reader.diagnostics.logWarning
import android.database.sqlite.SQLiteConstraintException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Copies book files into the library: [booksDir] holds the books (named by content hash),
 * [coversDir] their cover images. Imports run one at a time.
 */
class BookImporter(
    private val dao: BookDao,
    private val booksDir: File,
    private val coversDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Called with each new book; returns it with what a restored backup kept for it (its
     * progress, see `Backup.onBookAdded`).
     */
    private val restore: suspend (BookEntity) -> BookEntity = { it },
) {
    private val mutex = Mutex()

    sealed interface Result {
        data class Added(val book: BookEntity) : Result

        data class AlreadyInLibrary(val book: BookEntity) : Result

        /** Neither an EPUB nor a text file. */
        data object Unsupported : Result

        /** An EPUB that cannot be read, or a file that could not be copied. */
        data object Unreadable : Result
    }

    /**
     * Adds the book in [input]; [displayName] is the file's name as the user sees it. [source] and
     * [sourceId] say where it came from (an OPDS entry id for catalogue downloads).
     */
    suspend fun import(
        input: InputStream,
        displayName: String,
        source: BookSource = BookSource.FILE,
        sourceId: String? = null,
    ): Result = mutex.withLock {
        withContext(Dispatchers.IO) { importLocked(input, displayName, source, sourceId) }
    }

    private suspend fun importLocked(
        input: InputStream,
        displayName: String,
        source: BookSource,
        sourceId: String?,
    ): Result {
        booksDir.mkdirs()
        coversDir.mkdirs()
        val temp = try {
            File.createTempFile("import", ".tmp", booksDir)
        } catch (_: IOException) {
            // Storage full or not writable: the book can't be copied.
            return Result.Unreadable
        }
        try {
            val (hash, head) = try {
                copyAndHash(input, temp)
            } catch (_: Exception) {
                // IOException, or whatever another app's content provider throws while reading.
                return Result.Unreadable
            }
            var format = formatOf(displayName, head) ?: return Result.Unsupported
            dao.findByHash(hash)?.let { return Result.AlreadyInLibrary(it) }

            val metadata = when (format) {
                BookFormat.EPUB -> try {
                    EpubMetadata.read(temp)
                } catch (_: InvalidEpubException) {
                    return Result.Unreadable
                }
                BookFormat.TXT -> {
                    // The library keeps text as UTF-8, and Aozora Bunko texts as EPUBs so their
                    // furigana show as ruby. The hash above stays that of the original file.
                    val aozora = try {
                        TextEncoding.convertToUtf8(temp)
                        convertAozora(temp, hash, displayName)
                    } catch (_: IOException) {
                        return Result.Unreadable
                    }
                    aozora?.let {
                        format = BookFormat.EPUB
                        EpubMetadata(title = it.title, author = it.author, cover = null)
                    }
                }
            }

            val bookFile = File(booksDir, "$hash.${format.extension}")
            if (!temp.renameTo(bookFile)) return Result.Unreadable
            val coverFileName = metadata?.cover?.let { cover ->
                val name = "$hash.${cover.extension}"
                try {
                    File(coversDir, name).writeBytes(cover.bytes)
                    name
                } catch (_: IOException) {
                    null
                }
            }

            val book = BookEntity(
                contentHash = hash,
                title = metadata?.title ?: titleFromFileName(displayName),
                author = metadata?.author,
                format = format,
                fileName = bookFile.name,
                coverFileName = coverFileName,
                source = source,
                sourceId = sourceId,
                addedAt = now(),
            )
            val added = try {
                book.copy(id = dao.insert(book))
            } catch (_: SQLiteConstraintException) {
                // Imports are serialized, so this only happens if the row appeared meanwhile.
                return dao.findByHash(hash)?.let { Result.AlreadyInLibrary(it) } ?: Result.Unreadable
            } catch (e: Exception) {
                // No row, so no owner for the files: don't leave them behind.
                bookFile.delete()
                coverFileName?.let { File(coversDir, it).delete() }
                throw e
            }
            return Result.Added(
                try {
                    restore(added)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWarning("Honmani.library", "Restoring a backup's data for an added book failed", e)
                    // The book is in; only what a backup kept for it is missing.
                    added
                },
            )
        } finally {
            temp.delete()
        }
    }

    /** Rewrites [file] as an EPUB if it is an Aozora Bunko text; returns the book, or null. */
    private fun convertAozora(file: File, hash: String, displayName: String): AozoraBook? {
        val text = file.readText(Charsets.UTF_8)
        if (!AozoraText.isAozora(text)) return null
        val book = AozoraText.parse(text, fallbackTitle = titleFromFileName(displayName))
        AozoraEpub.write(book, identifier = "urn:sha256:$hash", target = file)
        return book
    }

    /**
     * Deletes copies an import left behind when the app was killed half way. Waits for a running
     * import, so it never removes the file of one in progress.
     */
    suspend fun sweepTemporaryFiles() = mutex.withLock {
        withContext(Dispatchers.IO) {
            booksDir.listFiles { file -> file.name.startsWith("import") && file.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
        }
    }

    /** Removes the book's files; the caller removes the row. */
    fun deleteFiles(book: BookEntity) {
        File(booksDir, book.fileName).delete()
        book.coverFileName?.let { File(coversDir, it).delete() }
    }

    fun bookFile(book: BookEntity): File = File(booksDir, book.fileName)

    fun coverFile(book: BookEntity): File? = book.coverFileName?.let { File(coversDir, it) }

    companion object {
        private const val HEAD_SIZE = 4

        /** Copies [input] to [target]; returns the SHA-256 (hex) and the first bytes. */
        private fun copyAndHash(input: InputStream, target: File): Pair<String, ByteArray> {
            val digest = MessageDigest.getInstance("SHA-256")
            var head = ByteArray(0)
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (head.size < HEAD_SIZE) {
                        head += buffer.copyOf(minOf(read, HEAD_SIZE - head.size))
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } to head
        }

        /** By extension; a file without a known one counts as an EPUB if it is a zip. */
        internal fun formatOf(displayName: String, head: ByteArray): BookFormat? {
            val extension = displayName.substringAfterLast('.', "").lowercase()
            BookFormat.entries.firstOrNull { it.extension == extension }?.let { return it }
            val isZip = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            return if (isZip && '.' !in displayName) BookFormat.EPUB else null
        }

        internal fun titleFromFileName(displayName: String): String {
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val base = if (BookFormat.entries.any { it.extension == extension }) {
                displayName.substringBeforeLast('.')
            } else {
                displayName
            }
            return base.trim().ifEmpty { displayName }
        }
    }
}
