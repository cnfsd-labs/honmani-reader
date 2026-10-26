package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.BookWords
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.lookup.JapaneseText
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/** A word as read so far, over all graded books: its frequency rank and how many times it was read. */
data class ReadWord(val rank: Int, val reads: Int)

/** What the reader has read, and how well they know each word ([WordProgress]). */
class WordKnowledge(
    /** Every word in the parts of graded books read ([ReadStretches]), by dictionary form. */
    val read: Map<String, ReadWord>,
    /** Looked-up words' progress, by word id. */
    val progress: Map<Long, WordStep>,
    /** Dictionary forms of looked-up words not known yet. */
    val learning: Set<String>,
    /** The share of each graded book read (0 to 1), by content hash. */
    val readShares: Map<String, Float> = emptyMap(),
    /**
     * How words not known yet were written where they were last looked up (食べました, ねこ):
     * marked as written, also where the book's splitter finds another word there (ねこ for
     * 猫) or splits it otherwise.
     */
    val learningForms: Set<String> = emptySet(),
) {
    /** [learningForms] by first character, longest first. */
    private val formsByFirst: Map<Char, List<String>> by lazy {
        learningForms.filter { it.isNotEmpty() }.groupBy { it[0] }.mapValues { (_, forms) -> forms.sortedByDescending { it.length } }
    }

    /** The longest of [learningForms] written in [text] at [index] that [fits], if any. */
    fun learningFormAt(text: String, index: Int, fits: (String) -> Boolean = { true }): String? =
        formsByFirst[text[index]]?.firstOrNull { text.startsWith(it, index) && fits(it) }

    fun steps(word: WordEntity): Int = progress[word.id]?.steps ?: 0

    fun isKnown(word: WordEntity): Boolean = steps(word) >= WordProgress.KNOWN

    /**
     * Whether the reader probably knows the word [key] of frequency [rank], as the vocabulary
     * estimate counts it: not being learned, and met in reading or within the reader's
     * [vocabulary] (a frequency rank, `VocabularyEstimate.words`).
     */
    fun knows(key: String, rank: Int, vocabulary: Int): Boolean = key !in learning && (key in read || rank <= vocabulary)

    /** Words known from reading alone (never looked up), with how often each was read, most read first. */
    fun knownByReading(lookedUp: Set<String>): List<Pair<String, Int>> =
        read.filter { (key, word) -> word.reads >= WordProgress.KNOWN && key !in lookedUp }
            .map { (key, word) -> key to word.reads }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    companion object {
        val EMPTY = WordKnowledge(emptyMap(), emptyMap(), emptySet())
    }
}

/**
 * Keeps looked-up words' progress between runs: it depends on the order of reads and lookups,
 * which the books and the word list alone don't tell. In app storage, like the book grades; lost,
 * it is worked out again from the totals.
 */
class WordProgressStore(private val file: File) {
    /** Goes up when a restore changed the file: what was loaded before is out of date. */
    @Volatile
    var generation = 0
        private set

    fun load(): Map<Long, WordStep> = synchronized(this) { read() }

    /** Saves [progress], unless a restore changed the file since [generation] was read. */
    fun save(progress: Map<Long, WordStep>, generation: Int) {
        synchronized(this) {
            if (generation == this.generation) save(progress)
        }
    }

    /** Sets the steps of the words in [restored] (from a backup), by word id. */
    fun restore(restored: Map<Long, WordStep>) {
        if (restored.isEmpty()) return
        synchronized(this) {
            save(read() + restored)
            generation++
        }
    }

    private fun read(): Map<Long, WordStep> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associate { id ->
            val values = json.getJSONArray(id)
            id.toLong() to WordStep(values.getInt(0), values.getInt(1), values.getInt(2), values.getBoolean(3), values.optBoolean(4))
        }
    }.getOrDefault(emptyMap())

    fun save(progress: Map<Long, WordStep>) {
        synchronized(this) {
            val json = JSONObject()
            for ((id, step) in progress) {
                json.put(id.toString(), JSONArray().put(step.steps).put(step.reads).put(step.lookups).put(step.learned).put(step.kana))
            }
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.writeText(json.toString())
            temporary.renameTo(file)
        }
    }
}

/**
 * Works out [WordKnowledge] whenever the parts read, the books, their grades or the word list
 * change: the words read from each graded book's words ([BookWords]) in the parts of it read
 * ([ReadStretches]: pages on screen long enough, not everything up to the reader's place), and
 * each looked-up word's progress brought up to date ([WordProgress.update]).
 */
class WordKnowledgeTracker(
    books: Flow<List<BookEntity>>,
    /** [ReadStretches] by content hash. */
    stretches: Flow<Map<String, IntArray>>,
    /** Book grades by content hash. */
    grades: Flow<Map<String, StoredDifficulty>>,
    /** Every saved word. */
    words: Flow<List<WordEntity>>,
    /** A book's words by content hash ([app.honmani.reader.data.difficulty.DifficultyStore.words]). */
    private val bookWords: (String) -> BookWords?,
    private val store: WordProgressStore,
    context: CoroutineContext = Dispatchers.IO,
) {
    /** Words already read, by content hash and grade signature: they only change when a book is graded again. */
    private val cache = ConcurrentHashMap<String, Pair<String, BookWords?>>()
    private var progress: Map<Long, WordStep>? = null
    private var generation = -1

    val knowledge: Flow<WordKnowledge> = combine(books, stretches, grades, words) { books, stretches, grades, words ->
        val read = HashMap<String, ReadWord>()
        val shares = HashMap<String, Float>()
        for (hash in books.mapTo(HashSet()) { it.contentHash }) {
            val grade = grades[hash]?.takeIf { it.difficulty.score > 0 } ?: continue
            val parts = stretches[hash]?.takeIf { it.isNotEmpty() } ?: continue
            val inBook = wordsOf(hash, grade.signature) ?: continue
            shares[hash] = ReadStretches.share(parts)
            inBook.forEachRead(parts) { key, rank, reads ->
                val before = read[key]
                read[key] = if (before == null) ReadWord(rank, reads) else ReadWord(minOf(before.rank, rank), before.reads + reads)
            }
        }
        val loaded = store.generation
        val previous = progress?.takeIf { generation == loaded } ?: store.load()
        val updated = words.associate { word ->
            val before = previous[word.id]
            val kana = before?.kana == true || lookedUpInKana(word)
            word.id to WordProgress.update(
                before,
                reads = readsOf(word, kana, read),
                lookups = word.lookupCount,
                learned = word.status == WordStatus.LEARNED,
            ).copy(kana = kana)
        }
        if (updated != previous) store.save(updated, loaded)
        progress = updated
        generation = loaded
        val notKnown = words.filter { (updated[it.id]?.steps ?: 0) < WordProgress.KNOWN }
        val learning = notKnown.mapTo(HashSet()) { it.expression }
        val forms = notKnown.mapNotNullTo(HashSet()) { word ->
            word.lastSentence.takeIf { 0 <= word.lastSurfaceStart && word.lastSurfaceStart < word.lastSurfaceEnd && word.lastSurfaceEnd <= it.length }
                ?.substring(word.lastSurfaceStart, word.lastSurfaceEnd)
                ?.takeIf { it.isNotBlank() }
        }
        WordKnowledge(read, updated, learning, shares, forms)
    }.flowOn(context)

    /**
     * How often [word] was read: as written in its entry (分かる), and if it was looked up in kana,
     * also as its reading in kana (わかる), which is how the books' words count it there.
     */
    private fun readsOf(word: WordEntity, kana: Boolean, read: Map<String, ReadWord>): Int {
        val asWritten = read[word.expression]?.reads ?: 0
        if (!kana || word.reading.isEmpty() || word.reading == word.expression) return asWritten
        return asWritten + (read[word.reading]?.reads ?: 0)
    }

    /** Whether the book wrote [word] without kanji where it was last looked up. */
    private fun lookedUpInKana(word: WordEntity): Boolean {
        val sentence = word.lastSentence
        if (word.lastSurfaceStart < 0 || word.lastSurfaceEnd > sentence.length || word.lastSurfaceStart >= word.lastSurfaceEnd) return false
        val surface = sentence.substring(word.lastSurfaceStart, word.lastSurfaceEnd)
        return surface.isNotBlank() && surface.none { JapaneseText.isKanji(it.code) }
    }

    private fun wordsOf(contentHash: String, signature: String): BookWords? {
        cache[contentHash]?.let { (cached, words) -> if (cached == signature) return words }
        return bookWords(contentHash).also { cache[contentHash] = signature to it }
    }
}
