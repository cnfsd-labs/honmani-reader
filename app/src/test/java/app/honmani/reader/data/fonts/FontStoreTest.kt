package app.honmani.reader.data.fonts

import app.honmani.reader.data.dictionary.FailureReason
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private var failure: IOException? = null
    private var gate: CompletableDeferred<Unit>? = null
    private val progress = mutableListOf<Float?>()

    private fun TestScope.store() = FontStore(
        dir = File(folder.root, "fonts"),
        scope = this,
        download = { url, destination, onProgress ->
            assertEquals(font.url, url)
            gate?.await()
            failure?.let { throw it }
            onProgress(served.size / 2L, served.size.toLong())
            destination.writeBytes(served)
            onProgress(served.size.toLong(), served.size.toLong())
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
}
