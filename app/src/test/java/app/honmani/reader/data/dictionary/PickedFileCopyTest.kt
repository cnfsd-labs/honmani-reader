package app.honmani.reader.data.dictionary

import app.honmani.reader.data.dictionary.yomitan.InvalidDictionaryException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PickedFileCopyTest {
    @Test
    fun aPickedFileIsCopiedUpToTheDownloadLimit() {
        val bytes = ByteArray(20_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        DictionaryImportWorker.copyAtMost(ByteArrayInputStream(bytes), out, maxBytes = 20_000)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun aLargerPickedFileStopsTheCopy() {
        val out = ByteArrayOutputStream()
        assertThrows(InvalidDictionaryException::class.java) {
            DictionaryImportWorker.copyAtMost(ByteArrayInputStream(ByteArray(20_001)), out, maxBytes = 20_000)
        }
        assertTrue(out.size() <= 20_000)
    }
}
