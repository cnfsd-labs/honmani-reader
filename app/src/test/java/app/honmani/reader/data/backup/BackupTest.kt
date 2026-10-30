package app.honmani.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.words.LookupEventEntity
import app.honmani.reader.data.opds.OpdsCatalogEntity
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.WordProgressStore
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.data.words.WordStep
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val phones = ArrayList<Phone>()
    private val today = LocalDate.of(2026, 10, 22)

    private companion object {
        /** When the book was last opened on the old phone: its position was read after that. */
        const val LAST_OPENED = 1_700_000_000_000L
    }

    /** One installation of the app: its database, preferences and files. */
    private inner class Phone(name: String) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val database: HonmaniDatabase = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = context.getSharedPreferences("$name-settings", Context.MODE_PRIVATE)
        val milestones = MilestoneStore(context.getSharedPreferences("$name-device", Context.MODE_PRIVATE))
        private val files = folder.newFolder(name)
        val stretches = ReadStretchStore(files.resolve("read-stretches.txt"))
        val progress = WordProgressStore(files.resolve("word-progress.json"))
        val restored = RestoredBooks(files.resolve("restored-books.json"))
        val words = WordRepository(database, now = { 1_800_000_000_000L })
        val backup = Backup(
            database = database,
            settings = settings,
            milestones = milestones,
            stretches = stretches,
            wordProgress = progress,
            restored = restored,
            grades = { emptyMap() },
            installationId = { name },
            now = { 1_800_000_000_000L },
        )

        init {
            phones += this
        }

        suspend fun addBook(hash: String, characters: Long = 0, progress: Float = 0f, lastOpenedAt: Long? = null): BookEntity {
            val book = BookEntity(
                contentHash = hash,
                title = "Book $hash",
                format = BookFormat.EPUB,
                fileName = "$hash.epub",
                addedAt = 1_000,
                characters = characters,
                progress = progress,
                lastOpenedAt = lastOpenedAt,
            )
            return book.copy(id = database.bookDao().insert(book))
        }

        suspend fun read(bookId: Long, day: String, seconds: Long, characters: Long) {
            database.readingDao().insertIfMissing(ReadingDayEntity(bookId, day))
            database.readingDao().add(bookId, day, seconds, characters)
        }

        suspend fun days() = database.backupDao().readingDays()

        suspend fun snapshot() = listOf(
            database.backupDao().books(),
            database.backupDao().readingDays(),
            database.backupDao().lookupEvents().map { it.lookedUpAt to it.bookId },
            database.backupDao().words().map { it.copy(id = 0) },
            database.backupDao().wordLookups().map { it.copy(id = 0) },
            database.backupDao().catalogs().map { it.copy(id = 0) },
            database.backupDao().removedBooks(),
            restored.waiting(),
            stretches.all().mapValues { it.value.toList() },
            progress.load(),
        )
    }

    @After
    fun tearDown() {
        phones.forEach { it.database.close() }
    }

    private fun lookup(expression: String, bookId: Long?) = LookedUpWord(
        expression = expression,
        reading = "よみ",
        gloss = "gloss",
        surface = expression,
        sentence = "${expression}だ。",
        surfaceStart = 0,
        bookId = bookId,
        bookTitle = bookId?.let { "Book" },
    )

    /** Through the file format, as a real backup goes. */
    private fun throughFile(backup: BackupFile): BackupFile {
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(backup, it) }.toByteArray()
        return BackupCodec.read(ByteArrayInputStream(bytes))
    }

    /** A phone with a book read on two days, a removed book's reading, words, settings and more. */
    private suspend fun oldPhone(): Phone = Phone("old").apply {
        val book = addBook("hash-a", characters = 5_000, progress = 0.25f, lastOpenedAt = LAST_OPENED)
        read(book.id, "2026-10-20", seconds = 600, characters = 3_000)
        read(book.id, "2026-10-21", seconds = 400, characters = 2_000)
        // A book removed from the library: its reading still counts.
        read(99, "2026-10-19", seconds = 300, characters = 1_000)
        words.record(lookup("猫", book.id))
        words.record(lookup("犬", null))
        val dog = words.find("犬", "よみ")!!
        words.markLearned(dog.id)
        progress.save(mapOf(dog.id to WordStep(9, 4, 1, true)))
        settings.edit().putString("accent", "TEAL").putBoolean("show_difficulty", false).putFloat("accent_custom_hue", 120f).apply()
        milestones.record(listOf("characters_10000"), today)
        stretches.add("hash-a", 0f, 0.25f)
        database.opdsCatalogDao().insert(
            OpdsCatalogEntity(title = "Home", url = "http://nas/opds", username = "me", passwordCipher = "secret", addedAt = 1),
        )
    }

    @Test
    fun aNewPhoneGetsEverythingBack_andABookAddedAgainTakesItsProgress() = runTest {
        val old = oldPhone()
        val backup = throughFile(old.backup.export(readerSettings = mapOf("fontSize" to "30", "theme" to null)))
        val new = Phone("new")

        val summary = new.backup.restore(backup)

        assertEquals(0, summary.booksInLibrary)
        assertEquals(1, summary.booksWaiting)
        assertEquals(2, summary.wordsAdded)
        assertEquals(1, summary.catalogsAdded)
        assertEquals(mapOf("fontSize" to "30", "theme" to null), backup.readerSettings)
        assertEquals(6_000L, new.days().sumOf { it.characters })
        assertEquals(1_300L, new.days().sumOf { it.seconds })
        assertTrue(new.days().all { it.bookId < 0 })
        assertEquals("TEAL", new.settings.getString("accent", null))
        assertEquals(false, new.settings.getBoolean("show_difficulty", true))
        assertEquals(120f, new.settings.getFloat("accent_custom_hue", 0f))
        assertEquals(mapOf("characters_10000" to today), new.milestones.seen.value)
        assertEquals(listOf(0, 2_500), new.stretches.all()["hash-a"]?.toList())
        val dog = new.words.find("犬", "よみ")!!
        assertEquals(WordStatus.LEARNED, dog.status)
        assertEquals(WordStep(9, 4, 1, true), new.progress.load()[dog.id])
        val catalog = new.database.backupDao().catalogs().single()
        assertEquals("http://nas/opds", catalog.url)
        assertEquals("me", catalog.username)
        assertNull(catalog.passwordCipher)

        // The same file is added again: its reading days, lookups and progress are its own again.
        val added = new.addBook("hash-a")
        val restored = new.backup.onBookAdded(added)

        assertEquals(5_000L, restored.characters)
        assertEquals(0.25f, restored.progress)
        assertEquals(5_000L, new.database.bookDao().find(added.id)?.characters)
        assertEquals(5_000L, new.days().filter { it.bookId == added.id }.sumOf { it.characters })
        assertEquals(1, new.database.wordDao().countLookups(added.id).first())
        assertEquals(RestoredPosition(5_000, 0.25f, savedAt = LAST_OPENED), new.restored.takePosition("hash-a"))
        assertNull(new.restored.takePosition("hash-a"))
        assertTrue(new.restored.waiting().none { it.key == "hash-a" })
    }

    @Test
    fun settingsOfAnotherTypeOrUnknownAreLeftOut() = runTest {
        val backup = throughFile(
            BackupFile(
                exportedAt = 1,
                settings = mapOf(
                    "accent" to BackupValue(BackupValue.INT, JsonPrimitive(3)),
                    "show_difficulty" to BackupValue(BackupValue.STRING, JsonArray(listOf(JsonPrimitive("x")))),
                    "anki_deck" to BackupValue(BackupValue.STRING, JsonPrimitive("Mining")),
                    "something_else" to BackupValue(BackupValue.STRING, JsonPrimitive("x")),
                ),
            ),
        )
        val new = Phone("new")

        new.backup.restore(backup)

        assertEquals(mapOf("anki_deck" to "Mining"), new.settings.all)
    }

    @Test
    fun restoringTheSameBackupTwiceChangesNothing() = runTest {
        val backup = throughFile(oldPhone().backup.export(readerSettings = null))
        val new = Phone("new")
        new.backup.restore(backup)
        val once = new.snapshot()

        val again = new.backup.restore(backup)

        assertEquals(once, new.snapshot())
        assertEquals(0, again.wordsAdded)
        assertEquals(0, again.booksWaiting)
        assertEquals(0, again.catalogsAdded)
    }

    @Test
    fun aBackupOfARestoredPhoneRestoresLikeTheFirst() = runTest {
        val backup = throughFile(oldPhone().backup.export(readerSettings = null))
        val middle = Phone("middle")
        middle.backup.restore(backup)
        val new = Phone("new")
        new.backup.restore(throughFile(middle.backup.export(readerSettings = null)))
        val once = new.snapshot()

        // The first backup again: the books waiting and the removed book's reading are the same ones.
        new.backup.restore(backup)

        assertEquals(once, new.snapshot())
        assertEquals(6_000L, new.days().sumOf { it.characters })
    }

    @Test
    fun aRestoreKeepsWhatThisPhoneHas_andTakesTheFurtherPosition() = runTest {
        val backup = throughFile(oldPhone().backup.export(readerSettings = null))
        val new = Phone("new")
        val book = new.addBook("hash-a", characters = 1_000, progress = 0.05f)
        new.read(book.id, "2026-10-21", seconds = 900, characters = 500)
        new.read(book.id, "2026-10-22", seconds = 100, characters = 100)
        new.words.record(lookup("猫", book.id))

        val summary = new.backup.restore(backup)

        assertEquals(1, summary.booksInLibrary)
        assertEquals(0, summary.booksWaiting)
        assertEquals(listOf(book.id), summary.positions.map { it.id })
        assertEquals(5_000L, new.database.bookDao().find(book.id)?.characters)
        val days = new.days().filter { it.bookId == book.id }.associateBy { it.day }
        assertEquals(600L, days.getValue("2026-10-20").seconds)
        assertEquals(900L, days.getValue("2026-10-21").seconds)
        assertEquals(2_000L, days.getValue("2026-10-21").characters)
        assertEquals(100L, days.getValue("2026-10-22").seconds)
        assertEquals(1, new.database.backupDao().words().count { it.expression == "猫" })
        assertEquals(RestoredPosition(5_000, 0.25f, savedAt = LAST_OPENED), new.restored.takePosition("hash-a"))
    }

    @Test
    fun restoringOnTheSamePhoneDoesNotCountTheReadingOfRemovedBooksTwice() = runTest {
        val phone = oldPhone()
        // A lookup in the removed book (id 99), too.
        phone.database.backupDao().insertLookupEvents(listOf(LookupEventEntity(lookedUpAt = 5, bookId = 99)))
        val before = phone.snapshot()
        val backup = throughFile(phone.backup.export(readerSettings = null))

        phone.backup.restore(backup)

        assertEquals(6_000L, phone.days().sumOf { it.characters })
        assertTrue(phone.days().none { it.bookId < 0 })
        assertEquals(1, phone.database.backupDao().lookupEvents().count { it.bookId == 99L })
        assertTrue(phone.restored.waiting().isEmpty())
        assertEquals(before, phone.snapshot())
    }

    @Test
    fun ankiNotesOfAnotherPhoneAreNotTakenOver() = runTest {
        val old = oldPhone()
        old.words.setAnkiNote(old.words.find("猫", "よみ")!!.id, 1234)
        val backup = throughFile(old.backup.export(readerSettings = null))
        val file = BackupCodec.read(ByteArrayInputStream(ByteArrayOutputStream().also { BackupCodec.write(backup, it) }.toByteArray()))
        // A backup from an older version still names the note.
        val withNote = file.copy(words = file.words.map { if (it.expression == "猫") it.copy(ankiNoteId = 1234) else it })
        val new = Phone("new")
        new.backup.restore(throughFile(withNote))

        assertNull(new.words.find("猫", "よみ")!!.ankiNoteId)
        assertNull(old.backup.export(readerSettings = null).words.first { it.expression == "猫" }.ankiNoteId)
    }

    @Test
    fun aFailedMoveLeavesTheRestoredBookWaiting() = runTest {
        val old = oldPhone()
        val new = Phone("new")
        new.backup.restore(throughFile(old.backup.export(readerSettings = null)))
        val added = new.addBook("hash-a")
        new.database.close()

        runCatching { new.backup.onBookAdded(added) }.also { assertTrue(it.isFailure) }

        assertTrue(new.restored.waiting().any { it.key == "hash-a" })
    }

    @Test
    fun aBookWithoutAnOpeningDateTakesTheBackupsTimeForItsPosition() = runTest {
        val old = Phone("old")
        old.addBook("hash-b", characters = 800, progress = 0.5f)
        val new = Phone("new")
        val book = new.addBook("hash-b", characters = 100, progress = 0.1f)

        new.backup.restore(throughFile(old.backup.export(readerSettings = null)))

        assertEquals(RestoredPosition(800, 0.5f, savedAt = 1_800_000_000_000L), new.restored.takePosition(book.contentHash))
    }

    @Test(expected = BackupFormatException::class)
    fun aZipWithoutABackupIsRefused() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("book.xhtml"))
                zip.write("<html/>".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        BackupCodec.read(ByteArrayInputStream(bytes))
    }

    @Test(expected = BackupFormatException::class)
    fun aBackupFromANewerVersionIsRefused() {
        throughFile(BackupFile(exportedAt = 1, version = BackupFile.VERSION + 1))
    }

    @Test(expected = BackupFormatException::class)
    fun aBackupWhoseDataInflatesBeyondTheLimitIsRefused() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(BackupCodec.ENTRY))
                zip.write(ByteArray(10_000) { ' '.code.toByte() })
                zip.closeEntry()
            }
        }.toByteArray()
        BackupCodec.read(ByteArrayInputStream(bytes), maxJsonBytes = 5_000)
    }
}
