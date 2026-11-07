package app.honmani.reader.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelMomentsTest {
    @Test
    fun theFirstRunTakesTheLevelAsKnownAndSaysNothing() {
        val decision = LevelMoments.decide(celebrated = null, current = 12)
        assertEquals(12, decision.remember)
        assertNull(decision.announce)
    }

    @Test
    fun noChangeAnnouncesNothing() {
        assertEquals(LevelDecision(), LevelMoments.decide(celebrated = 7, current = 7))
    }

    @Test
    fun aLowerLevelAnnouncesNothing() {
        assertEquals(LevelDecision(), LevelMoments.decide(celebrated = 9, current = 7))
    }

    @Test
    fun oneLevelUpWithinATitleHasNoNewTitle() {
        assertEquals(
            LevelDecision(announce = LevelMoment(level = 12, newTitle = false)),
            LevelMoments.decide(celebrated = 11, current = 12),
        )
    }

    @Test
    fun oneLevelUpOntoATitleStartIsANewTitle() {
        assertEquals(
            LevelDecision(announce = LevelMoment(level = 15, newTitle = true)),
            LevelMoments.decide(celebrated = 14, current = 15),
        )
    }

    @Test
    fun severalLevelsMakeOneAnnouncementForTheCurrentLevel() {
        assertEquals(
            LevelDecision(announce = LevelMoment(level = 21, newTitle = true)),
            LevelMoments.decide(celebrated = 12, current = 21),
        )
        assertEquals(
            LevelDecision(announce = LevelMoment(level = 3, newTitle = false)),
            LevelMoments.decide(celebrated = 1, current = 3),
        )
    }
}
