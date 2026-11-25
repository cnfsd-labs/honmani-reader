package app.honmani.reader.data.sync

import app.honmani.reader.data.library.TestBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class EpubFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun book(entries: Map<String, String>): File =
        folder.newFile().apply { writeBytes(TestBooks.zip(entries.mapValues { it.value.toByteArray() })) }

    private fun find(name: String, directory: String, href: String): String? =
        ZipFile(book(mapOf(name to "x"))).use { EpubFiles(it).find(directory, href)?.name }

    @Test
    fun referencesAreRelativeToTheirFolder_withoutTheirFragment() {
        assertEquals("OEBPS/text/a.xhtml", find("OEBPS/text/a.xhtml", "OEBPS", "./text/../text/a.xhtml#p1"))
        assertNull(find("OEBPS/a.xhtml", "OEBPS/text", "a.xhtml"))
        assertNull(find("OEBPS/a.xhtml", "OEBPS", "#p1"))
    }

    @Test
    fun aReferenceAboveTheRootMeansTheRoot() {
        assertEquals("a.xhtml", find("a.xhtml", "OEBPS", "../../a.xhtml"))
    }

    @Test
    fun encodedReferencesAndNamesAreFound() {
        assertEquals("OEBPS/第1章 a+b.xhtml", find("OEBPS/第1章 a+b.xhtml", "OEBPS", "%E7%AC%AC1%E7%AB%A0%20a+b.xhtml"))
        // The name itself looks encoded.
        assertEquals("OEBPS/a%20b.xhtml", find("OEBPS/a%20b.xhtml", "OEBPS", "a%20b.xhtml"))
        assertEquals("OEBPS/100%.xhtml", find("OEBPS/100%.xhtml", "OEBPS", "100%.xhtml"))
    }

    @Test
    fun otherCaseUnicodeFormAndBackslashesAreFound() {
        assertEquals("OEBPS/Text/A.xhtml", find("OEBPS/Text/A.xhtml", "OEBPS", "text/a.XHTML"))
        // が as か and a combining mark, as macOS stores it.
        assertEquals("OEBPS/が.xhtml", find("OEBPS/が.xhtml", "OEBPS", "が.xhtml"))
        assertEquals("OEBPS/text/a.xhtml", find("OEBPS/text/a.xhtml", "OEBPS", "text\\a.xhtml"))
    }

    @Test
    fun theExactNameComesBeforeASimilarOne() {
        val file = book(mapOf("A.xhtml" to "x", "a.xhtml" to "x"))
        ZipFile(file).use { zip ->
            assertEquals("A.xhtml", EpubFiles(zip).find("", "A.xhtml")?.name)
            assertEquals("a.xhtml", EpubFiles(zip).find("", "a.xhtml")?.name)
        }
    }

    private fun page(text: String) = "<html><body><p>$text</p></body></html>"

    private fun spine(manifest: String, spine: String, files: Map<String, String>): File = book(
        mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
            "content.opf" to "<package><manifest>$manifest</manifest><spine>$spine</spine></package>",
        ) + files,
    )

    private fun names(file: File): List<String> = ZipFile(file).use { zip -> EpubPositions.spineOf(zip).map { it.name } }

    @Test
    fun aPictureWithAPageToFallBackToIsThatPage() {
        val file = spine(
            """<item id="i" href="i.pdf" media-type="application/pdf" fallback="j"/>
               <item id="j" href="j.png" media-type="image/png" fallback="p"/>
               <item id="p" href="p.xhtml" media-type="application/xhtml+xml"/>""",
            """<itemref idref="i"/>""",
            mapOf("i.pdf" to "", "j.png" to "", "p.xhtml" to page("猫である")),
        )
        assertEquals(listOf("p.xhtml"), names(file))
        assertEquals(4, EpubPositions.read(file)!!.total)
    }

    @Test
    fun fallbacksThatGoInACircleEnd() {
        val file = spine(
            """<item id="i" href="i.png" media-type="image/png" fallback="j"/>
               <item id="j" href="j.png" media-type="image/png" fallback="i"/>""",
            """<itemref idref="i"/>""",
            mapOf("i.png" to "", "j.png" to ""),
        )
        assertEquals(listOf("i.png"), names(file))
    }

    @Test
    fun whatIsMissingIsSkipped_andPagesAreKnownByTheirExtension() {
        val file = spine(
            """<item id="a" href="a.html" media-type="application/octet-stream"/>
               <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
               <item id="c" href="c.xhtml" media-type="text/x-oeb1-document"/>""",
            """<itemref idref="a"/><itemref idref="b"/><itemref idref="nowhere"/><itemref idref="c"/>""",
            mapOf("a.html" to page("一二"), "c.xhtml" to page("三四五")),
        )
        assertEquals(listOf("a.html", "c.xhtml"), names(file))
        assertEquals(5, EpubPositions.read(file)!!.total)
    }
}
