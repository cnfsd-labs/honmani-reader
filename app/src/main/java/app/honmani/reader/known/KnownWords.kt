package app.honmani.reader.known

import app.honmani.reader.data.words.KnownBatchRepository
import app.honmani.reader.data.words.KnownCandidate
import app.honmani.reader.data.words.KnownChange
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.lookup.JapaneseText
import kotlin.random.Random

/**
 * A seeding action worked out and ready to confirm: [resolved] are the words it would mark,
 * [change] what that does to the saved words, [skipped] the lines or notes that held no word.
 */
class KnownPlan(
    val source: KnownSource,
    val detail: String,
    val resolved: ResolvedWords,
    val change: KnownChange,
    val skipped: Int = 0,
)

/** The answer to [KnownWords.planTopN]. */
sealed interface TopNResult {
    /** No frequency dictionary (ranks) is installed, so "the N most common words" has no meaning. */
    data object NoFrequencyDictionary : TopNResult

    data class Ready(val plan: KnownPlan, val dictionary: String) : TopNResult
}

/**
 * A row of the frequency dictionary: the word as written and its reading, and whether this rank is the
 * word's in kana ([usuallyKana], Jiten's mark: ここ, これ, この, for 此処, 是, 此の).
 */
data class RankedWord(val written: String, val reading: String?, val usuallyKana: Boolean = false) {
    /** The word's kana spelling, if this rank is the one in kana. */
    fun kanaSpelling(): String? =
        if (usuallyKana) (reading?.takeIf { it.isNotEmpty() } ?: written).takeIf(::isKanaOnly) else null
}

private fun isKanaOnly(text: String) = text.isNotEmpty() && text.all { JapaneseText.isKana(it) }

/**
 * One question of the quick quiz: the word at [rank] of the frequency dictionary (1 = most common).
 * A word [usuallyKana] is asked in kana (ここ, not 此処), as books write it.
 */
class QuizQuestion(val rank: Int, val word: KnownCandidate, val usuallyKana: Boolean = false) {
    /** The kana spelling, when it is the one asked; null for other words. */
    private val kana: String? = word.reading.takeIf { usuallyKana && it != word.expression && isKanaOnly(it) }

    /** What the question shows. */
    val shown: String get() = kana ?: word.expression

    /**
     * What a "know" answer marks: the dictionary word, and its kana spelling as a word of its own,
     * because a book that writes ここ is matched by that spelling and not by 此処 ([KnownWordsTest]).
     */
    val marked: List<KnownCandidate> =
        listOfNotNull(word, kana?.let { KnownCandidate(it, it, word.gloss) })
}

/** The questions of one quiz, and how many ranked words the slider can reach ([size]). */
class QuizSetup(val dictionary: String, val size: Int, val questions: List<QuizQuestion>)

/**
 * Marking many words as Known: a word list, the most common words, an Anki deck. Each is first
 * worked out into a [KnownPlan] (nothing is saved), then [confirm]ed into one undoable batch.
 */
class KnownWords(
    private val resolver: KnownWordResolver,
    val batches: KnownBatchRepository,
    /** The frequency dictionary ranks come from, with its title; null if none is installed. */
    private val frequencyDictionary: suspend () -> Pair<Long, String>?,
    /** The words ranked [maxRank] or better in a frequency dictionary, most common first. */
    private val topWords: suspend (dictionary: Long, maxRank: Int) -> List<RankedWord>,
) {
    suspend fun planWordList(name: String, list: ParsedWordList, onProgress: (Int) -> Unit = {}): KnownPlan =
        plan(KnownSource.WORD_LIST, name, list.entries.map { WantedWord(it.word, it.reading) }, deinflect = true, skipped = list.skippedLines, onProgress)

    /** The words of an Anki deck's notes, as the notes spell them. */
    suspend fun planDeck(deck: String, forms: Collection<String>, skippedNotes: Int, onProgress: (Int) -> Unit = {}): KnownPlan =
        plan(KnownSource.ANKI_DECK, deck, forms.map { WantedWord(it) }, deinflect = true, skipped = skippedNotes, onProgress)

    /**
     * The [n] most common words by the frequency dictionary, particles and basic grammar words
     * included, names left out; words the installed dictionaries lack are skipped.
     */
    suspend fun planTopN(n: Int, onProgress: (Int) -> Unit = {}): TopNResult {
        val (id, title) = frequencyDictionary() ?: return TopNResult.NoFrequencyDictionary
        val ranked = topWords(id, n)
        val resolved = withKanaSpellings(resolver.resolve(ranked.map { WantedWord(it.written, it.reading) }, deinflect = false, onProgress), ranked)
        return TopNResult.Ready(KnownPlan(KnownSource.TOP_N, n.toString(), resolved, batches.preview(resolved.words)), title)
    }

    /**
     * The words to ask in the quick quiz, spread over the frequency dictionary's ranks (see
     * [PlacementQuiz]); null if no frequency dictionary with enough words is installed. A rank whose
     * word no dictionary explains, or which is a name, is not asked.
     */
    suspend fun prepareQuiz(random: Random = Random.Default): QuizSetup? {
        val (id, title) = frequencyDictionary() ?: return null
        val ranked = topWords(id, PlacementQuiz.MAX_WORDS).take(PlacementQuiz.MAX_WORDS)
        if (ranked.size < MIN_QUIZ_WORDS) return null
        val seen = HashSet<String>()
        // Each reading of a written form is a ranked row of its own; only the best-ranked reading is
        // asked (女性 as じょせい, not にょしょう). The ranks stay those of the list, which planQuiz cuts.
        val firstReading = HashMap<String, String?>()
        for (row in ranked) if (row.written !in firstReading) firstReading[row.written] = row.reading
        val questions = PlacementQuiz.sampleRanks(ranked.size, random).mapNotNull { rank ->
            val row = ranked[rank - 1]
            if (firstReading[row.written] != row.reading) return@mapNotNull null
            val word = resolver.resolve(listOf(WantedWord(row.written, row.reading)), deinflect = false).words.firstOrNull()
            if (word != null && seen.add(word.expression)) QuizQuestion(rank, word, usuallyKana = row.usuallyKana) else null
        }
        return QuizSetup(title, ranked.size, questions)
    }

    /**
     * What the quiz would mark: the [n] most common words, minus every word answered "don't know"
     * (by written form), plus every word answered "know" (also beyond [n]).
     */
    suspend fun planQuiz(n: Int, answers: List<Pair<QuizQuestion, Boolean>>, onProgress: (Int) -> Unit = {}): TopNResult {
        val (id, title) = frequencyDictionary() ?: return TopNResult.NoFrequencyDictionary
        val ranked = topWords(id, PlacementQuiz.MAX_WORDS).take(PlacementQuiz.MAX_WORDS).take(n)
        val resolved = withKanaSpellings(resolver.resolve(ranked.map { WantedWord(it.written, it.reading) }, deinflect = false, onProgress), ranked)
        val unknown = answers.filterNot { it.second }.flatMapTo(HashSet()) { (question, _) -> question.marked.map { it.expression } }
        val words = LinkedHashMap<String, KnownCandidate>()
        for (word in resolved.words) if (word.expression !in unknown) words.putIfAbsent("${word.expression}\u0000${word.reading}", word)
        for ((question, known) in answers) if (known) for (word in question.marked) words.putIfAbsent("${word.expression}\u0000${word.reading}", word)
        val kept = ResolvedWords(words.values.toList(), resolved.unmatched, resolved.names)
        return TopNResult.Ready(KnownPlan(KnownSource.QUIZ, n.toString(), kept, batches.preview(kept.words)), title)
    }

    /**
     * Words the frequency dictionary ranks in kana (ここ for 此処) also as that spelling: the books
     * that write ここ are matched by it, a known 此処 does not cover them.
     */
    private fun withKanaSpellings(resolved: ResolvedWords, ranked: List<RankedWord>): ResolvedWords {
        val kana = ranked.mapNotNullTo(HashSet()) { it.kanaSpelling() }
        if (kana.isEmpty()) return resolved
        val words = LinkedHashMap<String, KnownCandidate>()
        for (word in resolved.words) {
            words.putIfAbsent("${word.expression}\u0000${word.reading}", word)
            if (word.reading in kana && word.reading != word.expression) {
                words.putIfAbsent("${word.reading}\u0000${word.reading}", KnownCandidate(word.reading, word.reading, word.gloss))
            }
        }
        return ResolvedWords(words.values.toList(), resolved.unmatched, resolved.names)
    }

    /** Saves the plan as one batch; returns its id (null if nothing changed) and what changed. */
    suspend fun confirm(plan: KnownPlan) = batches.apply(plan.source, plan.detail, plan.resolved.words)

    private suspend fun plan(
        source: KnownSource,
        detail: String,
        wanted: List<WantedWord>,
        deinflect: Boolean,
        skipped: Int,
        onProgress: (Int) -> Unit,
    ): KnownPlan {
        val resolved = resolver.resolve(wanted, deinflect, onProgress)
        return KnownPlan(source, detail, resolved, batches.preview(resolved.words), skipped)
    }

    private companion object {
        /** A frequency dictionary with fewer ranked words than this cannot place anyone. */
        const val MIN_QUIZ_WORDS = 500
    }
}
