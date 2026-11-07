package app.honmani.reader.data.stats

import app.honmani.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelTitlesTest {
    @Test
    fun aTitleHoldsForFiveLevels() {
        assertEquals(R.string.level_title_1, LevelTitles.titleFor(1))
        assertEquals(R.string.level_title_1, LevelTitles.titleFor(4))
        assertEquals(R.string.level_title_5, LevelTitles.titleFor(5))
        assertEquals(R.string.level_title_5, LevelTitles.titleFor(9))
        assertEquals(R.string.level_title_10, LevelTitles.titleFor(10))
        assertEquals(R.string.level_title_95, LevelTitles.titleFor(99))
    }

    @Test
    fun levelOneHundredAndUpIsTheLegend() {
        assertEquals(R.string.level_title_100, LevelTitles.titleFor(100))
        assertEquals(R.string.level_title_100, LevelTitles.titleFor(250))
    }

    @Test
    fun theNextTitleComesAtTheNextMultipleOfFive() {
        assertEquals(5, LevelTitles.nextStart(1))
        assertEquals(5, LevelTitles.nextStart(4))
        assertEquals(10, LevelTitles.nextStart(5))
        assertEquals(10, LevelTitles.nextStart(9))
        assertEquals(100, LevelTitles.nextStart(99))
        assertNull(LevelTitles.nextStart(100))
        assertNull(LevelTitles.nextStart(250))
        assertEquals(R.string.level_title_5, LevelTitles.nextTitleFor(2))
        assertNull(LevelTitles.nextTitleFor(250))
    }
}
