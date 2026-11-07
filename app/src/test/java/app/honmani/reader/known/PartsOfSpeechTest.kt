package app.honmani.reader.known

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsOfSpeechTest {
    private fun askable(vararg entries: String) = PartsOfSpeech.isAskable(entries.map { it.split(' ').filter(String::isNotEmpty).toSet() })

    @Test
    fun contentWordsAreAsked() {
        assertTrue(askable("v1 vt")) // 食べる
        assertTrue(askable("n")) // 猫
        assertTrue(askable("adj-na n")) // きれい
        assertTrue(askable("v5r vi", "n")) // 帰る, 変わる
        assertTrue(askable("vs n adj-no"))
        assertTrue(askable("adv adv-to"))
        assertTrue(askable("adj-i"))
    }

    @Test
    fun functionWordsAndFragmentsAreNot() {
        assertFalse(askable("prt")) // の
        assertFalse(askable("prt", "prt conj")) // には
        assertFalse(askable("exp")) // んです
        assertFalse(askable("aux-v")) // ます
        assertFalse(askable("cop")) // です
        assertFalse(askable("conj")) // しかし
        assertFalse(askable("pn")) // これ
        assertFalse(askable("suf", "n-suf")) // 的
        assertFalse(askable("int")) // ああ
        assertFalse(askable("adj-pn")) // この
    }

    @Test
    fun oneFunctionEntryKeepsAWordOutEvenIfAnotherIsANoun() {
        assertFalse(askable("n", "prt")) // の with a noun sense
        assertFalse(askable("n exp"))
    }

    @Test
    fun aWordWithoutClassesIsNotAsked() {
        assertFalse(askable(""))
        assertFalse(PartsOfSpeech.isAskable(emptyList()))
        assertFalse(askable("unc"))
    }

    @Test
    fun codesComeFromDefinitionTagsAndFromJitendexGlossaries() {
        assertEquals(setOf("n", "vs"), PartsOfSpeech.codes("n vs", "[\"to study\"]"))
        // The sense number JMdict prefixes some tags with is not a class.
        assertEquals(setOf("v1"), PartsOfSpeech.codes("1 v1", "[]"))
        val jitendex = """[{"type":"structured-content","content":[{"tag":"div","data":{"content":"sense-group"},"content":[
            {"tag":"span","title":"noun (common) (futsuumeishi)","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
            {"tag":"span","title":"transitive verb","data":{"class":"tag","code":"vt","content":"part-of-speech-info"},"content":"transitive"},
            {"tag":"span","data":{"code":"on","content":"misc-info"},"content":"x"}]}]}]"""
        assertEquals(setOf("n", "vt"), PartsOfSpeech.codes(null, jitendex))
        assertEquals(emptySet<String>(), PartsOfSpeech.codes(null, "[\"cat\"]"))
    }
}
