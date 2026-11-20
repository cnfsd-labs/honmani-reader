package app.honmani.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackTest {
    @Test
    fun theVoicePausesWhenTheAppLeavesTheScreenOnlyWithKeepPlayingOff() {
        assertFalse(BackgroundPlayback.pauseWhenStopped(keepPlaying = true))
        assertTrue(BackgroundPlayback.pauseWhenStopped(keepPlaying = false))
    }

    @Test
    fun theNotificationShowsWhileAVoicePlaysOrIsPaused() {
        val off = AudiobookPhase.OFF
        assertEquals(NowPlaying("本", true), BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.SPEAKING, off))
        assertEquals(NowPlaying("本", true), BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.STARTING, off))
        assertEquals(NowPlaying("本", false), BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.PAUSED, off))
        assertEquals(NowPlaying("本", true), BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.OFF, AudiobookPhase.PLAYING))
        assertEquals(NowPlaying("本", false), BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.OFF, AudiobookPhase.PAUSED))
    }

    @Test
    fun noNotificationWhenNothingPlays_itEnded_orKeepPlayingIsOff() {
        assertNull(BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.OFF, AudiobookPhase.OFF))
        assertNull(BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.ENDED, AudiobookPhase.OFF))
        assertNull(BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.PROBLEM, AudiobookPhase.OFF))
        assertNull(BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.OFF, AudiobookPhase.ENDED))
        assertNull(BackgroundPlayback.nowPlaying(true, "本", ReadAloudPhase.OFF, AudiobookPhase.LOADING))
        assertNull(BackgroundPlayback.nowPlaying(false, "本", ReadAloudPhase.SPEAKING, AudiobookPhase.OFF))
        assertNull(BackgroundPlayback.nowPlaying(false, "本", ReadAloudPhase.OFF, AudiobookPhase.PLAYING))
    }

    @Test
    fun theHubSaysWhenTheServiceHasToStart() {
        val hub = PlaybackHub()
        assertTrue(hub.publish(NowPlaying("本", true)))
        assertFalse(hub.publish(NowPlaying("本", false)))
        assertFalse(hub.publish(null))
        assertNull(hub.now.value)
        assertTrue(hub.publish(NowPlaying("本", true)))
    }
}
