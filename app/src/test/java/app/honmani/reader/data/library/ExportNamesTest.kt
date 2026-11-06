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
    fun sameTitlesGetNumbersIgnoringCase() {
        val taken = mutableSetOf("neko.epub")
        assertEquals("Neko (2).epub", ExportNames.unique("Neko", "epub", taken))
        assertEquals("neko (3).epub", ExportNames.unique("neko", "epub", taken))
        assertEquals("neko.txt", ExportNames.unique("neko", "txt", taken))
    }
}
