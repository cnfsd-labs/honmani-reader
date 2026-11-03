package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookPlaceTest {
    @Test
    fun theSliderMapsToCharactersAndBack() {
        assertEquals(0L, BookPlace.charactersAt(0f, 24_847))
        assertEquals(24_847L, BookPlace.charactersAt(1f, 24_847))
        assertEquals(10_436L, BookPlace.charactersAt(0.42f, 24_847))
        assertEquals(24_847L, BookPlace.charactersAt(3f, 24_847))
        assertEquals(0L, BookPlace.charactersAt(0.5f, 0))
        assertEquals(0.5f, BookPlace.fraction(50, 100), 1e-6f)
        assertEquals(0f, BookPlace.fraction(5, 0), 0f)
        assertEquals(1f, BookPlace.fraction(500, 100), 0f)
    }

    @Test
    fun percentIsRoundedDownAndKeptInRange() {
        assertEquals(42, BookPlace.percent(10_436, 24_847))
        assertEquals(0, BookPlace.percent(5, 0))
        assertEquals(100, BookPlace.percent(200, 100))
    }

    @Test
    fun theDefaultNameIsTheFirstTwentyCharactersWithBlanksTidied() {
        assertEquals("吾輩は猫である。 名前はまだ無い。", BookPlace.defaultName("  吾輩は猫である。\n  名前はまだ無い。"))
        assertEquals("あ".repeat(20), BookPlace.defaultName("あ".repeat(50)))
        // Not cut inside a surrogate pair.
        assertEquals("\uD842\uDFB7".repeat(20), BookPlace.defaultName("\uD842\uDFB7".repeat(30)))
        assertNull(BookPlace.defaultName(" \n "))
    }
}
