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
    fun aBackupThatCouldNotBeMadeLeavesTheLastOne() = runBlocking {
        auto.checkOffer()
        assertTrue(auto.saveIfDue { backup() })
        clock += AutoBackup.MIN_INTERVAL_MS

        assertFalse(auto.saveIfDue { throw IllegalStateException("reader busy") })
        assertTrue(auto.file.isFile)
    }
}
