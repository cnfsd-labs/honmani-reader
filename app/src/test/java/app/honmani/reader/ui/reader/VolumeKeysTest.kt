package app.honmani.reader.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeysTest {
    @Test
    fun theySwitchedOnSettingTurnsPagesWhenNothingIsReadAloud() {
        assertTrue(VolumeKeys.turnPages(setting = true, listening = false))
    }

    @Test
    fun theyChangeTheVolumeWhileReadAloudOrTheAudiobookIsOn() {
        assertFalse(VolumeKeys.turnPages(setting = true, listening = true))
    }

    @Test
    fun theyNeverTurnPagesWhenTheSettingIsOff() {
        assertFalse(VolumeKeys.turnPages(setting = false, listening = false))
        assertFalse(VolumeKeys.turnPages(setting = false, listening = true))
    }

    @Test
    fun theyChangeTheVolumeAfterListenInThePanelUntilItCloses() {
        assertFalse(VolumeKeys.turnPages(setting = true, listening = false, panelSpeaking = true))
        assertTrue(VolumeKeys.turnPages(setting = true, listening = false, panelSpeaking = false))
    }
}
