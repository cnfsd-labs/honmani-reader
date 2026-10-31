package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchLengthTest {
    @Test
    fun shorterIsOneCharacterLess() {
        assertEquals(4, MatchLength.shorter("食べました。", 5))
        assertEquals(1, MatchLength.shorter("食べました。", 2))
    }

    @Test
    fun shorterNeverEmpties() {
        assertNull(MatchLength.shorter("食べました。", 1))
        assertNull(MatchLength.shorter("", 0))
    }

    @Test
    fun shorterSkipsAWholeSurrogatePair() {
        // 𠮷 is one character of two UTF-16 units.
        assertEquals(2, MatchLength.shorter("𠮷野家", 3))
        assertNull(MatchLength.shorter("𠮷野家", 2))
    }

    @Test
    fun longerListsEveryLengthUpToTheLimit() {
        assertEquals(listOf(3, 4, 5), MatchLength.longer("食べました。", 2, 5))
    }

    @Test
    fun longerStopsAtTheTextAndNeverSplitsAPair() {
        assertEquals(listOf(1, 2), MatchLength.longer("a猫", 0, 10))
        assertEquals(listOf(3, 4), MatchLength.longer("𠮷野家", 2, 4))
        assertEquals(listOf(2), MatchLength.longer("𠮷野家", 0, 2))
    }

    @Test
    fun longerHasNothingAtTheLimit() {
        assertEquals(emptyList<Int>(), MatchLength.longer("食べました。", 5, 5))
        assertEquals(emptyList<Int>(), MatchLength.longer("食べました。", 9, 5))
    }
}
