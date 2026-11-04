package app.honmani.reader.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.FolderResult
import app.honmani.reader.data.library.Folders
import app.honmani.reader.ui.reader.TtuLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/** The library's folder view on a real database: filter, counts, hidden books and deleted folders. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryFoldersTest {
    @get:Rule
    val files = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var folders: Folders
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        folders = Folders(database.folderDao())
        viewModel = LibraryViewModel(
            dao = database.bookDao(),
            importer = BookImporter(database.bookDao(), File(files.root, "books"), File(files.root, "covers")),
            ttu = TtuLibrary(pageTimeoutMs = 2_000, importTimeoutMs = 2_000),
            openFile = { null },
            now = { 42L },
            folders = folders,
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun addBook(title: String, progress: Float = 0f) = database.bookDao().insert(
        BookEntity(
            contentHash = title,
            title = title,
            format = BookFormat.EPUB,
            fileName = "$title.epub",
            addedAt = 1,
            progress = progress,
        ),
    )

    private suspend fun folder(name: String) = (folders.create(name) as FolderResult.Done).id

    private suspend fun state(accept: (LibraryUiState) -> Boolean): LibraryUiState =
        withTimeout(5_000) { viewModel.uiState.first(accept) }

    @Test
    fun withNoFolder_theLibraryShowsNoFolderUi() = runBlocking {
        addBook("neko")

        val state = state { it.books?.size == 1 }
        assertTrue(state.folders.isEmpty())
        assertNull(state.folderId)
    }

    @Test
    fun aFolderShowsOnlyItsBooks_withCounts_combinedWithStatusAndSearch() = runBlocking {
        val neko = addBook("neko", progress = 0.5f)
        val inu = addBook("inu")
        val tori = addBook("tori", progress = 0.5f)
        val novels = folder("Novels")
        val manga = folder("Manga")
        folders.move(neko, novels)
        folders.move(inu, novels)
        folders.move(tori, manga)

        val all = state { it.folders.size == 2 && it.books?.size == 3 }
        assertEquals(listOf("Manga" to 1, "Novels" to 2), all.folders.map { it.name to it.count })

        viewModel.selectFolder(novels)
        val inNovels = state { it.folderId == novels }
        assertEquals(setOf(neko, inu), inNovels.books!!.map { it.id }.toSet())
        assertEquals(3, inNovels.totalBooks)
        assertEquals(mapOf(ReadingStatus.READING to 1, ReadingStatus.NOT_STARTED to 1), inNovels.counts)

        viewModel.setFilter(ReadingStatus.READING)
        val reading = state { it.filter == ReadingStatus.READING }
        assertEquals(listOf(neko), reading.books!!.map { it.id })
        assertEquals(novels, reading.folderId)

        viewModel.setFilter(null)
        viewModel.search("inu")
        val found = state { it.query == "inu" && it.books?.size == 1 }
        assertEquals(inu, found.books!!.single().id)

        viewModel.search("tori")
        val none = state { it.query == "tori" && it.books?.isEmpty() == true }
        assertEquals(novels, none.folderId)
    }

    @Test
    fun hiddenBooksAreNotCountedInAFolder_andShowOnlyOnTheHiddenShelf() = runBlocking {
        val neko = addBook("neko")
        val inu = addBook("inu")
        val novels = folder("Novels")
        folders.move(neko, novels)
        folders.move(inu, novels)

        viewModel.setHidden(neko, true)
        val library = state { it.hiddenCount == 1 && it.folders.singleOrNull()?.count == 1 }
        viewModel.selectFolder(novels)
        val inFolder = state { it.folderId == novels && it.books?.size == 1 }
        assertEquals(inu, inFolder.books!!.single().id)

        viewModel.showHidden(true)
        val hidden = state { it.showHidden }
        assertEquals(listOf(neko), hidden.books!!.map { it.id })
        assertEquals(1, library.folders.single().count)
    }

    @Test
    fun theChosenFolderSurvivesTheStatusFilterAndTheHiddenShelf() = runBlocking {
        val neko = addBook("neko")
        val novels = folder("Novels")
        folders.move(neko, novels)
        viewModel.selectFolder(novels)

        viewModel.showHidden(true)
        viewModel.showHidden(false)
        viewModel.setFilter(ReadingStatus.NOT_STARTED)

        assertEquals(novels, state { it.filter == ReadingStatus.NOT_STARTED }.folderId)
    }

    @Test
    fun deletingTheChosenFolder_goesBackToAll_andTheBooksStay() = runBlocking {
        val neko = addBook("neko")
        addBook("inu")
        val novels = folder("Novels")
        folders.move(neko, novels)
        viewModel.selectFolder(novels)
        state { it.folderId == novels && it.books?.size == 1 }

        viewModel.deleteFolder(novels)

        val all = state { it.folders.isEmpty() && it.books?.size == 2 }
        assertNull(all.folderId)
        assertNull(database.bookDao().find(neko)!!.folderId)
    }

    @Test
    fun renamingAndMovingGoThroughTheViewModel_refusedNamesAreReported() = runBlocking {
        val neko = addBook("neko")
        val novels = folder("Novels")
        folder("Manga")
        // Room works on its own thread: each call answers when it is done, so wait for it.
        suspend fun answer(call: ((FolderResult) -> Unit) -> Unit): FolderResult {
            val result = kotlinx.coroutines.CompletableDeferred<FolderResult>()
            call { result.complete(it) }
            return withTimeout(5_000) { result.await() }
        }
        val results = listOf(
            answer { viewModel.renameFolder(novels, "manga", it) },
            answer { viewModel.renameFolder(novels, " ", it) },
            answer { viewModel.renameFolder(novels, "Books", it) },
            answer { viewModel.createFolder("Fresh", neko, it) },
            answer { viewModel.createFolder("fresh", neko, it) },
        )

        assertEquals(
            listOf(FolderResult.Duplicate, FolderResult.Blank, FolderResult.Done(novels)),
            results.take(3),
        )
        assertTrue(results[3] is FolderResult.Done)
        assertEquals(FolderResult.Duplicate, results[4])
        val state = state { it.folders.size == 3 && it.folders.any { f -> f.name == "Fresh" && f.count == 1 } }
        assertEquals(listOf("Books", "Fresh", "Manga"), state.folders.map { it.name })

        viewModel.moveToFolder(neko, null)
        state { it.folders.all { f -> f.count == 0 } }
        assertNull(database.bookDao().find(neko)!!.folderId)
    }
}
