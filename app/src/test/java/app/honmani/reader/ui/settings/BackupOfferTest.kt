package app.honmani.reader.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.backup.AutoBackup
import app.honmani.reader.data.backup.BackupCodec
import app.honmani.reader.data.backup.BackupFile
import app.honmani.reader.data.backup.RestoreSummary
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupOfferTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val device = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("backup-offer-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private fun autoBackupWithOffer(): AutoBackup {
        val dir = File(folder.root, "auto-backup").apply { mkdirs() }
        File(dir, AutoBackup.FILE_NAME).outputStream().use { BackupCodec.write(BackupFile(exportedAt = 1_700_000_000_000L), it) }
        return AutoBackup(dir, device).also { runBlocking { it.checkOffer() } }
    }

    private val restored = BackupState.Restored(RestoreSummary(0, 0, 0, 0))

    @Test
    fun aRestoreThatIsCancelledKeepsTheOffer() = runBlocking {
        val auto = autoBackupWithOffer()
        assertNotNull(auto.offer.first())

        assertThrows(CancellationException::class.java) {
            runBlocking { BackupViewModel.restoreOffered(auto) { throw CancellationException("left the screen") } }
        }

        assertNotNull(auto.offer.first())
        assertEquals(false, device.getBoolean("auto_backup_offer_answered", false))
    }

    @Test
    fun aRestoreThatFailsKeepsTheOffer() = runBlocking {
        val auto = autoBackupWithOffer()

        assertThrows(IOException::class.java) {
            runBlocking { BackupViewModel.restoreOffered(auto) { throw IOException("No space left on device") } }
        }

        assertNotNull(auto.offer.first())
    }

    @Test
    fun aRestoredBackupAnswersTheOffer() = runBlocking {
        val auto = autoBackupWithOffer()

        assertEquals(restored, BackupViewModel.restoreOffered(auto) { restored })

        assertNull(auto.offer.first())
        assertEquals(true, device.getBoolean("auto_backup_offer_answered", false))
    }
}
