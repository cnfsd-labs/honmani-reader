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
}
