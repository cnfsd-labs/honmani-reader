package app.honmani.reader.ui.settings

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFailureTest {
    @Test
    fun aFullPhoneGetsItsOwnFailure() {
        assertEquals(
            BackupState.Failed(noSpace = true),
            BackupViewModel.failedState(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(
            BackupState.Failed(noSpace = true),
            BackupViewModel.failedState(RuntimeException("backup", IOException("No space left on device"))),
        )
    }

    @Test
    fun anyOtherFailureIsTheGeneralOne() {
        assertEquals(BackupState.Failed(noSpace = false), BackupViewModel.failedState(IOException("Cannot write")))
        assertEquals(BackupState.Failed(noSpace = false), BackupViewModel.failedState(SecurityException("denied")))
    }

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aWriteThatFailsHalfWayLeavesTheChosenFileUntouched() {
        val target = ByteArrayOutputStream()
        var opened = false
        val temporary = File(folder.root, "backup-export.zip")

        assertThrows(IOException::class.java) {
            BackupViewModel.writeViaTemporaryFile(
                temporary,
                write = { it.write(1); throw IOException("No space left on device") },
                open = { opened = true; target },
            )
        }

        assertFalse(opened)
        assertEquals(0, target.size())
        assertFalse(temporary.exists())
    }

    @Test
    fun aCompleteBackupIsCopiedIntoTheChosenFile() {
        val target = ByteArrayOutputStream()
        val temporary = File(folder.root, "backup-export.zip")

        BackupViewModel.writeViaTemporaryFile(temporary, write = { it.write(byteArrayOf(1, 2, 3)) }, open = { target })

        assertArrayEquals(byteArrayOf(1, 2, 3), target.toByteArray())
        assertFalse(temporary.exists())
        assertTrue(folder.root.listFiles().isNullOrEmpty())
    }
}
