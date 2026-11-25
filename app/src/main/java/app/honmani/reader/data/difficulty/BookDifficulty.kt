package app.honmani.reader.data.difficulty

import app.honmani.reader.data.sync.RubyText
import app.honmani.reader.lookup.JapaneseText
import kotlin.math.ln
import kotlin.math.roundToInt

/** How hard a book is to read, from one pass over its text. */
data class BookDifficulty(
    /** [Difficulty.VERSION] the result was computed with; older results are computed again. */
    val version: Int,
    /** 1 (picture books) to 100 (the hardest literature); 0 for text too short to grade. */
    val score: Int,
    /**
     * The frequency rank that covers 95% of the book's words, a word's later uses counting less
     * ([Difficulty.r95]).
     */
    val r95: Int,
    /** Share of the words written with kanji, 0 to 1, a word's later uses counting less like in [r95]. */
    val kanjiShare: Float,
    /** Characters per sentence, on average. */
    val sentenceLength: Float,
    /** Japanese characters in the book (letters, kana and kanji; no punctuation), for reading time. */
    val characters: Long = 0,
    /** Words counted (text no dictionary knows left out), and how many of them differ. */
    val words: Int,
    val distinctWords: Int,
    /** Share of the text's words no dictionary knew (mostly names), 0 to 1. */
    val unknownShare: Float,
    /**
     * Share of the words beyond the [Difficulty.COMMON_WORDS] most common (or in no frequency
     * list), 0 to 1, a word's later uses counting less like in [r95].
     */
    val rareShare: Float = 0f,
    /** How varied the vocabulary is: ln(distinct words) / ln(words), about 0.75 to 0.9. */
    val variety: Float = 0f,
)

/**
 * The distinct words of a book (dictionary forms, [Lexicon.UNRANKED] words included; names left
 * out), with their frequency rank and every place each occurs, as a share of the text in
 * [POSITIONS] steps, ascending: how often a reader has read each word in the parts read.
 */
class BookWords(val keys: List<String>, val ranks: IntArray, val positions: List<ShortArray>) {
    val size: Int get() = keys.size

    /** Where word [index] first appears, 0 to 1. */
    fun firstAt(index: Int): Float = positions[index][0] / POSITIONS.toFloat()

    /**
     * The words in the parts of the book read ([stretches]: ascending pairs `from, to` in
     * [POSITIONS] steps, see `ReadStretches`), with how many times each was read there.
     */
    inline fun forEachRead(stretches: IntArray, action: (key: String, rank: Int, reads: Int) -> Unit) {
        if (stretches.isEmpty()) return
        for (i in keys.indices) {
            var reads = 0
            for (j in stretches.indices step 2) {
                reads += countUpTo(positions[i], stretches[j + 1]) - countUpTo(positions[i], stretches[j] - 1)
            }
            if (reads > 0) action(keys[i], ranks[i], reads)
        }
    }

    companion object {
        const val POSITIONS = 10_000

        /** How many of the ascending [positions] are at most [limit]. */
        fun countUpTo(positions: ShortArray, limit: Int): Int {
            var low = 0
            var high = positions.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (positions[middle] <= limit) low = middle + 1 else high = middle
            }
            return low
        }
    }
}

/** A book's grade and its words, from one pass over its text. */
class Measured(val difficulty: BookDifficulty, val words: BookWords)

/**
 * Bands of the 1–100 scale, each about one JLPT level, at learnnatively.com's boundaries (N5 up to
 * level 12, N4 13–19, N3 20–26, N2 27–33, N1 34–40) converted with [Difficulty.score]'s scale.
 * [highest] is the band's top score.
 */
enum class DifficultyBand(val highest: Int) {
    N5(28),
    N4(43),
    N3(59),
    N2(74),
    N1(90),
    BEYOND_N1(100),
    ;

    companion object {
        fun of(score: Int): DifficultyBand = entries.first { score.coerceIn(1, 100) <= it.highest }
    }
}

/**
 * Book difficulty from four measures of the text, weighted as fitted to how readers grade books
 * on learnnatively.com: how many of its words are rare (beyond the 10,000 most common), how much
 * of it is written in kanji, how long its sentences are and how varied its vocabulary is. In the
 * first two a word's later uses count less ([weightOfUses]), as a reader learns a word from its
 * first few uses; counting every use, a short story that keeps coming back to a few words in kanji (子狐,
 * 手袋, 人間 in 手袋を買いに) was graded like a hard novel, and on the books compared both
 * measures followed the readers' grades much better weighted.
 */
object Difficulty {
    /**
     * Raise when the scale or the word splitting changes, so stored results are redone. 2: EPUB
     * paragraphs end sentences (dialogue without 。 had made sentences far too long). 3: the
     * book's characters are kept. 4: the book's words are kept ([BookWords]). 5: names left out
     * ([isName]); every place a word occurs is kept. 6: a word's later uses count less
     * ([weightOfUses]), refitted; Aozora Bunko's colophon left out ([storyEnd]). 7: graded from
     * the share of rare words and the vocabulary's variety instead of R95, refitted with light
     * novels. 8: Jiten's ranks of words in kana no longer rank their kanji spellings
     * (`LexiconLoader.addRank`), kanji with furigana count whole, the ratios to jiten.moe's
     * measures measured again. 9: endings short words look like count as less likely
     * ([Segmenter]), katakana words and numbers stay whole, rough speech and old spellings.
     */
    const val VERSION = 9

    const val COVERAGE = 0.95

    /**
     * Forms of the copula and polite endings that dictionaries don't list and the deinflector
     * doesn't reach after a noun (こと|でした). They are as common as だ and です.
     */
    val GRAMMAR = listOf(
        "でした", "でしょう", "でしょ", "でして", "だった", "だろう", "だろ", "だって", "であった", "であろう",
        "ました", "ません", "ませんでした", "ましょう", "まして", "なかった", "なくて",
        // In the old spelling of older books.
        "でせう", "だつた", "だらう", "だつて", "であつた", "であらう", "ませう", "なかつた",
    )

    /** Adds [GRAMMAR] to a lexicon being built from the dictionaries. */
    fun addGrammar(builder: Lexicon.Builder): Lexicon.Builder {
        for (form in GRAMMAR) builder.add(form, 0, GRAMMAR_RANK)
        return builder
    }

    private const val GRAMMAR_RANK = 10

    /**
     * The smallest rank N such that words ranked N or better make up [COVERAGE] of a text's
     * words, a word's later uses counting less ([weightOfUses]). [ranks] and [uses] give each
     * distinct word's rank and how often the text uses it.
     */
    fun r95(ranks: IntArray, uses: IntArray): Int {
        if (ranks.isEmpty()) return 0
        val order = ranks.indices.sortedBy { ranks[it] }
        var total = 0.0
        for (count in uses) total += weightOfUses(count)
        val needed = total * COVERAGE - 1e-9
        var covered = 0.0
        for (i in order) {
            covered += weightOfUses(uses[i])
            if (covered >= needed) return ranks[i]
        }
        return ranks[order.last()]
    }

    /**
     * How much a word used [count] times counts towards [r95] and the kanji share: its first
     * uses 1, 0.75, 0.5 and 0.25, later ones [LATER_USE]. A reader looks a word up the first few
     * times and then knows it. Later uses counting 0 followed the readers' grades best (0.1 and
     * 0.25 were tried).
     */
    fun weightOfUses(count: Int): Double {
        var weight = 0.0
        for (i in 0 until minOf(count, FIRST_USES.size)) weight += FIRST_USES[i]
        return weight + (count - FIRST_USES.size).coerceAtLeast(0) * LATER_USE
    }

    /** Words up to this frequency rank count as common for [BookDifficulty.rareShare]. */
    const val COMMON_WORDS = 10_000

    /**
     * The grade on learnnatively.com's scale (0 to about 45; JLPT N5 is up to 12, N1 34 to 40),
     * from the fit: the share of rare words, of words in kanji, the sentence length and the
     * vocabulary's variety ([BookDifficulty]).
     */
    fun nativelyLevel(rareShare: Double, kanjiShare: Double, sentenceLength: Double, variety: Double): Double {
        // The fit's measures are jiten.moe's; on the same texts Honmani's split finds a little
        // fewer rare words and words in kanji.
        val rare = (rareShare / RARE_RATIO).coerceIn(0.0, 1.0)
        val kanji = (kanjiShare / KANJI_RATIO).coerceIn(0.0, 1.0)
        val length = sentenceLength.coerceIn(MIN_SENTENCE, MAX_SENTENCE)
        return INTERCEPT + RARE_WEIGHT * rare + KANJI_WEIGHT * kanji + SENTENCE_WEIGHT * length + VARIETY_WEIGHT * variety
    }

    /** 1–100 from [nativelyLevel]: level [TOP_LEVEL] and above is 100, so a score is about 2.2 levels. */
    fun score(rareShare: Double, kanjiShare: Double, sentenceLength: Double, variety: Double): Int =
        scoreOfLevel(nativelyLevel(rareShare, kanjiShare, sentenceLength, variety))

    /**
     * The score of a book whose R95 is [r95]: for the vocabulary part of the skill, which is a
     * frequency rank. Fitted to [score] on the books [score] was fitted on, so a reader whose
     * vocabulary reaches a book's R95 gets about that book's score.
     */
    fun scoreOfRank(r95: Int, kanjiShare: Double, sentenceLength: Double): Int {
        val rank = (r95 / RANK_SPLIT_RATIO).coerceIn(MIN_R95, MAX_R95)
        val kanji = (kanjiShare / KANJI_RATIO).coerceIn(0.0, 1.0)
        val length = sentenceLength.coerceIn(MIN_SENTENCE, MAX_SENTENCE)
        return scoreOfLevel(RANK_INTERCEPT + RANK_WEIGHT * ln(rank) + RANK_KANJI_WEIGHT * kanji + RANK_SENTENCE_WEIGHT * length)
    }

    private fun scoreOfLevel(level: Double): Int = (1 + 99 * level / TOP_LEVEL).roundToInt().coerceIn(1, 100)

    /** Splits [text] with [segmenter] and grades it; null for text with too few words to tell. */
    fun of(text: Sequence<RubyText>, segmenter: Segmenter): BookDifficulty? = measure(text, segmenter)?.difficulty

    /** [of], with the book's words. */
    fun measure(text: Sequence<RubyText>, segmenter: Segmenter): Measured? {
        // First the words: how often each is used and how often in kanji. Names are only known
        // once the whole book is counted.
        val keys = LinkedHashMap<String, Int>()
        val keyRanks = IntList()
        val occurrences = ArrayList<IntList>()
        val kanjiUses = IntList()
        var counted = 0
        var offset = 0L
        var unknown = 0
        var characters = 0L
        var sentences = 0L
        for (chapter in text) {
            val part = chapter.text.substring(0, storyEnd(chapter.text))
            segmenter.segment(part) { token ->
                if (token.rank == Token.UNKNOWN) {
                    unknown++
                    return@segment
                }
                val index = keys.getOrPut(token.key) {
                    keyRanks.add(token.rank)
                    occurrences += IntList(4)
                    kanjiUses.add(0)
                    keys.size
                }
                counted++
                occurrences[index].add((offset + token.start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if ((token.start until token.start + token.length).any { JapaneseText.isKanji(part[it].code) }) kanjiUses[index]++
            }
            var inSentence = false
            for (c in part) {
                if (Segmenter.isJapanese(c)) {
                    characters++
                    inSentence = true
                } else if (c in SENTENCE_ENDS && inSentence) {
                    sentences++
                    inSentence = false
                }
            }
            if (inSentence) sentences++
            offset += chapter.text.length
        }
        val names = BooleanArray(keys.size) { isName(keyRanks[it], occurrences[it].size, counted) }
        val keptIndices = (0 until keys.size).filter { !names[it] }
        val uses = IntArray(keptIndices.size) { occurrences[keptIndices[it]].size }
        val wordCount = uses.sum()
        unknown += counted - wordCount
        if (wordCount < MIN_WORDS || sentences == 0L) return null
        val ranks = IntArray(keptIndices.size) { keyRanks[keptIndices[it]] }
        val r95 = r95(ranks, uses)
        // Weighted like r95: a word's share of kanji, or being rare, counts as much as the word does.
        var kanjiWeight = 0.0
        var rareWeight = 0.0
        var allWeight = 0.0
        for (i in keptIndices.indices) {
            val weight = weightOfUses(uses[i])
            kanjiWeight += weight * kanjiUses[keptIndices[i]] / uses[i]
            if (ranks[i] > COMMON_WORDS) rareWeight += weight
            allWeight += weight
        }
        val kanjiShare = kanjiWeight / allWeight
        val rareShare = rareWeight / allWeight
        val variety = ln(keptIndices.size.coerceAtLeast(2).toDouble()) / ln(wordCount.toDouble())
        val sentenceLength = characters.toDouble() / sentences
        val length = offset.coerceAtLeast(1).toDouble()
        val kept = keys.keys.filterIndexed { index, _ -> !names[index] }
        val words = BookWords(
            keys = kept,
            ranks = ranks,
            positions = keptIndices.map { index ->
                val at = occurrences[index]
                ShortArray(at.size) { (at[it] / length * BookWords.POSITIONS).toInt().coerceAtMost(BookWords.POSITIONS).toShort() }
            },
        )
        val difficulty = BookDifficulty(
            version = VERSION,
            score = score(rareShare, kanjiShare, sentenceLength, variety),
            r95 = r95,
            kanjiShare = kanjiShare.toFloat(),
            sentenceLength = sentenceLength.toFloat(),
            characters = characters,
            words = wordCount,
            distinctWords = kept.size,
            unknownShare = unknown.toFloat() / (unknown + wordCount),
            rareShare = rareShare.toFloat(),
            variety = variety.toFloat(),
        )
        return Measured(difficulty, words)
    }

    /**
     * A word the book uses far more than its rank says words are used: mostly names of people
     * and places (茅, 八尋, 千早 in a novel), which dictionaries list as rare words. jiten.moe, which
     * the scale was fitted on, leaves names out; counted, a novel's names alone made it read
     * 10–15 points harder. Also a book's own rare terms, which a reader learns within the book.
     * By Zipf's law a word of rank r is about 1 / (r · ln N) of a text's words.
     */
    internal fun isName(rank: Int, count: Int, words: Int): Boolean {
        if (count < NAME_MIN_COUNT || rank < NAME_MIN_RANK) return false
        val expected = words / (rank.toDouble() * ln(Segmenter.RARE_RANK.toDouble()))
        return count >= NAME_EXCESS * expected
    }

    /**
     * Where the text of [chapter] ends: before Aozora Bunko's notes on the source edition and
     * the volunteers who typed it (底本：…), which close its books but aren't the story; in a
     * short story their publishers and names were a few percent of the words.
     */
    internal fun storyEnd(chapter: String): Int {
        if (chapter.startsWith(COLOPHON)) return 0
        val at = chapter.indexOf("\n" + COLOPHON)
        return if (at < 0) chapter.length else at + 1
    }

    private const val COLOPHON = "底本："

    /** Short stories count; a page or two doesn't. */
    private const val MIN_WORDS = 500

    private const val SENTENCE_ENDS = "。！？!?\n"

    private val FIRST_USES = doubleArrayOf(1.0, 0.75, 0.5, 0.25)
    private const val LATER_USE = 0.0

    private const val NAME_MIN_COUNT = 20
    private const val NAME_MIN_RANK = 5_000
    private const val NAME_EXCESS = 1_000.0

    // Fitted on jiten.moe's word lists of 498 books and light novel series learnnatively.com
    // grades (non-fiction left out; 2026-10-22), leave-one-out rank correlation 0.85 (R95 instead
    // of the rare share: 0.81), then spread 1.1 times about the mean level 30.85, so that easy
    // and hard books aren't pulled towards the middle. Refit and raise VERSION together.
    private const val INTERCEPT = -54.78
    private const val RARE_WEIGHT = 42.23
    private const val KANJI_WEIGHT = 38.47
    private const val SENTENCE_WEIGHT = 0.2236
    private const val VARIETY_WEIGHT = 49.16

    // Honmani's measure / jiten.moe's (median; variety is 0.996) on five Aozora Bunko works and
    // three modern novels (each book: rare 0.87-0.98, kanji 0.98-1.01). Before version 8 they were 0.838 and
    // 0.895, from the Aozora works only: their rare spellings (或る) and furigana had made
    // Honmani's shares lower there, and modern books came out about 10 points too hard.
    private const val RARE_RATIO = 0.97
    private const val KANJI_RATIO = 0.99

    // [scoreOfRank]: R95, kanji share and sentence length fitted to [nativelyLevel] on the same
    // books (2026-10-22; mean error under 0.6 levels in every band). Version 6's rank model, kept
    // until then, gave easy books 2 to 4 points too much and hard ones about 5 too little.
    private const val RANK_INTERCEPT = -100.89
    private const val RANK_WEIGHT = 8.752
    private const val RANK_KANJI_WEIGHT = 43.29
    private const val RANK_SENTENCE_WEIGHT = 0.2552
    // Honmani's R95 / jiten.moe's on the same eight books (median; 0.99-1.10).
    private const val RANK_SPLIT_RATIO = 1.04
    private const val MIN_R95 = 3_000.0
    private const val MAX_R95 = 300_000.0
    private const val MIN_SENTENCE = 6.0
    private const val MAX_SENTENCE = 50.0
    private const val TOP_LEVEL = 45.0

    private class IntList(capacity: Int = 4096) {
        private var values = IntArray(capacity)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        operator fun get(index: Int): Int = values[index]

        operator fun set(index: Int, value: Int) {
            values[index] = value
        }
    }
}
