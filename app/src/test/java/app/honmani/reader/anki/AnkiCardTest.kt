package app.honmani.reader.anki

import app.honmani.reader.lookup.Furigana
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiCardTest {
    private val content = CardContent(
        expression = "取り扱う",
        reading = "とりあつかう",
        senses = listOf(listOf("to handle", "to deal with"), listOf("to treat")),
        sentence = "荷物を丁寧に取り扱った。",
        surfaceStart = 6,
        surfaceEnd = 11,
        bookTitle = "猫 & 犬",
        pitches = listOf(0),
        frequency = "Jiten #5000",
    )

    @Test
    fun fieldsFollowTheNoteType() {
        val fields = AnkiCard.fields(content, wordAudio = "[sound:w.mp3]", sentenceAudio = "[sound:s.mp3]")

        assertEquals(AnkiCard.FIELDS.size, fields.size)
        assertEquals("取り扱う", fields[0])
        assertEquals("とりあつかう", fields[1])
        assertEquals("<ruby>取<rt>と</rt></ruby>り<ruby>扱<rt>あつか</rt></ruby>う", fields[2])
        assertEquals("<ol><li>to handle; to deal with</li><li>to treat</li></ol>", fields[3])
        assertEquals("荷物を丁寧に<b>取り扱った</b>。", fields[4])
        assertEquals("猫 &amp; 犬", fields[5])
        assertEquals("[sound:w.mp3]", fields[6])
        assertEquals("[sound:s.mp3]", fields[7])
        // 平板: low on the first mora, high after it.
        assertEquals(
            "と" + listOf("り", "あ", "つ", "か", "う").joinToString("") { "<span class=\"h\">$it</span>" },
            fields[8],
        )
        assertEquals("Jiten #5000", fields[9])
    }

    @Test
    fun ankisFieldSeparatorNeverReachesAField() {
        val odd = content.copy(sentence = "荷物を\u001f丁寧に取り扱った。", senses = listOf(listOf("to\u001f handle")), bookTitle = "a\u001fb")

        val fields = AnkiCard.fields(odd, wordAudio = "", sentenceAudio = "")

        assertEquals(AnkiCard.FIELDS.size, fields.size)
        assertEquals(emptyList<String>(), fields.filter { '\u001f' in it })
    }

    @Test
    fun oneSenseIsPlainText_andHtmlInTextIsEscaped() {
        assertEquals("a &lt;b&gt; c", AnkiCard.meaning(listOf(listOf("a <b> c"))))
        assertEquals("", AnkiCard.meaning(emptyList()))
        assertEquals("1&lt;2", AnkiCard.sentence("1<2", 0, 0))
    }

    @Test
    fun kanaWordsGetNoRuby_andAMissingReadingFallsBackToTheWord() {
        assertEquals("ありがとう", AnkiCard.furigana("ありがとう", "ありがとう"))
        val fields = AnkiCard.fields(content.copy(expression = "すごい", reading = ""))
        assertEquals("すごい", fields[1])
    }

    @Test
    fun checksumMatchesAnki() {
        // int(sha1(text).hexdigest()[:8], 16), as Anki computes it.
        assertEquals(3578598137L, AnkiCard.checksum("名前"))
        assertEquals(567984260L, AnkiCard.checksum("<b>食べる</b>"))
    }

    @Test
    fun checksumDecodesTheEntitiesEscapeWrites() {
        // sha1 of the decoded text: AT&T, a<b>c, "quoted" it's, R&D <>
        assertEquals(2973542706L, AnkiCard.checksum(AnkiCard.escape("AT&T")))
        assertEquals(303421242L, AnkiCard.checksum(AnkiCard.escape("a<b>c")))
        assertEquals(1492148111L, AnkiCard.checksum(AnkiCard.escape("\"quoted\" it's")))
        assertEquals(1322740652L, AnkiCard.checksum("R&amp;D <i>&lt;&gt;</i>"))
        assertEquals(1322740652L, AnkiCard.checksum("R&#38;D &#x3C;&#62;"))
    }

    @Test
    fun theSentenceNoteTypeHasTheSentenceUnmarkedOnTheFront_andWordReadingMeaningOnTheBack() {
        val fields = SentenceCard.fields(content, wordAudio = "[sound:w.mp3]", sentenceAudio = "[sound:s.mp3]")

        assertEquals(SentenceCard.FIELDS.size, fields.size)
        assertEquals(listOf("Word", "Reading", "Meaning", "Sentence", "WordAudio", "SentenceAudio", "FuriganaPlain"), SentenceCard.FIELDS)
        assertEquals("取り扱う", fields[0])
        assertEquals("とりあつかう", fields[1])
        assertEquals("<ol><li>to handle; to deal with</li><li>to treat</li></ol>", fields[2])
        // No <b> around the looked-up word, no ruby.
        assertEquals("荷物を丁寧に取り扱った。", fields[3])
        assertEquals("[sound:w.mp3]", fields[4])
        assertEquals("[sound:s.mp3]", fields[5])
        assertEquals(fields, CardStyle.SENTENCE.model.build(content, "[sound:w.mp3]", "[sound:s.mp3]"))
    }

    @Test
    fun theSentenceNoteTypeEscapesHtml_dropsTheFieldSeparator_andFallsBackToTheWord() {
        val odd = content.copy(sentence = "1 < 2 & \u001f3")
        assertEquals("1 &lt; 2 &amp; 3", SentenceCard.fields(odd)[3])

        assertEquals("取り扱う", SentenceCard.fields(content.copy(sentence = ""))[3])
        assertEquals("取り扱う", SentenceCard.fields(content.copy(sentence = "  "))[3])
        assertEquals("すごい", SentenceCard.fields(content.copy(expression = "すごい", reading = ""))[1])
    }

    @Test
    fun plainFurigana_isAnkisNotation_withASpaceBeforeEveryReadingAfterTheStart() {
        assertEquals("取[と]り 扱[あつか]う", AnkiCard.plainFurigana("取り扱う", "とりあつかう"))
        assertEquals("漢字[かんじ]", AnkiCard.plainFurigana("漢字", "かんじ"))
        assertEquals("お 茶[ちゃ]", AnkiCard.plainFurigana("お茶", "おちゃ"))
        assertEquals("すごい", AnkiCard.plainFurigana("すごい", "すごい"))
        assertEquals("A&amp;B[えーあんどびー]", AnkiCard.plainFurigana("A&B", "えーあんどびー"))
    }

    @Test
    fun bothNoteTypesEndWithThePlainFurigana() {
        for (model in listOf(AnkiCard.MODEL, SentenceCard.MODEL)) {
            assertEquals(AnkiCard.PLAIN_FURIGANA, model.fields.last())
            assertEquals(listOf(AnkiCard.PLAIN_FURIGANA), model.addedFields)
            val fields = model.build(content, "", "")
            assertEquals(model.fields.size, fields.size)
            assertEquals("取[と]り 扱[あつか]う", fields.last())
        }
    }

    @Test
    fun theTwoNoteTypesAreTold_apart() {
        assertEquals(CardStyle.HONMANI, CardStyle.fromName(null))
        assertEquals(CardStyle.HONMANI, CardStyle.fromName("nonsense"))
        assertEquals(CardStyle.SENTENCE, CardStyle.fromName("SENTENCE"))
        assertEquals("Honmani", CardStyle.HONMANI.model.name)
        assertEquals("Honmani Sentence", CardStyle.SENTENCE.model.name)
        assertEquals(2, CardStyle.entries.map { it.model.id }.distinct().size)
        assertEquals(true, "{{Sentence}}" in SentenceCard.FRONT)
        assertEquals(false, "{{Word}}" in SentenceCard.FRONT)
    }

    @Test
    fun aFuriganaOverrideReachesTheFuriganaFieldsButNotTheReading() {
        val corrected = content.copy(furigana = "取[と]り 扱[あつかい]う")
        val fields = AnkiCard.fields(corrected)
        assertEquals("とりあつかう", fields[1])
        assertEquals("<ruby>取<rt>と</rt></ruby>り<ruby>扱<rt>あつかい</rt></ruby>う", fields[2])
        assertEquals("取[と]り 扱[あつかい]う", fields.last())
        val sentence = SentenceCard.fields(corrected)
        assertEquals("とりあつかう", sentence[1])
        assertEquals("取[と]り 扱[あつかい]う", sentence.last())
        // An override for another spelling of the word is ignored.
        val stale = AnkiCard.fields(content.copy(furigana = "取り扱い[とりあつかい]"))
        assertEquals(AnkiCard.fields(content), stale)
        assertEquals("飛[ひ]び 散[ち]る", AnkiCard.plainFurigana("飛び散る", "とびちる", "飛[ひ]び 散[ち]る"))
        assertEquals("飛[と]び 散[ち]る", AnkiCard.plainFurigana("飛び散る", "とびちる"))
    }
}
