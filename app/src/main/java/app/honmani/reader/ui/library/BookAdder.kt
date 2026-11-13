package app.honmani.reader.ui.library

import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.ui.reader.TtuBook
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuLibraryException
import java.io.InputStream

/** Adds a book to the library and hands it to the reader: picked files and catalogue downloads. */
class BookAdder(
    private val dao: BookDao,
    private val importer: BookImporter,
    private val ttu: TtuLibrary,
) {
    sealed interface Result {
        data class Added(val book: BookEntity) : Result

        data class AlreadyInLibrary(val book: BookEntity) : Result

        data object Unsupported : Result

        data object Unreadable : Result

        /** Larger than [BookImporter.MAX_BOOK_BYTES]. */
        data object TooLarge : Result

        /** ッツ Ebook Reader could not import the book; it was not kept. */
        data class ReaderFailed(val title: String) : Result
    }

    suspend fun add(
        input: InputStream,
        name: String,
        source: BookSource = BookSource.FILE,
        sourceId: String? = null,
    ): Result = when (val result = importer.import(input, name, source, sourceId)) {
        is BookImporter.Result.Added -> handToReader(result.book)
        is BookImporter.Result.AlreadyInLibrary -> Result.AlreadyInLibrary(result.book)
        BookImporter.Result.Unsupported -> Result.Unsupported
        BookImporter.Result.Unreadable -> Result.Unreadable
        BookImporter.Result.TooLarge -> Result.TooLarge
    }

    /** Imports a new book into the reader now, so problems show up at once and it opens fast. */
    private suspend fun handToReader(book: BookEntity): Result = try {
        link(book, ensureInReader(book, null))
        Result.Added(book)
    } catch (_: TtuLibraryException) {
        // A book the reader cannot show is no use in the library.
        dao.delete(book.id)
        importer.deleteFiles(book)
        Result.ReaderFailed(book.title)
    }

    /** Has the reader hold [book], without replacing another library book that shares its title. */
    suspend fun ensureInReader(book: BookEntity, known: TtuBook?): TtuBook = ttu.ensure(
        known,
        LibraryViewModel.fileUrl(book),
        LibraryViewModel.readerFileName(book),
        keepIds = dao.ttuIdsExcept(book.id),
        titleSuffix = " (${book.id})",
    )

    suspend fun link(book: BookEntity, ttuBook: TtuBook) {
        dao.setTtuBook(book.id, ttuBook.id, ttuBook.title)
        // Normally no other book has this id; after a reader reset or an old version's overwrite one may.
        dao.forgetTtuBookExcept(ttuBook.id, book.id)
    }
}
