package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadAloudSettingsTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @Test
    fun halfSpeedIsTheSlowestAndTheSpeedsCycleFromTheFastestBackToIt() {
        assertEquals(0.5f, ReadAloudSettings.RATES.first(), 0f)
        assertTrue(ReadAloudSettings.RATES.contains(1f))
        assertEquals(0.75f, ReadAloudSettings.nextRate(0.5f), 0f)
        assertEquals(0.5f, ReadAloudSettings.nextRate(ReadAloudSettings.RATES.last()), 0f)
    }

    @Test
    fun keepPlayingWithTheScreenOffIsOnByDefault_andRemembered() {
        val settings = ReadAloudSettings(preferences())
        assertTrue(settings.keepPlaying.value)
        settings.setKeepPlaying(false)
        assertFalse(ReadAloudSettings(preferences()).keepPlaying.value)
    }

    @Test
    fun reloadReadsKeepPlayingAgain() {
        val settings = ReadAloudSettings(preferences())
        preferences().edit().putBoolean(ReadAloudSettings.KEY_KEEP_PLAYING, false).commit()
        settings.reload()
        assertFalse(settings.keepPlaying.value)
    }
}
