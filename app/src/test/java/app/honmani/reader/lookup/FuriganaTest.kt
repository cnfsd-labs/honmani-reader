package app.honmani.reader.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuriganaTest {
    @Test
    fun replacesRubyBasesUpToTheLastOne() {
        val ruby = listOf(RubySpan(0, 1, "た"))
        assertEquals("た", Furigana.readingPrefix("食べました", ruby))
    }

    @Test
    fun keepsKanaBetweenBases() {
        // 取り扱い with furigana over 取 and 扱.
        val ruby = listOf(RubySpan(0, 1, "と"), RubySpan(2, 3, "あつか"))
        assertEquals("とりあつか", Furigana.readingPrefix("取り扱い", ruby))
    }

    @Test
    fun ignoresBasesBeyondTheSource() {
        val ruby = listOf(RubySpan(0, 2, "きょう"), RubySpan(3, 5, "てんき"))
        assertEquals("きょうはてんき", Furigana.readingPrefix("今日は天気", ruby))
        assertEquals("きょう", Furigana.readingPrefix("今日は天", ruby))
        assertNull(Furigana.readingPrefix("今", ruby))
    }

    @Test
    fun noFuriganaGivesNoReading() {
        assertNull(Furigana.readingPrefix("今日", emptyList()))
    }

    @Test
    fun katakanaFuriganaCountsAsHiragana() {
        val prefix = Furigana.readingPrefix("煙草", listOf(RubySpan(0, 2, "タバコ")))!!
        assertTrue(Furigana.agrees(prefix, "たばこ"))
    }

    @Test
    fun agreesComparesTheStartOfTheReading() {
        assertTrue(Furigana.agrees("おこな", "おこなう"))
        assertFalse(Furigana.agrees("おこな", "いく"))
    }

    @Test
    fun segmentsPutTheReadingOverEachKanjiRun() {
        assertEquals(
            listOf(RubySegment("取", "と"), RubySegment("り"), RubySegment("扱", "あつか"), RubySegment("う")),
            Furigana.segments("取り扱う", "とりあつかう"),
        )
        assertEquals(listOf(RubySegment("食", "た"), RubySegment("べる")), Furigana.segments("食べる", "たべる"))
        assertEquals(listOf(RubySegment("大人", "おとな"), RubySegment("しい")), Furigana.segments("大人しい", "おとなしい"))
        assertEquals(listOf(RubySegment("今日", "きょう")), Furigana.segments("今日", "きょう"))
        assertEquals(listOf(RubySegment("お"), RubySegment("茶", "ちゃ")), Furigana.segments("お茶", "おちゃ"))
    }

    @Test
    fun segmentsLeaveKanaWithoutRuby() {
        assertEquals(listOf(RubySegment("たべる")), Furigana.segments("たべる", "たべる"))
        assertEquals(listOf(RubySegment("コーヒー")), Furigana.segments("コーヒー", "こーひー"))
    }

    @Test
    fun segmentsMatchKatakanaInTheExpressionAgainstAHiraganaReading() {
        assertEquals(listOf(RubySegment("バス"), RubySegment("停", "てい")), Furigana.segments("バス停", "ばすてい"))
    }

    @Test
    fun segmentsFallBackToTheWholeReading() {
        // The kana of the expression is not in the reading (an irregular or wrong reading).
        assertEquals(listOf(RubySegment("明日る", "あした")), Furigana.segments("明日る", "あした"))
    }

    @Test
    fun anOverrideRoundTripsThroughItsStoredForm() {
        val segments = listOf(RubySegment("飛", "と"), RubySegment("び"), RubySegment("散", "ち"), RubySegment("る"))
        assertEquals("飛[と]び 散[ち]る", FuriganaOverride.format(segments))
        assertEquals(segments, FuriganaOverride.parse("飛[と]び 散[ち]る", "飛び散る"))
        // What the automatic split gives goes through unchanged, also with kana first or katakana ruby.
        for ((expression, reading) in listOf("飛び散る" to "とびちる", "大人" to "おとな", "お茶" to "おちゃ", "漢字" to "カンジ")) {
            val auto = Furigana.segments(expression, reading)
            assertEquals(auto, FuriganaOverride.parse(FuriganaOverride.format(auto), expression))
        }
        assertEquals(listOf(RubySegment("大人", "おとな")), FuriganaOverride.parse("大人[おとな]", "大人"))
        assertEquals(listOf(RubySegment("漢字", "カンジ")), FuriganaOverride.parse("漢字[カンジ]", "漢字"))
    }

    @Test
    fun anOverrideThatNoLongerFitsTheWordOrIsBrokenFallsBack() {
        val auto = Furigana.segments("飛び散る", "とびちる")
        for (bad in listOf("", "飛[と]び 散[ち]ら", "飛[と]び 散[ち]", "飛[と", "飛と]び散る", "[と]飛び散る", "飛[]び散る", "飛[[と]]び散る", "x".repeat(300))) {
            assertNull(bad, FuriganaOverride.parse(bad, "飛び散る"))
            assertEquals(bad, auto, Furigana.shown("飛び散る", "とびちる", bad))
        }
        assertEquals(listOf(RubySegment("飛", "ひ"), RubySegment("び散る")), Furigana.shown("飛び散る", "とびちる", "飛[ひ]び散る"))
    }

    @Test
    fun theEditorStartsFromTheShownRubyAndOnlyAcceptsKana() {
        assertTrue(FuriganaOverride.canCorrect("飛び散る"))
        assertFalse(FuriganaOverride.canCorrect("たべる"))
        assertFalse(FuriganaOverride.canCorrect("コーヒー"))
        assertEquals(listOf("と", "ち"), FuriganaOverride.prefill("飛び散る", Furigana.segments("飛び散る", "とびちる")))
        assertEquals(listOf("おとな"), FuriganaOverride.prefill("大人", Furigana.segments("大人", "おとな")))
        // A shown ruby that does not follow the runs (the kana was not found) gives empty fields.
        assertEquals(listOf(""), FuriganaOverride.prefill("お茶", Furigana.segments("お茶", "ちゃ")))
        assertEquals("飛[と]び 散[ち]る", FuriganaOverride.build("飛び散る", listOf("と", " ち ")))
        assertEquals("お 茶[ちゃ]", FuriganaOverride.build("お茶", listOf("ちゃ")))
        assertNull(FuriganaOverride.build("飛び散る", listOf("と", "")))
        assertNull(FuriganaOverride.build("飛び散る", listOf("と", "chi")))
        assertNull(FuriganaOverride.build("飛び散る", listOf("と", "ち散")))
        assertNull(FuriganaOverride.build("飛び散る", listOf("と")))
        assertTrue(FuriganaOverride.isReading("カンジー"))
        assertFalse(FuriganaOverride.isReading("かん字"))
        assertFalse(FuriganaOverride.isReading("[か]"))
        assertEquals("とびちる", FuriganaOverride.reading(Furigana.segments("飛び散る", "とびちる")))
    }
}
