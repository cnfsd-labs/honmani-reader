package app.honmani.reader.ui.library

import android.net.Uri
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.library.FakeDocumentTree
import app.honmani.reader.data.library.FakeFolderTarget
import app.honmani.reader.data.library.WatchedFolder
import app.honmani.reader.data.library.FakeDocumentTree.Companion.file
import app.honmani.reader.data.library.FakeDocumentTree.Companion.folder
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.library.TestBooks
import app.honmani.reader.ui.reader.LibraryMessage
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuPage
import app.honmani.reader.ui.reader.TtuProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The library with a fake reader page that answers like `library-bridge.js`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dao = FakeBookDao()
    private val ttu = TtuLibrary(pageTimeoutMs = 2_000, importTimeoutMs = 2_000)
    private val page = FakeReaderPage(ttu)
    private val files = mutableMapOf<String, ByteArray>()
    private lateinit var importer: BookImporter
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        importer = BookImporter(dao, File(folder.root, "books"), File(folder.root, "covers"))
        viewModel = LibraryViewModel(
            dao = dao,
            importer = importer,
            ttu = ttu,
            openFile = { uri -> files[uri.lastPathSegment]?.let { PickedFile(uri.lastPathSegment!!, it.inputStream()) } },
            now = { 42L },
        )
        ttu.attach(page)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pick(name: String, bytes: ByteArray): Uri {
        files[name] = bytes
        return Uri.parse("content://files/$name")
    }

    /** Waits for [read] to return a value; the importer works on another thread. */
    private suspend fun <T : Any> eventually(read: suspend () -> T?): T = withTimeout(5_000) {
        var value = read()
        while (value == null) {
            delay(10)
            value = read()
        }
        value
    }

    private suspend fun addAndWait(name: String, bytes: ByteArray): BookEntity {
        val before = dao.books.value.size
        viewModel.add(listOf(pick(name, bytes)))
        return eventually { dao.books.value.takeIf { it.size > before }?.last()?.takeIf { it.ttuId != null } }
    }

    @Test
    fun addingABook_handsItToTheReader() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())

        val ensure = page.commands.single { it.first == "ensure" }.second
        assertEquals("null", ensure["ttuId"].toString())
        assertEquals("/honmani-books/${book.fileName}", ensure["url"]!!.jsonPrimitive.content)
        assertEquals("吾輩は猫である.epub", ensure["fileName"]!!.jsonPrimitive.content)
        assertEquals(page.lastTtuId, book.ttuId)
        assertEquals("吾輩は猫である", book.ttuTitle)
    }

    @Test
    fun aSecondBookWithTheSameTitle_asksTheReaderToKeepTheFirst() = runBlocking {
        val first = addAndWait("a.epub", TestBooks.epub(author = "初版"))
        val second = addAndWait("b.epub", TestBooks.epub(author = "改訂版"))

        val ensure = page.commands.last { it.first == "ensure" }.second
        assertEquals(listOf(first.ttuId), ensure["keepIds"]!!.jsonArray.map { it.jsonPrimitive.longOrNull })
        assertEquals(" (${second.id})", ensure["titleSuffix"]!!.jsonPrimitive.content)
        // Both stay linked: neither re-imports over the other when opened.
        assertEquals(first.ttuId, dao.find(first.id)!!.ttuId)
    }

    @Test
    fun aBookTheReaderRejectsIsNotKept() = runBlocking {
        page.failImports = true
        viewModel.add(listOf(pick("neko.epub", TestBooks.epub())))

        assertEquals(LibraryNotice.ReaderFailed("吾輩は猫である"), viewModel.notices.first())
        assertTrue(dao.books.value.isEmpty())
        assertTrue(File(folder.root, "books").list()!!.isEmpty())
    }

    @Test
    fun addingTheSameBookAgainSaysSo() = runBlocking {
        addAndWait("neko.epub", TestBooks.epub())
        viewModel.add(listOf(pick("copy.epub", TestBooks.epub())))

        assertEquals(LibraryNotice.AlreadyInLibrary("吾輩は猫である"), viewModel.notices.first())
        assertEquals(1, dao.books.value.size)
    }

    @Test
    fun unsupportedAndUnreadableFilesAreReported() = runBlocking {
        viewModel.add(listOf(pick("a.pdf", "%PDF".toByteArray()), Uri.parse("content://files/missing.epub")))

        assertEquals(LibraryNotice.Unsupported("a.pdf"), viewModel.notices.first())
        assertEquals(LibraryNotice.Unreadable("missing.epub"), viewModel.notices.first())
    }

    @Test
    fun openingABook_reusesTtusCopyAndLoadsTheReader() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())

        viewModel.open(book.id)
        eventually { page.loads.firstOrNull() }

        val ensure = page.commands.last { it.first == "ensure" }.second
        assertEquals(book.ttuId, ensure["ttuId"]!!.jsonPrimitive.longOrNull)
        assertEquals("/b?id=${book.ttuId}", page.loads.single())
        assertEquals(42L, eventually { dao.find(book.id)?.lastOpenedAt })
    }

    @Test
    fun theSampleBookIsAddedAndOpened() = runBlocking {
        val withSample = LibraryViewModel(
            dao = dao,
            importer = importer,
            ttu = ttu,
            openFile = { null },
            now = { 42L },
            openSample = { sample -> PickedFile(sample.fileName, TestBooks.epub().inputStream()) },
        )

        withSample.addSample()

        val book = eventually { dao.books.value.singleOrNull()?.takeIf { it.lastOpenedAt != null } }
        assertEquals("/b?id=${book.ttuId}", page.loads.single())
    }

    @Test
    fun sharedTextBecomesATextBookAndOpens() = runBlocking {
        viewModel.addText("吾輩は猫である。\n名前はまだ無い。", null)

        val book = eventually { dao.books.value.singleOrNull()?.takeIf { it.lastOpenedAt != null } }
        assertEquals("吾輩は猫である。", book.title)
        assertEquals(app.honmani.reader.data.library.BookFormat.TXT, book.format)
        assertEquals("/b?id=${book.ttuId}", page.loads.single())
    }

    @Test
    fun emptyOrHugeSharedTextOnlyGetsAMessage() = runBlocking {
        viewModel.addText("  \n ", "Title")
        assertEquals(LibraryNotice.TextEmpty, viewModel.notices.first())
        viewModel.addText("あ".repeat(SharedText.MAX_BYTES), null)
        assertEquals(LibraryNotice.TextTooLarge, viewModel.notices.first())
        assertEquals(0, dao.books.value.size)
    }

    @Test
    fun aCopyOfTheBookFileIsSavedWhereTheReaderPicked() = runBlocking {
        val saved = HashMap<String, ByteArray>()
        viewModel = LibraryViewModel(
            dao = dao,
            importer = importer,
            ttu = ttu,
            openFile = { uri -> files[uri.lastPathSegment]?.let { PickedFile(uri.lastPathSegment!!, it.inputStream()) } },
            now = { 42L },
            writeDocument = { file, uri ->
                if (uri.toString().contains("readonly")) throw java.io.IOException("read-only")
                saved[uri.toString()] = file.readBytes()
            },
        )
        val bytes = TestBooks.epub()
        val book = addAndWait("neko.epub", bytes)

        viewModel.saveCopy(book.id, Uri.parse("content://downloads/neko.epub"))
        assertEquals(LibraryNotice.CopySaved("吾輩は猫である"), viewModel.notices.first())
        assertTrue(bytes.contentEquals(saved.getValue("content://downloads/neko.epub")))

        viewModel.saveCopy(book.id, Uri.parse("content://readonly/neko.epub"))
        assertEquals(LibraryNotice.CopyFailed("吾輩は猫である"), viewModel.notices.first())
        assertEquals("a_b.txt", LibraryViewModel.copyFileName("a/b", BookFormat.TXT))
    }

    private fun viewModelOn(
        tree: FakeDocumentTree,
        written: MutableMap<String, ByteArray> = HashMap(),
        onOpen: () -> Unit = {},
        watched: WatchedFolder? = null,
    ) = LibraryViewModel(
        dao = dao,
        importer = importer,
        ttu = ttu,
        openFile = { uri ->
            onOpen()
            files[uri.path!!.removePrefix("/")]?.let { PickedFile(uri.lastPathSegment!!, it.inputStream()) }
        },
        now = { 42L },
        writeDocument = { file, uri ->
            if (uri.toString().endsWith("bad.epub")) throw java.io.IOException("full")
            written[uri.toString()] = file.readBytes()
        },
        openTree = { tree },
        watchedFolder = watched,
    )

    @Test
    fun importingAFolderAddsTheBooksInAllSubfolders() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(file("root/a.epub"), file("root/readme.pdf"), folder("root/s"), folder("root/empty")),
                "root/s" to listOf(file("root/s/b.epub"), file("root/s/copy-of-a.epub"), file("root/s/broken.epub"), folder("root/s/t")),
                "root/s/t" to listOf(file("root/s/t/c.epub")),
                "root/empty" to emptyList(),
            ),
        )
        files["root/a.epub"] = TestBooks.epub(title = "A")
        files["root/s/b.epub"] = TestBooks.epub(title = "B")
        files["root/s/copy-of-a.epub"] = TestBooks.epub(title = "A")
        files["root/s/broken.epub"] = "not a zip".toByteArray()
        files["root/s/t/c.epub"] = TestBooks.epub(title = "C")
        viewModel = viewModelOn(tree)

        viewModel.importFolder(Uri.parse("content://tree/root"))

        assertEquals(LibraryNotice.FolderImported(3, 1, 1, stopped = false, incomplete = false), viewModel.notices.first())
        assertEquals(listOf("A", "B", "C"), dao.books.value.map { it.title })
        assertTrue(dao.books.value.all { it.ttuId != null })
        assertNull(viewModel.uiState.value.batch)
    }

    private fun watchedFolder(permitted: Boolean = true): WatchedFolder {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("watched-vm-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return WatchedFolder(preferences, File(folder.root, "seen.tsv"), FakeFolderTarget()) { permitted }
            .also { it.choose("content://tree/root") }
    }

    @Test
    fun theWatchedFolderAddsNewBooksOnceAndStaysQuietWhenThereAreNone() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf("root" to listOf(file("root/a.epub").copy(size = 1, modified = 1), file("root/readme.pdf"))),
        )
        files["root/a.epub"] = TestBooks.epub(title = "A")
        var opened = 0
        val watched = watchedFolder()
        viewModel = viewModelOn(tree, onOpen = { opened++ }, watched = watched)

        viewModel.scanWatchedFolder()

        assertEquals(LibraryNotice.WatchedFolderAdded(1, 0), withTimeout(5_000) { viewModel.notices.first() })
        assertEquals(listOf(BookSource.FOLDER), dao.books.value.map { it.source })
        assertTrue(dao.books.value.single().ttuId != null)
        assertEquals(WatchedFolder.State.Watching("root"), watched.state())

        // The next library open: nothing new, nothing opened, no notice.
        opened = 0
        delay(100)
        viewModel.scanWatchedFolder()
        delay(200)
        assertEquals(0, opened)
        assertEquals(1, dao.books.value.size)
        assertNull(withTimeoutOrNull(300) { withTimeout(5_000) { viewModel.notices.first() } })
    }

    @Test
    fun aFileAddedLaterIsPickedUpOnTheNextScan() = runBlocking {
        val entries = mutableListOf(file("root/a.epub").copy(size = 1, modified = 1))
        val tree = FakeDocumentTree(mapOf("root" to entries))
        files["root/a.epub"] = TestBooks.epub(title = "A")
        files["root/b.epub"] = TestBooks.epub(title = "B")
        viewModel = viewModelOn(tree, watched = watchedFolder())
        viewModel.scanWatchedFolder()
        assertEquals(LibraryNotice.WatchedFolderAdded(1, 0), withTimeout(5_000) { viewModel.notices.first() })

        entries += file("root/b.epub").copy(size = 2, modified = 2)
        // The first scan's job may still be finishing, and a scan is skipped while one runs.
        eventually {
            viewModel.scanWatchedFolder()
            delay(50)
            dao.books.value.takeIf { it.size == 2 }
        }

        assertEquals(LibraryNotice.WatchedFolderAdded(1, 0), withTimeout(5_000) { viewModel.notices.first() })
        assertEquals(listOf("A", "B"), dao.books.value.map { it.title })
    }

    @Test
    fun aBookRemovedFromTheLibraryStaysAwayFromTheWatchedFolder() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to listOf(file("root/a.epub").copy(size = 1, modified = 1))))
        val bytes = TestBooks.epub(title = "A")
        files["root/a.epub"] = bytes
        val watched = watchedFolder()
        viewModel = viewModelOn(tree, watched = watched)
        viewModel.scanWatchedFolder()
        withTimeout(5_000) { viewModel.notices.first() }
        val book = dao.books.value.single()

        viewModel.remove(book.id)
        eventually { dao.books.value.takeIf { it.isEmpty() } }
        assertEquals(listOf(RemovedBookEntity(BookSource.FOLDER, book.contentHash, 42L)), dao.removed.value)

        // The file changed (new modified time) but its content is the removed book's.
        val changed = FakeDocumentTree(mapOf("root" to listOf(file("root/a.epub").copy(size = 1, modified = 9))))
        viewModel = viewModelOn(changed, watched = watched)
        viewModel.scanWatchedFolder()
        delay(300)
        assertTrue(dao.books.value.isEmpty())
    }

    @Test
    fun aMissingPermissionOrFolderGivesTheUnavailableStateWithoutANotice() = runBlocking {
        val noPermission = watchedFolder(permitted = false)
        viewModel = viewModelOn(FakeDocumentTree(mapOf("root" to emptyList())), watched = noPermission)
        viewModel.scanWatchedFolder()
        delay(200)
        assertEquals(WatchedFolder.State.Unavailable("root"), noPermission.state())
        assertNull(withTimeoutOrNull(300) { withTimeout(5_000) { viewModel.notices.first() } })

        val gone = watchedFolder()
        viewModel = viewModelOn(FakeDocumentTree(emptyMap()), watched = gone)
        viewModel.scanWatchedFolder()
        delay(200)
        assertEquals(WatchedFolder.State.Unavailable("root"), gone.state())
        assertTrue(dao.books.value.isEmpty())
    }

    @Test
    fun importingAFolderWithoutBooksSaysSo() = runBlocking {
        viewModel = viewModelOn(FakeDocumentTree(mapOf("root" to listOf(file("root/x.pdf")))))

        viewModel.importFolder(Uri.parse("content://tree/root"))

        assertEquals(LibraryNotice.NoBooksInFolder, viewModel.notices.first())
    }

    @Test
    fun cancellingAFolderImportKeepsWhatWasAdded() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to (1..40).map { file("root/b$it.epub") }))
        for (i in 1..40) files["root/b$i.epub"] = TestBooks.epub(title = "Book $i")
        var opened = 0
        // The user taps Cancel while the third file is being opened.
        viewModel = viewModelOn(tree, onOpen = { if (++opened == 3) viewModel.cancelBatch() })

        viewModel.importFolder(Uri.parse("content://tree/root"))

        val notice = viewModel.notices.first() as LibraryNotice.FolderImported
        assertEquals(LibraryNotice.FolderImported(2, 0, 0, stopped = true, incomplete = false), notice)
        assertEquals(2, dao.books.value.size)
        assertNull(eventually { viewModel.uiState.value.takeIf { it.batch == null } }.batch)
    }

    @Test
    fun exportingTheLibraryWritesEveryBookNamedByTitle() = runBlocking {
        val written = HashMap<String, ByteArray>()
        val tree = FakeDocumentTree(mapOf("root" to listOf(file("root/Neko.epub"))))
        viewModel = viewModelOn(tree, written)
        val first = TestBooks.epub(title = "Neko")
        val second = TestBooks.epub(title = "Neko", author = "someone else")
        val third = TestBooks.epub(title = "a/b")
        addAndWait("1.epub", first)
        addAndWait("2.epub", second)
        addAndWait("3.epub", third)

        viewModel.exportLibrary(Uri.parse("content://tree/root"))

        assertEquals(LibraryNotice.LibraryExported(3, 0, stopped = false), viewModel.notices.first())
        assertEquals(
            setOf("content://tree/root/Neko (2).epub", "content://tree/root/Neko (3).epub", "content://tree/root/a_b.epub"),
            written.keys,
        )
        assertTrue(first.contentEquals(written.getValue("content://tree/root/Neko (2).epub")))
    }

    @Test
    fun aFailedExportRemovesItsHalfWrittenFileAndIsCounted() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to emptyList()))
        viewModel = viewModelOn(tree)
        addAndWait("1.epub", TestBooks.epub(title = "bad"))
        addAndWait("2.epub", TestBooks.epub(title = "good"))

        viewModel.exportLibrary(Uri.parse("content://tree/root"))

        assertEquals(LibraryNotice.LibraryExported(1, 1, stopped = false), viewModel.notices.first())
        assertEquals(listOf(Uri.parse("content://tree/root/bad.epub")), tree.deleted)
    }

    @Test
    fun exportingAnEmptyLibrarySaysSo() = runBlocking {
        viewModel = viewModelOn(FakeDocumentTree(mapOf("root" to emptyList())))

        viewModel.exportLibrary(Uri.parse("content://tree/root"))

        assertEquals(LibraryNotice.NothingToExport, viewModel.notices.first())
    }

    @Test
    fun progressFromTheReaderIsStored() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())

        ttu.onMessage(LibraryMessage.Progress(listOf(TtuProgress(book.ttuId!!, 0.25f))))

        assertEquals(0.25f, eventually { dao.find(book.id)?.progress?.takeIf { it > 0f } })
        assertEquals(0.25f, viewModel.uiState.map { it.books?.singleOrNull()?.progress }.first { it != null })
    }

    @Test
    fun resettingProgressClearsItInTheReaderAndTheLibrary() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())
        ttu.onMessage(LibraryMessage.Progress(listOf(TtuProgress(book.ttuId!!, 0.5f))))
        eventually { dao.find(book.id)?.progress?.takeIf { it > 0f } }

        viewModel.resetProgress(book.id)

        assertEquals(0f, eventually { dao.find(book.id)?.progress?.takeIf { it == 0f } })
        val reset = page.commands.single { it.first == "resetProgress" }.second
        assertEquals(book.ttuId, reset["ttuId"]!!.jsonPrimitive.long)
    }

    @Test
    fun filtersBySearchAndReadingStatus() = runBlocking {
        val neko = addAndWait("neko.epub", TestBooks.epub())
        val kokoro = addAndWait("kokoro.epub", TestBooks.epub(title = "こころ"))
        ttu.onMessage(LibraryMessage.Progress(listOf(TtuProgress(neko.ttuId!!, 0.5f))))
        eventually { dao.find(neko.id)?.progress?.takeIf { it > 0f } }

        viewModel.setFilter(ReadingStatus.READING)
        val state = viewModel.uiState.first { it.filter == ReadingStatus.READING && it.books?.size == 1 }
        assertEquals(neko.id, state.books!!.single().id)
        assertEquals(2, state.totalBooks)
        assertEquals(mapOf(ReadingStatus.READING to 1, ReadingStatus.NOT_STARTED to 1), state.counts)

        viewModel.setFilter(null)
        viewModel.search("ココロ")
        val found = viewModel.uiState.first { it.query == "ココロ" && it.books?.size == 1 }
        assertEquals(kokoro.id, found.books!!.single().id)
    }

    @Test
    fun theBookReadLastIsOfferedToContinue() = runBlocking {
        val neko = addAndWait("neko.epub", TestBooks.epub())
        val kokoro = addAndWait("kokoro.epub", TestBooks.epub(title = "こころ"))
        ttu.onMessage(LibraryMessage.Progress(listOf(TtuProgress(neko.ttuId!!, 0.5f))))
        eventually { dao.find(neko.id)?.progress?.takeIf { it > 0f } }
        dao.setLastOpened(kokoro.id, 1L)
        dao.setLastOpened(neko.id, 2L)

        val state = viewModel.uiState.first { it.continueBook != null }
        assertEquals(neko.id, state.continueBook!!.id)

        // A finished book isn't offered.
        viewModel.markFinished(neko.id)
        val finished = viewModel.uiState.first { state ->
            state.books?.any { it.status == ReadingStatus.FINISHED } == true
        }
        assertEquals(null, finished.continueBook)
    }

    @Test
    fun aSwipedAwayBubbleStaysAwayUntilABookIsOpenedAgain() = runBlocking {
        val dismissed = MutableStateFlow<String?>(null)
        val library = LibraryViewModel(
            dao = dao,
            importer = importer,
            ttu = ttu,
            openFile = { null },
            dismissedContinue = dismissed,
            saveDismissedContinue = { dismissed.value = it },
        )
        val neko = addAndWait("neko.epub", TestBooks.epub())
        ttu.onMessage(LibraryMessage.Progress(listOf(TtuProgress(neko.ttuId!!, 0.5f))))
        eventually { dao.find(neko.id)?.progress?.takeIf { it > 0f } }
        dao.setLastOpened(neko.id, 2L)
        library.uiState.first { it.continueBook != null }

        library.dismissContinue()
        library.uiState.first { it.continueBook == null && it.books?.isNotEmpty() == true }

        dao.setLastOpened(neko.id, 3L)
        assertEquals(neko.id, library.uiState.first { it.continueBook != null }.continueBook!!.id)
    }

    @Test
    fun hiddenBooksOnlyShowOnTheHiddenShelf() = runBlocking {
        val neko = addAndWait("neko.epub", TestBooks.epub())
        val kokoro = addAndWait("kokoro.epub", TestBooks.epub(title = "こころ"))

        viewModel.setHidden(neko.id, true)
        val library = viewModel.uiState.first { it.hiddenCount == 1 && it.books?.size == 1 }
        assertEquals(kokoro.id, library.books!!.single().id)
        assertEquals(mapOf(ReadingStatus.NOT_STARTED to 1), library.counts)

        viewModel.showHidden(true)
        val hidden = viewModel.uiState.first { it.showHidden && it.books?.size == 1 }
        assertEquals(neko.id, hidden.books!!.single().id)

        // Unhiding the last hidden book goes back to all books by itself.
        viewModel.setHidden(neko.id, false)
        val all = viewModel.uiState.first { !it.showHidden && it.hiddenCount == 0 && it.books?.size == 2 }
        assertEquals(2, all.counts[ReadingStatus.NOT_STARTED])
    }

    @Test
    fun markingFinishedLastsUntilTheBookIsReset() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())

        viewModel.markFinished(book.id)
        assertEquals(42L, eventually { dao.find(book.id)?.finishedAt })
        assertEquals(
            ReadingStatus.FINISHED,
            viewModel.uiState.map { it.books?.singleOrNull()?.status }.first { it == ReadingStatus.FINISHED },
        )

        viewModel.resetProgress(book.id)
        assertNull(eventually { dao.find(book.id)?.takeIf { it.finishedAt == null } }.finishedAt)
    }

    @Test
    fun aFailedProgressReset_namesTheBookSoTheNoticeCanRetry() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())
        page.failResets = true

        viewModel.resetProgress(book.id)

        assertEquals(LibraryNotice.ResetFailed("吾輩は猫である", book.id), viewModel.notices.first())
    }

    @Test
    fun removingABookDeletesItEverywhere() = runBlocking {
        val book = addAndWait("neko.epub", TestBooks.epub())

        viewModel.remove(book.id)
        // The file goes last, after the row; waiting only for the row raced with it.
        eventually { (dao.books.value.isEmpty() && !importer.bookFile(book).exists()).takeIf { it } }

        val remove = page.commands.single { it.first == "remove" }.second
        assertEquals(book.ttuId, remove["ttuId"]!!.jsonPrimitive.long)
        assertNull(dao.find(book.id))
    }

    @Test
    fun removingABookDeletesItsBookmarksAndHighlights() = runBlocking {
        val bookmarks = mutableListOf<String>()
        val highlights = mutableListOf<String>()
        viewModel = LibraryViewModel(
            dao = dao,
            importer = importer,
            ttu = ttu,
            openFile = { uri -> files[uri.lastPathSegment]?.let { PickedFile(uri.lastPathSegment!!, it.inputStream()) } },
            now = { 42L },
            forgetBookmarks = { bookmarks += it },
            forgetHighlights = { highlights += it },
        )
        val book = addAndWait("neko.epub", TestBooks.epub())

        viewModel.remove(book.id)
        eventually { highlights.takeIf { it.isNotEmpty() } }

        assertEquals(listOf(book.contentHash), highlights)
        assertEquals(listOf(book.contentHash), bookmarks)
    }

    private fun viewModelRecording(
        recorded: MutableList<FinishedBookEntity>,
        forgotten: MutableList<Pair<Long, String>>,
    ) = LibraryViewModel(
        dao = dao,
        importer = importer,
        ttu = ttu,
        openFile = { uri -> files[uri.lastPathSegment]?.let { PickedFile(uri.lastPathSegment!!, it.inputStream()) } },
        now = { 42L },
        recordFinished = { recorded += it },
        forgetStatistics = { id, hash -> forgotten += id to hash },
    )

    @Test
    fun removingAFinishedBookRecordsItForTheStatistics_anUnfinishedOneDoesNot() = runBlocking {
        val recorded = mutableListOf<FinishedBookEntity>()
        viewModel = viewModelRecording(recorded, mutableListOf())
        val finished = addAndWait("neko.epub", TestBooks.epub())
        val reading = addAndWait("botchan.epub", TestBooks.epub(title = "坊っちゃん", author = "夏目漱石"))
        dao.setFinished(finished.id, 1_234L)

        viewModel.remove(reading.id)
        eventually { (dao.find(reading.id) == null).takeIf { it } }
        viewModel.remove(finished.id)
        eventually { (dao.find(finished.id) == null).takeIf { it } }

        assertEquals(listOf(FinishedBookEntity(finished.contentHash, finished.title, 1_234L)), recorded)
    }

    @Test
    fun removingABookWithItsStatisticsDeletesThemInsteadOfRecordingIt() = runBlocking {
        val recorded = mutableListOf<FinishedBookEntity>()
        val forgotten = mutableListOf<Pair<Long, String>>()
        viewModel = viewModelRecording(recorded, forgotten)
        val book = addAndWait("neko.epub", TestBooks.epub())
        dao.setFinished(book.id, 1_234L)

        viewModel.remove(book.id, deleteStatistics = true)
        eventually { (dao.find(book.id) == null).takeIf { it } }

        assertEquals(emptyList<FinishedBookEntity>(), recorded)
        assertEquals(listOf(book.id to book.contentHash), forgotten)
    }

    @Test
    fun removingABookForgetsItsDerivedDataButNotOtherBooks() = runBlocking {
        val forgotten = mutableListOf<String>()
        viewModel = LibraryViewModel(
            dao = dao,
            importer = importer,
            ttu = ttu,
            openFile = { uri -> files[uri.lastPathSegment]?.let { PickedFile(uri.lastPathSegment!!, it.inputStream()) } },
            now = { 42L },
            forgetDerivedData = { synchronized(forgotten) { forgotten += it } },
        )
        val first = addAndWait("neko.epub", TestBooks.epub())
        val second = addAndWait("botchan.epub", TestBooks.epub(title = "坊っちゃん", author = "夏目漱石"))

        viewModel.remove(first.id)
        eventually { synchronized(forgotten) { forgotten.toList() }.takeIf { it.isNotEmpty() } }

        assertEquals(listOf(first.contentHash), synchronized(forgotten) { forgotten.toList() })
        assertEquals(second.id, dao.books.value.single().id)
    }
}

/** Answers commands at once, like the bridge on a loaded page. */
private class FakeReaderPage(private val ttu: TtuLibrary) : TtuPage {
    val commands = mutableListOf<Pair<String, JsonObject>>()
    val loads = mutableListOf<String>()
    var failImports = false
    var failResets = false
    var lastTtuId = 0L

    override fun evaluate(script: String) {
        val name = script.substringAfter("honmaniLibrary.").substringBefore("(")
        val arguments = script.substringAfter("(").substringBeforeLast(")")
        if (arguments.isEmpty()) return
        val json = Json.parseToJsonElement(arguments).jsonObject
        commands += name to json
        if (name == "open") {
            // The bridge opens the book inside the page; recorded like a page load.
            loads += "/b?id=${json["ttuId"]!!.jsonPrimitive.long}"
            return
        }
        val requestId = json["requestId"]!!.jsonPrimitive.long
        when (name) {
            "ensure" -> {
                val known = json["ttuId"]!!.jsonPrimitive.longOrNull
                val title = json["fileName"]!!.jsonPrimitive.content.substringBeforeLast('.')
                when {
                    known != null -> ttu.onMessage(LibraryMessage.Ready(requestId, known, title))
                    failImports -> ttu.onMessage(LibraryMessage.Failed(requestId, "Error importing"))
                    else -> ttu.onMessage(LibraryMessage.Ready(requestId, ++lastTtuId, title))
                }
            }
            "remove" -> ttu.onMessage(LibraryMessage.Removed(requestId))
            // A page that never answers makes the library time out, which is how a reset fails.
            "resetProgress" -> if (!failResets) ttu.onMessage(LibraryMessage.ProgressReset(requestId))
        }
    }

    override fun load(path: String) {
        loads += path
    }
}

private class FakeBookDao : BookDao {
    val books = MutableStateFlow<List<BookEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<BookEntity>> = books

    override suspend fun find(id: Long) = books.value.find { it.id == id }

    override suspend fun findByTtuId(ttuId: Long) = books.value.find { it.ttuId == ttuId }

    override suspend fun findByTitle(title: String) = books.value.filter { it.title == title }.take(2)

    override suspend fun findByHash(contentHash: String) = books.value.find { it.contentHash == contentHash }

    override fun observeSourceIds(source: BookSource): Flow<List<String>> =
        books.map { list -> list.filter { it.source == source }.mapNotNull { it.sourceId } }

    override suspend fun insertRow(book: BookEntity): Long {
        val id = nextId++
        books.update { it + book.copy(id = id) }
        return id
    }

    private fun change(predicate: (BookEntity) -> Boolean, transform: (BookEntity) -> BookEntity) =
        books.update { list -> list.map { if (predicate(it)) transform(it) else it } }

    override suspend fun setTtuBook(id: Long, ttuId: Long, ttuTitle: String) =
        change({ it.id == id }) { it.copy(ttuId = ttuId, ttuTitle = ttuTitle) }

    override suspend fun ttuIdsExcept(id: Long) =
        books.value.filter { it.id != id }.mapNotNull { it.ttuId }

    override suspend fun forgetTtuBookExcept(ttuId: Long, keepId: Long) =
        change({ it.ttuId == ttuId && it.id != keepId }) { it.copy(ttuId = null, ttuTitle = null) }

    override suspend fun setLastOpened(id: Long, openedAt: Long) =
        change({ it.id == id }) { it.copy(lastOpenedAt = openedAt) }

    override suspend fun setProgressByTtuId(ttuId: Long, progress: Float) =
        change({ it.ttuId == ttuId }) { it.copy(progress = progress) }

    override suspend fun setCharactersByTtuId(ttuId: Long, characters: Long) =
        change({ it.ttuId == ttuId }) { it.copy(characters = characters) }

    override suspend fun setFinished(id: Long, finishedAt: Long, now: Long) =
        change({ it.id == id }) { it.copy(finishedAt = finishedAt) }

    override suspend fun setFinishedByTtuId(ttuId: Long, finishedAt: Long, now: Long) =
        change({ it.ttuId == ttuId && it.finishedAt == null }) { it.copy(finishedAt = finishedAt) }

    override suspend fun setHidden(id: Long, hidden: Boolean, now: Long) =
        change({ it.id == id }) { it.copy(hidden = hidden) }

    override suspend fun clearProgress(id: Long, now: Long) =
        change({ it.id == id }) { it.copy(progress = 0f, characters = 0, finishedAt = null) }

    val removed = MutableStateFlow<List<RemovedBookEntity>>(emptyList())

    override suspend fun sourceIds(source: BookSource) =
        books.value.filter { it.source == source }.mapNotNull { it.sourceId }

    override suspend fun insertRemoved(removed: RemovedBookEntity) =
        this.removed.update { list -> list.filterNot { it.source == removed.source && it.sourceId == removed.sourceId } + removed }

    override suspend fun removedSourceIds(source: BookSource) =
        removed.value.filter { it.source == source }.map { it.sourceId }

    override suspend fun delete(id: Long) = books.update { list -> list.filterNot { it.id == id } }
}
