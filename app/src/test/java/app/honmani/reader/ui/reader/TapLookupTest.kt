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
        assertEquals(BridgeMessage.Activity, BridgeMessage.parse("""{"type":"reader.activity"}"""))
        assertEquals(BridgeMessage.OpenSettings, BridgeMessage.parse("""{"type":"reader.settings"}"""))
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
