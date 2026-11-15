package app.honmani.reader.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlossLayoutTest {
    /** Lines as text: depth, marker, style, and items (`<tag>`, `漢{かん}`, `*keyword*`, `[link](word)`). */
    private fun render(glossary: String): List<String> =
        GlossLayout.lines(StructuredContent.parse(glossary)).map { line ->
            val items = line.items.joinToString("") { item ->
                when (item) {
                    is GlossItem.Tag -> "<${item.text}>"
                    is GlossItem.Ruby -> (if (item.keyword) "*" else "") + "${item.base}{${item.reading}}" +
                        (if (item.keyword) "*" else "") + (item.query?.let { "($it)" } ?: "")
                    is GlossItem.Text -> when {
                        item.query != null -> "[${item.text}](${item.query})"
                        item.keyword -> "*${item.text}*"
                        else -> item.text
                    }
                }
            }
            "${line.depth} ${line.marker ?: "-"} ${line.style} $items"
        }

    @Test
    fun plainDefinitionsAreOneLineEach() {
        assertEquals(listOf("0 - GLOSS to eat", "0 - GLOSS to live on"), render("""["to eat", "to live on"]"""))
        assertEquals(listOf("0 - GLOSS ⟶ [食べる](食べる)"), render("""[["食べる", ["v1"]]]"""))
    }

    @Test
    fun sensesWithTagsGlossariesExamplesAndReferences() {
        val glossary = """[{"type":"structured-content","content":[
            {"tag":"div","data":{"content":"sense-group"},"content":[
                {"tag":"span","data":{"class":"tag","content":"part-of-speech-info"},"content":"noun"},
                {"tag":"ol","content":[
                    {"tag":"li","style":{"listStyleType":"\"①\""},"data":{"content":"sense"},"content":[
                        {"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"cat"},{"tag":"li","content":"feline"}]},
                        {"tag":"div","data":{"content":"extra-info"},"content":[
                            {"tag":"div","data":{"content":"example-sentence"},"content":[
                                {"tag":"div","data":{"content":"example-sentence-a"},"content":["その",
                                    {"tag":"span","data":{"content":"example-keyword"},"content":{"tag":"ruby","content":["猫",{"tag":"rt","content":"ねこ"}]}},
                                    "は白い。"]},
                                {"tag":"div","data":{"content":"example-sentence-b"},"content":"That cat is white."}]},
                            {"tag":"div","data":{"content":"sense-note"},"content":[
                                {"tag":"div","data":{"content":"sense-note-label"},"content":"Note"},
                                {"tag":"div","data":{"content":"sense-note-content"},"content":"also a family name"}]}]}]},
                    {"tag":"li","style":{"listStyleType":"\"②\""},"data":{"content":"sense"},"content":[
                        {"tag":"span","data":{"class":"tag","content":"misc-info"},"content":"slang"},
                        {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"geisha"}},
                        {"tag":"div","data":{"content":"xref"},"content":[
                            {"tag":"span","data":{"content":"reference-label"},"content":"See also"},
                            {"tag":"a","href":"?query=%E7%8C%AB%E8%BB%8A&wildcards=off","content":[{"tag":"ruby","content":["猫",{"tag":"rt","content":"ねこ"}]},"車"]}]}]}]}]},
            {"tag":"div","data":{"content":"attribution"},"content":"JMdict"},
            {"tag":"img","path":"a.avif"}]}]"""

        assertEquals(
            listOf(
                "0 - GLOSS <noun>",
                "1 ① GLOSS cat; feline",
                "1 - EXAMPLE その*猫{ねこ}*は白い。",
                "1 - TRANSLATION That cat is white.",
                "1 - NOTE Note: also a family name",
                "1 ② GLOSS <slang>geisha",
                "1 - NOTE See also 猫{ねこ}(猫車)[車](猫車)",
            ),
            render(glossary),
        )
    }

    @Test
    fun wiktionarySensesWithLabelsExamplesAndSynonyms_withoutOriginAndWebLinks() {
        assertEquals(
            listOf(
                "1 1. GLOSS <非形式的>あやうい。あぶない。",
                "1 - EXAMPLE 今会うのは非常にやばい。",
                "1 - NOTE （出典）",
                "1 2. GLOSS すばらしい。",
                "0 - NOTE 類義語: [危ない](危ない)、[危うい](危うい)",
            ),
            render(WIKTIONARY_YABAI),
        )
        val example = GlossLayout.lines(StructuredContent.parse(WIKTIONARY_YABAI))[1]
        assertEquals(listOf(false, true, false), example.items.map { (it as GlossItem.Text).bold })
    }

    @Test
    fun listsWithoutMarkersAreNumberedOrBulleted() {
        val glossary = """[{"type":"structured-content","content":[
            {"tag":"ol","content":[{"tag":"li","content":"first"},{"tag":"li","content":"second"}]},
            {"tag":"ul","content":[{"tag":"li","content":"point"}]}]}]"""
        assertEquals(listOf("1 1. GLOSS first", "1 2. GLOSS second", "1 • GLOSS point"), render(glossary))
    }

    @Test
    fun aVeryDeeplyNestedEntryGivesNothing_insteadOfOverflowingTheStack() {
        // kotlinx-serialization refuses JSON nested deeper than 400 levels (maxNestingDepth), so
        // the recursive walks below never see more: keep it that way when the library changes.
        val levels = 20_000
        val deep = """{"tag":"span","content":""".repeat(levels) + "\"deep\"" + "}".repeat(levels)
        val glossary = """[{"type":"structured-content","content":[{"tag":"div","content":"shallow"},$deep]}]"""

        assertEquals(emptyList<String>(), render(glossary))
        assertEquals(emptyList<Sense>(), GlossaryFormatter.format(glossary))
        assertNull(Redirect.target(-1, glossary))
    }

    @Test
    fun brokenGlossariesGiveNothing() {
        assertEquals(emptyList<String>(), render("not json"))
        assertEquals(emptyList<String>(), render("""[{"type":"image","path":"a.png"}]"""))
    }

    @Test
    fun onlyLinksWithinTheDictionaryAreLookups() {
        assertEquals("猫車", StructuredContent.query("?query=%E7%8C%AB%E8%BB%8A&wildcards=off"))
        assertNull(StructuredContent.query("https://tatoeba.org/en/sentences/show/1"))
    }

    @Test
    fun tagsLast_movesLeadingLabelsAfterTheMeaning() {
        val line = GlossLine(listOf(GlossItem.Tag("noun"), GlossItem.Tag("suru"), GlossItem.Text("experiment")), marker = "①")
        assertEquals(
            listOf(GlossItem.Text("experiment"), GlossItem.Tag("noun"), GlossItem.Tag("suru")),
            line.tagsLast().items,
        )
        assertEquals("①", line.tagsLast().marker)
        val labelsOnly = GlossLine(listOf(GlossItem.Tag("noun")))
        assertEquals(labelsOnly, labelsOnly.tagsLast())
        val inside = GlossLine(listOf(GlossItem.Text("to eat"), GlossItem.Tag("colloquial"), GlossItem.Text("(rare)")))
        assertEquals(inside, inside.tagsLast())
    }
}
