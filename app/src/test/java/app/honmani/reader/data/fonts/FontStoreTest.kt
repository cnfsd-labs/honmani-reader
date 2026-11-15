package app.honmani.reader.data.fonts

import app.honmani.reader.data.dictionary.FailureReason
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FontStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = "font bytes".toByteArray()
    private val font = CatalogFont(
        id = "test",
        name = "Test Mincho",
        fileName = "TestMincho-Regular.ttf",
        url = "https://example.org/TestMincho-Regular.ttf",
        sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
        sizeBytes = content.size.toLong(),
        style = FontStyle.MINCHO,
    )

    private var served = content
    private var failure: Exception? = null
    private var gate: CompletableDeferred<Unit>? = null
    /** Like a blocking read, the download does not notice it was cancelled while it waits at [gate]. */
    private var stubborn = false
    private var reportedTotal: Long? = null
    private val progress = mutableListOf<Float?>()

    private fun TestScope.store() = FontStore(
        dir = File(folder.root, "fonts"),
        scope = this,
        download = { url, destination, onProgress ->
            assertEquals(font.url, url)
            if (stubborn) withContext(NonCancellable) { gate?.await() } else gate?.await()
            failure?.let { throw it }
            val total = reportedTotal ?: served.size.toLong()
            onProgress(served.size / 2L, total)
            destination.writeBytes(served)
            onProgress(served.size.toLong(), total)
        },
        catalog = listOf(font),
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun aDownloadedFontIsCheckedAndInstalled() = runTest(StandardTestDispatcher()) {
        val store = store()
        assertEquals(FontState.Available, store.states.value[font.id])

        store.download(font)
        assertTrue(store.states.value[font.id] is FontState.Downloading)
        advanceUntilIdle()

        assertEquals(FontState.Installed, store.states.value[font.id])
        assertEquals(listOf(font), store.installed())
        assertTrue(store.file(font).readBytes().contentEquals(content))
        // Installed fonts are found again after a restart.
        assertEquals(FontState.Installed, store().states.value[font.id])
    }

    @Test
    fun aDamagedDownloadIsNotInstalled() = runTest(StandardTestDispatcher()) {
        served = "something else".toByteArray()
        val store = store()

        store.download(font)
        advanceUntilIdle()

        assertTrue(store.states.value[font.id] is FontState.Failed)
        assertFalse(store.file(font).exists())
        assertTrue(File(folder.root, "fonts").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFailedDownloadCanBeTriedAgain() = runTest(StandardTestDispatcher()) {
        failure = IOException("offline")
        val store = store()
        store.download(font)
        advanceUntilIdle()
        assertEquals(FontState.Failed(FailureReason.OTHER), store.states.value[font.id])

        failure = null
        store.download(font)
        advanceUntilIdle()
        assertEquals(FontState.Installed, store.states.value[font.id])
    }

    @Test
    fun deletingRemovesTheFont_orStopsItsDownload() = runTest(StandardTestDispatcher()) {
        val store = store()
        store.download(font)
        advanceUntilIdle()

        store.delete(font)
        advanceUntilIdle()
        assertEquals(FontState.Available, store.states.value[font.id])
        assertFalse(store.file(font).exists())

        gate = CompletableDeferred()
        store.download(font)
        advanceUntilIdle()
        store.delete(font)
        advanceUntilIdle()
        assertEquals(FontState.Available, store.states.value[font.id])
        assertFalse(store.file(font).exists())
    }

    @Test
    fun anUnexpectedErrorFailsTheDownload() = runTest(StandardTestDispatcher()) {
        failure = IllegalStateException("bad response")
        val store = store()
        store.download(font)
        advanceUntilIdle()
        assertEquals(FontState.Failed(FailureReason.OTHER), store.states.value[font.id])
        assertTrue(File(folder.root, "fonts").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aDownloadFarLargerThanTheFontIsStopped() = runTest(StandardTestDispatcher()) {
        // The server gives no size, and the file grows well past the catalog's.
        served = ByteArray(3 shl 20)
        reportedTotal = -1
        val store = store()
        store.download(font)
        advanceUntilIdle()
        assertTrue(store.states.value[font.id] is FontState.Failed)
        assertTrue(File(folder.root, "fonts").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deletingWaitsForADownloadThatIsSlowToStop() = runTest(StandardTestDispatcher()) {
        stubborn = true
        gate = CompletableDeferred()
        val store = store()
        store.download(font)
        advanceUntilIdle()

        store.delete(font)
        advanceUntilIdle()
        // Still ending: no new download can start over it.
        assertTrue(store.states.value[font.id] is FontState.Downloading)
        store.download(font)

        gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(FontState.Available, store.states.value[font.id])
        assertFalse(store.file(font).exists())
        assertTrue(File(folder.root, "fonts").listFiles().orEmpty().isEmpty())

        // A later download is tracked again, so deleting stops it.
        stubborn = false
        gate = CompletableDeferred()
        store.download(font)
        advanceUntilIdle()
        store.delete(font)
        advanceUntilIdle()
        gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(FontState.Available, store.states.value[font.id])
        assertFalse(store.file(font).exists())
    }
}
