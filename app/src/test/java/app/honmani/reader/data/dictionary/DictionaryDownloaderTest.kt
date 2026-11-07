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
                // Redirects to a different host name for the same local server.
                "/elsewhere.zip" ->
                    out.head("302 Found", "Location: http://localhost:${server.port}/assets/dict-2026.zip", "Content-Length: 0")
                "/loop.zip" -> out.head("302 Found", "Location: $base/loop.zip", "Content-Length: 0")
                "/assets/dict-2026.zip" -> {
                    out.head("200 OK", "Content-Length: ${payload.size}")
                    out.write(payload)
                }
                // Announces more than it sends, then closes.
                "/truncated.zip" -> {
                    out.head("200 OK", "Content-Length: ${payload.size * 2}")
                    out.write(payload)
                }
                // Does not say how long it is, and just goes on until the connection closes.
                "/unknown-length.zip" -> {
                    out.head("200 OK")
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

        DictionaryDownloader(allowHttp = true).fetchText("$base/index.json?token=secret")

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

        DictionaryDownloader(allowHttp = true).download("$base/releases/latest/download/dict.zip", destination) { done, total ->
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
        val downloader = DictionaryDownloader(allowHttp = true)

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
    fun aFileAnnouncedAsTooLargeIsRefusedBeforeItIsRead() = runTest {
        val destination = File(temp.root, "big.zip")

        assertDownloadFails("$base/assets/dict-2026.zip", "The file is larger than", maxBytes = payload.size - 1L)
        assertTrue(!destination.exists() || destination.length() == 0L)
    }

    @Test
    fun aFileOfUnknownLengthIsStoppedAtTheLimit() = runTest {
        val destination = File(temp.root, "out.zip")

        assertDownloadFails("$base/unknown-length.zip", "The file is larger than", maxBytes = 100_000)
        assertTrue(destination.length() <= 100_000)
    }

    @Test
    fun aFileWithinTheLimitIsDownloaded() = runTest {
        val destination = File(temp.root, "ok.zip")

        DictionaryDownloader(allowHttp = true, maxDownloadBytes = payload.size.toLong()).download("$base/assets/dict-2026.zip", destination)

        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun aRedirectToAnotherHostIsRefused_unlessItsOnTheList() = runTest {
        assertDownloadFails("$base/elsewhere.zip", "The server sent the download to another site (localhost)")

        val destination = File(temp.root, "listed.zip")
        DictionaryDownloader(allowHttp = true, redirectHosts = setOf("localhost"))
            .download("$base/elsewhere.zip", destination)
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun redirectsThatNeverEndAreStopped() = runTest {
        assertDownloadFails("$base/loop.zip", "The server redirected too many times")
    }

    @Test
    fun plainHttpIsRefusedUnlessAllowed() = runTest {
        try {
            DictionaryDownloader().download("$base/assets/dict-2026.zip", File(temp.root, "x.zip"))
            fail("expected DownloadException")
        } catch (e: DownloadException) {
            assertTrue(e.message, e.message!!.startsWith("Only secure (https) addresses"))
        }
    }

    @Test
    fun theBuiltInSourcesRedirectHostsAreListed() {
        val hosts = DictionaryDownloader.REDIRECT_HOSTS
        // GitHub release assets, Hugging Face's CDN.
        assertTrue(DictionaryDownloader.isListed("release-assets.githubusercontent.com", hosts))
        assertTrue(DictionaryDownloader.isListed("objects.githubusercontent.com", hosts))
        assertTrue(DictionaryDownloader.isListed("us.aws.cdn.hf.co", hosts))
        assertTrue(DictionaryDownloader.isListed("huggingface.co", hosts))
        // Look-alikes.
        assertTrue(!DictionaryDownloader.isListed("evilgithub.com", hosts))
        assertTrue(!DictionaryDownloader.isListed("github.com.evil.example", hosts))
        assertTrue(!DictionaryDownloader.isListed("hf.co.example", hosts))
    }

    @Test
    fun unreachableHostsAreReported() = runTest {
        // Port 1: nothing listens there, and no test server can take it (below 1024). A port
        // just freed by stopping the server was taken by another test's server once.
        assertDownloadFails("http://127.0.0.1:1/missing.zip", "Could not connect")
    }

    private suspend fun assertDownloadFails(
        url: String,
        messageStart: String,
        maxBytes: Long = DictionaryDownloader.MAX_DOWNLOAD_BYTES,
        redirectHosts: Set<String> = emptySet(),
    ) {
        try {
            DictionaryDownloader(
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
                maxDownloadBytes = maxBytes,
                allowHttp = true,
                redirectHosts = redirectHosts,
            )
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
