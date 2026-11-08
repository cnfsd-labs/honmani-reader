package app.honmani.reader.data.dictionary

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
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

    @Test
    fun anyFailureIsMappedToAPlainKind() {
        assertEquals(FailureReason.NO_SPACE, FailureReason.of(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(FailureReason.OFFLINE, FailureReason.of(UnknownHostException("example.org")))
        assertEquals(FailureReason.OFFLINE, FailureReason.of(SocketTimeoutException("timeout")))
        assertEquals(FailureReason.OFFLINE, FailureReason.of(IOException("wrapped", SSLHandshakeException("handshake"))))
        assertEquals(FailureReason.SERVER, FailureReason.of(DownloadException("The server answered 500", reason = FailureReason.SERVER)))
        assertEquals(FailureReason.OTHER, FailureReason.of(IOException("The downloaded file is damaged")))
        assertEquals(FailureReason.OTHER, FailureReason.of(IllegalStateException("collection closed")))
    }
}
