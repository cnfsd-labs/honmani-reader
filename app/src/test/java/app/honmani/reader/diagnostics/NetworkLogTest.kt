package app.honmani.reader.diagnostics

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLogTest {
    private var time = 1_000L
    private val log = NetworkLog(limit = 3, now = { time++ })

    @Test
    fun hostsHaveNoSchemePortPathQueryOrLogin() {
        assertEquals("example.com", NetworkLog.hostOf("https://example.com/a/b.zip?key=secret#frag"))
        assertEquals("example.com", NetworkLog.hostOf("https://user:p%40ss@Example.COM:8443/opds?x=1"))
        assertEquals("192.168.1.5", NetworkLog.hostOf("http://192.168.1.5:8083/kosync/syncs/progress"))
        assertEquals("[::1]", NetworkLog.hostOf("http://[::1]:80/x"))
        assertEquals("example.com", NetworkLog.hostOf("https://example.com?token=a@b"))
        assertEquals("example.com", NetworkLog.hostOf("https://example.com#a@b"))
    }

    @Test
    fun addressesWithoutAHostAreUnknown() {
        assertEquals("unknown", NetworkLog.hostOf("not a url"))
        assertEquals("unknown", NetworkLog.hostOf("https:///path"))
        assertEquals("unknown", NetworkLog.hostOf(""))
    }

    @Test
    fun anAnsweredRequestKeepsStatusAndSize() {
        log.answered(NetworkPurpose.DICTIONARY, "https://huggingface.co/x/dict.zip?download=1", 200, 1234)

        val entry = log.entries().single()
        assertEquals(NetworkEntry(1_000L, NetworkPurpose.DICTIONARY, "huggingface.co", "HTTP 200", 1234), entry)
    }

    @Test
    fun anUnknownSizeIsNotKept() {
        log.answered(NetworkPurpose.BOOK_SERVER, "https://books.example/opds", 401, -1)

        assertNull(log.entries().single().bytes)
    }

    @Test
    fun aFailureKeepsOnlyTheErrorClass_notItsMessage() {
        log.failed(
            NetworkPurpose.PROGRESS_SYNC,
            "https://user:pw@sync.example/kosync",
            SocketTimeoutException("timeout talking to https://sync.example/kosync?key=secret"),
        )

        val entry = log.entries().single()
        assertEquals("SocketTimeoutException", entry.result)
        assertEquals("sync.example", entry.host)
        val line = entry.line()
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("pw"))
        assertFalse(line.contains("kosync"))
        assertTrue(line.contains("progress_sync"))
    }

    @Test
    fun onlyTheLatestEntriesAreKept_newestFirst() {
        for (i in 1..5) log.answered(NetworkPurpose.GOOGLE_VOICE, "https://h$i.example/", 200)

        assertEquals(listOf("h5.example", "h4.example", "h3.example"), log.entries().map { it.host })
    }

    @Test
    fun theLimitIsTwoHundredByDefault() {
        val big = NetworkLog()
        repeat(250) { big.answered(NetworkPurpose.DICTIONARY, "https://a.example/", 200) }

        assertEquals(200, big.entries().size)
    }

    @Test
    fun clearEmptiesTheList() {
        log.answered(NetworkPurpose.DICTIONARY, "https://a.example/", 200)
        log.clear()

        assertTrue(log.entries().isEmpty())
    }
}
