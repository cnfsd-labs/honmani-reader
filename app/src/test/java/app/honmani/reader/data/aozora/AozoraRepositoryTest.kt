package app.honmani.reader.data.aozora

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stands in for the network: serves [json] (or fails), remembers the ETag it was asked with. */
internal class FakeAozoraSource(
    var json: String = indexJson(works = work("1", "題", "だい", 0, "nn")),
    var etag: String? = "\"v1\"",
) : AozoraSource {
    var failure: IOException? = null
    val asked = mutableListOf<String?>()
    val books = mutableMapOf<String, ByteArray>()
    var bookFailure: IOException? = null
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val requestedBooks = mutableListOf<String>()

    override suspend fun list(target: File, etag: String?): ListFetch {
        asked += etag
        failure?.let { throw it }
        if (etag != null && etag == this.etag) return ListFetch.NotModified
        target.writeText(json)
        return ListFetch.Downloaded(this.etag)
    }

    override suspend fun book(file: String, onProgress: (done: Long, total: Long) -> Unit): ByteArray {
        requestedBooks += file
        onProgress(5, 10)
        gate?.await()
        bookFailure?.let { throw it }
        return books[file] ?: throw AozoraException(AozoraException.Reason.SERVER, "missing")
    }
}

class AozoraRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "aozora")
    private val source = FakeAozoraSource()
    private val repository get() = AozoraRepository(dir, source)

    @Test
    fun nothingIsKeptAtFirst() = runTest {
        assertNull(repository.cached())
    }

    @Test
    fun refreshKeepsTheListAndItsEtag() = runTest {
        val index = repository.refresh()
        assertEquals(1, index.works.size)
        assertEquals(listOf<String?>(null), source.asked)
        assertEquals("\"v1\"", File(dir, "index.etag").readText())
        assertEquals(1, repository.cached()!!.works.size)
        assertFalse(File(dir, "index.json.tmp").exists())
    }

    @Test
    fun aSecondRefreshAsksConditionallyAndKeepsTheList() = runTest {
        repository.refresh()
        val again = repository.refresh()
        assertEquals(listOf<String?>(null, "\"v1\""), source.asked)
        assertEquals(1, again.works.size)
    }

    @Test
    fun aChangedListReplacesTheKeptOne() = runTest {
        repository.refresh()
        source.json = indexJson(works = work("1", "題", "だい", 0, "nn") + "," + work("2", "二", "に", 0, "nn"))
        source.etag = "\"v2\""
        assertEquals(2, repository.refresh().works.size)
        assertEquals(2, repository.cached()!!.works.size)
        assertEquals("\"v2\"", File(dir, "index.etag").readText())
    }

    @Test
    fun aListWithoutAnEtagDropsTheOldOne() = runTest {
        repository.refresh()
        source.etag = null
        source.json = indexJson(works = "")
        repository.refresh()
        assertFalse(File(dir, "index.etag").exists())
    }

    @Test
    fun aFailedRefreshKeepsTheOldList() = runTest {
        repository.refresh()
        source.failure = IOException("offline")
        try {
            repository.refresh()
            fail()
        } catch (_: IOException) {
        }
        assertEquals(1, repository.cached()!!.works.size)
        assertFalse(File(dir, "index.json.tmp").exists())
    }

    @Test
    fun anUnreadableDownloadKeepsTheOldList() = runTest {
        repository.refresh()
        source.json = "<html>captive portal</html>"
        source.etag = "\"v2\""
        try {
            repository.refresh()
            fail()
        } catch (e: AozoraException) {
            assertEquals(AozoraException.Reason.BAD_DATA, e.reason)
        }
        assertEquals(1, repository.cached()!!.works.size)
        assertEquals("\"v1\"", File(dir, "index.etag").readText())
    }

    @Test
    fun aDamagedKeptListIsDeletedAndNotModifiedIsNotTrusted() = runTest {
        dir.mkdirs()
        File(dir, "index.json").writeText("broken")
        File(dir, "index.etag").writeText("\"v1\"")
        assertNull(repository.cached())
        assertFalse(File(dir, "index.json").exists())
        assertFalse(File(dir, "index.etag").exists())
        // The next refresh asks for the whole list.
        repository.refresh()
        assertEquals(listOf<String?>(null), source.asked)
    }

    @Test
    fun anEtagWithoutAKeptListIsNotSent() = runTest {
        dir.mkdirs()
        File(dir, "index.etag").writeText("\"v1\"")
        repository.refresh()
        assertEquals(listOf<String?>(null), source.asked)
        assertTrue(File(dir, "index.json").isFile)
    }
}
