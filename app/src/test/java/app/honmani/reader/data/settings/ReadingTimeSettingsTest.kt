package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingTimeSettingsTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("time-test", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        preferences().edit().clear().commit()
    }

    @Test
    fun countsDuringALookupUntilSwitchedOff() {
        val settings = ReadingTimeSettings(preferences())
        assertEquals(true, settings.countDuringLookup.value)

        settings.setCountDuringLookup(false)

        assertEquals(false, ReadingTimeSettings(preferences()).countDuringLookup.value)
    }

    @Test
    fun reloadReadsARestoredBackup() {
        val settings = ReadingTimeSettings(preferences())
        preferences().edit().putBoolean("count_time_during_lookup", false).commit()

        settings.reload()

        assertEquals(false, settings.countDuringLookup.value)
    }
}
