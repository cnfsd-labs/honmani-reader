package app.honmani.reader.known

import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.words.KnownCandidate
import app.honmani.reader.lookup.DictionaryLookup
import app.honmani.reader.lookup.GlossaryFormatter
import app.honmani.reader.lookup.LookupEntry

/** A word to look for: the text as written, and its reading if known. */
data class WantedWord(val text: String, val reading: String? = null)

/**
 * What [KnownWordResolver.resolve] found: the words to mark ([words]), what no dictionary knows
 * ([unmatched], as written) and how many were left out as names ([names]).
 */
data class ResolvedWords(val words: List<KnownCandidate>, val unmatched: List<String>, val names: Int = 0)

/**
 * Turns written words into the dictionary forms and readings Honmani saves (the same ones a
 * lookup in a book saves), with the first meaning. A word is found by its written form, else as
 * a reading, else (if [deinflect]) as an inflected form. Several readings are all taken only if
 * the dictionary gives them the same meanings; otherwise the most common one is. Names are left out.
 */
class KnownWordResolver(private val lookup: DictionaryLookup) {
    /** Resolves [wanted] in the dictionaries; [onProgress] gets the number of words done so far. */
    suspend fun resolve(
        wanted: List<WantedWord>,
        deinflect: Boolean,
        onProgress: (Int) -> Unit = {},
    ): ResolvedWords {
        val words = LinkedHashMap<String, KnownCandidate>()
        val unmatched = ArrayList<String>()
        var names = 0
        var done = 0
        for (chunk in wanted.chunked(CHUNK)) {
            val entries = lookup.entriesFor(chunk.flatMap { listOfNotNull(it.text, it.reading) })
            val byExpression = entries.groupBy { it.term.expression }
            val byReading = entries.filter { it.term.reading != it.term.expression }.groupBy { it.term.reading }
            for (word in chunk) {
                val written = byExpression[word.text].orEmpty().sortedWith(ORDER)
                val candidates = written.filterNot { it.isName() }
                val chosen: List<LookupEntry> = when {
                    candidates.isNotEmpty() -> candidates
                    written.isNotEmpty() -> emptyList()
                    else -> byReading[word.text].orEmpty().filterNot { it.isName() }.sortedWith(ORDER).take(1)
                }
                val found = if (chosen.isNotEmpty()) {
                    pick(chosen, word.reading)
                } else if (written.isNotEmpty()) {
                    names++
                    null
                } else if (deinflect) {
                    deinflected(word)
                } else {
                    null
                }
                if (found != null && found.isEmpty()) {
                    names++
                } else if (found != null) {
                    for (candidate in found) words.putIfAbsent("${candidate.expression}\u0000${candidate.reading}", candidate)
                } else if (written.isEmpty() || candidates.isNotEmpty()) {
                    unmatched += word.text
                }
                onProgress(++done)
            }
        }
        return ResolvedWords(words.values.toList(), unmatched, names)
    }

    /**
     * For each of [words]: the word class codes ([PartsOfSpeech.codes]) of each of its entries in the
     * dictionaries (the entries of its reading where it has any), names left out. Empty where no
     * dictionary has the word. One batch of queries for all words.
     */
    suspend fun wordClasses(words: List<WantedWord>): List<List<Set<String>>> {
        val byExpression = lookup.entriesFor(words.map { it.text }).filterNot { DictionaryCatalog.isNames(it.dictionary.title) }
            .groupBy { it.term.expression }
        return words.map { word ->
            val written = byExpression[word.text].orEmpty()
            val reading = word.reading?.let(::hiragana)
            val ofReading = if (reading == null) written else written.filter { hiragana(it.term.reading) == reading }.ifEmpty { written }
            ofReading.filterNot { it.isFormsList() }.map { entry ->
                val term = lookup.unpack(entry).term
                PartsOfSpeech.codes(term.definitionTags, term.glossary.json)
            }
        }
    }

    /** An inflected form (食べた, 高くない): the dictionary form it comes from, if the whole text is one word. */
    private suspend fun deinflected(word: WantedWord): List<KnownCandidate>? {
        val result = lookup.lookup(word.text, maxLength = word.text.length)
        val entries = result.entries.filter { it.source == word.text && !it.isName() }
        if (entries.isEmpty()) return null
        val expression = entries.first().term.expression
        return pick(entries.filter { it.term.expression == expression }, word.reading)
    }

    /**
     * The words among [entries] (best first, one written form): the reading asked for, else the
     * most common reading plus every reading with the same meanings. Empty: a name.
     */
    private suspend fun pick(entries: List<LookupEntry>, reading: String?): List<KnownCandidate> {
        val real = entries.filterNot { it.isFormsList() }.ifEmpty { entries }
        val unpacked = real.map { lookup.unpack(it) }
        val groups = unpacked.groupBy { it.term.reading }.entries.toList()
        val first = groups.first()
        val wanted = reading?.let(::hiragana)?.let { r -> groups.firstOrNull { hiragana(it.key) == r } }
        val chosen = when {
            wanted != null -> listOf(wanted)
            groups.size == 1 -> listOf(first)
            else -> {
                val expression = first.value.first().term.expression
                val rank = lookup.frequencies(groups.map { expression to it.key })
                val best = groups.minByOrNull { rank["$expression\u0000${it.key}"]?.rank ?: Double.MAX_VALUE } ?: first
                val senses = senses(best.value)
                listOf(best) + groups.filter { it !== best && senses(it.value) == senses }
            }
        }
        // A name by its sense (found only now that the glossary is unpacked): an empty list.
        if (chosen.first().value.any { it.isName() }) return emptyList()
        return chosen.map { (readingOf, group) ->
            KnownCandidate(group.first().term.expression, readingOf.ifEmpty { group.first().term.expression }, gloss(group))
        }
    }

    private fun senses(group: List<LookupEntry>): List<List<String>> =
        group.flatMap { GlossaryFormatter.format(it.term.glossary.json) }.map { it.glosses }

    private fun gloss(group: List<LookupEntry>): String =
        senses(group).firstOrNull()?.joinToString("; ").orEmpty()

    /** A name: from a names dictionary, or a sense the dictionary tags as a name (surname, place, ...). */
    private fun LookupEntry.isName(): Boolean {
        if (DictionaryCatalog.isNames(dictionary.title)) return true
        val json = term.glossary.takeUnless { it.isPacked }?.json ?: return false
        return NAME_CODE.containsMatchIn(json)
    }

    private fun LookupEntry.isFormsList(): Boolean = term.definitionTags?.split(' ')?.contains("forms") == true

    private fun hiragana(text: String): String =
        buildString { for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }

    companion object {
        private const val CHUNK = 300

        /** Dictionary priority, then the higher score, as a lookup orders the same word. */
        private val ORDER: Comparator<LookupEntry> = compareBy<LookupEntry>({ it.dictionary.priority }, { it.dictionary.title })
            .thenByDescending { it.term.score }
            .thenBy { it.term.sequence ?: Long.MAX_VALUE }
            .thenBy { it.term.id }

        /** JMdict name types (not usage tags such as "fem" or "male", which common words carry). */
        private val NAME_CODE = Regex(
            "\"code\":\"(surname|place|person|given|product|company|organization|station|work|char|creat|ev|fic|obj|group|leg|myth|oth|relig|serv|unc)\"",
        )
    }
}
