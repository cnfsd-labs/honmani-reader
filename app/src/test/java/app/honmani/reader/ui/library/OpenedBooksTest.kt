package app.honmani.reader.ui.library

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenedBooksTest {
    private val book = Uri.parse("content://downloads/public_downloads/12")
    private val other = Uri.parse("content://media/external/file/34")

    @Test
    fun openWithGivesTheFile() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(book, "application/epub+zip")
        assertEquals(listOf(book), OpenedBooks.bookUris(intent))
    }

    @Test
    fun sharingGivesOneOrSeveralFiles() {
        val one = Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, book)
        assertEquals(listOf(book), OpenedBooks.bookUris(one))

        val several = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/epub+zip")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(book, other))
        assertEquals(listOf(book, other), OpenedBooks.bookUris(several))
    }

    @Test
    fun theLauncherAndPlainFilePathsGiveNothing() {
        assertEquals(emptyList<Uri>(), OpenedBooks.bookUris(Intent(Intent.ACTION_MAIN)))
        assertEquals(emptyList<Uri>(), OpenedBooks.bookUris(null))
        // Another app's private file path: not readable by Honmani, and not allowed since Android 7.
        val file = Intent(Intent.ACTION_VIEW).setData(Uri.parse("file:///data/data/other/book.epub"))
        assertEquals(emptyList<Uri>(), OpenedBooks.bookUris(file))
    }

    @Test
    fun receivedFilesWaitUntilTheLibraryAsks() = runBlocking {
        val opened = OpenedBooks()
        assertFalse(opened.receive(Intent(Intent.ACTION_MAIN)))
        assertTrue(opened.receive(Intent(Intent.ACTION_VIEW).setData(book)))

        assertEquals(listOf(book), opened.received.first())
    }
}
