package app.honmani.reader.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoBackupTest {
    private val dir: File = Files.createTempDirectory("auto-backup").toFile()
    private val device = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("auto-backup-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private var clock = 1_000_000_000_000L
    private val auto = AutoBackup(dir, device, now = { clock })

    private fun backup(at: Long = clock) = BackupFile(exportedAt = at)

    @Test
    fun aFreshInstallWithoutABackupStartsSavingAtOnce() = runBlocking {
        auto.checkOffer()
        assertNull(auto.offer.first())

        assertTrue(auto.saveIfChanged { backup() })
        assertEquals(clock, auto.read().exportedAt)
    }

    private fun changed(at: Long = clock, what: String = "22") =
        BackupFile(exportedAt = at, readerSettings = mapOf("fontSize" to what))

    @Test
    fun aBackupIsWrittenAgainAtOnceWhenSomethingChanged() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfChanged { changed(what = "22") })

        clock += 60_000
        assertTrue(auto.saveIfChanged { changed(what = "30") })
        assertEquals(clock, auto.read().exportedAt)
        assertEquals("30", auto.read().readerSettings?.get("fontSize"))
    }

    @Test
    fun anUnchangedBackupIsNotRewritten() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfChanged { changed() })
        val made = clock
        val stamp = auto.file.lastModified()

        // Only the export's own date differs.
        clock += 24 * 60 * 60 * 1000L
        assertEquals(AutoBackup.Outcome.Skipped, auto.save { changed() })
        assertEquals(made, auto.read().exportedAt)
        assertEquals(stamp, auto.file.lastModified())

        // Back up now still writes it.
        assertTrue(auto.saveIfChanged(force = true) { changed() })
        assertEquals(clock, auto.read().exportedAt)
    }

    @Test
    fun aMissingBackupIsWrittenAgainEvenWhenUnchanged() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfChanged { changed() })
        auto.file.delete()

        assertTrue(auto.saveIfChanged { changed() })
        assertTrue(auto.file.isFile)
    }

    @Test
    fun readerSettingsOfTheLastBackupSurviveAnotherWithoutThePage() = runBlocking {
        auto.checkOffer()
        val settings = mapOf("fontSize" to "22", "theme" to null)
        assertTrue(auto.saveIfChanged { BackupFile(exportedAt = clock, readerSettings = settings) })

        clock += 60_000
        assertTrue(auto.saveIfChanged(force = true) { BackupFile(exportedAt = clock, readerSettings = null) })
        assertEquals(clock, auto.read().exportedAt)
        assertEquals(settings, auto.read().readerSettings)

        clock += 60_000
        val newer = mapOf("fontSize" to "30")
        assertTrue(auto.saveIfChanged { BackupFile(exportedAt = clock, readerSettings = newer) })
        assertEquals(newer, auto.read().readerSettings)
    }

    @Test
    fun theOldPhonesBackupIsOfferedAndKeptUntilAnswered() = runBlocking {
        // Android brought back the old phone's file before the first start.
        dir.mkdirs()
        auto.file.outputStream().use { BackupCodec.write(backup(at = 42), it) }

        auto.checkOffer()
        assertTrue(auto.offer.first() != null)
        assertFalse(auto.saveIfChanged(force = true) { backup() })
        assertEquals(42L, auto.read().exportedAt)

        auto.answered()
        assertNull(auto.offer.first())
        assertTrue(auto.saveIfChanged(force = true) { backup() })
        assertEquals(clock, auto.read().exportedAt)

        // Asked once: the next start offers nothing.
        AutoBackup(dir, device, now = { clock }).also { it.checkOffer() }.offer.first().let(::assertNull)
    }

    @Test
    fun theOfferNamesTheBackupsOwnDateNotTheRestoredFilesDate() = runBlocking {
        dir.mkdirs()
        auto.file.outputStream().use { BackupCodec.write(backup(at = 1_793_440_249_218), it) }
        // Android's restore gave the file seconds as milliseconds: January 1970.
        auto.file.setLastModified(1_793_440_249)

        auto.checkOffer()

        assertEquals(1_793_440_249_218, auto.offer.first())
    }

    @Test
    fun aBackupThatCouldNotBeMadeLeavesTheLastOne() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfChanged { backup() })
        clock += 60_000

        assertFalse(auto.saveIfChanged { throw IllegalStateException("reader busy") })
        assertTrue(auto.file.isFile)
    }

    @Test
    fun aBackupThatCouldNotBeWrittenIsSkipped() = runBlocking {
        val blocked = File(dir, "not-a-directory").apply { writeText("") }
        val auto = AutoBackup(blocked, device, now = { clock })
        auto.checkOffer()

        assertFalse(auto.saveIfChanged { backup() })
        assertFalse(File(blocked, "${AutoBackup.FILE_NAME}.part").exists())
    }

    @Test
    fun aFailedWriteKeepsTheLastBackupAndLeavesNoPartFile() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfChanged { backup() })
        val before = auto.file.readBytes()
        val stamp = auto.file.lastModified()

        // The temporary file can't be opened: its name is taken by a directory.
        val partial = File(dir, "${AutoBackup.FILE_NAME}.part").apply { mkdirs() }
        clock += 60_000
        assertFalse(auto.saveIfChanged { changed() })

        assertFalse(partial.exists())
        assertTrue(before.contentEquals(auto.file.readBytes()))
        assertEquals(stamp, auto.file.lastModified())
        assertEquals(1_000_000_000_000L, auto.read().exportedAt)
        assertEquals(listOf(AutoBackup.FILE_NAME), dir.list()?.toList().orEmpty())

        // The next attempt works again.
        assertTrue(auto.saveIfChanged(force = true) { backup() })
        assertEquals(clock, auto.read().exportedAt)
    }

    @Test
    fun aBackupThatCouldNotReplaceTheOldFileLeavesNoPartFile() = runBlocking {
        auto.checkOffer()
        // The rename fails: the backup's name is taken by a directory that isn't empty.
        File(auto.file, "in-the-way").apply { parentFile?.mkdirs() }.writeText("x")

        assertFalse(auto.saveIfChanged { backup() })

        assertFalse(File(dir, "${AutoBackup.FILE_NAME}.part").exists())
        assertEquals(listOf(AutoBackup.FILE_NAME), dir.list()?.toList().orEmpty())
    }

    /** A backup that zip can't shrink: random text, about three quarters of its size after zip. */
    private fun hugeBackup() = BackupFile(
        exportedAt = clock,
        settings = mapOf("noise" to BackupValue(BackupValue.STRING, JsonPrimitive(randomText((AutoBackup.MAX_BYTES * 3 / 2).toInt())))),
    )

    private fun randomText(length: Int): String {
        val random = java.util.Random(1)
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }

    @Test
    fun aBackupOverTheLimitKeepsTheLastOneAndIsKeptApart() = runBlocking {
        auto.checkOffer()
        assertEquals(AutoBackup.Outcome.Saved, auto.save { backup() })
        val before = auto.file.readBytes()
        assertFalse(auto.tooLarge.value)

        val savedAt = clock
        clock += 60_000
        assertEquals(AutoBackup.Outcome.Large, auto.save { hugeBackup() })

        assertTrue(auto.tooLarge.value)
        assertTrue(before.contentEquals(auto.file.readBytes()))
        assertTrue(auto.largeFile.length() > AutoBackup.MAX_BYTES)
        assertEquals(savedAt, auto.read().exportedAt)
        assertEquals(listOf(AutoBackup.FILE_NAME), dir.list()?.toList().orEmpty())
        assertFalse(auto.saveIfChanged { hugeBackup() })

        // The large file counts as the last backup: the same content again isn't rewritten.
        clock += 60_000
        assertEquals(AutoBackup.Outcome.Skipped, auto.save { hugeBackup() })

        // Back to a normal size: the large file goes and the note with it.
        clock += 60_000
        assertEquals(AutoBackup.Outcome.Saved, auto.save { backup() })
        assertFalse(auto.largeFile.exists())
        assertFalse(auto.tooLarge.value)
        assertEquals(clock, auto.read().exportedAt)
    }

    @Test
    fun aLargeBackupFromEarlierIsRememberedAfterARestart() = runBlocking {
        auto.checkOffer()
        assertEquals(AutoBackup.Outcome.Large, auto.save { hugeBackup() })

        assertTrue(AutoBackup(dir, device, now = { clock }).tooLarge.value)
    }

    @Test
    fun theLargeBackupIsNotInTheDirectoryAndroidBacksUp() {
        assertFalse(auto.largeFile.parentFile == dir)
        assertFalse(AutoBackup.LARGE_DIRECTORY.startsWith(AutoBackup.DIRECTORY + "/"))
    }
}
