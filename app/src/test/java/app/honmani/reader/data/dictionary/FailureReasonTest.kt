package app.honmani.reader.data.dictionary

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class FailureReasonTest {
    @Test
    fun aFullDiskIsNoSpace() {
        assertEquals(FailureReason.NO_SPACE, FailureReason.ofStorage(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(FailureReason.NO_SPACE, FailureReason.ofStorage(RuntimeException("x", IOException("No space left on device"))))
        assertEquals(FailureReason.NO_SPACE, FailureReason.ofStorage(IllegalStateException("database or disk is full (code 13)")))
    }

    @Test
    fun otherErrorsKeepTheFallback() {
        assertEquals(FailureReason.OTHER, FailureReason.ofStorage(IOException("boom")))
        assertEquals(FailureReason.OFFLINE, FailureReason.ofStorage(IOException("Connection reset"), FailureReason.OFFLINE))
    }

    @Test
    fun aSavedReasonIsReadBackAndUnknownOnesAreOther() {
        FailureReason.entries.forEach { assertEquals(it, FailureReason.parse(it.name)) }
        assertEquals(FailureReason.OTHER, FailureReason.parse(null))
        assertEquals(FailureReason.OTHER, FailureReason.parse("nonsense"))
    }
}
