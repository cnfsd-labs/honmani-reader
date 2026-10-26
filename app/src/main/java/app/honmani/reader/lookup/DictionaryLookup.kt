package app.honmani.reader.lookup

import app.honmani.reader.data.dictionary.DictionaryDao
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.GlossaryPacker
import app.honmani.reader.data.dictionary.KanjiDao
import app.honmani.reader.data.dictionary.MAX_LOOKUP_TEXTS
import app.honmani.reader.data.dictionary.TermDao
import app.honmani.reader.data.dictionary.TermEntity
import app.honmani.reader.data.dictionary.TermMetaDao
import app.honmani.reader.data.dictionary.TermMetaEntity

/** A dictionary entry that matches the start of the looked-up text. */
data class LookupEntry(
    val term: TermEntity,
    val dictionary: DictionaryEntity,
    /** The part of the looked-up text this entry covers, as written there. */
    val source: String,
    /** Grammar undone to get from [source] to the entry, from the entry outwards. */
    val inflections: List<Inflection>,
    /** True if [source] matched the entry's written form, false if only its reading. */
    val matchedExpression: Boolean,
    /** True if the book's furigana over [source] gives this entry's reading. */
    val matchesFurigana: Boolean = false,
)

/** How common a word is according to one frequency dictionary. */
data class Frequency(
    val dictionaryTitle: String,
    /** As the dictionary shows it ("395", "1㋕"). */
    val display: String,
    /** Lower is more common; null if the dictionary counts occurrences or gives only text. */
    val rank: Double?,
) {
    /** "Jiten #395", as on cards. */
    val label: String get() = "$dictionaryTitle #$display"
}

/** Frequency and pitch accent of one word (written form and reading). */
data class WordMeta(
    /** One per frequency dictionary, in dictionary order. */
    val frequencies: List<Frequency> = emptyList(),
    /** Downstep positions in morae (0: no downstep), from the first pitch dictionary that has them. */
    val pitches: List<Int> = emptyList(),
) {
    /** The best rank among the frequency dictionaries, for ordering; null if none ranks the word. */
    val rank: Double? get() = frequencies.mapNotNull { it.rank }.minOrNull()

    companion object {
        val NONE = WordMeta()
    }
}

data class LookupResult(
    /** Best first. */
    val entries: List<LookupEntry>,
    /** False if no dictionary is installed and enabled, so nothing could match. */
    val dictionariesAvailable: Boolean = true,
    /** Frequency and pitch of the entries' words, by [metaKey]. */
    val meta: Map<String, WordMeta> = emptyMap(),
) {
    fun metaOf(expression: String, reading: String): WordMeta = meta[metaKey(expression, reading)] ?: WordMeta.NONE

    /** Length of the longest match, for highlighting it in the book; 0 if nothing matched. */
    val matchedLength: Int get() = entries.firstOrNull()?.source?.length ?: 0
}

/** Key of a word's frequency and pitch in [LookupResult.meta]. */
fun metaKey(expression: String, reading: String): String = "$expression\u0000$reading"

/**
 * Finds dictionary entries for the text after a tap, Yomitan-style: every prefix of the text is
 * deinflected, candidates are looked up by written form and reading, entries whose word class
 * does not fit the deinflection are dropped, and the rest are ranked, longest match first.
 * Furigana in the book, if any, ranks the entries with that reading first, above longer matches
 * it contradicts.
 */
class DictionaryLookup(
    private val dictionaryDao: DictionaryDao,
    private val termDao: TermDao,
    private val termMetaDao: TermMetaDao? = null,
    private val kanjiDao: KanjiDao? = null,
) {
    private data class Candidate(val source: String, val deinflection: Deinflection)

    /**
     * Presets of the dictionaries seen, by id and import time (an id can come back after a
     * delete); an empty array for a dictionary without one.
     */
    private val presets = HashMap<Pair<Long, Long>, ByteArray>()

    private suspend fun presetOf(dictionary: DictionaryEntity): ByteArray? {
        val key = dictionary.id to dictionary.importedAt
        val preset = synchronized(presets) { presets[key] }
            ?: (termDao.glossaryPreset(dictionary.id) ?: ByteArray(0)).also { synchronized(presets) { presets[key] = it } }
        return preset.takeIf { it.isNotEmpty() }
    }

    /** [term] with its glossary unpacked, so [Glossary.json] reads it. */
    private suspend fun unpacked(term: TermEntity, dictionary: DictionaryEntity): TermEntity =
        if (term.glossary.isPacked) {
            term.copy(glossary = Glossary.plain(GlossaryPacker.unpack(term.glossary, presetOf(dictionary))))
        } else {
            term
        }

    suspend fun lookup(
        text: String,
        ruby: List<RubySpan> = emptyList(),
        maxLength: Int = MAX_SOURCE_LENGTH,
    ): LookupResult {
        val dictionaries = dictionaryDao.enabledReady().associateBy { it.id }
        // Frequency, pitch and kanji dictionaries alone can't find words.
        if (dictionaries.values.none { it.termCount > 0 }) {
            return LookupResult(emptyList(), dictionariesAvailable = false)
        }

        val readingPrefixes = HashMap<String, String?>()
        fun readingPrefix(source: String) =
            readingPrefixes.getOrPut(source) { Furigana.readingPrefix(source, ruby) }

        suspend fun match(candidates: List<Candidate>): List<LookupEntry> {
            val candidatesByText = candidates.groupBy { it.deinflection.text }
            val terms = candidatesByText.keys.chunked(MAX_LOOKUP_TEXTS)
                .flatMap { termDao.findByExpressionOrReading(it) }
                .distinctBy { it.id }
            return terms.mapNotNull { term ->
                val dictionary = dictionaries[term.dictionaryId] ?: return@mapNotNull null
                val termClasses = WordClass.fromRules(term.rules)
                val matches = (candidatesByText[term.expression].orEmpty().map { it to true }) +
                    (candidatesByText[term.reading].orEmpty().map { it to false })
                matches
                    .filter { (candidate, _) -> candidate.deinflection.fits(termClasses) }
                    .maxWithOrNull(matchOrder)
                    ?.let { (candidate, matchedExpression) ->
                        LookupEntry(
                            term = term,
                            dictionary = dictionary,
                            source = candidate.source,
                            inflections = candidate.deinflection.inflections,
                            matchedExpression = matchedExpression,
                            matchesFurigana = readingPrefix(candidate.source)?.let { prefix ->
                                Furigana.agrees(prefix, term.reading.ifEmpty { term.expression })
                            } ?: false,
                        )
                    }
            }
        }

        // Only the entries that matched are unpacked.
        suspend fun resolved(matched: List<LookupEntry>) =
            followPointers(matched.map { it.copy(term = unpacked(it.term, it.dictionary)) }) { source, reading ->
                readingPrefix(source)?.let { Furigana.agrees(it, reading) } ?: false
            }

        var matched = match(candidates(text, maxLength))
        // Older books write more okurigana than dictionaries do (驚ろいて, 困まる): if nothing
        // longer than the leading kanji matched, try again without the kana right after them.
        val kanjiEnd = leadingKanjiEnd(text)
        if (kanjiEnd > 0 && (matched.maxOfOrNull { it.source.length } ?: 0) <= kanjiEnd) {
            matched = (matched + match(candidates(text, maxLength, dropKanaAt = kanjiEnd))).distinctBy { it.term.id }
        }
        var (entries, pointers) = resolved(matched)
        // Furigana that is no reading of the word as written names the word meant: 宅 read うち
        // is 家, 本気 read マジ is マジ. Then the furigana's word is looked up too, its longest
        // match only (not every word read like the first kana).
        val byFurigana = match(furiganaCandidates(text, ruby, entries))
        // Hiragana furigana over kanji names a word written in kanji: 宅 read うち is 家, not the
        // prefix うち written in kana, which would otherwise win as matched by its written form.
        // Katakana furigana names the katakana word itself (停車場 read ステーション).
        var kanaOnlyNamed = emptySet<Long>()
        if (byFurigana.isNotEmpty()) {
            val longest = byFurigana.maxOf { it.source.length }
            val (moreEntries, morePointers) = resolved(byFurigana.filter { it.source.length == longest })
            val first = ruby.first()
            val hiraganaOverKanji = first.reading.all(JapaneseText::isHiragana) &&
                text.substring(first.start, first.end).codePoints().anyMatch(JapaneseText::isKanji)
            if (hiraganaOverKanji) {
                kanaOnlyNamed = moreEntries
                    .filter { entry -> entry.term.expression.codePoints().noneMatch(JapaneseText::isKanji) }
                    .mapTo(HashSet()) { it.term.id }
            }
            entries = (entries + moreEntries.map { it.copy(matchesFurigana = true) }).distinctBy { it.term.id }
            pointers = pointers + morePointers
        }
        val meta = meta(entries, dictionaries)
        // An entry the book's furigana contradicts ranks below every entry it agrees with, even a
        // shorter one: 誰が with だれ over 誰 is 誰 + が, not the archaic 誰が (たが). With no
        // entry agreeing, the longest match still comes first.
        val furiganaDecides = entries.any { it.matchesFurigana }
        fun contradicted(entry: LookupEntry) =
            furiganaDecides && !entry.matchesFurigana && readingPrefix(entry.source) != null
        val order = compareBy<LookupEntry> { contradicted(it) }
            .thenByDescending { it.source.length }
            // A pointer whose target is missing says nothing itself.
            .thenBy { it.term.id in pointers }
            .thenByDescending { it.matchesFurigana }
            .thenBy { it.term.id in kanaOnlyNamed }
            .thenBy { it.inflections.size }
            .thenByDescending { it.matchedExpression }
            // The more common word first (by the frequency dictionaries, if any are installed).
            .thenBy { meta[metaKey(it.term.expression, it.term.reading)]?.rank ?: Double.MAX_VALUE }
            .then(entryOrder)
        return LookupResult(entries.sortedWith(order), meta = meta)
    }

    /**
     * [entries] with each pointer entry ([Redirect]) replaced by the entries it points to in the
     * same dictionary, matched as the pointer was: 生れた shows 生まれる's definitions, not
     * "⟶ 生れる". Also returns the ids of pointers whose target is missing.
     */
    private suspend fun followPointers(
        entries: List<LookupEntry>,
        matchesFurigana: (source: String, reading: String) -> Boolean,
    ): Pair<List<LookupEntry>, Set<Long>> {
        val targets = entries
            .mapNotNull { entry -> Redirect.target(entry.term.sequence, entry.term.glossary.json)?.let { entry.term.id to it } }
            .toMap()
        if (targets.isEmpty()) return entries to emptySet()
        val found = targets.values.distinct().chunked(MAX_LOOKUP_TEXTS)
            .flatMap { termDao.findByExpressionOrReading(it) }
        val unresolved = HashSet<Long>()
        val resolved = entries.flatMap { entry ->
            val target = targets[entry.term.id] ?: return@flatMap listOf(entry to false)
            val sequence = entry.term.sequence
            val terms = found
                .filter {
                    it.dictionaryId == entry.term.dictionaryId && it.expression == target &&
                        (sequence == null || sequence >= 0 || it.sequence == -sequence)
                }
                .map { unpacked(it, entry.dictionary) }
                // One step only: a pointer to a pointer is left alone.
                .filter { Redirect.target(it.sequence, it.glossary.json) == null }
            if (terms.isEmpty()) {
                unresolved += entry.term.id
                listOf(entry to false)
            } else {
                terms.map { term ->
                    entry.copy(
                        term = term,
                        matchesFurigana = matchesFurigana(entry.source, term.reading.ifEmpty { term.expression }),
                    ) to true
                }
            }
        }
        // A target the text also matched directly shows once, preferably as matched directly
        // (食べた as 食べる's past form rather than as the form-of entry's lemma).
        val order = compareByDescending<Pair<LookupEntry, Boolean>> { it.first.source.length }
            .thenBy { it.second }
            .thenBy(entryOrder) { it.first }
        return resolved.sortedWith(order).distinctBy { it.first.term.id }.map { it.first } to unresolved
    }

    /**
     * What the enabled kanji dictionaries say about the kanji in [text], by character; the
     * first dictionary (by order) that has a character wins.
     */
    suspend fun kanji(text: String): Map<String, KanjiInfo> {
        val dao = kanjiDao ?: return emptyMap()
        val characters = JapaneseText.kanjiIn(text).take(MAX_LOOKUP_TEXTS)
        if (characters.isEmpty()) return emptyMap()
        val dictionaries = dictionaryDao.enabledReady().filter { it.kanjiCount > 0 }.associateBy { it.id }
        if (dictionaries.isEmpty()) return emptyMap()
        val dictionaryOrder = compareBy<DictionaryEntity>({ it.priority }, { it.title })
        return dao.find(characters)
            .filter { it.dictionaryId in dictionaries }
            .groupBy { it.character }
            .mapValues { (_, rows) ->
                val row = rows.minWith(compareBy(dictionaryOrder) { dictionaries.getValue(it.dictionaryId) })
                KanjiInfo.from(row, dictionaries.getValue(row.dictionaryId))
            }
    }

    /** Frequency and pitch of each word among [entries], from the enabled meta dictionaries. */
    private suspend fun meta(
        entries: List<LookupEntry>,
        dictionaries: Map<Long, DictionaryEntity>,
    ): Map<String, WordMeta> {
        val dao = termMetaDao ?: return emptyMap()
        if (entries.isEmpty() || dictionaries.values.none { it.metaCount > 0 }) return emptyMap()
        val words = entries.map { it.term.expression to it.term.reading }.distinct()
        val rows = words.map { it.first }.distinct().chunked(MAX_LOOKUP_TEXTS)
            .flatMap { dao.find(it) }
            .filter { it.dictionaryId in dictionaries }
        if (rows.isEmpty()) return emptyMap()
        val byExpression = rows.groupBy { it.expression }
        val dictionaryOrder = compareBy<DictionaryEntity>({ it.priority }, { it.title })
        return words.associate { (expression, reading) ->
            val matching = byExpression[expression].orEmpty().filter { it.reading == null || it.reading == reading }
            metaKey(expression, reading) to wordMeta(matching, dictionaries, dictionaryOrder)
        }.filterValues { it != WordMeta.NONE }
    }

    private fun wordMeta(
        rows: List<TermMetaEntity>,
        dictionaries: Map<Long, DictionaryEntity>,
        dictionaryOrder: Comparator<DictionaryEntity>,
    ): WordMeta {
        if (rows.isEmpty()) return WordMeta.NONE
        val byDictionary = rows.groupBy { dictionaries.getValue(it.dictionaryId) }.toSortedMap(dictionaryOrder)
        val frequencies = byDictionary.mapNotNull { (dictionary, dictionaryRows) ->
            val frequency = dictionaryRows.filter { it.kind == TermMetaEntity.Kind.FREQUENCY }
                // A row for this reading says more than one for any reading; then the most common.
                .sortedWith(
                    compareBy<TermMetaEntity> { it.reading == null }.thenBy {
                        if (dictionary.ranksFrequency) it.value ?: Double.MAX_VALUE else -(it.value ?: 0.0)
                    },
                )
                .firstOrNull() ?: return@mapNotNull null
            val display = frequency.display ?: frequency.value?.let(::formatNumber) ?: return@mapNotNull null
            Frequency(dictionary.title, display, frequency.value.takeIf { dictionary.ranksFrequency })
        }
        val pitches = byDictionary.values.firstNotNullOfOrNull { dictionaryRows ->
            dictionaryRows.filter { it.kind == TermMetaEntity.Kind.PITCH }
                .flatMap { row -> row.pitches.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() } }
                .distinct()
                .ifEmpty { null }
        }.orEmpty()
        return WordMeta(frequencies, pitches)
    }

    /** Whether lower frequency values of this dictionary mean more common words. */
    private val DictionaryEntity.ranksFrequency: Boolean
        get() = frequencyMode != DictionaryEntity.OCCURRENCE_BASED

    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && value < 1e15) value.toLong().toString() else value.toString()

    /**
     * Candidates for the furigana's own word, when furigana starts [text] and none of the
     * [entries] that hold its base agrees with it: the furigana followed by the hiragana after the
     * base (失策 read しま + った is しまった), matched as the base and that hiragana. None if the
     * furigana is not all kana (emphasis dots, glosses).
     */
    private fun furiganaCandidates(text: String, ruby: List<RubySpan>, entries: List<LookupEntry>): List<Candidate> {
        val first = ruby.firstOrNull()?.takeIf { it.start == 0 && it.end <= text.length } ?: return emptyList()
        if (entries.any { it.source.length >= first.end && it.matchesFurigana }) return emptyList()
        if (!first.reading.all(JapaneseText::isKana)) return emptyList()
        var kanaEnd = first.end
        while (kanaEnd < text.length && JapaneseText.isHiragana(text[kanaEnd]) && ruby.none { it.start == kanaEnd }) kanaEnd++
        // Katakana furigana as written too: dictionaries read ステーション in katakana.
        val readings = setOf(first.reading, JapaneseText.toHiragana(first.reading))
        return (kanaEnd downTo first.end).flatMap { end ->
            readings.flatMap { reading ->
                Deinflector.deinflect(reading + text.substring(first.end, end)).map { Candidate(text.substring(0, end), it) }
            }
        }
    }

    /**
     * Where the kana after the kanji that start [text] begin (as a UTF-16 index), or 0 if [text]
     * does not start with kanji followed by a hiragana that could be okurigana: a particle (私は)
     * is none.
     */
    private fun leadingKanjiEnd(text: String): Int {
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            if (!JapaneseText.isKanji(codePoint) && codePoint != '々'.code) break
            end += Character.charCount(codePoint)
        }
        val okurigana = end > 0 && end < text.length && JapaneseText.isHiragana(text[end]) && text[end] !in PARTICLES
        return if (okurigana) end else 0
    }

    /**
     * Prefixes of [text] (whole code points, longest first) with their deinflections, each also
     * normalized ([JapaneseText.normalize]), in hiragana and without emphasis
     * ([JapaneseText.emphaticForms]). With [dropKanaAt] (a UTF-16 index of a kana), only the
     * prefixes that go past it, without that kana.
     */
    private fun candidates(text: String, maxLength: Int, dropKanaAt: Int? = null): List<Candidate> {
        val codePoints = text.codePoints().toArray()
        val drop = dropKanaAt?.let { text.codePointCount(0, it) }
        val ends = (minOf(codePoints.size, maxLength) downTo 1)
            .filter { String(codePoints, 0, it).isNotBlank() }
            // At least one more kana after the dropped one (困ま alone is no 困), and only kana.
            .filter { end ->
                drop == null ||
                    end > drop + 1 && (drop + 1 until end).all { JapaneseText.isHiragana(codePoints[it].toChar()) }
            }
        return ends.flatMap { end ->
            val written = String(codePoints, 0, end)
            val looked = if (drop == null) written else String(codePoints, 0, drop) + String(codePoints, drop + 1, end - drop - 1)
            // A clipped word (痛っ) ends the text or comes before punctuation, never before more kana.
            val clipped = end == codePoints.size || !isWordCharacter(codePoints[end])
            val variants = buildSet {
                val normalized = JapaneseText.normalize(looked)
                add(looked)
                add(normalized)
                // Only on the text as written: katakana words in hiragana would lose their ー
                // (ノート is no のと).
                addAll(JapaneseText.emphaticForms(normalized, clipped))
                // Katakana words (ハマる) also in hiragana, but not after kanji: 赤シ is no 赤し.
                if (JapaneseText.containsKatakana(normalized) && normalized.codePoints().noneMatch(JapaneseText::isKanji)) {
                    add(JapaneseText.toHiragana(normalized))
                }
            }
            variants.filter { it.isNotBlank() }
                .flatMap { variant -> Deinflector.deinflect(variant).map { Candidate(written, it) } }
        }
    }

    private fun isWordCharacter(codePoint: Int): Boolean =
        JapaneseText.isKanji(codePoint) || codePoint == '々'.code ||
            (Character.isBmpCodePoint(codePoint) && JapaneseText.isKana(codePoint.toChar()))

    private fun Deinflection.fits(termClasses: Int): Boolean =
        conditions == WordClass.ANY || conditions and termClasses != 0

    private companion object {
        /** Matches the tap bridge's scan length. */
        const val MAX_SOURCE_LENGTH = 20

        /** Hiragana after a kanji that are particles rather than okurigana. */
        const val PARTICLES = "はがをにへとでものや"

        /** Better match for one term: longer source, then fewer inflections, then written form. */
        val matchOrder: Comparator<Pair<Candidate, Boolean>> =
            compareBy<Pair<Candidate, Boolean>> { it.first.source.length }
                .thenByDescending { it.first.deinflection.inflections.size }
                .thenBy { it.second }

        val entryOrder: Comparator<LookupEntry> =
            compareByDescending<LookupEntry> { it.source.length }
                .thenByDescending { it.matchesFurigana }
                .thenBy { it.inflections.size }
                .thenByDescending { it.matchedExpression }
                .thenBy { it.dictionary.priority }
                .thenBy { it.dictionary.title }
                .thenByDescending { it.term.score }
                .thenBy { it.term.sequence ?: Long.MAX_VALUE }
                .thenBy { it.term.id }
    }
}
