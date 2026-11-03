package app.honmani.reader.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NestedLookupTest {
    @Test
    fun scansFromTheTappedCharacterOn() {
        assertEquals("食べる人", NestedLookup.scanText("私は食べる人", 2))
    }

    @Test
    fun scansAtMostTwentyCharacters() {
        val text = "あ".repeat(50)
        assertEquals(20, NestedLookup.scanText(text, 5)?.length)
    }

    @Test
    fun latinTextDigitsPunctuationAndSpacesDoNothing() {
        val text = "to eat 食べる 3。"
        assertNull(NestedLookup.scanText(text, 0))
        assertNull(NestedLookup.scanText(text, 6))
        assertNull(NestedLookup.scanText(text, 11))
        assertNull(NestedLookup.scanText(text, text.length - 1))
    }

    @Test
    fun outOfRangeIndexesDoNothing() {
        assertNull(NestedLookup.scanText("食べる", -1))
        assertNull(NestedLookup.scanText("食べる", 3))
        assertNull(NestedLookup.scanText("", 0))
    }

    @Test
    fun keepsRareKanjiOutsideTheBasicPlaneWhole() {
        val rare = "𠮷" // 𠮷
        val text = "あ" + rare.repeat(30)
        val scanned = NestedLookup.scanText(text, 0)!!
        assertFalse(scanned.last().isHighSurrogate())
        assertEquals(rare, NestedLookup.scanText(text, 1)!!.take(2))
    }

    @Test
    fun longVowelAndRepeatMarksAreJapanese() {
        assertEquals("ーー", NestedLookup.scanText("ーー", 0))
        assertEquals("々", NestedLookup.scanText("々", 0))
    }
}
