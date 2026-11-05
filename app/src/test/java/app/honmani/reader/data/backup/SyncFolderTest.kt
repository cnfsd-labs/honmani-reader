package app.honmani.reader.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.merge.SyncKeys
import app.honmani.reader.data.merge.UpdateClock
import app.honmani.reader.data.opds.OpdsCatalogEntity
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordProgressStore
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** This phone's sync file: name, content, atomic write, no rewrite of unchanged data, errors. */
@RunWith(RobolectricTestRunner::class)
class SyncFolderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: HonmaniDatabase
    private val folder = FakeFolder()
    private var clock = 5_000L
    private val id = "install-1"
    private val file = "content://tree/a/${SyncFolder.fileName(id)}"
    private val settings = context.getSharedPreferences("sync-test-settings", Context.MODE_PRIVATE)
    private val device = context.getSharedPreferences("sync-test-device", Context.MODE_PRIVATE)
    private lateinit var sync: SyncFolder

    @Before
    fun setUp() {
        UpdateClock.source = { 1_000L }
        settings.edit().clear().putString("secret_setting", "reader-choice").commit()
        device.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, HonmaniDatabase::class.java).allowMainThreadQueries().build()
        val files = tmp.newFolder()
        val backup = Backup(
            database = db,
            settings = settings,
            milestones = MilestoneStore(context.getSharedPreferences("sync-test-milestones", Context.MODE_PRIVATE)),
            stretches = ReadStretchStore(files.resolve("stretches.txt")),
            wordProgress = WordProgressStore(files.resolve("progress.json")),
            restored = RestoredBooks(files.resolve("restored.json")),
            grades = { emptyMap() },
            installationId = { id },
            now = { clock },
        )
        sync = SyncFolder(
            device = device,
            target = folder,
            installationId = { id },
            deviceName = "Test Phone",
            export = { backup.export(mapOf("ttu" to "x"), it) },
            merge = {},
            pruneTombstones = { db.tombstoneDao().deleteOlderThan(it) },
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        db.close()
        UpdateClock.source = System::currentTimeMillis
    }

    private fun word(expression: String) = WordEntity(
        expression = expression, reading = "よみ", gloss = "g", lookupCount = 1, firstLookedUpAt = 1,
        lastLookedUpAt = 1, rankKey = 1.0, lastSentence = "", lastSurfaceStart = 0, lastSurfaceEnd = 0,
    )

    private suspend fun fill() {
        val bookId = db.bookDao().insert(BookEntity(contentHash = "h1", title = "T", format = BookFormat.EPUB, fileName = "h1.epub", addedAt = 1))
        db.readingDao().insertIfMissing(ReadingDayEntity(bookId, "2026-11-01"))
        db.readingDao().add(bookId, "2026-11-01", 60, 500)
        db.wordDao().insert(word("猫"))
        val gone = db.wordDao().insert(word("犬"))
        db.wordDao().delete(gone)
        db.bookmarkDao().put(BookmarkEntity(contentHash = "h1", name = "here", characters = 10, total = 100, createdAt = 1))
        db.opdsCatalogDao().insert(OpdsCatalogEntity(title = "Server", url = "http://x", username = "me", passwordCipher = "secret", addedAt = 1))
    }

    private fun read(address: String = file): BackupFile =
        BackupCodec.read(ByteArrayInputStream(folder.files.getValue(address)))

    @Test
    fun offByDefaultWritesNothing() = runBlocking {
        fill()
        sync.write()

        assertTrue(folder.files.isEmpty())
        assertEquals(SyncFolderState.Off, sync.state.value)
    }

    @Test
    fun writesOneFileNamedByTheInstallationWithTheSyncFields() = runBlocking {
        fill()
        assertTrue(sync.choose("content://tree/a"))
        sync.write()

        assertEquals(listOf(file), folder.files.keys.toList())
        assertEquals("honmani-sync-install-1.zip", SyncFolder.fileName(id))
        val written = read()
        assertEquals(1, written.syncFormat)
        assertEquals(id, written.deviceId)
        assertEquals("Test Phone", written.deviceName)
        assertEquals(listOf("猫"), written.words.map { it.expression })
        // Merely looked up or added: nothing of the reader's choosing that should beat another phone.
        assertEquals(0L, written.words.single().updatedAt)
        assertEquals(0L, written.books.single().book!!.updatedAt)
        assertEquals(1_000L, written.bookmarks.single().updatedAt)
        assertEquals(SyncFolderState.On(null, 5_000L), sync.state.value)
    }

    @Test
    fun readingDaysCarryThisPhonesIdAndTombstonesAreIncluded() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()

        val written = read()
        assertEquals(listOf(id), written.readingDays.map { it.device })
        assertEquals(60L, written.readingDays.single().seconds)
        assertEquals(
            listOf(BackupTombstone(SyncKeys.WORD, SyncKeys.word("犬", "よみ"), 1_000L)),
            written.tombstones.filter { it.kind == SyncKeys.WORD },
        )
    }

    @Test
    fun noSettingsCatalogsOrCredentials() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()

        val written = read()
        assertTrue(written.settings.isEmpty())
        assertNull(written.readerSettings)
        assertTrue(written.catalogs.isEmpty())
        // The zip is deflated: check the JSON the codec writes, which is what the zip holds.
        val json = BackupCodec.toJson(written)
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("reader-choice"))
        assertFalse(json.contains("\"me\""))
    }

    @Test
    fun anOldReaderStillParsesTheFile() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()

        // The reader of backups ignores keys it doesn't know: the file is a BackupFile with extras.
        val written = read()
        assertEquals(BackupFile.FORMAT, written.format)
        assertEquals(BackupFile.VERSION, written.version)
        val asBackup = BackupCodec.read(ByteArrayInputStream(folder.files.getValue(file)))
        assertEquals(written.words, asBackup.words)
    }

    @Test
    fun writingLeavesNoTemporaryFile() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()
        assertTrue(folder.files.keys.none { ".part" in it })

        folder.renamable = false
        db.wordDao().insert(word("鳥"))
        sync.write()

        assertEquals(listOf(file), folder.files.keys.toList())
        assertEquals(setOf("猫", "鳥"), read().words.map { it.expression }.toSet())
    }

    @Test
    fun anInterruptedWriteKeepsTheEarlierFile() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()
        val before = folder.files.getValue(file).copyOf()

        db.wordDao().insert(word("鳥"))
        folder.failAfter = 10
        clock = 9_000L
        sync.write()

        assertEquals(before.toList(), folder.files.getValue(file).toList())
        assertTrue(folder.files.keys.none { ".part" in it })
        assertTrue(sync.state.value is SyncFolderState.Unavailable)
    }

    @Test
    fun unchangedDataIsNotRewrittenButChangesAre() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()
        val first = folder.files.getValue(file)

        clock = 9_000L
        sync.write()
        assertTrue(first === folder.files.getValue(file))
        assertEquals(SyncFolderState.On(null, 5_000L), sync.state.value)

        db.wordDao().insert(word("鳥"))
        sync.write()
        assertEquals(SyncFolderState.On(null, 9_000L), sync.state.value)
        assertEquals(setOf("猫", "鳥"), read().words.map { it.expression }.toSet())

        // A file removed from the folder is written again.
        folder.files.clear()
        clock = 11_000L
        sync.write()
        assertEquals(listOf(file), folder.files.keys.toList())
    }

    @Test
    fun oldTombstonesAreDroppedBeforeWriting() = runBlocking {
        fill()
        clock = 1_000L + SyncFolder.TOMBSTONE_KEEP_MS + 1
        sync.choose("content://tree/a")
        sync.write()

        assertTrue(read().tombstones.isEmpty())
    }

    @Test
    fun lostPermissionShowsUnavailableAndRecovers() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        folder.folderGone = true
        sync.write()

        assertEquals(SyncFolderState.Unavailable(null), sync.state.value)
        assertTrue(folder.files.isEmpty())

        folder.folderGone = false
        sync.write()
        assertEquals(SyncFolderState.On(null, 5_000L), sync.state.value)
    }

    @Test
    fun aFolderTheProviderRefusesIsNotAccepted() {
        folder.denyGrant = true

        assertFalse(sync.choose("content://tree/a"))
        assertEquals(SyncFolderState.Off, sync.state.value)
    }

    @Test
    fun switchingOffForgetsTheFolderButKeepsTheFile() = runBlocking {
        fill()
        sync.choose("content://tree/a")
        sync.write()
        sync.disable()

        assertEquals(SyncFolderState.Off, sync.state.value)
        assertTrue(folder.granted.isEmpty())
        assertEquals(listOf(file), folder.files.keys.toList())
        assertNull(sync.folder)
        db.wordDao().insert(word("鳥"))
        sync.write()
        assertEquals(setOf("猫"), read().words.map { it.expression }.toSet())
    }
}
