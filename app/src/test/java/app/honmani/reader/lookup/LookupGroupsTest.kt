package app.honmani.reader.lookup

import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.TermEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LookupGroupsTest {
    private val jmdict = DictionaryEntity(id = 1, title = "JMdict", revision = "1", formatVersion = 3, importedAt = 0)
    private val jitendex = DictionaryEntity(id = 2, title = "Jitendex", revision = "1", formatVersion = 3, importedAt = 0)
    private var nextId = 1L

    private fun entry(dictionary: DictionaryEntity, expression: String, reading: String, gloss: String) = LookupEntry(
        term = TermEntity(
            id = nextId++,
            dictionaryId = dictionary.id,
            expression = expression,
            reading = reading,
            definitionTags = null,
            rules = "",
            score = 0.0,
            glossary = Glossary.plain("""["$gloss"]"""),
            sequence = null,
            termTags = null,
        ),
        dictionary = dictionary,
        source = expression,
        inflections = emptyList(),
        matchedExpression = true,
    )

    @Test
    fun entriesForTheSameWordAreGroupedInRankOrder() {
        val result = LookupResult(
            listOf(
                entry(jmdict, "猫", "ねこ", "cat"),
                entry(jmdict, "ねこ", "ねこ", "cat (kana)"),
                entry(jitendex, "猫", "ねこ", "feline"),
                // JMdict keeps senses as separate rows of the same word.
                entry(jmdict, "猫", "ねこ", "shamisen"),
            ),
        )

        val groups = LookupGroups.from(result)

        assertEquals(listOf("猫", "ねこ"), groups.map { it.expression })
        assertEquals(
            listOf(
                "JMdict" to listOf(Sense(listOf("cat")), Sense(listOf("shamisen"))),
                "Jitendex" to listOf(Sense(listOf("feline"))),
            ),
            groups.first().sections.map { it.dictionaryTitle to it.senses },
        )
    }

    @Test
    fun readingsWithTheSameMeaningsBecomeOneEntry() {
        val result = LookupResult(
            listOf(
                entry(jmdict, "盗人", "ぬすっと", "thief; robber"),
                entry(jitendex, "盗人", "ぬすっと", "thief"),
                entry(jmdict, "盗人", "ぬすと", "thief; robber"),
                entry(jmdict, "盗人", "とうじん", "person who steals"),
                // Jitendex alone has this reading: nothing in the first entry to compare with.
                entry(jitendex, "盗人", "ぬすびと", "burglar"),
            ),
        )

        val groups = LookupGroups.from(result)

        assertEquals(
            listOf(
                Triple("盗人", "ぬすっと", listOf("ぬすと")),
                Triple("盗人", "とうじん", emptyList()),
                Triple("盗人", "ぬすびと", emptyList()),
            ),
            groups.map { Triple(it.expression, it.reading, it.otherReadings) },
        )
    }

    @Test
    fun entriesWithoutAReadingJoinTheSameWordFromAnotherDictionary_inDictionaryOrder() {
        // Moved to the top of the Dictionaries screen.
        val wiktionary = DictionaryEntity(
            id = 3, title = "wty-ja-ja", revision = "1", formatVersion = 3, importedAt = 0, priority = -1,
        )
        val result = LookupResult(
            listOf(
                entry(wiktionary, "杞憂", "", "取り越し苦労"),
                entry(jmdict, "杞憂", "きゆう", "needless anxiety"),
                entry(wiktionary, "猫又", "", "猫の妖怪"),
            ),
        )

        val groups = LookupGroups.from(result)

        assertEquals(listOf("杞憂" to "きゆう", "猫又" to ""), groups.map { it.expression to it.reading })
        assertEquals(listOf("wty-ja-ja", "JMdict"), groups.first().sections.map { it.dictionaryTitle })
    }

    @Test
    fun splitSensesAreNumbered_theSpellingListIsLeftOut_andWordClassesBecomeTags() {
        fun term(tags: String, glossary: String) = TermEntity(
            id = nextId++, dictionaryId = 1, expression = "あっさり", reading = "あっさり", definitionTags = tags,
            rules = "", score = 0.0, glossary = Glossary.plain(glossary), sequence = 1, termTags = null,
        )
        val lines = LookupGroups.lines(
            listOf(
                term("1 adv vs", """["easily", "readily"]"""),
                term("2 adv", """["lightly"]"""),
                term("forms", """["あっさり（★）", "アッサリ"]"""),
            ),
        )

        assertEquals(
            listOf(
                "1." to listOf(GlossItem.Tag("adv"), GlossItem.Tag("vs"), GlossItem.Text("easily")),
                null to listOf(GlossItem.Text("readily")),
                "2." to listOf(GlossItem.Tag("adv"), GlossItem.Text("lightly")),
            ),
            lines.map { it.marker to it.items },
        )
    }

    @Test
    fun grammarWordsAreParticlesAuxiliariesAndTheCopula_notWordsWithAContentClass() {
        fun group(vararg tags: String) = LookupGroup(
            expression = "x",
            reading = "x",
            source = "x",
            inflections = emptyList(),
            sections = listOf(
                DictionarySection(
                    dictionaryTitle = "Jitendex",
                    senses = emptyList(),
                    lines = listOf(GlossLine(tags.map(GlossItem::Tag) + GlossItem.Text("meaning"))),
                ),
            ),
        )

        assertEquals(true, LookupGroups.isGrammarWord(group("particle")))
        assertEquals(true, LookupGroups.isGrammarWord(group("copula", "auxiliary verb")))
        assertEquals(true, LookupGroups.isGrammarWord(group("auxiliary adjective", "suffix")))
        assertEquals(true, LookupGroups.isGrammarWord(group("prt")))
        assertEquals(false, LookupGroups.isGrammarWord(group("noun (common) (futsuumeishi)")))
        assertEquals(false, LookupGroups.isGrammarWord(group("noun", "particle")))
        assertEquals(false, LookupGroups.isGrammarWord(group("adv", "prt")))
        assertEquals(false, LookupGroups.isGrammarWord(group()))
    }

    @Test
    fun theStemIsOnlyShownWhenNothingElseWasUndone() {
        assertEquals(
            listOf(Inflection.POLITE, Inflection.PAST),
            LookupGroups.visibleInflections(listOf(Inflection.STEM, Inflection.POLITE, Inflection.PAST)),
        )
        assertEquals(listOf(Inflection.STEM), LookupGroups.visibleInflections(listOf(Inflection.STEM)))
    }

    @Test
    fun brief_keepsTheFirstThreeMeaningsWithoutExamples() {
        fun line(text: String, marker: String? = null, depth: Int = 1, style: GlossStyle = GlossStyle.GLOSS) =
            GlossLine(listOf(GlossItem.Text(text)), style, depth, marker)
        val lines = listOf(
            line("now", "①"),
            line("今更後悔しても無駄だ。", style = GlossStyle.EXAMPLE),
            line("It's no use regretting it now.", style = GlossStyle.TRANSLATION),
            line("Note: usually kana", style = GlossStyle.NOTE),
            line("afresh", "②"),
            line("sub-sense", "a.", depth = 2),
            line("again", "③"),
            line("fourth", "④"),
            line("its note", style = GlossStyle.NOTE),
        )
        assertEquals(
            listOf("now", "Note: usually kana", "afresh", "sub-sense", "again"),
            LookupGroups.brief(lines).map { (it.items.single() as GlossItem.Text).text },
        )
        // Without markers, everything but the examples is the one meaning.
        assertEquals(1, LookupGroups.brief(listOf(line("a"), line("ex", style = GlossStyle.EXAMPLE))).size)
    }

    @Test
    fun shortTitle_dropsTheBracketedDate() {
        assertEquals("Jitendex.org", LookupGroups.shortTitle("Jitendex.org [2026-10-03]"))
        assertEquals("JMdict", LookupGroups.shortTitle("JMdict"))
        assertEquals("[x]", LookupGroups.shortTitle("[x]"))
    }
}
