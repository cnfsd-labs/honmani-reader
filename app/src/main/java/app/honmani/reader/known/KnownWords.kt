package app.honmani.reader.known

import app.honmani.reader.data.words.KnownBatchRepository
import app.honmani.reader.data.words.KnownChange
import app.honmani.reader.data.words.KnownSource

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
}
