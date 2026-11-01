package app.honmani.reader.lookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiTest {
    @Test
    fun kanaInHepburn() {
        assertEquals("wagahaihanekodearu", Romaji.fromKana("わがはいはねこである"))
        assertEquals("toukyou", Romaji.fromKana("とうきょう"))
        assertEquals("shashin", Romaji.fromKana("しゃしん"))
        assertEquals("matcha", Romaji.fromKana("まっちゃ"))
        assertEquals("kitte", Romaji.fromKana("きって"))
        assertEquals("kan'i", Romaji.fromKana("かんい"))
        assertEquals("ko-hi-", Romaji.fromKana("コーヒー"))
        assertEquals("fairu", Romaji.fromKana("ファイル"))
        assertEquals("ti-shatsu", Romaji.fromKana("ティーシャツ"))
        assertEquals("noruwei", Romaji.fromKana("ノルウェイ"))
    }

    @Test
    fun looseFormsMeet() {
        fun same(a: String, b: String) = assertEquals(Romaji.loose(a), Romaji.loose(b))
        same("toukyou", "tokyo")
        same("tôkyô", "Tokyo")
        same("wagahaiha", "wagahai wa")
        same("shinbun", "shimbun")
        same("tsuki", "tuki")
        same("shashin", "syasin")
        same("konnichiha", "konichiwa")
        same("matcha", "maccha")
    }

    @Test
    fun recognisesRomajiQueries() {
        assertTrue(Romaji.isRomaji("Neko"))
        assertTrue(Romaji.isRomaji("tôkyô"))
        assertFalse(Romaji.isRomaji("ねこ"))
        assertFalse(Romaji.isRomaji("123"))
    }

    @Test
    fun readsKanjiWithTheDictionary() = runBlocking {
        val words = mapOf("吾輩" to "わがはい", "猫" to "ねこ")
        val reading = TextReading.of("吾輩は猫である") { text ->
            words.entries.firstOrNull { text.startsWith(it.key) }?.let { it.key.length to it.value }
        }
        assertEquals("わがはいはねこである", reading)
        assertEquals("tokyo", Romaji.loose(Romaji.fromKana(TextReading.of("東京") { 2 to "とうきょう" })))
    }

    @Test
    fun readsKanjiOutsideTheBasicPlane() = runBlocking {
        // 𠮟 (U+20B9F) is a surrogate pair; the segment is cut on whole code points.
        val segments = mutableListOf<String>()
        val reading = TextReading.of("𠮟る") { text ->
            segments += text
            if (text.startsWith("𠮟")) 2 to "しか" else null
        }
        assertEquals("しかる", reading)
        assertEquals(listOf("𠮟る"), segments)
    }

    @Test
    fun readingOfAnInflectedSource() {
        assertEquals("たべた", TextReading.readingOf("食べた", "食べる", "たべる", matchedExpression = true))
        assertEquals("ねこ", TextReading.readingOf("猫", "猫", "ねこ", matchedExpression = true))
        assertEquals("ねこ", TextReading.readingOf("ねこ", "猫", "ねこ", matchedExpression = false))
    }

    private fun kana(text: String) = Romaji.toKana(text).also { assertTrue(text, it.complete) }.kana

    @Test
    fun typedRomajiBecomesKanaAsOnAJapaneseKeyboard() {
        assertEquals("たべる", kana("taberu"))
        assertEquals("たべる", kana("TABERU"))
        assertEquals("しゃつ", kana("shatsu"))
        assertEquals("しゃつ", kana("syatu"))
        assertEquals("ちず", kana("tizu"))
        assertEquals("つくえ", kana("tukue"))
        assertEquals("まっちゃ", kana("matcha"))
        assertEquals("きって", kana("kitte"))
        assertEquals("こんにちは", kana("konnichiha"))
        assertEquals("こんにちは", kana("konnnichiha"))
        assertEquals("かんぱい", kana("kampai"))
        assertEquals("かんぱい", kana("kanpai"))
        assertEquals("たんい", kana("tan'i"))
        assertEquals("ほん", kana("honn"))
        assertEquals("とうきょう", kana("tōkyō"))
        assertEquals("こーひー", kana("ko-hi-"))
        assertEquals("ふぁいる", kana("fairu"))
        assertEquals("じゃま", kana("jama"))
        assertEquals("じゃま", kana("zyama"))
        assertEquals("ねこです", kana("ねこdesu"))
    }

    @Test
    fun aHalfTypedSyllableStaysOpen() {
        assertEquals(Romaji.Typed("た", listOf("ば", "び", "ぶ", "べ", "ぼ")), Romaji.toKana("tab"))
        assertEquals(Romaji.Typed("た", listOf("ば", "び", "ぶ", "べ", "ぼ")), Romaji.toKana("たb"))
        assertEquals(listOf("し"), Romaji.toKana("sh").pending)
        assertEquals(listOf("つ"), Romaji.toKana("ts").pending)
        assertEquals(listOf("きゃ", "きゅ", "きょ"), Romaji.toKana("ky").pending)
        assertEquals(listOf("た", "ち", "つ", "て", "と"), Romaji.toKana("t").pending)
        // A final n is ん or the start of the n row.
        val hon = Romaji.toKana("hon")
        assertEquals("ほ", hon.kana)
        assertEquals(listOf("な", "に", "ぬ", "ね", "の", "ん").sorted(), hon.pending.sorted())
    }

    @Test
    fun searchQueriesFromRomaji() {
        val full = SearchQuery.parse("neko")
        assertEquals(listOf("ねこ", "ネコ"), full.exact)
        assertEquals(listOf("ねこ", "ネコ"), full.prefixes)
        val half = SearchQuery.parse("tab")
        assertTrue(half.exact.isEmpty())
        assertEquals(listOf("たば", "タバ", "たび", "タビ", "たぶ", "タブ", "たべ", "タベ", "たぼ", "タボ"), half.prefixes)
        assertEquals(listOf("ほん", "ホン"), SearchQuery.parse("hon").exact)
        assertEquals(listOf("猫"), SearchQuery.parse(" 猫 ").exact)
        assertEquals(listOf("こーひー", "コーヒー"), SearchQuery.parse("ｋｏ－ｈｉ－").exact)
    }

    @Test
    fun romajiAlsoLooksForUnwrittenLongVowels() {
        val tokyo = SearchQuery.parse("tokyo")
        assertEquals(listOf("ときょ", "トキョ"), tokyo.exact)
        assertTrue(tokyo.longVowels.toString(), tokyo.longVowels.containsAll(listOf("とうきょう", "とおきょお", "トーキョー")))
        assertTrue("ときょ" !in tokyo.longVowels)
        assertTrue(SearchQuery.parse("ru").longVowels.contains("るう"))
        // Written out already, typed in kana, or with no o or u: nothing to add.
        assertTrue(SearchQuery.parse("ou").longVowels.none { it.startsWith("おうう") })
        assertTrue(SearchQuery.parse("ときょ").longVowels.isEmpty())
        assertTrue(SearchQuery.parse("taberi").longVowels.isEmpty())
    }

    @Test
    fun wildcardsBecomeGlobPatterns() {
        assertEquals(listOf("た*る", "タ*ル"), SearchQuery.parse("ta*ru").patterns)
        assertEquals(listOf("食?"), SearchQuery.parse("食？").patterns)
        assertEquals(listOf("た*", "タ*"), SearchQuery.parse("tab*").patterns)
        assertEquals(listOf("[[]*"), SearchQuery.parse("[*").patterns)
        assertTrue(SearchQuery.parse("**").isEmpty)
        assertTrue(SearchQuery.parse("  ").isEmpty)
    }
}
