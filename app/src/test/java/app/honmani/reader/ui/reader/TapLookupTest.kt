package app.honmani.reader.ui.reader

import app.honmani.reader.data.words.WordMarksMode
import app.honmani.reader.lookup.RubySpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapLookupTest {
    @Test
    fun parse_readsBridgeMessage() {
        val message = """{"type":"lookup","text":"猫である。","sentence":"吾輩は猫である。","sentenceOffset":3}"""

        assertEquals(
            TapLookup(text = "猫である。", sentence = "吾輩は猫である。", sentenceOffset = 3),
            TapLookup.parse(message),
        )
    }

    @Test
    fun parse_marksALongPressLookup() {
        val message = """{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"selection":true}"""

        assertEquals(true, TapLookup.parse(message)?.bySelection)
        assertEquals(false, TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0}""")?.bySelection)
    }

    @Test
    fun parse_readsTheSelectionSentenceAndParagraph_forAHighlight() {
        val message = """{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"selection":true,"highlight":{"word":{"start":3,"end":4,"text":"猫"},"sentence":{"start":0,"end":7,"text":"吾輩は猫である。"},"paragraph":{"start":0,"end":14,"text":"吾輩は猫である。名前はまだ無い。"}}}"""

        val extents = TapLookup.parse(message)?.selected
        assertEquals(SelectedPassage(3, 4, "猫"), extents?.selection)
        assertEquals(SelectedPassage(0, 7, "吾輩は猫である。"), extents?.sentence)
        assertEquals(SelectedPassage(0, 14, "吾輩は猫である。名前はまだ無い。"), extents?.paragraph)
        assertEquals(
            listOf(HighlightExtent.Selection, HighlightExtent.Sentence, HighlightExtent.Paragraph),
            extents?.available?.keys?.toList(),
        )
        assertEquals(SelectedPassage(0, 14, "吾輩は猫である。名前はまだ無い。"), extents?.get(HighlightExtent.Paragraph))
        assertNull(TapLookup.parse(message.replace(""","selection":true""", ""))?.selected)
    }

    @Test
    fun parse_aSelectionWithoutOtherExtents_fallsBackToItself() {
        val message = """{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"selection":true,"highlight":{"word":{"start":3,"end":9,"text":"猫である"}}}"""

        val extents = TapLookup.parse(message)?.selected
        assertEquals(listOf(HighlightExtent.Selection), extents?.available?.keys?.toList())
        assertEquals(SelectedPassage(3, 9, "猫である"), extents?.get(HighlightExtent.Sentence))
    }

    @Test
    fun parse_dropsAnExtentThatDoesNotHoldTheSelection() {
        val message = """{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"selection":true,"highlight":{"word":{"start":3,"end":4,"text":"猫"},"sentence":{"start":5,"end":9,"text":"x"},"paragraph":{"start":0,"end":4,"text":"y"}}}"""

        val extents = TapLookup.parse(message)?.selected
        assertNull(extents?.sentence)
        assertEquals(SelectedPassage(0, 4, "y"), extents?.paragraph)
    }

    @Test
    fun parse_dropsAnImpossibleSelection_butKeepsTheLookup() {
        fun selected(word: String) =
            TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"selection":true,"highlight":{"word":$word}}""")

        assertNull(selected("""{"start":5,"end":5,"text":"猫"}""")?.selected)
        assertNull(selected("""{"start":-1,"end":5,"text":"猫"}""")?.selected)
        assertNull(selected("""{"start":1,"end":5,"text":" "}""")?.selected)
        assertEquals("猫", selected("""{"start":5,"end":2,"text":"猫"}""")?.text)
        assertEquals(200, selected("""{"start":1,"end":500,"text":"${"あ".repeat(300)}"}""")?.selected?.selection?.text?.length)
    }

    @Test
    fun parse_readsTheHighlightUnderATap_notUnderASelection() {
        val tap = """{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"highlightId":42}"""

        assertEquals(42L, TapLookup.parse(tap)?.highlightId)
        assertNull(TapLookup.parse(tap.replace("42", "0"))?.highlightId)
        assertNull(TapLookup.parse(tap.replace("}", ""","selection":true}"""))?.highlightId)
    }

    @Test
    fun highlightsRequest_needsABookId() {
        assertEquals(BridgeMessage.HighlightsRequest(7), BridgeMessage.parse("""{"type":"highlights.request","bookId":7}"""))
        assertNull(BridgeMessage.parse("""{"type":"highlights.request","bookId":0}"""))
        assertNull(BridgeMessage.parse("""{"type":"highlights.request"}"""))
        assertNull(BridgeMessage.parse("""{"type":"highlights.request","bookId":"x"}"""))
    }

    @Test
    fun parse_ignoresUnknownFields() {
        val message = """{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"rect":{}}"""

        assertEquals("猫", TapLookup.parse(message)?.text)
    }

    @Test
    fun parse_rejectsOtherTypes() {
        assertNull(TapLookup.parse("""{"type":"other","text":"猫","sentence":"猫","sentenceOffset":0}"""))
        assertNull(TapLookup.parse("""{"text":"猫","sentence":"猫","sentenceOffset":0}"""))
    }

    @Test
    fun parse_rejectsMalformedInput() {
        assertNull(TapLookup.parse(null))
        assertNull(TapLookup.parse(""))
        assertNull(TapLookup.parse("not json"))
        assertNull(TapLookup.parse("""["lookup"]"""))
        assertNull(TapLookup.parse("""{"type":"lookup","text":1,"sentence":"猫","sentenceOffset":0}"""))
    }

    @Test
    fun parse_rejectsInvalidValues() {
        assertNull(TapLookup.parse("""{"type":"lookup","text":"  ","sentence":"猫","sentenceOffset":0}"""))
        assertNull(TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":2}"""))
        assertNull(TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":-1}"""))
        val longText = "猫".repeat(65)
        assertNull(TapLookup.parse("""{"type":"lookup","text":"$longText","sentence":"猫","sentenceOffset":0}"""))
        val longSentence = "猫".repeat(1_001)
        assertNull(TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"$longSentence","sentenceOffset":0}"""))
    }

    @Test
    fun parse_readsFurigana() {
        val message = """{"type":"lookup","text":"今日は","sentence":"今日は","sentenceOffset":0,
            "ruby":[{"start":0,"end":2,"reading":"きょう"}]}"""

        assertEquals(listOf(RubySpan(0, 2, "きょう")), TapLookup.parse(message)?.ruby)
    }

    @Test
    fun parse_dropsFuriganaThatDoesNotFitTheText_butKeepsTheLookup() {
        fun parse(ruby: String) = TapLookup.parse(
            """{"type":"lookup","text":"今日は","sentence":"今日は","sentenceOffset":0,"ruby":$ruby}""",
        )

        for (ruby in listOf(
            """[{"start":0,"end":4,"reading":"きょう"}]""",
            """[{"start":-1,"end":1,"reading":"きょう"}]""",
            """[{"start":1,"end":1,"reading":"きょう"}]""",
            """[{"start":0,"end":1,"reading":" "}]""",
            """[{"start":0,"end":2,"reading":"a"},{"start":1,"end":3,"reading":"b"}]""",
        )) {
            val lookup = parse(ruby)
            assertEquals(ruby, "今日は", lookup?.text)
            assertEquals(ruby, emptyList<RubySpan>(), lookup?.ruby)
        }
    }

    @Test
    fun parse_keepsTheTapNumber() {
        val lookup = TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫だ。","sentenceOffset":0,"tap":7}""")

        assertEquals(7, lookup?.tapNumber)
    }

    @Test
    fun parse_keepsThePreviousSentence() {
        val lookup = TapLookup.parse(
            """{"type":"lookup","text":"猫","sentence":"猫だ。","sentenceOffset":0,"previousSentence":"吾輩は。"}""",
        )

        assertEquals("吾輩は。", lookup?.previousSentence)
    }

    @Test
    fun parse_takesAMessageWithoutThePreviousSentence() {
        val lookup = TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫だ。","sentenceOffset":0}""")

        assertEquals("", lookup?.previousSentence)
        assertEquals("猫", lookup?.text)
    }

    @Test
    fun parse_dropsAnOversizedPreviousSentence() {
        val long = "あ".repeat(1_001)

        assertNull(TapLookup.parse("""{"type":"lookup","text":"猫","sentence":"猫","sentenceOffset":0,"previousSentence":"$long"}"""))
    }

    @Test
    fun bridgeMessage_parsesLookupsAndDismiss() {
        val lookup = """{"type":"lookup","text":"猫","sentence":"猫だ。","sentenceOffset":0}"""

        assertEquals(BridgeMessage.Lookup(TapLookup("猫", "猫だ。", 0)), BridgeMessage.parse(lookup))
        assertEquals(BridgeMessage.Dismiss, BridgeMessage.parse("""{"type":"dismiss"}"""))
        assertEquals(BridgeMessage.TurnPage(true), BridgeMessage.parse("""{"type":"turnPage","next":true}"""))
        assertEquals(BridgeMessage.TurnPage(false), BridgeMessage.parse("""{"type":"turnPage","next":false}"""))
        assertNull(BridgeMessage.parse("""{"type":"turnPage","next":"true"}"""))
        assertNull(BridgeMessage.parse("""{"type":"turnPage"}"""))
        assertEquals(BridgeMessage.Activity, BridgeMessage.parse("""{"type":"reader.activity"}"""))
        assertEquals(BridgeMessage.OpenSettings, BridgeMessage.parse("""{"type":"reader.settings"}"""))
        assertEquals(BridgeMessage.OpenMenu, BridgeMessage.parse("""{"type":"reader.menu"}"""))
        assertNull(BridgeMessage.parse("""{"type":"reader.menu","extra":1}"""))
        assertEquals(BridgeMessage.OpenMenuBySwipe, BridgeMessage.parse("""{"type":"reader.menuSwipe"}"""))
        assertNull(BridgeMessage.parse("""{"type":"reader.menuSwipe","extra":1}"""))
        assertEquals(BridgeMessage.SettingsShown, BridgeMessage.parse("""{"type":"pageShown","path":"/settings"}"""))
        assertNull(BridgeMessage.parse("""{"type":"pageShown","path":"/manage"}"""))
        assertEquals(BridgeMessage.Position(3, 1_200), BridgeMessage.parse("""{"type":"reader.position","id":3,"characters":1200}"""))
        assertNull(BridgeMessage.parse("""{"type":"reader.position","id":3}"""))
        assertEquals(
            BridgeMessage.Position(3, 1_200, 0.25f),
            BridgeMessage.parse("""{"type":"reader.position","id":3,"characters":1200,"progress":0.25}"""),
        )
        assertEquals(
            BridgeMessage.Background(0xFF000000.toInt()),
            BridgeMessage.parse("""{"type":"reader.background","color":"#000000"}"""),
        )
        assertEquals(
            BridgeMessage.Background(0xFFF7F6EB.toInt()),
            BridgeMessage.parse("""{"type":"reader.background","color":"#f7f6eb"}"""),
        )
        assertNull(BridgeMessage.parse("""{"type":"reader.background","color":"red"}"""))
        assertNull(BridgeMessage.parse("""{"type":"reader.background","color":"#-12345"}"""))
        // A progress out of range is left out, the position still counts.
        assertEquals(
            BridgeMessage.Position(3, 1_200),
            BridgeMessage.parse("""{"type":"reader.position","id":3,"characters":1200,"progress":7}"""),
        )
        assertEquals(BridgeMessage.BookShown(7), BridgeMessage.parse("""{"type":"bookShown","id":7}"""))
        assertNull(BridgeMessage.parse("""{"type":"bookShown","id":0}"""))
        assertNull(BridgeMessage.parse("""{"type":"bookShown","id":"x"}"""))
        assertNull(BridgeMessage.parse("""{"type":"highlight","length":2}"""))
        assertNull(BridgeMessage.parse(null))
    }

    @Test
    fun bridgeMessage_parsesMarkRequests() {
        val request = """{"type":"marks.request","requestId":4,"mode":"new","blocks":[{"id":1,"text":"猫だ。"},{"id":2,"text":"}{"}]}"""

        assertEquals(
            BridgeMessage.Marks(4, WordMarksMode.NEW, listOf(BridgeMessage.MarksBlock(1, "猫だ。"), BridgeMessage.MarksBlock(2, "}{"))),
            BridgeMessage.parse(request),
        )
        // An unknown mode is the default.
        assertEquals(
            BridgeMessage.Marks(5, WordMarksMode.LEARNING, emptyList()),
            BridgeMessage.parse("""{"type":"marks.request","requestId":5,"mode":"x","blocks":[]}"""),
        )
        // Whether to mark known words (to hide their furigana) comes along.
        assertEquals(
            BridgeMessage.Marks(6, WordMarksMode.OFF, emptyList(), knownFurigana = true),
            BridgeMessage.parse("""{"type":"marks.request","requestId":6,"mode":"off","knownFurigana":true,"blocks":[]}"""),
        )
        // So does whether to mark the sentences with one unknown word.
        assertEquals(
            BridgeMessage.Marks(7, WordMarksMode.OFF, emptyList(), unknownSentences = true),
            BridgeMessage.parse("""{"type":"marks.request","requestId":7,"mode":"off","unknownSentences":true,"blocks":[]}"""),
        )
        // And whether to mark the words due for review.
        assertEquals(
            BridgeMessage.Marks(8, WordMarksMode.OFF, emptyList(), dueMarks = true),
            BridgeMessage.parse("""{"type":"marks.request","requestId":8,"mode":"off","dueMarks":true,"blocks":[]}"""),
        )
        assertNull(BridgeMessage.parse("""{"type":"marks.request","blocks":"x"}"""))
    }

    @Test
    fun bridgeMessage_capsEachKindSeparately() {
        val padding = " ".repeat(200)

        // Small messages of every kind are accepted, the same ones padded past 200 are not.
        for ((message, expected) in listOf(
            """{"type":"bookShown","id":7}""" to BridgeMessage.BookShown(7),
            """{"type":"reader.position","id":3,"characters":1200}""" to BridgeMessage.Position(3, 1_200),
            """{"type":"reader.background","color":"#000000"}""" to BridgeMessage.Background(0xFF000000.toInt()),
        )) {
            assertEquals(expected, BridgeMessage.parse(message))
            assertNull(BridgeMessage.parse(message + padding))
        }
        // Mark requests may be far longer; an oversized one is still dropped.
        val blocks = (1..100).joinToString(",") { """{"id":$it,"text":"${"猫".repeat(100)}"}""" }
        assertEquals(100, (BridgeMessage.parse("""{"type":"marks.request","requestId":1,"mode":"new","blocks":[$blocks]}""") as BridgeMessage.Marks).blocks.size)
        assertNull(BridgeMessage.parse("""{"type":"marks.request","requestId":1,"mode":"new","blocks":[$blocks]}""" + " ".repeat(64_000)))
        // A type that belongs to another kind never reaches its parser.
        assertNull(BridgeMessage.parse("""{"type":"library.unknown","requestId":1}"""))
        assertNull(BridgeMessage.parse("""{"type":5}"""))
    }
}
