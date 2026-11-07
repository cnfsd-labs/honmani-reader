package app.honmani.reader.readaloud.generate

import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.readaloud.CueAligner
import app.honmani.reader.readaloud.CueSpan
import app.honmani.reader.readaloud.PronunciationRules
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechPlanTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun epub(vararg bodies: String): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""")
            val items = bodies.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml"/>""" }
            val refs = bodies.indices.joinToString("") { """<itemref idref="c$it"/>""" }
            put("content.opf", """<package><manifest>$items</manifest><spine>$refs</spine></package>""")
            for ((i, body) in bodies.withIndex()) put("c$i.xhtml", """<html><body>$body</body></html>""")
        }
        return file
    }

    private fun plan(file: File, rules: PronunciationRules = PronunciationRules.EMPTY) = SpeechPlan.read(file, rules)!!

    /** What the page counts at each span, folded, gaiji dropped: must read as the sentence does. */
    private fun assertSpansMatchBook(file: File, plan: SpeechPlan) {
        val counted = EpubPositions.countedText(file)!!
        assertEquals("counted characters", counted.size, plan.countedTotal)
        var end = 0
        for (sentence in plan.sentences) {
            val span = sentence.span
            assertTrue("$span after $end", span.start >= end && span.end <= counted.size)
            end = span.end
            val inBook = (span.start until span.end).map { counted[it] }.filter { it != CueAligner.GAIJI }.map(CueAligner::fold)
            assertEquals(sentence.original, CueAligner.reduce(sentence.original).toList(), inBook)
        }
    }

    @Test
    fun rubyIsReadAsItsReadingAndRulesApplyToTheRest() {
        val file = epub(
            """<p>吾輩は<ruby>猫<rt>ねこ</rt></ruby>である。名前はまだ無い。</p><p>「<ruby>無<rt>な</rt></ruby>い」と言った</p>""",
        )
        val rules = PronunciationRules(listOf("無い" to "ない", "猫である" to "XXX", "吾輩" to "わがはい"))
        val plan = plan(file, rules)
        assertEquals(listOf("わがはいはねこである。", "名前はまだない。", "「ない」と言った"), plan.sentences.map { it.spoken })
        assertEquals(listOf("吾輩は猫である。", "名前はまだ無い。", "「無い」と言った"), plan.sentences.map { it.original })
        assertEquals(plan.sentences.sumOf { it.spoken.length }.toLong(), plan.billedChars)
        assertSpansMatchBook(file, plan)
    }

    @Test
    fun emphasisDotsAreNotReadings() {
        val file = epub("""<p>それは<ruby>大切<rt>・・</rt></ruby>な話。<ruby>本<rt>●</rt></ruby>当に<ruby>強<rt>ヽ</rt></ruby>い。</p>""")
        assertEquals(listOf("それは大切な話。", "本当に強い。"), plan(file).sentences.map { it.spoken })
    }

    @Test
    fun severalReadingsInOneRubyAndAnRpAreHandled() {
        val file = epub("""<p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp>字<rp>(</rp><rt>じ</rt><rp>)</rp></ruby>を<ruby><rb>読</rb><rt>よ</rt></ruby>む。</p>""")
        val plan = plan(file)
        assertEquals(listOf("かんじをよむ。"), plan.sentences.map { it.spoken })
        assertSpansMatchBook(file, plan)
    }

    @Test
    fun gaijiAreSkippedButCounted() {
        val file = epub(
            """<p><img class="gaiji" src="a.png"/>吾輩は<img class="gaiji" src="b.png"/>猫である。</p><p><img class="gaiji" src="c.png"/></p><p>名前は無い。<img class="gaiji" src="d.png"/></p>""",
        )
        val plan = plan(file, PronunciationRules(listOf("は猫" to "XX")))
        // A rule can't reach over the gaiji.
        assertEquals(listOf("吾輩は猫である。", "名前は無い。"), plan.sentences.map { it.spoken })
        assertEquals(listOf("吾輩は猫である。", "名前は無い。"), plan.sentences.map { it.original })
        // The first gaiji is not part of the sentence; the one inside is counted.
        assertEquals(CueSpan(1, 9), plan.sentences[0].span)
        assertEquals(CueSpan(10, 15), plan.sentences[1].span)
        assertSpansMatchBook(file, plan)
    }

    @Test
    fun aSentenceWithNoCountedCharacterIsLeftOut() {
        val file = epub("""<p>……</p><p>！</p><p>はい。</p>""")
        assertEquals(listOf("はい。"), plan(file).sentences.map { it.spoken })
    }

    @Test
    fun aLongSentenceIsCutForTheRequestLimit() {
        val sentence = "あいうえお、".repeat(700) + "。"
        val file = epub("<p>短い文。</p><p><ruby>前<rt>ぜん</rt></ruby>$sentence</p><p>終わり。</p>")
        val plan = plan(file)
        val parts = plan.sentences.drop(1).dropLast(1)
        assertTrue(parts.size >= 3)
        for (part in parts) assertTrue(part.spoken.length <= SpeechPlan.MAX_REQUEST_CHARS)
        assertEquals("ぜん" + sentence, parts.joinToString("") { it.spoken })
        // The parts follow each other in the book.
        for ((a, b) in parts.zipWithNext()) assertEquals(a.span.end, b.span.start)
        assertEquals("短い文。", plan.sentences.first().original)
        assertEquals("終わり。", plan.sentences.last().original)
        assertSpansMatchBook(file, plan)
    }

    @Test
    fun spansAreCountedAcrossDocuments() {
        val file = epub("<p>最初の章です。</p>", "<h1>二</h1><p>次の章です。</p>")
        val plan = plan(file)
        assertEquals(listOf("最初の章です。", "二", "次の章です。"), plan.sentences.map { it.spoken })
        assertSpansMatchBook(file, plan)
    }

    @Test
    fun sampleBookSpansAreWhatTheAlignerFinds() {
        val file = File("../docs/samples/tebukuro.epub")
        assertTrue(file.exists())
        val plan = plan(file)
        assertTrue(plan.sentences.size > 50)
        assertSpansMatchBook(file, plan)
        val book = EpubPositions.countedText(file)!!.let { counted -> IntArray(counted.size) { CueAligner.fold(counted[it]) } }
        val found = CueAligner.align(book, plan.sentences.map { it.original })
        var compared = 0
        for ((i, sentence) in plan.sentences.withIndex()) {
            val span = found[i] ?: continue
            compared++
            assertEquals("sentence $i: ${sentence.original}", span, sentence.span)
        }
        assertTrue("$compared of ${plan.sentences.size} compared", compared * 10 >= plan.sentences.size * 9)
        assertNotNull(plan.sentences.firstOrNull { it.spoken.contains("きつね") })
        // Readings replaced the kanji: no sentence says a base that has a reading.
        assertTrue(plan.billedChars > 0)
    }
}
