package app.honmani.reader.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookImporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var dao: BookDao
    private lateinit var importer: BookImporter
    private lateinit var booksDir: File
    private lateinit var coversDir: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.bookDao()
        booksDir = File(folder.root, "books")
        coversDir = File(folder.root, "covers")
        importer = BookImporter(dao, booksDir, coversDir, now = { 1_000L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sweepRemovesImportLeftoversOnly() = runTest {
        booksDir.mkdirs()
        val stale = File(booksDir, "import123.tmp").apply { writeText("x") }
        val book = File(booksDir, "abc.epub").apply { writeText("x") }
        importer.sweepTemporaryFiles()
        assertFalse(stale.exists())
        assertTrue(book.exists())
    }

    private suspend fun import(bytes: ByteArray, name: String) = importer.import(bytes.inputStream(), name)

    @Test
    fun anEpubIsCopiedWithItsMetadataAndCover() = runTest {
        val epub = TestBooks.epub(
            manifestExtra = """<item id="c" href="cover.png" media-type="image/png" properties="cover-image"/>""",
            files = mapOf("OEBPS/cover.png" to TestBooks.PNG),
        )

        val book = (import(epub, "neko.epub") as BookImporter.Result.Added).book

        assertEquals("吾輩は猫である", book.title)
        assertEquals("夏目漱石", book.author)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals(1_000L, book.addedAt)
        assertEquals(64, book.contentHash.length)
        assertEquals("${book.contentHash}.epub", book.fileName)
        assertArrayEquals(epub, importer.bookFile(book).readBytes())
        assertArrayEquals(TestBooks.PNG, importer.coverFile(book)!!.readBytes())
        assertEquals(listOf(book), dao.observeAll().first())
    }

    @Test
    fun aCatalogueDownloadRemembersItsEntry() = runTest {
        val result = importer.import(TestBooks.epub().inputStream(), "neko.epub", BookSource.OPDS, "urn:uuid:neko")
        val book = (result as BookImporter.Result.Added).book

        assertEquals(BookSource.OPDS, book.source)
        assertEquals("urn:uuid:neko", book.sourceId)
        assertEquals(listOf("urn:uuid:neko"), dao.observeSourceIds(BookSource.OPDS).first())
        assertEquals(emptyList<String>(), dao.observeSourceIds(BookSource.FILE).first())
    }

    @Test
    fun aTextFileTakesItsTitleFromTheFileName() = runTest {
        val book = (import("吾輩は猫である。".toByteArray(), "吾輩は猫である.TXT") as BookImporter.Result.Added).book

        assertEquals("吾輩は猫である", book.title)
        assertEquals(BookFormat.TXT, book.format)
        assertNull(book.author)
        assertNull(book.coverFileName)
    }

    @Test
    fun anAozoraBunkoTextBecomesAnEpubWithFurigana() = runTest {
        val text = "吾輩は猫である\n夏目漱石\n\n" + "-".repeat(30) + "\n【テキスト中に現れる記号について】\n" +
            "-".repeat(30) + "\n\n　吾輩《わがはい》は猫である。\n\n底本：「夏目漱石全集1」\n"

        val book = (import(text.toByteArray(charset("Shift_JIS")), "neko.txt") as BookImporter.Result.Added).book

        assertEquals("吾輩は猫である", book.title)
        assertEquals("夏目漱石", book.author)
        assertEquals(BookFormat.EPUB, book.format)
        assertTrue(book.fileName.endsWith(".epub"))
        val chapter = java.util.zip.ZipFile(importer.bookFile(book)).use { zip ->
            zip.getInputStream(zip.getEntry("OEBPS/chapter-1.xhtml")).readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(chapter, "<ruby>吾輩<rt>わがはい</rt></ruby>は猫である。" in chapter)
    }

    @Test
    fun aBookThatCannotBeWrittenIsUnreadable() = runTest {
        // A file where the books folder should be: nothing can be written there, as with full storage.
        booksDir.writeText("not a folder")

        assertEquals(BookImporter.Result.Unreadable, import(TestBooks.epub(), "neko.epub"))
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun aFailedInsertLeavesNoFilesBehind() = runTest {
        val failing = object : BookDao by dao {
            override suspend fun insert(book: BookEntity): Long = throw IllegalStateException("disk")
        }
        val failingImporter = BookImporter(failing, booksDir, coversDir, now = { 1_000L })

        try {
            failingImporter.import(TestBooks.epub().inputStream(), "neko.epub")
            org.junit.Assert.fail("expected the insert error")
        } catch (_: IllegalStateException) {
        }
        assertEquals(emptyList<String>(), booksDir.list()!!.toList())
        assertEquals(emptyList<String>(), coversDir.list()!!.toList())
    }

    @Test
    fun anEpubWithoutTitleTakesTheFileName() = runTest {
        val book = (import(TestBooks.epub(title = null), "kokoro.epub") as BookImporter.Result.Added).book

        assertEquals("kokoro", book.title)
    }

    @Test
    fun theSameFileTwiceIsAlreadyInTheLibrary() = runTest {
        val epub = TestBooks.epub()
        val first = (import(epub, "a.epub") as BookImporter.Result.Added).book

        val second = import(epub, "copy of a.epub")

        assertEquals(first, (second as BookImporter.Result.AlreadyInLibrary).book)
        assertEquals(1, dao.observeAll().first().size)
        assertEquals(listOf(first.fileName), booksDir.list()!!.toList())
    }

    @Test
    fun otherFileTypesAreUnsupported() = runTest {
        assertEquals(BookImporter.Result.Unsupported, import("%PDF-1.7".toByteArray(), "book.pdf"))
        assertTrue(dao.observeAll().first().isEmpty())
        assertTrue(booksDir.list()!!.isEmpty())
    }

    @Test
    fun aBrokenEpubIsUnreadable_andLeavesNothingBehind() = runTest {
        assertEquals(BookImporter.Result.Unreadable, import("PK not a zip".toByteArray(), "broken.epub"))
        assertTrue(dao.observeAll().first().isEmpty())
        assertTrue(booksDir.list()!!.isEmpty())
    }

    @Test
    fun deleteFilesRemovesTheBookAndCover() = runTest {
        val epub = TestBooks.epub(
            manifestExtra = """<item id="c" href="cover.png" media-type="image/png" properties="cover-image"/>""",
            files = mapOf("OEBPS/cover.png" to TestBooks.PNG),
        )
        val book = (import(epub, "neko.epub") as BookImporter.Result.Added).book

        importer.deleteFiles(book)

        assertFalse(importer.bookFile(book).exists())
        assertFalse(importer.coverFile(book)!!.exists())
    }

    @Test
    fun formatOf_usesTheExtension_orSniffsZipsWithoutOne() {
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertEquals(BookFormat.EPUB, BookImporter.formatOf("a.EPUB", byteArrayOf()))
        assertEquals(BookFormat.TXT, BookImporter.formatOf("a.txt", byteArrayOf()))
        assertEquals(BookFormat.EPUB, BookImporter.formatOf("download", zip))
        assertNull(BookImporter.formatOf("download", "text".toByteArray()))
        assertNull(BookImporter.formatOf("a.zip", zip))
    }
}
