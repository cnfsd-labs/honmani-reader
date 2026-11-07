package app.honmani.reader.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun animatesWhenNeitherEInkNorTheSystemSwitchesItOff() {
        assertTrue(motionEnabled(eInk = false, animatorScale = 1f))
        assertTrue(motionEnabled(eInk = false, animatorScale = 0.5f))
        assertTrue(motionEnabled(eInk = false, animatorScale = 10f))
    }

    @Test
    fun stillInEInkMode() {
        assertFalse(motionEnabled(eInk = true, animatorScale = 1f))
    }

    @Test
    fun stillWhenTheSystemAnimationsAreOff() {
        assertFalse(motionEnabled(eInk = false, animatorScale = 0f))
        assertFalse(motionEnabled(eInk = true, animatorScale = 0f))
    }

    @Test
    fun theSwitchTurnsAnimationsOffEvenWhenTheSystemAllowsThem() {
        assertFalse(motionEnabled(eInk = false, animatorScale = 1f, setting = false))
    }
}
