package app.honmani.reader.data.dictionary

import app.honmani.reader.data.opds.HeaderServer
import app.honmani.reader.data.opds.head
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryDownloadResumeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it * 7 % 251).toByte() }
    private lateinit var server: HeaderServer
    private lateinit var url: String

    /** Bytes sent before the connection drops on the next full answer; null sends everything. */
    private var dropAfter: Int? = null

    /** Whether the server honours ranges; when not, it always answers 200 with the whole file. */
    private var rangesSupported = true

    @Before
    fun setUp() {
        server = HeaderServer { _, out ->
            val headers = server.requests.last().headers
            val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            if (rangesSupported && range != null && range >= payload.size && headers["if-range"] == ETAG) {
                out.head("416 Range Not Satisfiable", "ETag: $ETAG", "Content-Range: bytes */${payload.size}", "Content-Length: 0")
            } else if (rangesSupported && range != null && headers["if-range"] == ETAG) {
                out.head(
                    "206 Partial Content",
                    "ETag: $ETAG",
                    "Content-Range: bytes $range-${payload.size - 1}/${payload.size}",
                    "Content-Length: ${payload.size - range}",
                )
                out.write(payload, range, payload.size - range)
            } else {
                out.head("200 OK", "ETag: $ETAG", "Content-Length: ${payload.size}")
                val cut = dropAfter
                dropAfter = null
                out.write(payload, 0, cut ?: payload.size)
            }
        }
        url = "http://127.0.0.1:${server.port}/dict.zip"
    }

    @After
    fun tearDown() = server.stop()

    private val downloader = DictionaryDownloader(allowHttp = true)
    private val partial get() = DictionaryDownloader.partialFile(temp.root, url)

    private suspend fun interruptedDownload(destination: File) {
        dropAfter = 120_000
        try {
            downloader.download(url, destination, partial = partial)
            fail("expected DownloadException")
        } catch (_: DownloadException) {
        }
        assertEquals(120_000L, partial.length())
    }

    @Test
    fun aDroppedDownloadResumesWhereItStopped() = runTest {
        val destination = File(temp.root, "dict.zip")
        interruptedDownload(destination)

        val progress = mutableListOf<Pair<Long, Long>>()
        downloader.download(url, destination, partial = partial) { done, total -> progress += done to total }

        val resume = server.requests.last().headers
        assertEquals("bytes=120000-", resume["range"])
        assertEquals(ETAG, resume["if-range"])
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        assertTrue(progress.first().first > 120_000)
        assertFalse(partial.exists())
    }

    @Test
    fun aDownloadKilledBeforeItsFileWasMovedFinishesWithoutDownloadingAgain() = runTest {
        val destination = File(temp.root, "dict.zip")
        partial.writeBytes(payload)
        File(partial.path + ".validator").writeText(ETAG)
        val before = server.requests.size

        downloader.download(url, destination, partial = partial)

        assertEquals(before + 1, server.requests.size)
        assertEquals("bytes=${payload.size}-", server.requests.last().headers["range"])
        assertArrayEquals(payload, destination.readBytes())
        assertFalse(partial.exists())
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("dictionary-download-") })
    }

    @Test
    fun aDownloadGivenUpOnIsSweptAfterAWeek_aRecentOneStaysForTheRetry() = runTest {
        interruptedDownload(File(temp.root, "dict.zip"))
        val validator = File(partial.path + ".validator")
        assertTrue(validator.exists())
        val other = File(temp.root, "keep.zip").apply { writeText("x") }
        val now = System.currentTimeMillis()

        DictionaryDownloader.sweepStalePartials(temp.root, now)
        assertTrue(partial.exists())

        val old = now - DictionaryDownloader.STALE_PARTIAL_MS - 1_000
        partial.setLastModified(old)
        validator.setLastModified(old)
        other.setLastModified(old)
        DictionaryDownloader.sweepStalePartials(temp.root, now)
        assertFalse(partial.exists())
        assertFalse(validator.exists())
        assertTrue(other.exists())
    }

    @Test
    fun aServerAnsweringWholeStartsOver() = runTest {
        val destination = File(temp.root, "dict.zip")
        interruptedDownload(destination)
        rangesSupported = false

        downloader.download(url, destination, partial = partial)

        assertEquals("bytes=120000-", server.requests.last().headers["range"])
        assertArrayEquals(payload, destination.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun withoutAPartialNothingIsKeptOrResumed() = runTest {
        val destination = File(temp.root, "dict.zip")
        downloader.download(url, destination)

        assertNull(server.requests.last().headers["range"])
        assertArrayEquals(payload, destination.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun aCancelledImportDropsItsPartialButAStoppedOneKeepsIt() = runTest {
        interruptedDownload(File(temp.root, "dict.zip"))
        val copy = File(temp.root, "copy.zip").apply { writeText("x") }
        var released = 0

        DictionaryImportWorker.cleanUp(DictionaryImportWorker.Ending.STOPPED, copy, listOf(partial)) { released++ }
        assertTrue(partial.exists())
        assertFalse(copy.exists())
        assertEquals(0, released)

        DictionaryImportWorker.cleanUp(DictionaryImportWorker.Ending.CANCELLED, copy, listOf(partial)) { released++ }
        assertFalse(partial.exists())
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("dictionary-download-") })
        assertEquals(1, released)
    }

    @Test
    fun aFinishedImportReleasesItsDocument() {
        var released = 0
        DictionaryImportWorker.cleanUp(DictionaryImportWorker.Ending.FINISHED, File(temp.root, "c.zip"), emptyList()) { released++ }
        assertEquals(1, released)
    }

    private companion object {
        const val ETAG = "\"v1\""
    }
}
