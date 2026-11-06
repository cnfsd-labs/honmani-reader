package app.honmani.reader.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSoundRuleTest {
    @Test
    fun playsOnlyWhenSwitchedOnAndNothingElseSounds() {
        assertTrue(UiSoundRule.maySound(enabled = true, readerSpeaking = false, wordAudioPlaying = false, mediaActive = false))
    }

    @Test
    fun staysSilentWhenSwitchedOff() {
        assertFalse(UiSoundRule.maySound(enabled = false, readerSpeaking = false, wordAudioPlaying = false, mediaActive = false))
    }

    @Test
    fun neverOverReadAloudTheAudiobookWordAudioOrOtherMedia() {
        assertFalse(UiSoundRule.maySound(enabled = true, readerSpeaking = true, wordAudioPlaying = false, mediaActive = false))
        assertFalse(UiSoundRule.maySound(enabled = true, readerSpeaking = false, wordAudioPlaying = true, mediaActive = false))
        assertFalse(UiSoundRule.maySound(enabled = true, readerSpeaking = false, wordAudioPlaying = false, mediaActive = true))
    }
}
