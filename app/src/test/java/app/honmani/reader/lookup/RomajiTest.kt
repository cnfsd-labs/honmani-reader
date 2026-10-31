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
}
