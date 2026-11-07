package app.honmani.reader.data.aozora

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

internal fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

class AozoraArchiveTest {
    private val text = "吾輩は猫である。\n名前はまだ無い。".toByteArray(Charsets.UTF_8)

    private fun assertReason(reason: AozoraException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: AozoraException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun takesTheOneTextFileOutOfAZip() {
        assertArrayEquals(text, AozoraArchive.text(zip("wagahai.txt" to text)))
    }

    @Test
    fun otherFilesAndFoldersInTheZipAreIgnored() {
        val bytes = zip("images/" to ByteArray(0), "images/a.png" to byteArrayOf(1, 2, 3), "book.TXT" to text)
        assertArrayEquals(text, AozoraArchive.text(bytes))
    }

    @Test
    fun plainTextIsReturnedAsItIs() {
        assertArrayEquals(text, AozoraArchive.text(text))
        // Shift_JIS text does not start with "PK\u0003\u0004".
        val sjis = "吾輩は猫である".toByteArray(charset("Shift_JIS"))
        assertArrayEquals(sjis, AozoraArchive.text(sjis))
    }

    @Test
    fun plainTextOverTheLimitIsTooLarge() {
        assertReason(AozoraException.Reason.TOO_LARGE) { AozoraArchive.text(ByteArray(AozoraArchive.MAX_BYTES + 1) { 'a'.code.toByte() }) }
    }

    @Test
    fun aZipThatUnpacksToTooMuchIsTooLarge() {
        // Zeros compress to almost nothing: a zip bomb in small.
        val bomb = zip("a.txt" to ByteArray(AozoraArchive.MAX_BYTES + 1))
        assertReason(AozoraException.Reason.TOO_LARGE) { AozoraArchive.text(bomb) }
    }

    @Test
    fun namesWithPathsAreRefused() {
        for (name in listOf("../evil.txt", "a/../../evil.txt", "/abs.txt", "\\win.txt", "C:evil.txt", "a\\..\\b.txt")) {
            assertReason(AozoraException.Reason.BAD_DATA) { AozoraArchive.text(zip(name to text)) }
        }
    }

    @Test
    fun twoTextFilesAreRefused() {
        assertReason(AozoraException.Reason.BAD_DATA) { AozoraArchive.text(zip("a.txt" to text, "b.txt" to text)) }
    }

    @Test
    fun noTextFileOrAnEmptyOneIsRefused() {
        assertReason(AozoraException.Reason.BAD_DATA) { AozoraArchive.text(zip("a.png" to byteArrayOf(1))) }
        assertReason(AozoraException.Reason.BAD_DATA) { AozoraArchive.text(zip("a.txt" to ByteArray(0))) }
    }

    @Test
    fun tooManyEntriesAreRefused() {
        val many = (0..AozoraArchive.MAX_ENTRIES).map { "f$it.png" to byteArrayOf(1) }.toTypedArray()
        assertReason(AozoraException.Reason.BAD_DATA) { AozoraArchive.text(zip("a.txt" to text, *many)) }
    }

    @Test
    fun aDamagedZipIsBadData() {
        val bytes = zip("a.txt" to text)
        val cut = bytes.copyOf(bytes.size / 2)
        assertReason(AozoraException.Reason.BAD_DATA) { AozoraArchive.text(cut) }
    }
}
