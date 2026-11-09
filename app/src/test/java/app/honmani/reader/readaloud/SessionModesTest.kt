package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModesTest {
    private val saved = mutableMapOf<String, String>()
    private val modes = SessionModes({ saved[it] }, { hash, mode -> saved[hash] = mode })

    @Test
    fun aBookOnlyReadIsReading() {
        modes.opened("a")
        assertEquals(SessionMode.READING, modes.mode("a"))
        assertFalse(modes.continueListening("a", paired = true, positionMs = 5_000))
    }

    @Test
    fun aBookNeverOpenedIsReading() {
        assertEquals(SessionMode.READING, modes.mode("a"))
    }

    @Test
    fun playingTheAudiobookMakesItListening() {
        modes.opened("a")
        modes.played("a")
        assertEquals(SessionMode.LISTENING, modes.mode("a"))
        assertTrue(modes.continueListening("a", paired = true, positionMs = 5_000))
    }

    @Test
    fun openingAgainWithoutPlayingMakesItReading() {
        modes.opened("a")
        modes.played("a")
        modes.opened("a")
        assertEquals(SessionMode.READING, modes.mode("a"))
        assertFalse(modes.continueListening("a", paired = true, positionMs = 5_000))
    }

    @Test
    fun listeningNeedsAPairingAndAPlace() {
        modes.played("a")
        assertFalse(modes.continueListening("a", paired = false, positionMs = 5_000))
        assertFalse(modes.continueListening("a", paired = true, positionMs = 0))
        assertEquals(SessionMode.READING, modes.mode("b"))
    }

    @Test
    fun everyChangeIsCountedButARepeatIsNot() {
        modes.opened("a")
        val before = modes.changes.value
        modes.opened("a")
        assertEquals(before, modes.changes.value)
        modes.played("a")
        assertEquals(before + 1, modes.changes.value)
    }

    @Test
    fun autoplayIsOneShotForTheArmedBookAndExpires() {
        modes.armAutoplay("a", now = 1_000)
        assertFalse(modes.takeAutoplay("b", now = 1_500))
        assertFalse("taking for another book disarms it", modes.takeAutoplay("a", now = 1_600))
        modes.armAutoplay("a", now = 1_000)
        assertTrue(modes.takeAutoplay("a", now = 1_500))
        assertFalse(modes.takeAutoplay("a", now = 1_600))
        modes.armAutoplay("a", now = 1_000)
        assertFalse(modes.takeAutoplay("a", now = 1_000 + SessionModes.AUTOPLAY_MS + 1))
    }
}
