package app.honmani.reader.ui.settings

import java.io.IOException
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
}
