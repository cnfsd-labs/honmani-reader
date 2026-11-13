package app.honmani.reader.readaloud

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusTest {
    private val heard = ArrayList<String>()
    private val listener = object : FocusListener {
        override fun onFocusLost() {
            heard += "lost"
        }

        override fun onFocusPaused() {
            heard += "paused"
        }

        override fun onFocusResumed() {
            heard += "resumed"
        }
    }
    private val pause = FocusPause()

    @Test
    fun aRequestToDuckOrATransientLossPausesAndKeepsTheFocusUntilItComesBack() {
        for (change in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            heard.clear()
            assertFalse(pause.onChange(change, listener) { false })
            assertTrue(pause.lostForAWhile)
            // Playing again after the gain: the focus stays.
            assertFalse(pause.onChange(AudioManager.AUDIOFOCUS_GAIN, listener) { true })
            assertFalse(pause.lostForAWhile)
            assertEquals(listOf("paused", "resumed"), heard)
        }
    }

    @Test
    fun theFocusBackWithNothingPlayingIsGivenUp() {
        pause.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, listener) { false }
        assertTrue(pause.onChange(AudioManager.AUDIOFOCUS_GAIN, listener) { false })
    }

    @Test
    fun aPermanentLossGivesTheFocusUpAndNothingResumes() {
        pause.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, listener) { false }
        assertTrue(pause.onChange(AudioManager.AUDIOFOCUS_LOSS, listener) { false })
        assertFalse(pause.lostForAWhile)
        assertFalse(pause.onChange(AudioManager.AUDIOFOCUS_GAIN, listener) { false })
        assertEquals(listOf("paused", "lost"), heard)
    }

    @Test
    fun aGainWithoutAPauseIsNotReported() {
        assertFalse(pause.onChange(AudioManager.AUDIOFOCUS_GAIN, listener) { false })
        assertEquals(emptyList<String>(), heard)
    }
}
