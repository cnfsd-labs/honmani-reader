package app.honmani.reader.data.difficulty

import app.honmani.reader.lookup.WordClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun theCacheStaysBoundedAcrossManyBooks() {
        val segmenter = Segmenter(lexicon, maxCached = 500)
        val random = kotlin.random.Random(7)
        val kana = ('ぁ'..'ゖ').toList()
        val reference = "吾輩は猫である。名前はまだ無い。"
        val before = buildList { segmenter.segment(reference) { add(it.key) } }
        repeat(5) {
            val book = String(CharArray(1_000) { kana[random.nextInt(kana.size)] })
            segmenter.segment(book) { }
            assertTrue(segmenter.cachedPrefixes <= 500)
        }
        assertEquals(before, buildList { segmenter.segment(reference) { add(it.key) } })
    }

    @Test
    fun anAuxiliaryAfterAPastVerbStaysOneWord() {
        // Ranks and classes as Jitendex and Jiten give them: しい is no stem of 知る (しる).
        val words = Difficulty.addGrammar(
            Lexicon.Builder()
                .add("発見", 0, 1_146)
                .add("する", WordClass.V5 or WordClass.VS, 10)
                .add("らしい", WordClass.ADJ_I, 138)
                .add("らし", 0, 28_679)
                .add("ら", 0, 784)
                .add("たら", 0, 2_909)
                .add("しい", WordClass.ADJ_I, 14_011)
                .add("しいる", WordClass.V1, 5_834)
                .add("しる", WordClass.V5, 66),
        ).build()
        val text = "発見されたらしい"
        val split = buildList { Segmenter(words).segment(text) { add(text.substring(it.start, it.start + it.length)) } }
        assertEquals(listOf("発見", "された", "らしい"), split)
    }

    /** Words of real sentences the splitter used to get wrong, ranked and classed as in Jitendex and Jiten. */
    private val real = Difficulty.addGrammar(
        Lexicon.Builder().apply {
            val v5 = WordClass.V5
            val adjective = WordClass.ADJ_I
            listOf("の" to 1, "は" to 2, "を" to 4, "が" to 5, "か" to 11, "だ" to 9, "な" to 14, "ね" to 17, "さ" to 104, "に" to 3)
                .forEach { (word, rank) -> add(word, 0, rank) }
            listOf(
                "ある" to 18, "いう" to 25, "言う" to 25, "かう" to 290, "あたる" to 660, "払う" to 953, "知る" to 66, "行く" to 30,
                "酔う" to 2_451,
            ).forEach { (word, rank) -> add(word, v5, rank) }
            add("する", v5 or WordClass.VS, 10)
            listOf("できる" to 60, "いる" to 37).forEach { (word, rank) -> add(word, WordClass.V1, rank) }
            listOf("いい" to 33, "長い" to 377, "すごい" to 500).forEach { (word, rank) -> add(word, adjective, rank) }
            listOf(
                "アラスカ" to 50_367, "ツノ" to 27_491, "長" to 2_975, "いつ" to 260, "アラ" to 20_000,
                "二" to 1_269, "十" to 900, "二十" to 5_222, "十分" to 614, "分" to 503, "後" to 545,
                "あれ" to 105, "かい" to 431, "あたり" to 1_814, "まんなか" to 2_505, "酔い" to 8_878,
                "言" to 5_551, "つ" to 4_421, "て" to 20, "すげ" to 40_000, "え" to 300, "ねえ" to 500, "やつ" to 400,
            ).forEach { (word, rank) -> add(word, 0, rank) }
        },
    ).build()

    private fun splitReal(text: String): List<String> = buildList {
        Segmenter(real).segment(text) {
            val written = text.substring(it.start, it.start + it.length)
            add(if (written == it.key) written else "$written=${it.key}")
        }
    }

    @Test
    fun aShortWordIsNoUncommonInflectionOfACommonVerb() {
        // Not the imperative of ある, the stems of あたる, 酔う and いう, the potential of する, the stem of かう.
        assertEquals(listOf("あれ", "だ"), splitReal("あれだ"))
        assertEquals(listOf("まんなか", "あたり", "に"), splitReal("まんなかあたりに"))
        assertEquals(listOf("酔い", "が"), splitReal("酔いが"))
        assertEquals(listOf("いい", "だろう", "ね"), splitReal("いいだろうね"))
        assertEquals(listOf("できる", "かい"), splitReal("できるかい"))
    }

    @Test
    fun aRunOfKatakanaKeepsItsFirstAndLastLetter() {
        // Not アラス (ある) + か, not 長 + いツ (いつ) + の.
        assertEquals(listOf("アラスカ", "の"), splitReal("アラスカの"))
        assertEquals(listOf("長い", "ツノ", "を"), splitReal("長いツノを"))
    }

    @Test
    fun aNumberInKanjiStaysWhole() {
        // Not 二 + 十分 (enough).
        assertEquals(listOf("二十", "分", "後"), splitReal("二十分後"))
        // A number no dictionary lists becomes one unknown word, however it ends.
        assertEquals(listOf("十十二", "分"), splitReal("十十二分"))
        assertEquals(listOf("二二"), splitReal("二二"))
    }

    @Test
    fun oldSpellingsAreTheWordsOfToday() {
        assertEquals(listOf("払わせたら=払う", "いゝ=いい", "な"), splitReal("払わせたらいゝな"))
        assertEquals(listOf("言つてる=言う", "の", "だつた"), splitReal("言つてるのだつた"))
        assertEquals(listOf("あれ", "だらう"), splitReal("あれだらう"))
    }

    @Test
    fun roughSpeechComesBackInItsDictionaryForm() {
        assertEquals(listOf("すげえ=すごい", "やつ", "だ"), splitReal("すげえやつだ"))
        assertEquals(listOf("知らねえ=知る", "な"), splitReal("知らねえな"))
        assertEquals(listOf("行きやがる=行く"), splitReal("行きやがる"))
    }
}
