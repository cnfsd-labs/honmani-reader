package app.honmani.reader.data.library

import app.honmani.reader.data.library.AozoraBlock.Blank
import app.honmani.reader.data.library.AozoraBlock.Heading
import app.honmani.reader.data.library.AozoraBlock.Paragraph
import app.honmani.reader.data.library.AozoraInline.Ruby
import app.honmani.reader.data.library.AozoraInline.Styled
import app.honmani.reader.data.library.AozoraInline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AozoraTextTest {
    private val dashes = "-".repeat(55)

    private fun aozora(body: String, header: String = "吾輩は猫である\n夏目漱石") =
        "$header\n\n$dashes\n【テキスト中に現れる記号について】\n\n《》：ルビ\n（例）吾輩《わがはい》\n$dashes\n\n$body\n\n" +
            "底本：「夏目漱石全集1」ちくま文庫、筑摩書房\n"

    private fun paragraphs(text: String) = AozoraText.parse(text, "fallback").chapters.flatMap { it.blocks }

    @Test
    fun recognisesAozoraTexts() {
        assertTrue(AozoraText.isAozora(aozora("本文")))
        assertTrue(AozoraText.isAozora("吾輩《わがはい》は猫である。\n\n底本：「全集」\n"))
        assertFalse(AozoraText.isAozora("吾輩は猫である。名前はまだ無い。"))
    }

    @Test
    fun otherTextsWithAozoraFuriganaCountToo() {
        val text = "　村瀬《むらせ》のおいしい話に乗った。\nその村瀬｜勝也《かつや》は、小峰《こみね》に背を向けた。\n"
        assertTrue(AozoraText.isAozora(text))
        // One reading in brackets is not enough to tell.
        assertFalse(AozoraText.isAozora("彼は「本《ほん》」と言った。"))

        val book = AozoraText.parse(text, "ひかり町")
        assertEquals("ひかり町", book.title)
        assertNull(book.author)
        // No title block: the first line is book text.
        assertEquals(
            Paragraph(listOf(Text("　"), Ruby("村瀬", "むらせ"), Text("のおいしい話に乗った。"))),
            book.chapters.single().blocks.first(),
        )
        assertEquals(2, book.chapters.single().blocks.size)
    }

    @Test
    fun readsTitleAndAuthorFromTheHeader() {
        val book = AozoraText.parse(aozora("本文"), "fallback")
        assertEquals("吾輩は猫である", book.title)
        assertEquals("夏目漱石", book.author)

        val translated = AozoraText.parse(aozora("本文", header = "変身\nフランツ・カフカ\n原田義人訳"), "fallback")
        assertEquals("フランツ・カフカ", translated.author)
    }

    @Test
    fun skipsTheNotationGuide() {
        val text = paragraphs(aozora("　本文。")).filterIsInstance<Paragraph>().first()
        assertEquals(Paragraph(listOf(Text("　本文。"))), text)
    }

    @Test
    fun rubyCoversTheKanjiBeforeIt() {
        assertEquals(
            Paragraph(listOf(Text("　"), Ruby("吾輩", "わがはい"), Text("は猫である。"))),
            paragraphs(aozora("　吾輩《わがはい》は猫である。")).first(),
        )
    }

    @Test
    fun aBarMarksWhereTheRubyBaseStarts() {
        assertEquals(
            Paragraph(listOf(Text("一番"), Ruby("獰悪", "どうあく"), Text("な種族"))),
            paragraphs(aozora("一番｜獰悪《どうあく》な種族")).first(),
        )
        assertEquals(
            Paragraph(listOf(Text("その上"), Ruby("今", "いま"), Text("まで"))),
            paragraphs(aozora("その上｜今《いま》まで")).first(),
        )
    }

    @Test
    fun rubyOverKanaNeedsABar_otherwiseTheBracketsStay() {
        assertEquals(Paragraph(listOf(Text("ねこ《ネコ》"))), paragraphs(aozora("ねこ《ネコ》")).first())
    }

    @Test
    fun headingsStartChaptersAndNameThem() {
        val book = AozoraText.parse(aozora("［＃８字下げ］一［＃「一」は中見出し］\n　本文一。\n［＃８字下げ］二［＃「二」は中見出し］\n　本文二。"), "f")
        assertEquals(listOf("一", "二"), book.chapters.map { it.title })
        assertEquals(Heading(2, listOf(Text("一"))), book.chapters[0].blocks[0])
        assertEquals(Paragraph(listOf(Text("　本文二。"))), book.chapters[1].blocks[1])
    }

    @Test
    fun pageBreaksStartChapters() {
        val book = AozoraText.parse(aozora("前。\n［＃改ページ］\n後。"), "f")
        assertEquals(2, book.chapters.size)
        assertNull(book.chapters[1].title)
    }

    @Test
    fun emphasisDotsMarkTheTextBeforeTheNote() {
        assertEquals(
            Paragraph(listOf(Text("その"), Styled(AozoraStyle.SESAME, listOf(Text("へっつい"))), Text("の中"))),
            paragraphs(aozora("そのへっつい［＃「へっつい」に傍点］の中")).first(),
        )
    }

    @Test
    fun otherMarksLinesAndStyles_afterTheText() {
        assertEquals(
            Paragraph(
                listOf(
                    Styled(AozoraStyle.OPEN_CIRCLE, listOf(Text("白丸"))),
                    Styled(AozoraStyle.WAVY_LINE, listOf(Text("波線"))),
                    Text("の"),
                    Styled(AozoraStyle.BOLD, listOf(Text("太字"))),
                    Text("、"),
                    Styled(AozoraStyle.UPRIGHT, listOf(Text("25"))),
                    Text("日"),
                ),
            ),
            paragraphs(aozora("白丸［＃「白丸」に白丸傍点］波線［＃「波線」に波線］の太字［＃「太字」は太字］、25［＃「25」は縦中横］日")).first(),
        )
    }

    @Test
    fun aMarkedWordMayHaveFurigana() {
        assertEquals(
            Paragraph(listOf(Text("この"), Styled(AozoraStyle.SESAME, listOf(Ruby("吾輩", "わがはい"), Text("こそ"))), Text("は"))),
            paragraphs(aozora("この吾輩《わがはい》こそ［＃「吾輩こそ」に傍点］は")).first(),
        )
        // A note whose text is not just before it changes nothing.
        assertEquals(Paragraph(listOf(Text("猫である"))), paragraphs(aozora("猫である［＃「犬」に傍点］")).first())
    }

    @Test
    fun rangesOfStyles() {
        assertEquals(
            Paragraph(
                listOf(
                    Text("前"),
                    Styled(AozoraStyle.SESAME, listOf(Text("強く"), Styled(AozoraStyle.BOLD, listOf(Text("太く"))))),
                    Text("後"),
                    Styled(AozoraStyle.ITALIC, listOf(Text("行末まで"))),
                ),
            ),
            paragraphs(aozora("前［＃傍点］強く［＃太字］太く［＃太字終わり］［＃傍点終わり］後［＃斜体］行末まで")).first(),
        )
    }

    @Test
    fun indentationAndBottomAlignment() {
        val blocks = paragraphs(
            aozora(
                listOf(
                    "［＃３字下げ］三字",
                    "［＃地付き］地付き",
                    "［＃地から２字上げ］字上げ",
                    "［＃ここから２字下げ］",
                    "範囲［＃「範囲」は底本では「範位」］。",
                    "［＃ここで字下げ終わり］",
                    "［＃ここから１字下げ、折り返して３字下げ］",
                    "折り返し",
                    "［＃ここで字下げ終わり］",
                    "普通",
                ).joinToString("\n"),
            ),
        )
        assertEquals(
            listOf(
                Paragraph(listOf(Text("三字")), AozoraLayout(indent = 3)),
                Paragraph(listOf(Text("地付き")), AozoraLayout(bottom = true)),
                Paragraph(listOf(Text("字上げ")), AozoraLayout(bottom = true, bottomGap = 2)),
                Paragraph(listOf(Text("範囲。")), AozoraLayout(indent = 2)),
                Paragraph(listOf(Text("折り返し")), AozoraLayout(indent = 1, hanging = 3)),
                Paragraph(listOf(Text("普通"))),
            ),
            // Then the colophon.
            blocks.take(6),
        )
    }

    @Test
    fun keepsTheMarkForCodePointsThatDoNotExist() {
        for (code in listOf("U+FFFFFF", "U+110000", "U+D800")) {
            assertEquals(
                Paragraph(listOf(Text("※を食う"))),
                paragraphs(aozora("※［＃「魚＋花」、$code、1-2-3］を食う")).first(),
            )
        }
    }

    @Test
    fun restoresCharactersGivenAsUnicode() {
        assertEquals(
            Paragraph(listOf(Text("𩸽を食う"))),
            paragraphs(aozora("※［＃「魚＋花」、U+29E3D、1-2-3］を食う")).first(),
        )
        assertEquals(
            Paragraph(listOf(Text("※を食う"))),
            paragraphs(aozora("※［＃「言＋墟のつくり」、第4水準2-88-74］を食う")).first(),
        )
    }

    @Test
    fun keepsBlankLinesInsideAChapter() {
        assertEquals(
            listOf(Paragraph(listOf(Text("前。"))), Blank, Paragraph(listOf(Text("後。")))),
            paragraphs(aozora("前。\n\n後。")).take(3),
        )
    }
}
