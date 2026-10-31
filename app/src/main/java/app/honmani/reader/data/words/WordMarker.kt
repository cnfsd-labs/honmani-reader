package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.Lexicon
import app.honmani.reader.data.difficulty.LexiconSources
import app.honmani.reader.data.difficulty.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Finds the words to mark in the paragraphs the reader is about to read ([WordMarks]). The
 * splitter's lexicon is built from the dictionaries when first needed (a few seconds, as for
 * grading) and kept while they stay the same.
 */
class WordMarker(
    private val sources: suspend () -> LexiconSources?,
    private val lexicon: suspend (LexiconSources) -> Lexicon,
    private val knowledge: suspend () -> WordKnowledge,
) {
    private val lock = Mutex()
    private var segmenter: Pair<String, Segmenter>? = null

    /**
     * The marks in each of [texts]; null without a word dictionary and a frequency list, or when
     * [isStale] says so once this request gets its turn (requests queue behind the lexicon build).
     */
    suspend fun mark(
        texts: List<String>,
        mode: WordMarksMode,
        knownWords: Boolean = false,
        isStale: () -> Boolean = { false },
    ): List<List<WordMark>>? = withContext(Dispatchers.Default) {
        lock.withLock {
            if (isStale()) return@withLock null
            val wanted = sources() ?: return@withLock null
            val split = segmenter?.takeIf { it.first == wanted.signature }?.second
                ?: Segmenter(lexicon(wanted)).also { segmenter = wanted.signature to it }
            val known = knowledge()
            texts.map { WordMarks.of(it, split, known, mode, knownWords) }
        }
    }
}
