package app.honmani.reader.data.library

import android.net.Uri
import app.honmani.reader.data.library.FakeDocumentTree.Companion.file
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryExporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val books = mutableListOf<BookEntity>()
    private val written = HashMap<String, ByteArray>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun book(title: String, bytes: ByteArray): BookEntity {
        val name = "${books.size}.epub"
        File(folder.root, name).writeBytes(bytes)
        return BookEntity(id = books.size + 1L, contentHash = name, title = title, format = BookFormat.EPUB, fileName = name, addedAt = 0)
            .also { books += it }
    }

    private fun exporter(tree: FakeDocumentTree?, write: suspend (File, Uri) -> Unit = { file, uri -> written[uri.toString()] = file.readBytes() }) =
        LibraryExporter(
            scope = scope,
            books = { books.toList() },
            bookFile = { File(folder.root, it.fileName) },
            openTree = { tree },
            writeDocument = { file, uri ->
                if (uri.toString().endsWith("bad.epub")) throw java.io.IOException("full")
                write(file, uri)
            },
        )

    private suspend fun LibraryExporter.run(tree: String = "content://tree/root"): LibraryExporter.Result? {
        val done = CompletableDeferred<LibraryExporter.Result?>()
        assertTrue(start(Uri.parse(tree)) { done.complete(it) })
        return withTimeout(5_000) { done.await() }
    }

    @Test
    fun exportingTheLibraryWritesEveryBookNamedByTitle() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to listOf(file("root/Neko.epub"))))
        val first = byteArrayOf(1, 2, 3)
        book("Neko", first)
        book("Neko", byteArrayOf(4, 5))
        book("a/b", byteArrayOf(6))
        val exporter = exporter(tree)

        assertEquals(LibraryExporter.Result.Exported(3, 0, stopped = false), exporter.run())

        assertEquals(
            setOf("content://tree/root/Neko (2).epub", "content://tree/root/Neko (3).epub", "content://tree/root/a_b.epub"),
            written.keys,
        )
        assertTrue(first.contentEquals(written.getValue("content://tree/root/Neko (2).epub")))
        assertEquals(LibraryExporter.Result.Exported(3, 0, stopped = false), exporter.lastResult.value)
        assertNull(exporter.progress.value)
    }

    @Test
    fun exportingAgainKeepsBooksAlreadyInTheFolder() = runBlocking {
        val same = byteArrayOf(1, 2, 3)
        val other = byteArrayOf(4, 5, 6)
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(
                    TreeEntry("root/Neko.epub", "Neko.epub", isFolder = false, size = same.size.toLong()),
                    TreeEntry("root/Inu.epub", "Inu.epub", isFolder = false, size = other.size.toLong()),
                ),
            ),
        )
        tree.contents["root/Neko.epub"] = same
        // Same name and size, different bytes: a different file.
        tree.contents["root/Inu.epub"] = other.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        book("Neko", same)
        book("Inu", other)

        assertEquals(LibraryExporter.Result.Exported(2, 0, stopped = false), exporter(tree).run())
        assertEquals(setOf("content://tree/root/Inu (2).epub"), written.keys)
    }

    @Test
    fun aFailedExportRemovesItsHalfWrittenFileAndIsCounted() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to emptyList()))
        book("bad", byteArrayOf(1))
        book("good", byteArrayOf(2))

        assertEquals(LibraryExporter.Result.Exported(1, 1, stopped = false), exporter(tree).run())
        assertEquals(listOf(Uri.parse("content://tree/root/bad.epub")), tree.deleted)
    }

    @Test
    fun anEmptyLibraryOrAnUnusableFolderSaysSo() = runBlocking {
        assertEquals(LibraryExporter.Result.NothingToExport, exporter(FakeDocumentTree(mapOf("root" to emptyList()))).run())
        book("Neko", byteArrayOf(1))
        assertEquals(LibraryExporter.Result.FolderUnusable, exporter(null).run())
    }

    @Test
    fun anAnnouncedResultWaitsUntilTakenOnce() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to emptyList()))
        book("Neko", byteArrayOf(1))
        val exporter = exporter(tree)

        exporter.run()
        assertNull("an export from Settings announces nothing", exporter.takeNotice())

        val done = CompletableDeferred<LibraryExporter.Result?>()
        assertTrue(exporter.start(Uri.parse("content://tree/root"), announce = true) { done.complete(it) })
        withTimeout(5_000) { done.await() }
        assertEquals(LibraryExporter.Result.Exported(1, 0, stopped = false), exporter.takeNotice())
        assertNull(exporter.takeNotice())
        assertEquals(LibraryExporter.Result.Exported(1, 0, stopped = false), exporter.lastResult.value)
    }

    @Test
    fun aSecondStartWhileOneRunsIsIgnored() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to emptyList()))
        book("Neko", byteArrayOf(1))
        book("Inu", byteArrayOf(2))
        val release = CompletableDeferred<Unit>()
        val exporter = exporter(tree) { file, uri ->
            release.await()
            written[uri.toString()] = file.readBytes()
        }
        val done = CompletableDeferred<LibraryExporter.Result?>()
        assertTrue(exporter.start(Uri.parse("content://tree/root")) { done.complete(it) })
        val progress = withTimeout(5_000) { exporter.progress.filterNotNull().first { it.total != null } }
        assertEquals(LibraryExporter.Progress(done = 0, total = 2, current = "Neko.epub"), progress)

        var secondDone = false
        assertFalse(exporter.start(Uri.parse("content://tree/other")) { secondDone = true })
        assertTrue(exporter.running)

        release.complete(Unit)
        assertEquals(LibraryExporter.Result.Exported(2, 0, stopped = false), withTimeout(5_000) { done.await() })
        assertFalse(secondDone)
        assertEquals(setOf("content://tree/root/Neko.epub", "content://tree/root/Inu.epub"), written.keys)
    }

    @Test
    fun cancellingKeepsWhatWasWrittenAndSaysStopped() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to emptyList()))
        book("Neko", byteArrayOf(1))
        book("Inu", byteArrayOf(2))
        lateinit var exporter: LibraryExporter
        exporter = exporter(tree) { file, uri ->
            if (written.isNotEmpty()) {
                exporter.cancel()
                kotlinx.coroutines.awaitCancellation()
            }
            written[uri.toString()] = file.readBytes()
        }

        assertEquals(LibraryExporter.Result.Exported(1, 0, stopped = true), exporter.run())
        assertEquals(listOf(Uri.parse("content://tree/root/Inu.epub")), tree.deleted)
    }
}
