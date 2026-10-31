package app.honmani.reader.data.dictionary

import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private lateinit var server: TestHttpServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = TestHttpServer { path, out ->
            when (path.substringBefore('?')) {
                // Like a GitHub "latest release" link: a redirect to the actual file.
                "/releases/latest/download/dict.zip" ->
                    out.head("302 Found", "Location: $base/assets/dict-2026.zip", "Content-Length: 0")
                "/assets/dict-2026.zip" -> {
                    out.head("200 OK", "Content-Length: ${payload.size}")
                    out.write(payload)
                }
                // Announces more than it sends, then closes.
                "/truncated.zip" -> {
                    out.head("200 OK", "Content-Length: ${payload.size * 2}")
                    out.write(payload)
                }
                "/index.json" -> {
                    val body = """{"revision":"2026.10.17"}""".toByteArray()
                    out.head("200 OK", "Content-Length: ${body.size}", "Content-Type: application/json")
                    out.write(body)
                }
                else -> out.head("404 Not Found", "Content-Length: 0")
            }
        }
        base = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun theRequestIsNoted_withItsHostOnly() = runTest {
        NetworkLog.shared.clear()

        DictionaryDownloader().fetchText("$base/index.json?token=secret")

        val entry = NetworkLog.shared.entries().single()
        assertEquals(NetworkPurpose.DICTIONARY, entry.purpose)
        assertEquals("127.0.0.1", entry.host)
        assertEquals("HTTP 200", entry.result)
        assertTrue(!entry.line().contains("secret") && !entry.line().contains("index.json"))
    }

    @Test
    fun followsRedirects_andReportsProgress() = runTest {
        val destination = File(temp.root, "dict.zip")
        val progress = mutableListOf<Pair<Long, Long>>()

        DictionaryDownloader().download("$base/releases/latest/download/dict.zip", destination) { done, total ->
            progress += done to total
        }

        assertArrayEquals(payload, destination.readBytes())
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        // Reported while downloading (per 64 KB read at most), not only at the end.
        assertTrue(progress.size >= 3)
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
    }

    @Test
    fun fetchesSmallTexts_andRefusesLargeOnes() = runTest {
        val downloader = DictionaryDownloader()

        assertEquals("""{"revision":"2026.10.17"}""", downloader.fetchText("$base/index.json"))
        try {
            downloader.fetchText("$base/assets/dict-2026.zip", maxBytes = 1_000)
            fail("expected DownloadException")
        } catch (e: DownloadException) {
            assertTrue(e.message, e.message!!.endsWith("is larger than expected"))
        }
    }

    @Test
    fun httpErrorsAreReported() = runTest {
        assertDownloadFails("$base/missing.zip", "The server answered 404")
    }

    @Test
    fun incompleteDownloadsAreReported() = runTest {
        assertDownloadFails("$base/truncated.zip", "The download was")
    }

    @Test
    fun unreachableHostsAreReported() = runTest {
        // Port 1: nothing listens there, and no test server can take it (below 1024). A port
        // just freed by stopping the server was taken by another test's server once.
        assertDownloadFails("http://127.0.0.1:1/missing.zip", "Could not connect")
    }

    private suspend fun assertDownloadFails(url: String, messageStart: String) {
        try {
            DictionaryDownloader(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
                .download(url, File(temp.root, "out.zip"))
            fail("expected DownloadException")
        } catch (e: DownloadException) {
            assertTrue(e.message, e.message!!.startsWith(messageStart))
        }
    }
}

private fun OutputStream.head(status: String, vararg headers: String) {
    val lines = listOf("HTTP/1.1 $status") + headers + "Connection: close"
    write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray())
}

/**
 * Minimal HTTP/1.1 server on localhost: one request per connection, answered by [respond].
 * (com.sun.net.httpserver is not on the unit-test classpath, which compiles against android.jar.)
 */
private class TestHttpServer(private val respond: (path: String, out: OutputStream) -> Unit) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                handle(client)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket) = client.use {
        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!reader.readLine().isNullOrEmpty()) Unit
        val out = it.getOutputStream().buffered()
        respond(path, out)
        out.flush()
    }

    fun stop() = socket.close()
}
