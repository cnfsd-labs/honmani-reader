package app.honmani.reader.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamesTest {
    @Test
    fun titlesBecomeSafeFileNames() {
        val taken = mutableSetOf<String>()
        assertEquals("a_b_c.epub", ExportNames.unique("a/b:c", "epub", taken))
        assertEquals("吾輩は猫である.epub", ExportNames.unique("吾輩は猫である", "epub", taken))
        assertEquals("book.epub", ExportNames.unique("  ...  ", "epub", taken))
        assertEquals(100 + ".epub".length, ExportNames.unique("x".repeat(300), "epub", taken).length)
    }

    @Test
    fun longJapaneseTitlesStayWithinTheByteLimit() {
        val name = ExportNames.unique("吾".repeat(120), "epub", mutableSetOf())
        assertEquals(66 + ".epub".length, name.length)
        assertEquals(true, name.toByteArray().size <= 255)
        // 4-byte characters: 50 of them fill 200 bytes, a pair is never cut in half.
        val pair = "\uD842\uDFB7"
        val long = ExportNames.unique("a" + pair.repeat(60), "epub", mutableSetOf())
        assertEquals("a" + pair.repeat(49) + ".epub", long)
    }

    @Test
    fun sameTitlesGetNumbersIgnoringCase() {
        val taken = mutableSetOf("neko.epub")
        assertEquals("Neko (2).epub", ExportNames.unique("Neko", "epub", taken))
        assertEquals("neko (3).epub", ExportNames.unique("neko", "epub", taken))
        assertEquals("neko.txt", ExportNames.unique("neko", "txt", taken))
    }

    @Test
    fun aPreviousExportNeedsThePlainNameAndTheSize() {
        val entries = listOf(
            TreeEntry("1", "neko.EPUB", isFolder = false, size = 10),
            TreeEntry("2", "Inu.epub", isFolder = false, size = 5),
            TreeEntry("3", "Tori.epub", isFolder = false),
            TreeEntry("4", "Kame.epub", isFolder = true),
        )
        assertEquals("1", ExportNames.previousExport("Neko", "epub", 10, entries)?.id)
        assertEquals(null, ExportNames.previousExport("Inu", "epub", 6, entries))
        // A provider that reports no size leaves it to the bytes.
        assertEquals("3", ExportNames.previousExport("Tori", "epub", 6, entries)?.id)
        assertEquals(null, ExportNames.previousExport("Kame", "epub", 6, entries))
        assertEquals(null, ExportNames.previousExport("Neko (2)", "epub", 10, entries))
    }

    @Test
    fun sameBytesComparesTheWholeFile() {
        val file = java.io.File.createTempFile("book", ".epub").apply { deleteOnExit(); writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(true, ExportNames.sameBytes(file, byteArrayOf(1, 2, 3).inputStream()))
        assertEquals(false, ExportNames.sameBytes(file, byteArrayOf(1, 2, 4).inputStream()))
        assertEquals(false, ExportNames.sameBytes(file, byteArrayOf(1, 2).inputStream()))
        assertEquals(false, ExportNames.sameBytes(file, byteArrayOf(1, 2, 3, 4).inputStream()))
    }
}
