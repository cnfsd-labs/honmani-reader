package app.honmani.reader.data.difficulty

import app.honmani.reader.lookup.WordClass
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmenterTest {
    /** A few words with made-up ranks, as the dictionaries and Jiten would give them. */
    private val lexicon = Difficulty.addGrammar(
        Lexicon.Builder()
            .add("私", 0, 30)
            .add("は", 0, 2)
            .add("を", 0, 4)
            .add("から", 0, 20)
            .add("からは", 0, 70_000)
            .add("猫", 0, 900)
            .add("である", 0, 300)
            .add("食べる", WordClass.V1, 187)
            .add("こと", 0, 15)
            .add("でし", 0, 29_000)
            .add("ケーキ", 0, 4_000)
            .add("め", 0, 400)
            .add("ろ", 0, 9_000)
            .add("の", 0, 1)
            .add("家", 0, 120)
            .add("裏", 0, 5_000)
            .add("家の裏", 0, 47_000)
            .add("猫", 0, 800),
    ).build()

    private fun split(text: String): List<String> = buildList {
        Segmenter(lexicon).segment(text) { add(it.key + if (it.rank == Token.UNKNOWN) "?" else "") }
    }

    @Test
    fun lexiconFindsKeys_andMergesRepeats() {
        val index = lexicon.find("猫")
        assertEquals(800, lexicon.rank(index))
        assertEquals(-1, lexicon.find("犬"))
        assertEquals(WordClass.V1, lexicon.classes(lexicon.find("食べる")))
        assertEquals(Lexicon.UNRANKED, Lexicon.Builder().add("犬", 0, 0).build().let { it.rank(it.find("犬")) })
    }

    @Test
    fun aGaijiPlaceholderStopsEveryWord() {
        // The reader text has U+FFFC where a gaiji picture is: 家 and 裏 around it are not 家の裏,
        // and the placeholder itself is no token.
        val tokens = buildList {
            Segmenter(lexicon).segment("猫\uFFFC家の\uFFFC裏") { add(Triple(it.key, it.start, it.length)) }
        }
        assertEquals(listOf("猫", "家", "の", "裏"), tokens.map { it.first })
        assertEquals(listOf(0, 2, 3, 5), tokens.map { it.second })
        assertEquals(listOf(1, 1, 1, 1), tokens.map { it.third })
    }

    @Test
    fun inflectedWordsComeBackInTheirDictionaryForm() {
        assertEquals(listOf("私", "は", "ケーキ", "を", "食べる"), split("私はケーキを食べさせられなかった"))
    }

    @Test
    fun commonWordsWinOverARarePhrase() {
        // からは is in a dictionary, but much rarer than から and は.
        assertEquals(listOf("家", "から", "は"), split("家からは"))
    }

    @Test
    fun aPhraseCommonEnoughStaysWhole() {
        // Rank 47,000 costs less than three words: the split follows how often text is written so.
        assertEquals(listOf("家の裏"), split("家の裏"))
    }

    @Test
    fun copulaFormsAfterNounsAreGrammar_notRareWords() {
        // Not でし (弟子) + an unknown た.
        assertEquals(listOf("こと", "でした"), split("ことでした"))
    }

    @Test
    fun unknownKatakanaStaysOneName() {
        // Not め + ろ + an unknown ス.
        assertEquals(listOf("メロス?", "は"), split("メロスは"))
    }

    @Test
    fun punctuationAndLatinAreSkipped() {
        assertEquals(listOf("猫", "である"), split("「猫」である。ABC 123"))
    }
}
