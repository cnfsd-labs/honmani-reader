package app.honmani.reader.known

import app.honmani.reader.data.words.KnownBatchRepository
import app.honmani.reader.data.words.KnownCandidate
import app.honmani.reader.data.words.KnownChange
import app.honmani.reader.data.words.KnownSource
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

/** One question of the quick quiz: the word at [rank] of the frequency dictionary (1 = most common). */
class QuizQuestion(val rank: Int, val word: KnownCandidate)

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
    /** (written form, reading) of the words ranked [maxRank] or better in a frequency dictionary, most common first. */
    private val topWords: suspend (dictionary: Long, maxRank: Int) -> List<Pair<String, String?>>,
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
        val wanted = topWords(id, n).map { (written, reading) -> WantedWord(written, reading) }
        return TopNResult.Ready(plan(KnownSource.TOP_N, n.toString(), wanted, deinflect = false, skipped = 0, onProgress), title)
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
        val questions = PlacementQuiz.sampleRanks(ranked.size, random).mapNotNull { rank ->
            val (written, reading) = ranked[rank - 1]
            val word = resolver.resolve(listOf(WantedWord(written, reading)), deinflect = false).words.firstOrNull()
            if (word != null && seen.add(word.expression)) QuizQuestion(rank, word) else null
        }
        return QuizSetup(title, ranked.size, questions)
    }

    /**
     * What the quiz would mark: the [n] most common words, minus every word answered "don't know"
     * (by written form), plus every word answered "know" (also beyond [n]).
     */
    suspend fun planQuiz(n: Int, answers: List<Pair<QuizQuestion, Boolean>>, onProgress: (Int) -> Unit = {}): TopNResult {
        val (id, title) = frequencyDictionary() ?: return TopNResult.NoFrequencyDictionary
        val wanted = topWords(id, PlacementQuiz.MAX_WORDS).take(PlacementQuiz.MAX_WORDS).take(n)
            .map { (written, reading) -> WantedWord(written, reading) }
        val resolved = resolver.resolve(wanted, deinflect = false, onProgress)
        val unknown = answers.filterNot { it.second }.mapTo(HashSet()) { it.first.word.expression }
        val words = LinkedHashMap<String, KnownCandidate>()
        for (word in resolved.words) if (word.expression !in unknown) words.putIfAbsent("${word.expression}\u0000${word.reading}", word)
        for ((question, known) in answers) if (known) words.putIfAbsent("${question.word.expression}\u0000${question.word.reading}", question.word)
        val kept = ResolvedWords(words.values.toList(), resolved.unmatched, resolved.names)
        return TopNResult.Ready(KnownPlan(KnownSource.QUIZ, n.toString(), kept, batches.preview(kept.words)), title)
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
