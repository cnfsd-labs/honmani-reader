package app.honmani.reader.data.merge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.FolderEntity
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.data.library.saveReplacingOverlaps
import app.honmani.reader.data.library.undoSave
import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewLogEntity
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.perBookAndDay
import app.honmani.reader.data.words.KnownBatchEntity
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.data.words.WordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every write stamps `updated_at`; every delete leaves a marker in its transaction; a re-insert removes it. */
@RunWith(RobolectricTestRunner::class)
class TombstoneTest {
    private lateinit var db: HonmaniDatabase
    private var clock = 1_000L

    @Before
    fun setUp() {
        UpdateClock.source = { clock }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HonmaniDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        UpdateClock.source = System::currentTimeMillis
    }

    private suspend fun markers() = db.tombstoneDao().all().map { it.kind to it.key }.toSet()

    private fun word(expression: String) = WordEntity(
        expression = expression, reading = "よみ", gloss = "g", lookupCount = 1, firstLookedUpAt = 1,
        lastLookedUpAt = 1, rankKey = 1.0, lastSentence = "", lastSurfaceStart = 0, lastSurfaceEnd = 0,
    )

    private fun book(hash: String) =
        BookEntity(contentHash = hash, title = hash, format = BookFormat.EPUB, fileName = "$hash.epub", addedAt = 1)

    @Test
    fun wordsAndCardsAreStampedAndTheirDeleteLeavesMarkers() = runTest {
        val words = db.wordDao()
        val id = words.insert(word("猫"))
        assertEquals("a word merely looked up holds no choice yet", 0L, words.find(id)!!.updatedAt)
        clock = 2_000
        words.setNote(id, "n")
        assertEquals(2_000L, words.find(id)!!.updatedAt)

        db.reviewDao().insertIfMissing(ReviewCardEntity(wordId = id, due = 5, addedAt = 1))
        assertEquals("a card never answered knows nothing yet", 0L, db.reviewDao().find(id)!!.updatedAt)
        clock = 3_000
        db.reviewDao().upsert(db.reviewDao().find(id)!!.copy(reps = 1))
        assertEquals(3_000L, db.reviewDao().find(id)!!.updatedAt)

        clock = 4_000
        words.delete(id)
        assertEquals(
            setOf(SyncKeys.WORD to SyncKeys.word("猫", "よみ"), SyncKeys.REVIEW_CARD to SyncKeys.reviewCard("猫", "よみ")),
            markers(),
        )
        assertTrue(db.tombstoneDao().all().all { it.deletedAt == 4_000L })

        // Saving the word again (a later lookup) removes the word's marker.
        words.insert(word("猫"))
        assertEquals(setOf(SyncKeys.REVIEW_CARD to SyncKeys.reviewCard("猫", "よみ")), markers())
        val again = words.find("猫", "よみ")!!.id
        db.reviewDao().insertIfMissing(ReviewCardEntity(wordId = again, due = 5, addedAt = 1))
        assertEquals(emptySet<Pair<String, String>>(), markers())
    }

    @Test
    fun reviewDeleteAndUndoOfALogLeaveMarkers() = runTest {
        val id = db.wordDao().insert(word("犬"))
        val review = db.reviewDao()
        review.insertIfMissing(ReviewCardEntity(wordId = id, due = 5, addedAt = 1))
        val logId = review.insertLog(ReviewLogEntity(wordId = id, at = 77, grade = 3, elapsedDays = 0.0))
        review.deleteLog(logId)
        val logKey = SyncKeys.REVIEW_LOG to SyncKeys.reviewLog("犬", "よみ", 77, 3)
        assertEquals(setOf(logKey), markers())
        review.delete(id)
        assertEquals(setOf(logKey, SyncKeys.REVIEW_CARD to SyncKeys.reviewCard("犬", "よみ")), markers())
        review.insertLog(ReviewLogEntity(wordId = id, at = 77, grade = 3, elapsedDays = 0.0))
        assertEquals(setOf(SyncKeys.REVIEW_CARD to SyncKeys.reviewCard("犬", "よみ")), markers())
    }

    @Test
    fun bookmarksHighlightsAndPronunciationsAreStampedAndMarkedOnDelete() = runTest {
        val bookmarks = db.bookmarkDao()
        val highlights = db.highlightDao()
        val readings = db.pronunciationDao()
        val b = bookmarks.put(BookmarkEntity(contentHash = "h", name = "a", characters = 10, total = 100, createdAt = 1))
        val h = highlights.put(HighlightEntity(contentHash = "h", start = 5, end = 9, color = 0, text = "t", total = 100, createdAt = 1))
        val p = readings.put(PronunciationEntity(contentHash = "h", text = "名", reading = "な", createdAt = 1))
        assertEquals(1_000L, bookmarks.byId(b)!!.updatedAt)
        clock = 2_000
        bookmarks.rename(b, "b")
        highlights.setColor(h, 2)
        assertEquals(2_000L, bookmarks.byId(b)!!.updatedAt)
        assertEquals(2_000L, highlights.byId(h)!!.updatedAt)

        val bKey = SyncKeys.BOOKMARK to SyncKeys.bookmark("h", 10)
        val hKey = SyncKeys.HIGHLIGHT to SyncKeys.highlight("h", 5, 9)
        val pKey = SyncKeys.PRONUNCIATION to SyncKeys.pronunciation("h", "名")
        bookmarks.delete(b)
        highlights.delete(h)
        readings.delete(p)
        assertEquals(setOf(bKey, hKey, pKey), markers())

        // Undo puts them back: the markers go.
        bookmarks.put(BookmarkEntity(contentHash = "h", name = "a", characters = 10, total = 100, createdAt = 1))
        highlights.put(HighlightEntity(contentHash = "h", start = 5, end = 9, color = 0, text = "t", total = 100, createdAt = 1))
        readings.put(PronunciationEntity(contentHash = "h", text = "名", reading = "な", createdAt = 1))
        assertEquals(emptySet<Pair<String, String>>(), markers())

        // Removing the book's data leaves no marker: the other phones keep their library.
        bookmarks.deleteForBook("h")
        highlights.deleteForBook("h")
        readings.deleteForBook("h")
        assertEquals(emptySet<Pair<String, String>>(), markers())
    }

    @Test
    fun replacedOverlapsAreMarkedAndUndoClearsThem() = runTest {
        val highlights = db.highlightDao()
        val old = HighlightEntity(contentHash = "h", start = 0, end = 10, color = 0, text = "t", total = 100, createdAt = 1)
        highlights.put(old)
        val saved = highlights.saveReplacingOverlaps(
            HighlightEntity(contentHash = "h", start = 5, end = 15, color = 1, text = "t", total = 100, createdAt = 2),
        )
        assertEquals(setOf(SyncKeys.HIGHLIGHT to SyncKeys.highlight("h", 0, 10)), markers())
        highlights.undoSave(saved)
        assertEquals(setOf(SyncKeys.HIGHLIGHT to SyncKeys.highlight("h", 5, 15)), markers())
    }

    @Test
    fun foldersBooksBatchesAndFinishedRecordsAreStampedAndMarked() = runTest {
        val folders = db.folderDao()
        val books = db.bookDao()
        val folder = folders.insert(FolderEntity(name = "Novels", createdAt = 1))
        val book = books.insert(book("a"))
        assertEquals("a plain new book holds no choice yet", 0L, books.find(book)!!.updatedAt)
        clock = 2_000
        folders.setBookFolder(book, folder)
        assertEquals(2_000L, books.find(book)!!.updatedAt)
        clock = 3_000
        books.setHidden(book, true)
        assertEquals(3_000L, books.find(book)!!.updatedAt)
        clock = 4_000
        books.setFinished(book, 9)
        assertEquals(4_000L, books.find(book)!!.updatedAt)
        clock = 5_000
        books.clearProgress(book)
        assertEquals(5_000L, books.find(book)!!.updatedAt)

        clock = 6_000
        folders.setName(folder, "Manga")
        assertEquals(setOf(SyncKeys.FOLDER to "novels"), markers())
        clock = 7_000
        folders.delete(folder)
        assertEquals(setOf(SyncKeys.FOLDER to "novels", SyncKeys.FOLDER to "manga"), markers())
        assertEquals(7_000L, books.find(book)!!.updatedAt)
        folders.insert(FolderEntity(name = "MANGA", createdAt = 1))
        assertEquals(setOf(SyncKeys.FOLDER to "novels"), markers())

        val batches = db.knownBatchDao()
        val batch = batches.insertBatch(KnownBatchEntity(source = KnownSource.WORD_LIST, detail = "d", createdAt = 3, count = 1))
        batches.deleteBatch(batch)
        val batchKey = SyncKeys.KNOWN_BATCH to SyncKeys.knownBatch("WORD_LIST", "d", 3)
        assertTrue(batchKey in markers())
        batches.insertBatch(KnownBatchEntity(source = KnownSource.WORD_LIST, detail = "d", createdAt = 3, count = 1))
        assertTrue(batchKey !in markers())

        val finished = db.finishedBookDao()
        finished.record(FinishedBookEntity("fh", "T", 5))
        finished.forget("fh")
        val finishedKey = SyncKeys.FINISHED_BOOK to SyncKeys.finishedBook("fh")
        assertTrue(finishedKey in markers())
        finished.record(FinishedBookEntity("fh", "T", 5))
        assertTrue(finishedKey !in markers())
        finished.forget("never-recorded")
        assertTrue(SyncKeys.FINISHED_BOOK to "never-recorded" !in markers())
    }

    @Test
    fun readingOfTwoPhonesAddsUp() = runTest {
        val dao = db.readingDao()
        dao.insertIfMissing(ReadingDayEntity(1, "2026-11-01"))
        dao.add(1, "2026-11-01", 20 * 60, 100)
        db.backupDao().putReadingDays(listOf(ReadingDayEntity(1, "2026-11-01", seconds = 15 * 60, characters = 50, deviceId = "other")))
        val rows = dao.observeAll().first()
        assertEquals(2, rows.size)
        val day = rows.perBookAndDay().single()
        assertEquals(35 * 60L, day.seconds)
        assertEquals(150L, day.characters)
    }
}
