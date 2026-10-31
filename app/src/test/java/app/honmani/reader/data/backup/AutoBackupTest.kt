package app.honmani.reader.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        assertTrue(auto.saveIfDue { backup() })
        assertEquals(clock, auto.read().exportedAt)
    }

    @Test
    fun aBackupIsWrittenAgainOnlyAfterSomeHours() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfDue { backup() })

        clock += AutoBackup.MIN_INTERVAL_MS / 2
        assertFalse(auto.saveIfDue { backup() })
        assertTrue(auto.saveIfDue(force = true) { backup() })

        clock += AutoBackup.MIN_INTERVAL_MS
        assertTrue(auto.saveIfDue { backup() })
        assertEquals(clock, auto.read().exportedAt)
    }

    @Test
    fun readerSettingsOfTheLastBackupSurviveAnotherWithoutThePage() = runBlocking {
        auto.checkOffer()
        val settings = mapOf("fontSize" to "22", "theme" to null)
        assertTrue(auto.saveIfDue { BackupFile(exportedAt = clock, readerSettings = settings) })

        clock += AutoBackup.MIN_INTERVAL_MS
        assertTrue(auto.saveIfDue { BackupFile(exportedAt = clock, readerSettings = null) })
        assertEquals(clock, auto.read().exportedAt)
        assertEquals(settings, auto.read().readerSettings)

        clock += AutoBackup.MIN_INTERVAL_MS
        val newer = mapOf("fontSize" to "30")
        assertTrue(auto.saveIfDue { BackupFile(exportedAt = clock, readerSettings = newer) })
        assertEquals(newer, auto.read().readerSettings)
    }

    @Test
    fun theOldPhonesBackupIsOfferedAndKeptUntilAnswered() = runBlocking {
        // Android brought back the old phone's file before the first start.
        dir.mkdirs()
        auto.file.outputStream().use { BackupCodec.write(backup(at = 42), it) }

        auto.checkOffer()
        assertTrue(auto.offer.first() != null)
        assertFalse(auto.saveIfDue(force = true) { backup() })
        assertEquals(42L, auto.read().exportedAt)

        auto.answered()
        assertNull(auto.offer.first())
        assertTrue(auto.saveIfDue(force = true) { backup() })
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
        assertTrue(auto.saveIfDue { backup() })
        clock += AutoBackup.MIN_INTERVAL_MS

        assertFalse(auto.saveIfDue { throw IllegalStateException("reader busy") })
        assertTrue(auto.file.isFile)
    }

    @Test
    fun aBackupThatCouldNotBeWrittenIsSkipped() = runBlocking {
        val blocked = File(dir, "not-a-directory").apply { writeText("") }
        val auto = AutoBackup(blocked, device, now = { clock })
        auto.checkOffer()

        assertFalse(auto.saveIfDue { backup() })
        assertFalse(File(blocked, "${AutoBackup.FILE_NAME}.part").exists())
    }

    @Test
    fun aFailedWriteKeepsTheLastBackupAndLeavesNoPartFile() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfDue { backup() })
        val before = auto.file.readBytes()
        val stamp = auto.file.lastModified()

        // The temporary file can't be opened: its name is taken by a directory.
        val partial = File(dir, "${AutoBackup.FILE_NAME}.part").apply { mkdirs() }
        clock += AutoBackup.MIN_INTERVAL_MS
        assertFalse(auto.saveIfDue { backup() })

        assertFalse(partial.exists())
        assertTrue(before.contentEquals(auto.file.readBytes()))
        assertEquals(stamp, auto.file.lastModified())
        assertEquals(1_000_000_000_000L, auto.read().exportedAt)
        assertEquals(listOf(AutoBackup.FILE_NAME), dir.list()?.toList().orEmpty())

        // The next attempt works again.
        assertTrue(auto.saveIfDue(force = true) { backup() })
        assertEquals(clock, auto.read().exportedAt)
    }

    @Test
    fun aBackupThatCouldNotReplaceTheOldFileLeavesNoPartFile() = runBlocking {
        auto.checkOffer()
        // The rename fails: the backup's name is taken by a directory that isn't empty.
        File(auto.file, "in-the-way").apply { parentFile?.mkdirs() }.writeText("x")

        assertFalse(auto.saveIfDue { backup() })

        assertFalse(File(dir, "${AutoBackup.FILE_NAME}.part").exists())
        assertEquals(listOf(AutoBackup.FILE_NAME), dir.list()?.toList().orEmpty())
    }
}
