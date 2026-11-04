package app.honmani.reader.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.backup.BackupDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FoldersTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var books: BookDao
    private lateinit var folders: Folders

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        books = database.bookDao()
        folders = Folders(database.folderDao(), now = { 7L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun addBook(hash: String) = books.insert(
        BookEntity(contentHash = hash, title = hash, format = BookFormat.EPUB, fileName = "$hash.epub", addedAt = 1),
    )

    private suspend fun names() = folders.observeAll().first().map { it.name }

    private suspend fun folderOf(id: Long) = books.find(id)!!.folderId

    @Test
    fun aFolderIsMadeWithATrimmedName_andListedByName() = runTest {
        val result = folders.create("  novels ")
        folders.create("Manga")

        assertTrue(result is FolderResult.Done)
        assertEquals(listOf("Manga", "novels"), names())
        assertEquals(7L, folders.observeAll().first().first().createdAt)
    }

    @Test
    fun blankAndDuplicateNamesAreRefused_ignoringCase() = runTest {
        folders.create("Novels")

        assertEquals(FolderResult.Blank, folders.create("   "))
        assertEquals(FolderResult.Duplicate, folders.create("NOVELS"))
        assertEquals(FolderResult.Duplicate, folders.create(" novels "))
        assertEquals(listOf("Novels"), names())

        val other = (folders.create("Manga") as FolderResult.Done).id
        assertEquals(FolderResult.Duplicate, folders.rename(other, "novels"))
        assertEquals(FolderResult.Blank, folders.rename(other, ""))
        assertEquals(listOf("Manga", "Novels"), names())
    }

    @Test
    fun aFolderCanBeRenamed_toTheSameNameInAnotherCase() = runTest {
        val id = (folders.create("novels") as FolderResult.Done).id

        assertEquals(FolderResult.Done(id), folders.rename(id, "Novels"))
        assertEquals(listOf("Novels"), names())
        assertEquals(FolderResult.Done(id), folders.rename(id, "Light novels"))
        assertEquals(listOf("Light novels"), names())
    }

    @Test
    fun aLongNameIsCut() = runTest {
        folders.create("x".repeat(FOLDER_NAME_MAX + 20))

        assertEquals(FOLDER_NAME_MAX, names().single().length)
    }

    @Test
    fun creatingAFolderFromABook_movesTheBookThere_orNothingHappens() = runTest {
        val book = addBook("a")

        val result = folders.createFor(book, "Novels") as FolderResult.Done
        assertEquals(result.id, folderOf(book))

        val second = addBook("b")
        assertEquals(FolderResult.Duplicate, folders.createFor(second, "novels"))
        assertNull(folderOf(second))
        assertEquals(FolderResult.Blank, folders.createFor(second, " "))
        assertNull(folderOf(second))
        assertEquals(1, names().size)
    }

    @Test
    fun deletingAFolder_keepsItsBooksInNoFolder() = runTest {
        val book = addBook("a")
        val other = addBook("b")
        val id = (folders.createFor(book, "Novels") as FolderResult.Done).id
        val keep = (folders.createFor(other, "Manga") as FolderResult.Done).id

        folders.delete(id)

        assertEquals(listOf("Manga"), names())
        assertEquals("a", books.find(book)!!.contentHash)
        assertNull(folderOf(book))
        assertEquals(keep, folderOf(other))
    }

    @Test
    fun aBookMovesBetweenFoldersAndOutOfThem() = runTest {
        val book = addBook("a")
        val one = (folders.create("One") as FolderResult.Done).id
        val two = (folders.create("Two") as FolderResult.Done).id

        folders.move(book, one)
        assertEquals(one, folderOf(book))
        folders.move(book, two)
        assertEquals(two, folderOf(book))
        folders.move(book, null)
        assertNull(folderOf(book))
    }

    @Test
    fun removingABookLeavesTheFolder_andTheFolderLinkGoesWithTheBook() = runTest {
        val book = addBook("a")
        val id = (folders.createFor(book, "Novels") as FolderResult.Done).id

        books.delete(book)

        assertNull(books.find(book))
        assertEquals(listOf("Novels"), names())
        assertEquals(0, database.query("SELECT 1 FROM book WHERE folder_id = $id", null).use { it.count })
    }

    @Test
    fun everyWayABookIsUpdatedKeepsItsFolder() = runTest {
        val book = addBook("a")
        val id = (folders.createFor(book, "Novels") as FolderResult.Done).id

        books.setLastOpened(book, 5)
        books.setHidden(book, true)
        books.setFinished(book, 9)
        books.clearProgress(book)
        val backupDao: BackupDao = database.backupDao()
        backupDao.updateBook(books.find(book)!!.copy(characters = 123, progress = 0.5f))

        val after = books.find(book)!!
        assertEquals(id, after.folderId)
        assertEquals(123L, after.characters)
    }
}
