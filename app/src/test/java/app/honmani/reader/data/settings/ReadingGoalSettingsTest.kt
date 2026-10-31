package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingGoalSettingsTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("goal-test", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        preferences().edit().clear().commit()
    }

    @Test
    fun theGoalIsOffUntilSwitchedOnThenTwentyMinutes() = runBlocking {
        val settings = ReadingGoalSettings(preferences())
        assertEquals(false, settings.enabled.value)
        assertNull(settings.goalMinutes.first())

        settings.setEnabled(true)

        assertEquals(20, settings.goalMinutes.first())
        assertEquals(true, ReadingGoalSettings(preferences()).enabled.value)
    }

    @Test
    fun keepsTheChosenMinutesAndHidesThemWhileOff() = runBlocking {
        val settings = ReadingGoalSettings(preferences())
        settings.setMinutes(45)

        assertNull(settings.goalMinutes.first())
        settings.setEnabled(true)
        assertEquals(45, settings.goalMinutes.first())
        assertEquals(45, ReadingGoalSettings(preferences()).minutes.value)
    }

    @Test
    fun minutesStayInARange() {
        val settings = ReadingGoalSettings(preferences())
        settings.setMinutes(0)
        assertEquals(1, settings.minutes.value)
        settings.setMinutes(100_000)
        assertEquals(600, ReadingGoalSettings(preferences()).minutes.value)
    }

    @Test
    fun reloadReadsARestoredBackup() {
        val settings = ReadingGoalSettings(preferences())
        preferences().edit().putBoolean("daily_goal_enabled", true).putInt("daily_goal_minutes", 30).commit()

        settings.reload()

        assertEquals(true, settings.enabled.value)
        assertEquals(30, settings.minutes.value)
    }
}
