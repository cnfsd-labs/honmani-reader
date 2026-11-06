package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NothingFoundTextTest {
    @Test
    fun cutsAtTheFirstPunctuation() {
        assertEquals("だと思っていた", nothingFoundText("だと思っていた。"))
        assertEquals("彼は", nothingFoundText("彼は、言った"))
        assertEquals("ほん", nothingFoundText("ほん 本"))
    }

    @Test
    fun keepsAtMostEightCharacters() {
        assertEquals("し長く書いてあり", nothingFoundText("し長く書いてありました"))
    }

    @Test
    fun leavesTextThatStartsWithPunctuation() {
        assertEquals("。。", nothingFoundText("。。"))
    }
}
