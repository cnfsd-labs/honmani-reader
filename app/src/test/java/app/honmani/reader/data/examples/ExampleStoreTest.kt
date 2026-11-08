package app.honmani.reader.data.examples

import app.honmani.reader.data.dictionary.FailureReason
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
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
class ExampleStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = "sqlite bytes".toByteArray()
    private val gzipped = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(content) } }.toByteArray()
    private val catalog = ExampleCatalog(
        url = "https://example.org/examples-1.db.gz",
        sha256 = sha256(gzipped),
        sizeBytes = gzipped.size.toLong(),
    )

    private var served = gzipped
    private var failure: IOException? = null
    private var gate: CompletableDeferred<Unit>? = null

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun TestScope.store() = ExampleStore(
        dir = File(folder.root, "examples"),
        scope = this,
        download = { url, destination, onProgress ->
            assertEquals(catalog.url, url)
            gate?.await()
            failure?.let { throw it }
            onProgress(served.size / 2L, served.size.toLong())
            destination.writeBytes(served)
            onProgress(served.size.toLong(), served.size.toLong())
        },
        catalog = catalog,
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun theDownloadIsCheckedUnpackedAndInstalled() = runTest(StandardTestDispatcher()) {
        val store = store()
        assertEquals(ExampleState.Available, store.state.value)

        store.download()
        assertTrue(store.state.value is ExampleState.Downloading)
        advanceUntilIdle()

        assertEquals(ExampleState.Installed, store.state.value)
        assertTrue(store.file.readBytes().contentEquals(content))
        // Only the unpacked file stays; it is found again after a restart.
        assertEquals(listOf("examples.db"), File(folder.root, "examples").list().orEmpty().toList())
        assertEquals(ExampleState.Installed, store().state.value)
    }

    @Test
    fun aDamagedDownloadIsNotInstalled() = runTest(StandardTestDispatcher()) {
        served = "something else".toByteArray()
        val store = store()

        store.download()
        advanceUntilIdle()

        assertEquals(ExampleState.Failed(FailureReason.OTHER), store.state.value)
        assertFalse(store.file.exists())
        assertTrue(File(folder.root, "examples").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFileThatChecksButIsNotGzipIsNotInstalled() = runTest(StandardTestDispatcher()) {
        served = "not gzip".toByteArray()
        val store = ExampleStore(
            dir = File(folder.root, "examples"),
            scope = this,
            download = { _, destination, _ -> destination.writeBytes(served) },
            catalog = catalog.copy(sha256 = sha256(served)),
            io = StandardTestDispatcher(testScheduler),
        )

        store.download()
        advanceUntilIdle()

        assertTrue(store.state.value is ExampleState.Failed)
        assertFalse(store.file.exists())
        assertTrue(File(folder.root, "examples").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFailedDownloadCanBeTriedAgain() = runTest(StandardTestDispatcher()) {
        failure = IOException("offline")
        val store = store()
        store.download()
        advanceUntilIdle()
        assertEquals(ExampleState.Failed(FailureReason.OTHER), store.state.value)

        failure = null
        store.download()
        advanceUntilIdle()
        assertEquals(ExampleState.Installed, store.state.value)
    }

    @Test
    fun deletingRemovesTheFile_orCancelsTheDownload() = runTest(StandardTestDispatcher()) {
        val store = store()
        store.download()
        advanceUntilIdle()

        store.delete()
        advanceUntilIdle()
        assertEquals(ExampleState.Available, store.state.value)
        assertFalse(store.file.exists())

        gate = CompletableDeferred()
        store.download()
        advanceUntilIdle()
        store.delete()
        advanceUntilIdle()
        assertEquals(ExampleState.Available, store.state.value)
        assertTrue(File(folder.root, "examples").listFiles().orEmpty().isEmpty())
    }
}
