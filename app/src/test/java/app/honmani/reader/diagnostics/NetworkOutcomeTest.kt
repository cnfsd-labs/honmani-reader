package app.honmani.reader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkOutcomeTest {
    @Test
    fun httpStatusesBecomeReadableOutcomes() {
        assertEquals(NetworkOutcome.ANSWERED, NetworkOutcome.of("HTTP 200"))
        assertEquals(NetworkOutcome.ANSWERED, NetworkOutcome.of("HTTP 304"))
        assertEquals(NetworkOutcome.SIGN_IN_REFUSED, NetworkOutcome.of("HTTP 401"))
        assertEquals(NetworkOutcome.SIGN_IN_REFUSED, NetworkOutcome.of("HTTP 403"))
        assertEquals(NetworkOutcome.NOT_FOUND, NetworkOutcome.of("HTTP 404"))
        assertEquals(NetworkOutcome.REFUSED, NetworkOutcome.of("HTTP 429"))
        assertEquals(NetworkOutcome.SERVER_ERROR, NetworkOutcome.of("HTTP 503"))
        assertEquals(NetworkOutcome.OTHER, NetworkOutcome.of("HTTP 100"))
    }

    @Test
    fun errorClassesBecomeReadableOutcomes() {
        assertEquals(NetworkOutcome.SERVER_NOT_FOUND, NetworkOutcome.of("UnknownHostException"))
        assertEquals(NetworkOutcome.TIMED_OUT, NetworkOutcome.of("SocketTimeoutException"))
        assertEquals(NetworkOutcome.NO_CONNECTION, NetworkOutcome.of("ConnectException"))
        assertEquals(NetworkOutcome.SECURE_CONNECTION_FAILED, NetworkOutcome.of("SSLHandshakeException"))
        assertEquals(NetworkOutcome.SECURE_CONNECTION_FAILED, NetworkOutcome.of("CertPathValidatorException"))
        assertEquals(NetworkOutcome.OTHER, NetworkOutcome.of("IOException"))
        assertEquals(NetworkOutcome.OTHER, NetworkOutcome.of("Error"))
    }

    @Test
    fun onlyAnsweredEntriesHaveAStatus() {
        assertEquals(401, NetworkEntry(0, NetworkPurpose.BOOK_SERVER, "h", "HTTP 401").status)
        assertNull(NetworkEntry(0, NetworkPurpose.BOOK_SERVER, "h", "UnknownHostException").status)
    }
}
