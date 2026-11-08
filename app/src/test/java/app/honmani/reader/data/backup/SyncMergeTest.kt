package app.honmani.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.FolderEntity
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.data.merge.UpdateClock
import app.honmani.reader.data.review.ReadDayEntity
import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewLogEntity
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity
import app.honmani.reader.data.words.WordProgressStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phones with a database each, one shared folder, and the real export, zip and merge between them. */
@RunWith(RobolectricTestRunner::class)
class SyncMergeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = FakeFolder()
    private val folderName = "content://tree/s"
    private var clock = 1_000L
    private var tick = 1L
    private val phones = ArrayList<Phone>()
    private val day = "2026-11-01"
    private val hash = "h1"

    private inner class Phone(val id: String) {
        private val dir = tmp.newFolder()
        val db: HonmaniDatabase = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java).allowMainThreadQueries().build()
        val settings = context.getSharedPreferences("merge-$id-settings", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        private val device = context.getSharedPreferences("merge-$id-device", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        private val milestones = MilestoneStore(context.getSharedPreferences("merge-$id-milestones", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        private val stretches = ReadStretchStore(dir.resolve("stretches.txt"))
        private val progress = WordProgressStore(dir.resolve("progress.json"))
        val restored = RestoredBooks(dir.resolve("restored.json"))
        val backup = Backup(db, settings, milestones, stretches, progress, restored, { emptyMap() }, { id }, { clock })
        private val merger = SyncMerge(db, milestones, stretches, progress, restored, now = { clock }) { id }
        private val syncSettings = SyncSettings(settings, device) { clock }
        val address = "$folderName/${SyncFolder.fileName(id)}"
        var merges = 0
        val sync = SyncFolder(
            device = device,
            target = folder,
            installationId = { id },
            deviceName = "Phone $id",
            export = { backup.export(null, it).copy(syncSettings = syncSettings.stamped()) },
            merge = {
                merges++
                merger.merge(it)
                syncSettings.merge(it.syncSettings)
            },
            pruneTombstones = { db.tombstoneDao().deleteOlderThan(it) },
            now = { clock },
        ).also { it.choose(folderName) }

        /** As a folder app would show it: a file that changed has a new date. */
        suspend fun sync() {
            val before = folder.files[address]
            sync.sync()
            if (folder.files[address] !== before) folder.modified[address] = ++tick
        }

        suspend fun merge(file: BackupFile) = merger.merge(file)

        suspend fun addBook(book: BookEntity = book()) = db.bookDao().insert(book)

        suspend fun addWord(word: WordEntity = word()) = db.wordDao().insert(word)

        suspend fun findWord(expression: String = "猫") = db.wordDao().find(expression, "ねこ")

        suspend fun read(bookId: Long, seconds: Long, listenSeconds: Long = 0) {
            db.readingDao().insertIfMissing(ReadingDayEntity(bookId, day))
            db.readingDao().add(bookId, day, seconds, seconds * 10, listenSeconds)
        }

        suspend fun secondsListened() = db.backupDao().readingDays().sumOf { it.listenSeconds }

        suspend fun secondsRead() = db.backupDao().readingDays().sumOf { it.seconds }

        suspend fun theBook() = db.bookDao().findByHash(hash)!!

        suspend fun logs() = db.backupDao().reviewLogs()

        fun close() = db.close()
    }

    private fun phone(id: String) = Phone(id).also { phones += it }

    private fun book() = BookEntity(contentHash = hash, title = "T", format = BookFormat.EPUB, fileName = "$hash.epub", addedAt = 1)

    private fun word(expression: String = "猫", lookups: Int = 1, lastLookedUpAt: Long = 1) = WordEntity(
        expression = expression, reading = "ねこ", gloss = "cat", lookupCount = lookups, firstLookedUpAt = 1,
        lastLookedUpAt = lastLookedUpAt, rankKey = 1.0, lastSentence = "", lastSurfaceStart = 0, lastSurfaceEnd = 0,
    )

    private fun card(wordId: Long, reps: Int = 0, stability: Double = 0.0) =
        ReviewCardEntity(wordId = wordId, due = 100, reps = reps, stability = stability, addedAt = 1)

    private fun lookup(wordId: Long, at: Long) = WordLookupEntity(
        wordId = wordId, surface = "猫", sentence = "s$at", surfaceStart = 0, surfaceEnd = 1, bookId = null,
        bookTitle = null, lookedUpAt = at, origin = LookupOrigin.READER,
    )

    /** Enough rounds for everything to reach everyone, in the order given. */
    private suspend fun settle(vararg order: Phone) {
        repeat(2) { order.forEach { it.sync() } }
    }

    @Before
    fun setUp() {
        UpdateClock.source = { clock }
    }

    @After
    fun tearDown() {
        phones.forEach { it.close() }
        UpdateClock.source = System::currentTimeMillis
    }

    // Clocks that are wrong

    private val oneDay = SyncStamps.DAY_MS
    private val tenYears = 3650 * oneDay

    /** Phone a has a wrong clock and stars the word; b (right clock) has read a's file. */
    private suspend fun starredInTheFuture(): Pair<Phone, Phone> {
        val a = phone("a")
        val b = phone("b")
        val wa = a.addWord()
        b.addWord()
        clock = tenYears
        a.db.wordDao().setStarred(wa, true)
        a.sync()
        clock = 2_000
        b.sync()
        return a to b
    }

    @Test
    fun aFileWithDatesInTheFutureIsHeldNotMerged() = runBlocking<Unit> {
        val (_, b) = starredInTheFuture()

        assertFalse(b.findWord()!!.starred)
        val held = (b.sync.state.value as SyncFolderState.On).held
        assertEquals("Phone a", held?.device)
        assertEquals(tenYears, held?.latest)
        assertEquals(0, b.merges)
        // Asking again changes nothing, and does not read the file again as new.
        b.sync()
        assertEquals(0, b.merges)
        assertEquals(held, (b.sync.state.value as SyncFolderState.On).held)
    }

    @Test
    fun mergingTheHeldFileSetsItsDatesBackAndALaterLocalEditWins() = runBlocking<Unit> {
        val (_, b) = starredInTheFuture()

        b.sync.mergeHeld()

        assertNull((b.sync.state.value as SyncFolderState.On).held)
        assertTrue(b.findWord()!!.starred)
        assertEquals(2_000 + oneDay, b.findWord()!!.updatedAt)
        // b's own edit, two days on, beats the clamped date; a's file is not merged again.
        clock = 2_000 + 2 * oneDay
        b.db.wordDao().setStarred(b.findWord()!!.id, false)
        b.sync()
        assertFalse(b.findWord()!!.starred)
        assertEquals(1, b.merges)
    }

    @Test
    fun skippingTheHeldFileLeavesLocalDataAndDoesNotAskAgainUntilItChanges() = runBlocking<Unit> {
        val (a, b) = starredInTheFuture()

        b.sync.skipHeld()
        b.sync()
        b.sync()

        assertNull((b.sync.state.value as SyncFolderState.On).held)
        assertFalse(b.findWord()!!.starred)
        assertEquals(0, b.merges)
        // The file changes: that is a new question.
        clock = tenYears + 5
        a.db.wordDao().setNote(a.findWord()!!.id, "again")
        a.sync()
        clock = 3_000
        b.sync()
        assertEquals("Phone a", (b.sync.state.value as SyncFolderState.On).held?.device)
    }

    @Test
    fun aFileWithNormalDatesIsMergedWithoutAsking() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val wa = a.addWord()
        b.addWord()
        clock = 2_000
        a.db.wordDao().setStarred(wa, true)
        settle(a, b)

        assertTrue(b.findWord()!!.starred)
        assertNull((b.sync.state.value as SyncFolderState.On).held)
    }

    // Words

    @Test
    fun theLaterWordEditWinsAsAGroupAndCountersKeepTheirRules() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val wa = a.addWord(word(lookups = 5))
        val wb = b.addWord(word(lookups = 2, lastLookedUpAt = 50))
        clock = 2_000
        a.db.wordDao().setNote(wa, "from a")
        clock = 3_000
        b.db.wordDao().setStarred(wb, true)
        a.db.wordDao().insertLookup(lookup(wa, 10))
        b.db.wordDao().insertLookup(lookup(wb, 20))
        a.db.reviewDao().insertReadDay(ReadDayEntity(wa, 7, 3))
        b.db.reviewDao().insertReadDay(ReadDayEntity(wb, 7, 5))

        settle(a, b)

        for (p in listOf(a, b)) {
            val word = p.findWord()!!
            assertTrue(word.starred)
            assertEquals("", word.note)
            assertEquals(3_000L, word.updatedAt)
            assertEquals(5, word.lookupCount)
            assertEquals(50L, word.lastLookedUpAt)
            assertEquals(setOf(10L, 20L), p.db.wordDao().allLookups(word.id).map { it.lookedUpAt }.toSet())
            assertEquals(listOf(5), p.db.backupDao().readDays().map { it.count })
        }
    }

    @Test
    fun anUnstarIsPassedOnWhereARestoreWouldKeepTheStar() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        clock = 2_000
        a.db.wordDao().setStarred(id, true)
        settle(a, b)
        assertTrue(b.findWord()!!.starred)

        clock = 3_000
        b.db.wordDao().setStarred(b.findWord()!!.id, false)
        settle(b, a)

        assertFalse(a.findWord()!!.starred)
        assertFalse(b.findWord()!!.starred)
    }

    @Test
    fun aDeletedWordGoesEverywhereAndStaysGone() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        settle(a, b)
        assertNotNull(b.findWord())

        clock = 2_000
        a.db.wordDao().delete(id)
        settle(a, b)

        assertNull(a.findWord())
        assertNull(b.findWord())
    }

    @Test
    fun aWordSavedAfterTheDeleteSurvivesOnBothPhones() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        settle(a, b)

        clock = 2_000
        a.db.wordDao().delete(id)
        clock = 3_000
        b.db.wordDao().setNote(b.findWord()!!.id, "still wanted")
        settle(a, b)

        assertEquals("still wanted", a.findWord()!!.note)
        assertEquals("still wanted", b.findWord()!!.note)
        assertTrue(a.db.tombstoneDao().all().none { it.kind == "word" })
        assertTrue(b.db.tombstoneDao().all().none { it.kind == "word" })
    }

    @Test
    fun aWordLookedUpAfterTheDeleteSurvives() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        settle(a, b)

        clock = 2_000
        a.db.wordDao().delete(id)
        val later = b.findWord()!!
        b.db.wordDao().updateRow(later.copy(lastLookedUpAt = 3_000, lookupCount = 2))
        settle(a, b)

        assertEquals(2, a.findWord()!!.lookupCount)
        assertNotNull(b.findWord())
    }

    @Test
    fun onEqualTimesTheDeleteWinsWhicheverPhoneGoesFirst() = runBlocking<Unit> {
        for ((first, second) in listOf("a1" to "b1", "b2" to "a2")) {
            val deleter = phone(if (first.startsWith("a")) first else second)
            val editor = phone(if (first.startsWith("a")) second else first)
            val id = deleter.addWord(word(expression = first))
            settle(deleter, editor)

            clock += 1_000
            deleter.db.wordDao().delete(id)
            editor.db.wordDao().setNote(editor.findWord(first)!!.id, "same moment")
            if (first.startsWith("a")) settle(deleter, editor) else settle(editor, deleter)

            assertNull(deleter.findWord(first))
            assertNull(editor.findWord(first))
            folder.files.clear()
        }
    }

    @Test
    fun aDeletedLookupDoesNotComeBack() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        val lookupId = a.db.wordDao().insertLookup(lookup(id, 10))
        settle(a, b)
        val onB = b.findWord()!!.id
        assertEquals(1, b.db.wordDao().allLookups(onB).size)

        a.db.wordDao().deleteLookup(lookupId)
        settle(b, a)

        assertTrue(a.db.wordDao().allLookups(id).isEmpty())
        assertTrue(b.db.wordDao().allLookups(onB).isEmpty())
    }

    @Test
    fun aFreshlyAddedRowDoesNotWipeTheOtherPhonesChoices() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val wb = b.addWord()
        val bookB = b.addBook()
        clock = 2_000
        b.db.wordDao().setNote(wb, "mine")
        b.db.bookDao().setFinished(bookB, 2_000)
        b.db.reviewDao().upsert(card(wb, reps = 3, stability = 9.0))
        b.sync()

        // Added here much later, by a plain lookup and import: no choice was made on this phone.
        clock = 9_000
        val wa = a.addWord()
        a.addBook()
        a.db.reviewDao().insertIfMissing(card(wa))
        settle(a, b)

        for (p in listOf(a, b)) {
            assertEquals("mine", p.findWord()!!.note)
            assertEquals(2_000L, p.theBook().finishedAt)
            assertEquals(9.0, p.db.reviewDao().find(p.findWord()!!.id)!!.stability, 0.0)
        }
    }

    @Test
    fun aRepeatedWordInAFileIsTakenOnce() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        a.addWord()
        a.sync()
        val file = BackupCodec.read(ByteArrayInputStream(folder.files.getValue(a.address)))

        b.merge(file.copy(words = file.words + file.words))

        assertEquals(1, b.db.backupDao().words().size)
    }

    // Review

    @Test
    fun theLaterCardWinsAndBothPhonesAnswersAreKept() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val wa = a.addWord()
        val wb = b.addWord()
        clock = 2_000
        a.db.reviewDao().upsert(card(wa, reps = 1, stability = 5.0))
        a.db.reviewDao().insertLog(ReviewLogEntity(wordId = wa, at = 2_000, grade = 3, elapsedDays = 0.0))
        clock = 3_000
        b.db.reviewDao().upsert(card(wb, reps = 1, stability = 9.0))
        b.db.reviewDao().insertLog(ReviewLogEntity(wordId = wb, at = 3_000, grade = 1, elapsedDays = 0.0))

        settle(a, b)

        for (p in listOf(a, b)) {
            assertEquals(9.0, p.db.reviewDao().find(p.findWord()!!.id)!!.stability, 0.0)
            assertEquals(listOf(2_000L, 3_000L), p.logs().map { it.at }.sorted())
        }
    }

    @Test
    fun anUndoneAnswerStaysUndone() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        a.db.reviewDao().insertIfMissing(card(id))
        settle(a, b)

        clock = 2_000
        a.db.reviewDao().upsert(card(id, reps = 1, stability = 5.0))
        val log = a.db.reviewDao().insertLog(ReviewLogEntity(wordId = id, at = 2_000, grade = 3, elapsedDays = 0.0))
        settle(a, b)
        assertEquals(1, b.logs().size)
        assertEquals(1, b.db.reviewDao().find(b.findWord()!!.id)!!.reps)

        clock = 3_000
        a.db.reviewDao().upsert(card(id))
        a.db.reviewDao().deleteLog(log)
        settle(b, a)

        for (p in listOf(a, b)) {
            assertTrue(p.logs().isEmpty())
            assertEquals(0, p.db.reviewDao().find(p.findWord()!!.id)!!.reps)
        }
    }

    @Test
    fun aDeletedCardGoesButAnAnswerLaterElsewhereKeepsIt() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        clock = 1_500
        a.db.reviewDao().upsert(card(id, reps = 1))
        settle(a, b)

        clock = 2_000
        a.db.reviewDao().delete(id)
        settle(a, b)
        assertNull(b.db.reviewDao().find(b.findWord()!!.id))

        clock = 3_000
        b.db.reviewDao().upsert(card(b.findWord()!!.id, reps = 2))
        settle(b, a)
        assertEquals(2, a.db.reviewDao().find(id)!!.reps)
    }

    @Test
    fun aWordComingBackDoesNotLogItsAnswersTwice() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val id = a.addWord()
        a.db.reviewDao().insertLog(ReviewLogEntity(wordId = id, at = 500, grade = 3, elapsedDays = 0.0))
        settle(a, b)

        clock = 2_000
        a.db.wordDao().delete(id)
        clock = 3_000
        b.db.wordDao().setNote(b.findWord()!!.id, "keep")
        settle(a, b)

        assertEquals(1, a.logs().size)
    }

    // Reading

    @Test
    fun listeningTimeTravelsWithTheReadingRows() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        a.read(a.addBook(), 20 * 60, listenSeconds = 5 * 60)
        b.read(b.addBook(), 15 * 60, listenSeconds = 15 * 60)

        settle(a, b)
        assertEquals(20L * 60, a.secondsListened())
        assertEquals(20L * 60, b.secondsListened())
    }

    @Test
    fun twoPhonesReadingAddUpAndStayAddedUp() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        a.read(a.addBook(), 20 * 60)
        b.read(b.addBook(), 15 * 60)

        settle(a, b)
        assertEquals(35L * 60, a.secondsRead())
        assertEquals(35L * 60, b.secondsRead())

        // The same files once more, as after a folder app touched them.
        folder.modified.keys.toList().forEach { folder.modified[it] = ++tick }
        settle(b, a)
        assertEquals(35L * 60, a.secondsRead())
        assertEquals(35L * 60, b.secondsRead())

        // Reading on: this phone's row grows, the other phone takes the larger value.
        a.read(a.theBook().id, 60)
        settle(a, b)
        assertEquals(36L * 60, b.secondsRead())
    }

    @Test
    fun deletingAPhonesOwnStatisticsIsNotUndoneByAnother() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook()
        b.addBook()
        a.read(bookA, 60)
        settle(a, b)
        assertEquals(60L, b.secondsRead())

        a.db.readingDao().deleteBook(bookA)
        settle(b, a)

        assertEquals(0L, a.secondsRead())
    }

    @Test
    fun aRemovedBooksStatisticsReturnWithIt() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook()
        a.read(bookA, 60)
        b.read(b.addBook(), 30)
        settle(a, b)

        // What the library does when a book is removed: the row goes, its statistics wait under the hash.
        a.restored.addWaiting(listOf(WaitingBook(hash, bookA)))
        a.db.bookDao().delete(bookA)
        settle(a, b)
        assertEquals(90L, a.secondsRead())
        assertEquals(90L, b.secondsRead())
        assertNotNull(b.db.bookDao().findByHash(hash))

        val again = a.addBook()
        a.backup.onBookAdded(a.db.bookDao().find(again)!!)
        settle(a, b)

        assertEquals(setOf(again), a.db.backupDao().readingDays().map { it.bookId }.toSet())
        assertEquals(90L, a.secondsRead())
        assertEquals(90L, b.secondsRead())
    }

    @Test
    fun aBookOnlyOnTheOtherPhoneWaitsAndJoinsWhenAdded() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook(book().copy(characters = 500, progress = 0.5f))
        a.read(bookA, 60)
        settle(a, b)

        assertNull(b.db.bookDao().findByHash(hash))
        assertEquals(60L, b.secondsRead())

        val added = b.addBook()
        val merged = b.backup.onBookAdded(b.db.bookDao().find(added)!!)
        assertEquals(500L, merged.characters)
        settle(a, b)

        assertEquals(setOf(added), b.db.backupDao().readingDays().map { it.bookId }.toSet())
        assertEquals(60L, b.secondsRead())
        assertTrue(b.restored.waiting().isEmpty())
    }

    @Test
    fun theFurthestPositionWinsAndWaitsForTheBookToOpen() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        a.addBook(book().copy(characters = 500, progress = 0.5f))
        b.addBook(book().copy(characters = 200, progress = 0.2f))

        settle(a, b)

        assertEquals(500L, a.theBook().characters)
        assertEquals(500L, b.theBook().characters)
        assertEquals(500L, b.restored.takePosition(hash)!!.characters)
        assertNull(a.restored.takePosition(hash))
    }

    @Test
    fun finishedAndHiddenGoByTheLaterChoiceSoAnUnfinishPasses() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook()
        val bookB = b.addBook()
        clock = 2_000
        a.db.bookDao().setFinished(bookA, 2_000)
        a.db.bookDao().setHidden(bookA, true)
        settle(a, b)
        assertEquals(2_000L, b.theBook().finishedAt)
        assertTrue(b.theBook().hidden)

        clock = 3_000
        b.db.bookDao().clearProgress(bookB)
        b.db.bookDao().setHidden(bookB, false)
        settle(b, a)

        assertNull(a.theBook().finishedAt)
        assertFalse(a.theBook().hidden)
    }

    // Marks in a book

    @Test
    fun bookmarkHighlightAndPronunciationEditsAndDeletesPass() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val mark = a.db.bookmarkDao().put(BookmarkEntity(contentHash = hash, name = "old", characters = 10, total = 100, createdAt = 1))
        val line = a.db.highlightDao().put(HighlightEntity(contentHash = hash, start = 5, end = 9, color = 0, text = "t", total = 100, createdAt = 1))
        val said = a.db.pronunciationDao().put(PronunciationEntity(contentHash = hash, text = "名", reading = "な", createdAt = 1))
        settle(a, b)

        clock = 2_000
        a.db.bookmarkDao().rename(mark, "new")
        a.db.highlightDao().setColor(line, 2)
        val saidThere = b.db.pronunciationDao().forBook(hash).single()
        b.db.pronunciationDao().put(saidThere.copy(reading = "めい"))
        settle(a, b)

        for (p in listOf(a, b)) {
            assertEquals("new", p.db.bookmarkDao().forBook(hash).single().name)
            assertEquals(2, p.db.highlightDao().forBook(hash).single().color)
            assertEquals("めい", p.db.pronunciationDao().forBook(hash).single().reading)
        }

        clock = 3_000
        a.db.bookmarkDao().delete(mark)
        a.db.highlightDao().delete(line)
        a.db.pronunciationDao().delete(said)
        settle(b, a)

        for (p in listOf(a, b)) {
            assertTrue(p.db.bookmarkDao().forBook(hash).isEmpty())
            assertTrue(p.db.highlightDao().forBook(hash).isEmpty())
            assertTrue(p.db.pronunciationDao().forBook(hash).isEmpty())
        }
    }

    @Test
    fun removingABookLeavesItsMarksOnTheOtherPhone() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        a.db.bookmarkDao().put(BookmarkEntity(contentHash = hash, name = "here", characters = 10, total = 100, createdAt = 1))
        settle(a, b)

        a.db.bookmarkDao().deleteForBook(hash)
        settle(a, b)

        assertEquals(1, b.db.bookmarkDao().forBook(hash).size)
    }

    // Folders

    @Test
    fun aSameNamedFolderStaysOneAndTheBooksFollowIt() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook()
        b.addBook()
        val fa = a.db.folderDao().insert(FolderEntity(name = "小説", createdAt = 1))
        b.db.folderDao().insert(FolderEntity(name = "小説", createdAt = 1))
        clock = 2_000
        a.db.folderDao().setBookFolder(bookA, fa)
        settle(a, b)

        assertEquals(listOf("小説"), b.db.folderDao().all().map { it.name })
        assertEquals(b.db.folderDao().findByName("小説")!!.id, b.theBook().folderId)
    }

    @Test
    fun aDeletedFolderGoesAndCreatingItAgainBringsItBack() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook()
        b.addBook()
        val first = a.db.folderDao().insert(FolderEntity(name = "小説", createdAt = clock))
        clock = 1_500
        a.db.folderDao().setBookFolder(bookA, first)
        settle(a, b)
        assertNotNull(b.theBook().folderId)

        clock = 2_000
        a.db.folderDao().delete(first)
        settle(b, a)
        assertTrue(a.db.folderDao().all().isEmpty())
        assertTrue(b.db.folderDao().all().isEmpty())
        assertNull(b.theBook().folderId)

        clock = 3_000
        a.db.folderDao().insert(FolderEntity(name = "小説", createdAt = clock))
        settle(a, b)
        assertEquals(listOf("小説"), b.db.folderDao().all().map { it.name })
        assertTrue(b.db.tombstoneDao().all().none { it.kind == "folder" })
    }

    @Test
    fun aRenamedFolderIsRenamedOnTheOtherPhoneWithItsBooks() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook()
        b.addBook()
        val id = a.db.folderDao().insert(FolderEntity(name = "old", createdAt = clock))
        clock = 1_500
        a.db.folderDao().setBookFolder(bookA, id)
        settle(a, b)

        clock = 2_000
        a.db.folderDao().setName(id, "new")
        settle(a, b)

        assertEquals(listOf("new"), b.db.folderDao().all().map { it.name })
        assertEquals(b.db.folderDao().findByName("new")!!.id, b.theBook().folderId)
        assertEquals(listOf("new"), a.db.folderDao().all().map { it.name })
    }

    // The folder

    @Test
    fun whatAToldBReachesCWithoutAsFile() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val c = phone("c")
        val id = a.addWord()
        a.read(a.addBook(), 60)
        b.read(b.addBook(), 30)
        c.addBook()
        a.addWord(word(expression = "犬"))
        clock = 2_000
        a.db.wordDao().setNote(id, "from a")
        a.sync()
        b.sync()
        folder.files.remove(a.address)

        c.sync()
        assertEquals("from a", c.findWord()!!.note)
        assertNotNull(c.findWord("犬"))
        assertEquals(90L, c.secondsRead())

        // A deletion takes the same way.
        clock = 3_000
        a.db.wordDao().delete(a.findWord("犬")!!.id)
        a.sync()
        b.sync()
        folder.files.remove(a.address)
        c.sync()
        assertNull(c.findWord("犬"))
    }

    @Test
    fun ownTemporaryAndConflictFilesAreNotRead() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        b.addWord()
        b.sync()
        val theirs = folder.files.getValue(b.address)
        folder.files.remove(b.address)

        folder.files["$folderName/${SyncFolder.tempName("b")}"] = theirs
        folder.files["$folderName/honmani-sync-b (1).zip"] = theirs
        folder.files["$folderName/honmani-sync-b.sync-conflict-20261105.zip"] = theirs
        folder.files["$folderName/notes.zip"] = theirs
        folder.files[a.address] = theirs
        a.sync()

        assertEquals(0, a.merges)
        assertNull(a.findWord())
        // Its own file is written over what lay under that name.
        assertEquals(emptyList<BackupWord>(), BackupCodec.read(ByteArrayInputStream(folder.files.getValue(a.address))).words)
    }

    @Test
    fun aFileThatIsNoSyncFileOrThisPhonesOwnIsNotMerged() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        b.addWord()
        b.sync()
        val file = BackupCodec.read(ByteArrayInputStream(folder.files.getValue(b.address)))

        folder.files["$folderName/honmani-sync-plain.zip"] = zip(file.copy(syncFormat = null))
        folder.files["$folderName/honmani-sync-renamed.zip"] = zip(file.copy(deviceId = "a"))
        folder.files.remove(b.address)
        a.sync()

        assertEquals(0, a.merges)
        assertNull(a.findWord())
    }

    @Test
    fun aHalfCopiedFileIsSkippedAndTriedAgain() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        b.addWord()
        b.sync()
        val whole = folder.files.getValue(b.address)

        for (length in listOf(0, 10, whole.size / 2)) {
            folder.files[b.address] = whole.copyOf(length)
            a.sync()
            assertNull(a.findWord())
            assertTrue(a.sync.state.value is SyncFolderState.On)
            // Even with the same size and date it is read again: it was never marked as done.
            a.sync()
            assertNull(a.findWord())
        }

        // Only the zip's closing index missing: the entry was checked whole, so it can be taken.
        folder.files[b.address] = whole.copyOf(whole.size - 1)
        a.sync()
        assertNotNull(a.findWord())
        assertEquals("Phone b", (a.sync.state.value as SyncFolderState.On).syncedWith)
    }

    @Test
    fun aNewerFileIsNamedAndTheOthersStillMerge() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val c = phone("c")
        c.addWord(word(expression = "犬"))
        c.sync()
        b.addWord()
        b.sync()
        val file = BackupCodec.read(ByteArrayInputStream(folder.files.getValue(b.address)))
        folder.files[b.address] = zip(file.copy(syncFormat = SyncFolder.FORMAT + 1))
        a.sync()

        assertEquals("Phone b", (a.sync.state.value as SyncFolderState.On).newerDevice)
        assertNull(a.findWord())
        assertNotNull(a.findWord("犬"))

        // A newer backup version is named too.
        folder.files[b.address] = zip(file.copy(version = BackupFile.VERSION + 1))
        folder.modified[b.address] = ++tick
        a.sync()
        assertEquals("Phone b", (a.sync.state.value as SyncFolderState.On).newerDevice)
        assertNull(a.findWord())

        // Once it can be read, the line goes.
        folder.files[b.address] = zip(file)
        folder.modified[b.address] = ++tick
        a.sync()
        assertNull((a.sync.state.value as SyncFolderState.On).newerDevice)
        assertNotNull(a.findWord())
    }

    @Test
    fun anUnchangedFileIsMergedOnce() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        b.addWord()
        b.sync()

        a.sync()
        a.sync()

        assertEquals(1, a.merges)
    }

    @Test
    fun phonesSettleInsteadOfWritingBackAndForth() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        val bookA = a.addBook(book().copy(characters = 300, progress = 0.3f))
        val bookB = b.addBook()
        a.read(bookA, 60)
        b.read(bookB, 30)
        val wa = a.addWord()
        val wb = b.addWord(word(lookups = 4))
        val gone = a.addWord(word(expression = "犬"))
        clock = 2_000
        a.db.wordDao().setNote(wa, "n")
        a.db.wordDao().delete(gone)
        a.db.wordDao().insertLookup(lookup(wa, 10))
        b.db.reviewDao().upsert(card(wb, reps = 1, stability = 2.0))
        b.db.reviewDao().insertLog(ReviewLogEntity(wordId = wb, at = 2_000, grade = 3, elapsedDays = 0.0))
        val fa = a.db.folderDao().insert(FolderEntity(name = "小説", createdAt = clock))
        a.db.folderDao().setBookFolder(bookA, fa)
        b.db.bookmarkDao().put(BookmarkEntity(contentHash = hash, name = "m", characters = 1, total = 9, createdAt = 1))
        b.db.highlightDao().put(HighlightEntity(contentHash = hash, start = 1, end = 2, color = 0, text = "t", total = 9, createdAt = 1))
        a.db.pronunciationDao().put(PronunciationEntity(contentHash = hash, text = "名", reading = "な", createdAt = 1))
        settle(a, b)

        val fileA = folder.files.getValue(a.address)
        val fileB = folder.files.getValue(b.address)
        val mergesA = a.merges
        val mergesB = b.merges

        clock = 50_000
        settle(a, b)
        assertTrue(fileA === folder.files.getValue(a.address))
        assertTrue(fileB === folder.files.getValue(b.address))
        assertEquals(mergesA, a.merges)
        assertEquals(mergesB, b.merges)

        // Reading the same files again changes no data either, so no file is rewritten.
        folder.modified.keys.toList().forEach { folder.modified[it] = ++tick }
        settle(a, b)
        assertTrue(a.merges > mergesA)
        assertTrue(fileA === folder.files.getValue(a.address))
        assertTrue(fileB === folder.files.getValue(b.address))
    }

    @Test
    fun theLaterSettingWinsAndOthersStayPerPhone() = runBlocking<Unit> {
        val a = phone("a")
        val b = phone("b")
        a.settings.edit().putInt(ReviewSettings.KEY_NEW_PER_DAY, 10).putBoolean("local_only", true).commit()
        clock = 2_000
        a.sync()
        b.settings.edit().putInt(ReviewSettings.KEY_NEW_PER_DAY, 20).commit()
        clock = 3_000
        b.sync()
        settle(a, b)

        assertEquals(20, a.settings.getInt(ReviewSettings.KEY_NEW_PER_DAY, 0))
        assertEquals(20, b.settings.getInt(ReviewSettings.KEY_NEW_PER_DAY, 0))
        assertFalse(b.settings.contains("local_only"))

        // A later change on the other phone goes back the same way.
        clock = 4_000
        a.settings.edit().putInt(ReviewSettings.KEY_NEW_PER_DAY, 30).commit()
        settle(a, b)
        assertEquals(30, b.settings.getInt(ReviewSettings.KEY_NEW_PER_DAY, 0))
    }

    private fun zip(file: BackupFile) = ByteArrayOutputStream().also { BackupCodec.write(file, it) }.toByteArray()
}
