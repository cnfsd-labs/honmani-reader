package app.honmani.reader.data.difficulty

import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.sync.RubyText
import java.util.BitSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {
    @Test
    fun r95IsTheRankCoveringNinetyFivePercent() {
        val ranks = IntArray(100) { if (it < 95) it + 1 else 10_000 + it }
        val once = IntArray(100) { 1 }
        assertEquals(95, Difficulty.r95(ranks, once))
        assertEquals(10_095, Difficulty.r95(ranks.copyOf().also { it[0] = 20_000 }, once))
        assertEquals(0, Difficulty.r95(IntArray(0), IntArray(0)))
    }

    @Test
    fun aWordsLaterUsesCountLess() {
        assertEquals(1.0, Difficulty.weightOfUses(1), 0.001)
        assertEquals(2.5, Difficulty.weightOfUses(4), 0.001)
        assertTrue(Difficulty.weightOfUses(10) < 4.0)
        // 95 common words once each and a rare one ten times (子狐 in a short story): counted
        // every time, the rare word would be 10% of the words and set the rank; it counts 2.5.
        val ranks = IntArray(96) { if (it < 95) it + 1 else 50_000 }
        val uses = IntArray(96) { if (it < 95) 1 else 10 }
        assertEquals(93, Difficulty.r95(ranks, uses))
        // Ten different rare words are ten words to learn.
        val rare = IntArray(105) { if (it < 95) it + 1 else 50_000 + it }
        assertEquals(50_099, Difficulty.r95(rare, IntArray(105) { 1 }))
    }

    @Test
    fun scoreRisesWithRareWordsKanjiLongSentencesAndVariety() {
        val base = Difficulty.score(rareShare = 0.25, kanjiShare = 0.35, sentenceLength = 25.0, variety = 0.83)
        assertTrue(Difficulty.score(0.35, 0.35, 25.0, 0.83) > base)
        assertTrue(Difficulty.score(0.25, 0.45, 25.0, 0.83) > base)
        assertTrue(Difficulty.score(0.25, 0.35, 35.0, 0.83) > base)
        assertTrue(Difficulty.score(0.25, 0.35, 25.0, 0.87) > base)
        assertEquals(1, Difficulty.score(0.0, 0.0, 5.0, 0.5))
        assertEquals(100, Difficulty.score(1.0, 1.0, 80.0, 1.0))
        assertTrue(Difficulty.scoreOfRank(80_000, 0.35, 25.0) > Difficulty.scoreOfRank(40_000, 0.35, 25.0))
    }

    @Test
    fun theVocabularyScaleFollowsTheBookScale() {
        // Two books of the fit (jiten.moe's measures converted to Honmani's: R95 × 1.04, kanji ×
        // 0.99, rare share × 0.97, variety × 0.996): 星のカービィ 夢幻の歯車を探せ！ and 愚物語.
        // A reader whose vocabulary reaches a book's R95 gets about the book's score.
        assertEquals(
            Difficulty.score(0.3055, 0.5219, 16.26, 0.8150).toDouble(),
            Difficulty.scoreOfRank(60_245, 0.5219, 16.26).toDouble(),
            3.0,
        )
        assertEquals(
            Difficulty.score(0.3732, 0.7388, 42.49, 0.8144).toDouble(),
            Difficulty.scoreOfRank(66_549, 0.7388, 42.49).toDouble(),
            3.0,
        )
    }

    @Test
    fun theScaleFollowsTheBooksItWasFittedOn() {
        // Measured with Honmani's splitting (version 8, 2026-10-23): rare share, kanji share,
        // sentence length, ln(distinct words) / ln(words). learnnatively.com grades 走れメロス 34,
        // 人間失格 41, editions of 手袋を買いに for learners 18 to 22, 無職転生 1 31 and
        // また、同じ夢を見ていた 24.
        assertEquals(34.0, Difficulty.nativelyLevel(0.2879, 0.6932, 18.09, 0.8649), 3.0)
        assertEquals(41.0, Difficulty.nativelyLevel(0.3743, 0.7110, 39.62, 0.8312), 3.0)
        assertTrue(Difficulty.nativelyLevel(0.2005, 0.5588, 32.0, 0.8435) < 27.0)
        assertEquals(31.0, Difficulty.nativelyLevel(0.2884, 0.7417, 19.09, 0.8170), 3.0)
        assertEquals(24.0, Difficulty.nativelyLevel(0.1927, 0.6663, 24.60, 0.7607), 3.0)
        assertEquals(DifficultyBand.N2, DifficultyBand.of(Difficulty.score(0.2884, 0.7417, 19.09, 0.8170)))
    }

    @Test
    fun bandsFollowNativelysJlptLevels() {
        assertEquals(DifficultyBand.N5, DifficultyBand.of(1))
        assertEquals(DifficultyBand.N5, DifficultyBand.of(28))
        assertEquals(DifficultyBand.N4, DifficultyBand.of(29))
        assertEquals(DifficultyBand.N3, DifficultyBand.of(59))
        assertEquals(DifficultyBand.N2, DifficultyBand.of(60))
        assertEquals(DifficultyBand.N1, DifficultyBand.of(90))
        assertEquals(DifficultyBand.BEYOND_N1, DifficultyBand.of(91))
        // Natively's level 24 (N3) is a score of about 54.
        assertEquals(DifficultyBand.N3, DifficultyBand.of((1 + 99 * 24 / 45.0).toInt()))
    }

    private val lexicon = Lexicon.Builder()
        .add("猫", 0, 900)
        .add("が", 0, 3)
        .add("好き", 0, 200)
        .add("です", 0, 5)
        .add("ねこ", 0, 900)
        .build()

    @Test
    fun aBookIsMeasuredOverAllItsChapters() {
        val chapter = RubyText("猫が好きです。".repeat(70))
        val difficulty = Difficulty.of(sequenceOf(chapter, chapter), Segmenter(lexicon))!!

        assertEquals(560, difficulty.words)
        assertEquals(4, difficulty.distinctWords)
        assertEquals(900, difficulty.r95)
        assertEquals(0.5f, difficulty.kanjiShare, 0.001f)
        assertEquals(6f, difficulty.sentenceLength, 0.001f)
        assertEquals(840L, difficulty.characters)
        assertEquals(Difficulty.VERSION, difficulty.version)
    }

    @Test
    fun theBooksWordsAreKeptWithWhereTheyFirstAppear() {
        val text = "猫が好きです。".repeat(130) + "ねこです。"
        val words = Difficulty.measure(sequenceOf(RubyText(text)), Segmenter(lexicon))!!.words

        assertEquals(listOf("猫", "が", "好き", "です", "ねこ"), words.keys)
        assertEquals(listOf(900, 3, 200, 5, 900), words.ranks.toList())
        assertEquals(0f, words.firstAt(0), 0.001f)
        assertEquals(910f / text.length, words.firstAt(4), 0.001f)
        val met = LinkedHashMap<String, Int>()
        words.forEachRead(intArrayOf(0, BookWords.POSITIONS / 2)) { key, _, reads -> met[key] = reads }
        // Half the book: 65 of the 130 sentences, each word once in each.
        assertEquals(listOf("猫", "が", "好き", "です"), met.keys.toList())
        assertEquals(65.0, met.getValue("猫").toDouble(), 1.0)
    }

    @Test
    fun namesTheBookRepeatsAreLeftOut() {
        // 八尋 is a rare word in the dictionaries, but this book's main character.
        val names = Lexicon.Builder().add("猫", 0, 900).add("が", 0, 3).add("好き", 0, 200).add("です", 0, 5).add("八尋", 0, 116_747).build()
        val measured = Difficulty.measure(sequenceOf(RubyText("八尋が猫が好きです。".repeat(150))), Segmenter(names))!!

        assertEquals(listOf("が", "猫", "好き", "です"), measured.words.keys)
        assertEquals(900, measured.difficulty.r95)
        assertTrue(Difficulty.isName(rank = 116_747, count = 488, words = 52_715))
        // A rare word used a few times more than its rank says is still a word (心持 in こころ).
        assertFalse(Difficulty.isName(rank = 63_707, count = 57, words = 70_798))
    }

    @Test
    fun kanjiWithFuriganaCountWhole() {
        // As in jiten.moe's measure, which the scale was fitted on: counted half, classics full
        // of furigana read easier than their readers graded them.
        val text = "猫が好きです。".repeat(130)
        val ruby = BitSet().apply { for (i in text.indices step 7) set(i) }
        val withFurigana = Difficulty.of(sequenceOf(RubyText(text, ruby)), Segmenter(lexicon))!!

        assertEquals(0.5f, withFurigana.kanjiShare, 0.001f)
    }

    @Test
    fun aRankOfTheWordInKanaCountsForTheKanaOnly() {
        val builder = Lexicon.Builder()
        LexiconLoader.addRank(builder, LexiconFrequency(1, "或る", "ある", 615.0, "615\u32D5"))
        LexiconLoader.addRank(builder, LexiconFrequency(2, "或る", "ある", 53_486.0, "53486"))
        LexiconLoader.addRank(builder, LexiconFrequency(3, "猫", "ねこ", 900.0, null))
        val ranks = builder.build()

        assertEquals(53_486, ranks.rank(ranks.find("或る")))
        assertEquals(615, ranks.rank(ranks.find("ある")))
        assertEquals(900, ranks.rank(ranks.find("猫")))
        assertEquals(900, ranks.rank(ranks.find("ねこ")))
    }

    @Test
    fun aWordsLaterUsesCountLessInTheKanjiShareToo() {
        // 猫 once among kana words used 200 times each: 1 of 802 uses, but 1 of 8.5 weighted.
        val text = "猫です。" + "ねこがねこです。".repeat(200)
        val difficulty = Difficulty.of(sequenceOf(RubyText(text)), Segmenter(lexicon))!!
        assertEquals(802, difficulty.words)
        assertEquals((1 / 8.5).toFloat(), difficulty.kanjiShare, 0.001f)
    }

    @Test
    fun aozoraBunkosColophonIsNotPartOfTheBook() {
        val story = "猫が好きです。".repeat(130)
        val colophon = "\n底本：「ねこ」ねこ\n入力：ねこ"
        assertEquals(story.length + 1, Difficulty.storyEnd(story + colophon))
        assertEquals(0, Difficulty.storyEnd(colophon.drop(1)))
        assertEquals(story.length, Difficulty.storyEnd(story))
        val words = Difficulty.measure(sequenceOf(RubyText(story + colophon), RubyText(story)), Segmenter(lexicon))!!.words
        assertEquals(listOf("猫", "が", "好き", "です"), words.keys)
    }

    @Test
    fun lineBreaksEndSentences_dialogueWithoutAFullStopToo() {
        // 「そうか」 on lines of their own: without the breaks this was one 900-character sentence.
        val dialogue = Difficulty.of(sequenceOf(RubyText("猫が好きです\n".repeat(150))), Segmenter(lexicon))!!
        assertEquals(6f, dialogue.sentenceLength, 0.001f)
    }

    @Test
    fun tooLittleTextIsNotGraded() {
        assertNull(Difficulty.of(sequenceOf(RubyText("猫が好きです。")), Segmenter(lexicon)))
    }

    private fun dictionary(id: Long, title: String, terms: Int = 0, meta: Int = 0, mode: String? = null, priority: Int = 0) =
        DictionaryEntity(
            id = id,
            title = title,
            revision = "1",
            formatVersion = 3,
            importedAt = 0,
            termCount = terms,
            metaCount = meta,
            frequencyMode = mode,
            priority = priority,
        )

    @Test
    fun gradingUsesJitenWhenInstalled_andNeedsWordsAndRanks() = runBlocking {
        val jitendex = dictionary(1, "Jitendex", terms = 300_000)
        val jiten = dictionary(2, "Jiten", meta = 500_000, mode = "rank-based", priority = 3)
        // No frequency mode means rank-based.
        val other = dictionary(3, "JPDB", meta = 400_000, priority = 1)
        val counts = dictionary(4, "Counts", meta = 100, mode = DictionaryEntity.OCCURRENCE_BASED)
        val pitch = dictionary(5, "Kanjium", meta = 120_000, priority = 0)
        val ranked: suspend (DictionaryEntity) -> Boolean = { it != pitch }
        suspend fun of(vararg enabled: DictionaryEntity) = LexiconSources.of(enabled.toList(), ranked)

        assertEquals(jiten, of(jitendex, pitch, other, jiten)!!.frequency)
        assertEquals(other, of(jitendex, pitch, other, counts)!!.frequency)
        assertEquals(listOf(jitendex), of(jitendex, jiten)!!.terms)
        assertNull(of(jitendex, counts, pitch))
        assertNull(of(jiten))
        // The frequency list alone, for the most common words: it does not need a word dictionary.
        assertEquals(jiten, LexiconSources.frequencyOf(listOf(pitch, jiten), ranked))
        assertNull(LexiconSources.frequencyOf(listOf(jitendex, counts, pitch), ranked))
        assertNotEquals(of(jitendex, jiten)!!.signature, of(jitendex, jiten.copy(revision = "2"))!!.signature)
    }
}
