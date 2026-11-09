package app.honmani.reader.readaloud

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.library.DocumentTree
import app.honmani.reader.data.library.FakeDocumentTree
import app.honmani.reader.readaloud.generate.AudiobookMaker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookExporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tree = Uri.parse("content://tree/chosen")
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nこんにちは\n"
    private val audioBytes = ByteArray(200_000) { (it % 251).toByte() }

    private val store by lazy {
        AudiobookStore(
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("exporter-test", Context.MODE_PRIVATE),
            File(folder.root, "audiobook"),
        )
    }
    private val maker by lazy { AudiobookMaker(File(folder.root, "audiobook/generated"), store) }
    private val documents = FakeDocumentTree(mapOf("root" to emptyList()))
    private val written = mutableMapOf<String, ByteArrayOutputStream>()

    /** What the sources answer: the pairing's address maps to bytes (or throws). */
    private var sources = mutableMapOf<String, () -> java.io.InputStream?>()
    private var info = AudioInfo("Source.m4a", "audio/mp4")
    private var failWriteAfter: Int? = null
    private var failWith: IOException = IOException("disk broke")
    private var openedTree: DocumentTree? = documents

    private fun exporter() = AudiobookExporter(
        store = store,
        maker = maker,
        openAudio = { sources[it]?.invoke() ?: throw FileNotFoundException(it) },
        audioInfo = { info },
        openTree = { openedTree },
        openOutput = { uri ->
            val buffer = ByteArrayOutputStream().also { written[uri.toString()] = it }
            val limit = failWriteAfter
            if (limit == null || uri.toString().endsWith(".srt")) buffer else object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (buffer.size() + len > limit) throw failWith
                    buffer.write(b, off, len)
                }
            }
        },
    )

    private fun pairImported(hash: String = "abc") {
        store.pair(hash, "content://audio/1", srt.toByteArray())
        sources["content://audio/1"] = { audioBytes.inputStream() }
    }

    private fun export(title: String = "My Book") = runBlocking { exporter().export("abc", title, tree) }

    @Test
    fun anImportedAudiobookIsCopiedWithItsRealExtensionAndTheSubtitlesAsUtf8() {
        pairImported()
        assertTrue(exporter().canExport("abc"))
        val result = export()
        assertEquals(AudiobookExportResult.Saved("My Book.m4a"), result)
        assertEquals(setOf("content://tree/root/My Book.m4a", "content://tree/root/My Book.srt"), documents.created.keys)
        assertEquals("audio/mp4", documents.created["content://tree/root/My Book.m4a"]!!.first)
        assertArrayEquals(audioBytes, written["content://tree/root/My Book.m4a"]!!.toByteArray())
        assertArrayEquals(srt.toByteArray(Charsets.UTF_8), written["content://tree/root/My Book.srt"]!!.toByteArray())
        assertTrue(documents.deleted.isEmpty())
    }

    @Test
    fun aMissingSubtitleCopyIsReportedAndOnlyTheAudioIsWritten() {
        pairImported()
        assertTrue(File(folder.root, "audiobook/abc.srt").delete())
        assertEquals(AudiobookExportResult.SavedWithoutSubtitles("My Book.m4a"), export())
        assertEquals(setOf("content://tree/root/My Book.m4a"), documents.created.keys)
    }

    @Test
    fun theExtensionFallsBackToTheMimeTypeAndThenToAudio() {
        pairImported()
        info = AudioInfo("document:42", "audio/mpeg")
        assertEquals(AudiobookExportResult.Saved("My Book.mp3"), export())
        info = AudioInfo(null, null)
        assertEquals(AudiobookExportResult.Saved("My Book.audio"), export())
    }

    @Test
    fun theTitleIsMadeSafeAsAFileName() {
        pairImported()
        val result = export("a/b: c?*<>|\"d")
        assertEquals(AudiobookExportResult.Saved("a_b_ c______d.m4a"), result)
        assertTrue(documents.created.keys.any { it.endsWith("a_b_ c______d.srt") })
    }

    @Test
    fun aFinishedGeneratedAudiobookIsSavedAsMp3() {
        val audio = maker.files("abc").audio
        audio.parentFile!!.mkdirs()
        audio.writeBytes(audioBytes)
        val address = AudiobookMaker.uri(audio)
        store.pair("abc", address, srt.toByteArray())
        sources[address] = { audio.inputStream() }
        assertTrue(exporter().canExport("abc"))
        assertEquals(AudiobookExportResult.Saved("My Book.mp3"), export())
        assertEquals("audio/mpeg", documents.created["content://tree/root/My Book.mp3"]!!.first)
        assertArrayEquals(audioBytes, written["content://tree/root/My Book.mp3"]!!.toByteArray())
    }

    @Test
    fun aGeneratedAudiobookThatIsNotFinishedIsRefused() {
        // Paired with the file it will become, which does not exist yet.
        store.pair("abc", AudiobookMaker.uri(maker.files("abc").audio), srt.toByteArray())
        assertFalse(exporter().canExport("abc"))
        assertEquals(AudiobookExportResult.NothingToExport, export())
        assertTrue(documents.created.isEmpty())
    }

    @Test
    fun aBookWithoutAudiobookHasNothingToExport() {
        assertFalse(exporter().canExport("abc"))
        assertEquals(AudiobookExportResult.NothingToExport, export())
    }

    @Test
    fun aMissingAudioFileIsReportedAndNothingIsWritten() {
        store.pair("abc", "content://audio/gone", srt.toByteArray())
        assertEquals(AudiobookExportResult.AudioMissing, export())
        assertTrue(documents.created.isEmpty())
    }

    @Test
    fun aHalfWrittenFileIsDeletedWhenTheCopyBreaks() {
        pairImported()
        failWriteAfter = 70_000
        assertEquals(AudiobookExportResult.Failed, export())
        assertEquals(documents.created.keys.map { Uri.parse(it) }, documents.deleted)
        assertEquals(1, documents.deleted.size)
    }

    @Test
    fun aFullDiskIsNamedAsSuch() {
        pairImported()
        failWriteAfter = 70_000
        failWith = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(AudiobookExportResult.OutOfSpace, export())
        assertEquals(1, documents.deleted.size)
    }

    @Test
    fun theAudioIsDeletedAgainWhenTheSubtitleFileCannotBeMade() {
        pairImported()
        val failing = object : DocumentTree by documents {
            override fun createFile(folderId: String, mimeType: String, name: String): Uri? =
                if (name.endsWith(".srt")) null else documents.createFile(folderId, mimeType, name)
        }
        openedTree = failing
        assertEquals(AudiobookExportResult.Failed, export())
        assertEquals(1, documents.deleted.size)
    }

    @Test
    fun anUnusableFolderIsReported() {
        pairImported()
        openedTree = null
        assertEquals(AudiobookExportResult.FolderUnusable, export())
        openedTree = documents
        documents.refuseCreate = true
        assertEquals(AudiobookExportResult.FolderUnusable, export())
    }
}
