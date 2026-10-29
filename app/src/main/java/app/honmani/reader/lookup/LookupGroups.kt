package app.honmani.reader.lookup

import app.honmani.reader.data.dictionary.TermEntity

/** Senses of one entry from one dictionary. */
data class DictionarySection(
    val dictionaryTitle: String,
    /** Plain meanings, for cards and the word list. */
    val senses: List<Sense>,
    /** The full definitions as drawn in the panel (examples, notes, references). */
    val lines: List<GlossLine> = emptyList(),
)

/**
 * Entries for the same word (written form and reading) from all dictionaries, in rank order.
 */
data class LookupGroup(
    val expression: String,
    val reading: String,
    /** The tapped text this word covers, as written in the book. */
    val source: String,
    /** Grammar undone from the word to [source], from the word outwards. */
    val inflections: List<Inflection>,
    val sections: List<DictionarySection>,
    /** How common the word is, per frequency dictionary. */
    val frequencies: List<Frequency> = emptyList(),
    /** Pitch accent downsteps of [reading] in morae (0: none); empty if unknown. */
    val pitches: List<Int> = emptyList(),
    /** Further readings of the word with the same meanings, merged into this entry. */
    val otherReadings: List<String> = emptyList(),
)

object LookupGroups {
    /**
     * Groups [result] by word, keeping the rank order of each word's best entry; each word's
     * dictionaries are in their order on the Dictionaries screen. An entry
     * without a reading (half the kanji words of the Japanese Wiktionary) joins the best ranked
     * entry with the same written form and a reading, from any dictionary.
     */
    fun from(result: LookupResult, maxGroups: Int = 30): List<LookupGroup> {
        val readings = HashMap<String, String>()
        result.entries.forEach { entry ->
            if (entry.term.reading.isNotEmpty()) readings.putIfAbsent(entry.term.expression, entry.term.reading)
        }
        fun readingOf(term: TermEntity) = term.reading.ifEmpty { readings[term.expression].orEmpty() }
        val groups = result.entries
            .groupBy { it.term.expression to readingOf(it.term) }
            .entries
            .take(maxGroups)
            .map { (word, entries) ->
                val (expression, reading) = word
                val best = entries.first()
                val meta = result.metaOf(expression, reading)
                LookupGroup(
                    expression = expression,
                    reading = reading,
                    source = best.source,
                    inflections = best.inflections,
                    // In the order of the Dictionaries screen, so a dictionary moved to the top
                    // (Japanese definitions, say) is read first.
                    sections = entries
                        .groupBy { it.dictionary.id }
                        .values
                        .sortedWith(compareBy({ it.first().dictionary.priority }, { it.first().dictionary.title }))
                        .map { fromDictionary ->
                            DictionarySection(
                                dictionaryTitle = fromDictionary.first().dictionary.title,
                                senses = fromDictionary.filterNot { it.term.isFormsList() }
                                    .flatMap { GlossaryFormatter.format(it.term.glossary.json) },
                                lines = lines(fromDictionary.map { it.term }),
                            )
                        },
                    frequencies = meta.frequencies,
                    pitches = meta.pitches,
                )
            }
        return mergeReadings(groups)
    }

    /**
     * One entry for readings with the same meanings: a later group of the same written form
     * joins an earlier one when every dictionary that lists it gives the same senses for both
     * (JMdict splits 盗人 into ぬすびと, ぬすっと and ぬすと with one set of meanings). Labels
     * that differ (one reading "dated") are not compared; the earlier group's are kept.
     */
    internal fun mergeReadings(groups: List<LookupGroup>): List<LookupGroup> {
        val merged = mutableListOf<LookupGroup>()
        for (group in groups) {
            val into = merged.indexOfFirst { it.expression == group.expression && sameMeanings(it, group) }
            if (into < 0 || group.reading.isEmpty()) {
                merged += group
            } else {
                val target = merged[into]
                merged[into] = target.copy(otherReadings = target.otherReadings + group.reading)
            }
        }
        return merged
    }

    /** Every section of [other] is also in [group], with the same senses. */
    private fun sameMeanings(group: LookupGroup, other: LookupGroup): Boolean =
        other.sections.isNotEmpty() && group.reading.isNotEmpty() && other.sections.all { section ->
            val match = group.sections.firstOrNull { it.dictionaryTitle == section.dictionaryTitle }
            match != null && match.senses.isNotEmpty() && match.senses == section.senses
        }

    /**
     * The definitions of [terms] (one dictionary's entries for a word) as lines. Entries split
     * into one sense each (as JMdict does) are numbered; a dictionary that keeps word classes
     * in its definition tags (JMdict) shows them as tags.
     */
    internal fun lines(terms: List<TermEntity>): List<GlossLine> {
        val shown = terms.filterNot { it.isFormsList() }
        val perTerm = shown.map { term ->
            val lines = GlossLayout.lines(StructuredContent.parse(term.glossary.json))
            val tags = term.definitionTags.orEmpty().split(' ')
                .filter { it.isNotBlank() && it.toIntOrNull() == null }
            if (tags.isEmpty() || lines.any { line -> line.items.any { it is GlossItem.Tag } } || lines.isEmpty()) {
                lines
            } else {
                listOf(lines.first().copy(items = tags.map(GlossItem::Tag) + lines.first().items)) + lines.drop(1)
            }
        }.filter { it.isNotEmpty() }
        if (perTerm.size <= 1 || perTerm.any { lines -> lines.any { it.marker != null } }) return perTerm.flatten()
        return perTerm.flatMapIndexed { i, lines ->
            lines.mapIndexed { j, line ->
                line.copy(depth = line.depth + 1, marker = if (j == 0) "${i + 1}." else line.marker)
            }
        }
    }

    /**
     * The first [maxSenses] meanings of [lines] without their examples, for the panel's short
     * view. A meaning starts at a marked line ("①", "1.") of the outermost marked depth;
     * without markers the whole definition is one meaning.
     */
    fun brief(lines: List<GlossLine>, maxSenses: Int = BRIEF_SENSES): List<GlossLine> {
        val outer = lines.filter { it.marker != null }.minOfOrNull { it.depth }
        var senses = 0
        val kept = ArrayList<GlossLine>()
        for (line in lines) {
            if (outer != null && line.marker != null && line.depth == outer) senses++
            if (senses > maxSenses) break
            if (line.style != GlossStyle.EXAMPLE && line.style != GlossStyle.TRANSLATION) kept += line
        }
        return kept
    }

    const val BRIEF_SENSES = 3

    /** "Jitendex.org [2026-10-03]" → "Jitendex.org": the date in brackets is noise in the panel. */
    fun shortTitle(title: String): String {
        if (!title.endsWith("]")) return title
        val start = title.lastIndexOf(" [")
        return if (start > 0) title.substring(0, start) else title
    }

    /** JMdict lists a word's spellings as an entry of its own ("forms"); the headword shows them. */
    private fun TermEntity.isFormsList(): Boolean = definitionTags?.split(' ')?.contains("forms") == true

    /**
     * The inflections worth showing: the 連用形 stem is only an intermediate step, so it is
     * listed only when nothing else is.
     */
    fun visibleInflections(inflections: List<Inflection>): List<Inflection> =
        inflections.filter { it != Inflection.STEM }.ifEmpty { inflections }

    /**
     * Whether [group] is a grammar word (の, です, ない): the word classes of its first meaning,
     * from the first dictionary that labels them, are particles, auxiliaries, the copula or
     * conjunctions, and none is a noun, verb, adjective or adverb. Jitendex writes the classes
     * out ("particle"), JMdict as codes ("prt"). Unlabelled words are not grammar words.
     */
    fun isGrammarWord(group: LookupGroup): Boolean {
        val tags = group.sections.firstNotNullOfOrNull { section ->
            section.lines.firstOrNull()?.items?.filterIsInstance<GlossItem.Tag>()?.map { it.text }?.ifEmpty { null }
        } ?: return false
        return tags.any { it in GRAMMAR_CLASSES } && tags.none { it !in GRAMMAR_CLASSES && isContentClass(it) }
    }

    private fun isContentClass(tag: String): Boolean =
        CONTENT_WORDS.any { tag.contains(it) } || CONTENT_CODE.matches(tag)

    private val GRAMMAR_CLASSES = setOf(
        "particle", "prt", "auxiliary", "aux", "auxiliary verb", "aux-v", "auxiliary adjective", "aux-adj",
        "copula", "cop", "conjunction", "conj", "助詞", "助動詞", "接続詞",
    )
    private val CONTENT_WORDS = listOf("noun", "verb", "adjective", "adverb", "pronoun", "interjection", "名詞", "動詞", "形容詞", "副詞")
    private val CONTENT_CODE = Regex("n|n-.*|pn|v.*|adj-.*|adv.*|int")
}
