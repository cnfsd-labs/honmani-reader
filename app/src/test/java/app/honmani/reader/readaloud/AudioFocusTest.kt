package app.honmani.reader.readaloud

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusTest {
    @Test
    fun aRequestToDuckPausesLikeATransientLoss() {
        assertTrue(pausesForFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(pausesForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertTrue(pausesForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(pausesForFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }
}
