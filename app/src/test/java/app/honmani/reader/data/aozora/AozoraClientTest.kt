package app.honmani.reader.data.aozora

import app.honmani.reader.data.opds.HeaderServer
import app.honmani.reader.data.opds.respond
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AozoraClientTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val servers = mutableListOf<HeaderServer>()

    @After
    fun tearDown() = servers.forEach { it.stop() }

    private fun server(respond: (path: String, out: OutputStream) -> Unit) = HeaderServer(respond).also { servers += it }

    private fun client(listServer: HeaderServer, booksServer: HeaderServer = listServer, allowHttp: Boolean = true) = AozoraClient(
        listUrl = "http://127.0.0.1:${listServer.port}/index.json",
        booksUrl = "http://127.0.0.1:${booksServer.port}/cards",
        allowHttp = allowHttp,
        connectTimeoutMs = 2_000,
        readTimeoutMs = 2_000,
    )

    private suspend fun assertReason(reason: AozoraException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: AozoraException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun downloadsTheListWithItsEtag() = runTest {
        val s = server { _, out -> out.respond("200 OK", LIST, "ETag: \"abc\"") }
        val target = folder.newFile("list.json")
        val fetched = client(s).list(target, null)
        assertEquals(ListFetch.Downloaded("\"abc\""), fetched)
        assertEquals(LIST, target.readText())
        assertFalse(s.requests.single().headers.containsKey("if-none-match"))
    }

    @Test
    fun aKeptEtagIsSentAndA304MeansNotModified() = runTest {
        val s = server { _, out -> out.respond("304 Not Modified", "") }
        val target = folder.newFile("list.json")
        assertEquals(ListFetch.NotModified, client(s).list(target, "\"abc\""))
        assertEquals("\"abc\"", s.requests.single().headers["if-none-match"])
        assertEquals(0L, target.length())
    }

    @Test
    fun aListLargerThanTheCapIsRefused_byItsLengthAndByWhatArrives() = runTest {
        val declared = server { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${AozoraClient.MAX_LIST_BYTES + 1}\r\nConnection: close\r\n\r\n".toByteArray())
        }
        assertReason(AozoraException.Reason.TOO_LARGE) { client(declared).list(folder.newFile("a.json"), null) }

        // No Content-Length (the connection just ends): the cap still applies while reading.
        val chunk = ByteArray(1024 * 1024) { 'x'.code.toByte() }
        val streamed = server { _, out ->
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
            try {
                repeat(22) { out.write(chunk) }
            } catch (_: java.io.IOException) {
                // the client hung up
            }
        }
        assertReason(AozoraException.Reason.TOO_LARGE) { client(streamed).list(folder.newFile("b.json"), null) }
    }

    @Test
    fun aRedirectToAnotherHostIsRefused() = runTest {
        val other = server { _, out -> out.respond("200 OK", LIST) }
        val s = server { _, out -> out.respond("302 Found", "", "Location: http://localhost:${other.port}/index.json") }
        assertReason(AozoraException.Reason.REFUSED) { client(s).list(folder.newFile("a.json"), null) }
        assertTrue(other.requests.isEmpty())
        // The same for a book.
        assertReason(AozoraException.Reason.REFUSED) { client(s).book("000148/x.zip") }
        assertTrue(other.requests.isEmpty())
    }

    @Test
    fun aRedirectWithinTheHostIsFollowed() = runTest {
        val s = server { path, out ->
            if (path == "/index.json") out.respond("301 Moved", "", "Location: /moved.json") else out.respond("200 OK", LIST)
        }
        val target = folder.newFile("a.json")
        client(s).list(target, null)
        assertEquals(listOf("/index.json", "/moved.json"), s.requests.map { it.path })
        assertEquals(LIST, target.readText())
    }

    @Test
    fun aRedirectLoopEnds() = runTest {
        val s = server { _, out -> out.respond("302 Found", "", "Location: /again") }
        assertReason(AozoraException.Reason.SERVER) { client(s).list(folder.newFile("a.json"), null) }
    }

    @Test
    fun plainHttpIsRefusedUnlessAllowed() = runTest {
        val s = server { _, out -> out.respond("200 OK", LIST) }
        assertReason(AozoraException.Reason.REFUSED) { client(s, allowHttp = false).list(folder.newFile("a.json"), null) }
        assertReason(AozoraException.Reason.REFUSED) { client(s, allowHttp = false).book("000148/x.zip") }
        assertTrue(s.requests.isEmpty())
    }

    @Test
    fun theDefaultAddressesAreHttps() {
        assertTrue(AozoraClient.LIST_URL.startsWith("https://cnfsd-labs.github.io/"))
        assertTrue(AozoraClient.BOOKS_URL.startsWith("https://www.aozora.gr.jp/"))
    }

    @Test
    fun aServerErrorIsAServerReason() = runTest {
        val s = server { _, out -> out.respond("404 Not Found", "") }
        assertReason(AozoraException.Reason.SERVER) { client(s).list(folder.newFile("a.json"), null) }
        assertReason(AozoraException.Reason.SERVER) { client(s).book("000148/x.zip") }
    }

    @Test
    fun noServerIsANetworkReason() = runTest {
        // A port nobody listens on: bind one, note it, close it.
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val c = AozoraClient("http://127.0.0.1:$closed/index.json", "http://127.0.0.1:$closed/cards", allowHttp = true, connectTimeoutMs = 2_000)
        assertReason(AozoraException.Reason.NETWORK) { c.list(folder.newFile("a.json"), null) }
    }

    @Test
    fun aBookIsFetchedFromTheCardsFilesFolder_unpackedAndReportsProgress() = runTest {
        val text = "吾輩は猫である".toByteArray()
        val archive = zip("w.txt" to text)
        val s = server { _, out ->
            val head = "HTTP/1.1 200 OK\r\nContent-Length: ${archive.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(archive)
        }
        val progress = mutableListOf<Pair<Long, Long>>()
        val bytes = client(s).book("000148/789_ruby.zip") { done, total -> progress += done to total }
        assertArrayEquals(text, bytes)
        assertEquals("/cards/000148/files/789_ruby.zip", s.requests.single().path)
        assertEquals(archive.size.toLong(), progress.last().first)
        assertEquals(archive.size.toLong(), progress.last().second)
    }

    @Test
    fun aBookLargerThanTheCapIsRefused() = runTest {
        val s = server { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${AozoraClient.MAX_BOOK_BYTES + 1}\r\nConnection: close\r\n\r\n".toByteArray())
        }
        assertReason(AozoraException.Reason.TOO_LARGE) { client(s).book("000148/x.zip") }
    }

    @Test
    fun aTruncatedBookIsANetworkFailure() = runTest {
        val s = server { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\nabc".toByteArray())
        }
        assertReason(AozoraException.Reason.NETWORK) { client(s).book("000148/x.zip") }
    }

    @Test
    fun badFileNamesNeverReachTheNetwork() = runTest {
        val s = server { _, out -> out.respond("200 OK", "x") }
        val c = client(s)
        for (file in listOf("", "000148", "000148/", "/x.zip", "000148/a/b.zip", "../x.zip", "000148/..x")) {
            assertReason(AozoraException.Reason.BAD_DATA) { c.book(file) }
        }
        assertTrue(s.requests.isEmpty())
    }

    @Test
    fun repositoryStyleUseWithATempFileLeavesNothingBehindOnFailure() = runTest {
        val s = server { _, out -> out.respond("500 Error", "") }
        val target = File(folder.root, "t.json")
        assertReason(AozoraException.Reason.SERVER) { client(s).list(target, null) }
        assertFalse(target.exists())
    }

    private companion object {
        const val LIST = """{"version":1,"generated":"2026-11-07","authors":[],"works":[]}"""
    }
}
