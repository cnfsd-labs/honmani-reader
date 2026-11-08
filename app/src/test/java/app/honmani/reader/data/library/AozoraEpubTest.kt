package app.honmani.reader.data.library

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AozoraEpubTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val book = AozoraBook(
        title = "吾輩は猫である",
        author = "夏目漱石",
        chapters = listOf(
            AozoraChapter(
                "一",
                listOf(
                    AozoraBlock.Heading(2, listOf(AozoraInline.Text("一"))),
                    AozoraBlock.Paragraph(
                        listOf(AozoraInline.Ruby("吾輩", "わがはい"), AozoraInline.Text("は<猫>である。")),
                    ),
                    AozoraBlock.Blank,
                    AozoraBlock.Paragraph(listOf(AozoraInline.Styled(AozoraStyle.SESAME, listOf(AozoraInline.Text("へっつい"))))),
                    AozoraBlock.Paragraph(
                        listOf(AozoraInline.Text("明治"), AozoraInline.Styled(AozoraStyle.UPRIGHT, listOf(AozoraInline.Text("30")))),
                        AozoraLayout(bottom = true, bottomGap = 2),
                    ),
                    AozoraBlock.Paragraph(listOf(AozoraInline.Text("歌")), AozoraLayout(indent = 1, hanging = 3)),
                ),
            ),
            AozoraChapter("二", listOf(AozoraBlock.Paragraph(listOf(AozoraInline.Text("二章。"))))),
        ),
    )

    @Test
    fun dropsCharactersXmlCannotHoldSoTheXhtmlStaysWellFormed() {
        val dirty = AozoraBook(
            title = "題\u0000名",
            author = "作\u000C者",
            chapters = listOf(
                AozoraChapter(
                    "章\u0001",
                    listOf(
                        AozoraBlock.Paragraph(
                            listOf(
                                AozoraInline.Text("あ\u000Cい\u0000う\u000B\u001F\uFFFE\uFFFF\uD800え\uDC00お"),
                                AozoraInline.Text("\uD83D\uDE00\t"),
                                AozoraInline.Ruby("漢\u0008", "かん\u000E"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val file = folder.newFile("dirty.epub")
        AozoraEpub.write(dirty, "urn:sha256:d\u0000", file)

        ZipFile(file).use { zip ->
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            for (name in listOf("OEBPS/chapter-1.xhtml", "OEBPS/content.opf", "OEBPS/nav.xhtml")) {
                val entry = zip.getEntry(name) ?: continue
                val builder = factory.newDocumentBuilder()
                builder.setErrorHandler(null)
                zip.getInputStream(entry).use { builder.parse(it) }
            }
            val chapter = zip.getInputStream(zip.getEntry("OEBPS/chapter-1.xhtml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(chapter, "<p>あいうえお\uD83D\uDE00\t<ruby>漢<rt>かん</rt></ruby></p>" in chapter)
        }
        val metadata = EpubMetadata.read(file)
        assertEquals("題名", metadata.title)
        assertEquals("作者", metadata.author)
    }

    @Test
    fun writesAnEpubTheLibraryCanRead() {
        val file = folder.newFile("neko.epub")
        AozoraEpub.write(book, "urn:sha256:abc", file)

        val metadata = EpubMetadata.read(file)
        assertEquals("吾輩は猫である", metadata.title)
        assertEquals("夏目漱石", metadata.author)

        ZipFile(file).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            fun text(name: String) = zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)
            val chapter = text("OEBPS/chapter-1.xhtml")
            assertTrue(chapter, "<h3>一</h3>" in chapter)
            assertTrue(chapter, "<p><ruby>吾輩<rt>わがはい</rt></ruby>は&lt;猫&gt;である。</p>" in chapter)
            assertTrue(chapter, "<em class=\"sesame\">へっつい</em>" in chapter)
            assertTrue(chapter, "<p style=\"text-align: end; padding-inline-end: 2em\">明治<span class=\"upright\">30</span></p>" in chapter)
            assertTrue(chapter, "<p style=\"padding-inline-start: 3em; text-indent: -2em\">歌</p>" in chapter)
            val style = text("OEBPS/style.css")
            assertTrue(style, "em.open-circle { -webkit-text-emphasis-style: open circle;" in style)
            assertTrue(style, "span.upright {" in style)
            assertTrue(text("OEBPS/chapter-2.xhtml"), "二章。" in text("OEBPS/chapter-2.xhtml"))
            val nav = text("OEBPS/nav.xhtml")
            assertTrue(nav, ">一</a>" in nav && ">二</a>" in nav)
            assertTrue("page-progression-direction=\"rtl\"" in text("OEBPS/content.opf"))
        }
    }
}
