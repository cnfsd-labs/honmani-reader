package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceSettingsTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    private fun device() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("device-test", Context.MODE_PRIVATE)

    @Test
    fun randomAccentIsOffByDefault_andChangesNothing() {
        val settings = AppearanceSettings(preferences(), device())
        assertEquals(AccentRandom.OFF, settings.randomAccent.value)
        settings.refreshRandomAccent(today = 100, newLaunch = true) { 0 }
        assertEquals(Accent.DEFAULT, settings.accent.value)
    }

    @Test
    fun everyLaunchPicksOnANewLaunchOnly_andIsRemembered() {
        val settings = AppearanceSettings(preferences(), device())
        settings.setRandomAccent(AccentRandom.LAUNCH, today = 100) { 0 }
        val first = settings.accent.value
        assertNotEquals(Accent.DEFAULT, first)
        // A resume on the same day is no launch.
        settings.refreshRandomAccent(today = 100, newLaunch = false) { 0 }
        assertEquals(first, settings.accent.value)
        settings.refreshRandomAccent(today = 100, newLaunch = true) { 0 }
        assertNotEquals(first, settings.accent.value)
        assertEquals(settings.accent.value, AppearanceSettings(preferences(), device()).accent.value)
    }

    @Test
    fun everyDayKeepsTheColourWithinTheDay() {
        val settings = AppearanceSettings(preferences(), device())
        settings.setRandomAccent(AccentRandom.DAILY, today = 100) { 0 }
        val picked = settings.accent.value
        settings.refreshRandomAccent(today = 100, newLaunch = true) { 0 }
        assertEquals(picked, settings.accent.value)
        // A new process on the same day keeps it too (the day is remembered).
        val restarted = AppearanceSettings(preferences(), device())
        restarted.refreshRandomAccent(today = 100, newLaunch = true) { 0 }
        assertEquals(picked, restarted.accent.value)
        restarted.refreshRandomAccent(today = 101, newLaunch = false) { 0 }
        assertNotEquals(picked, restarted.accent.value)
    }

    @Test
    fun choosingAColourByHandTurnsRandomOff() {
        val settings = AppearanceSettings(preferences(), device())
        settings.setRandomAccent(AccentRandom.LAUNCH, today = 100) { 0 }
        settings.setAccent(Accent.RED)
        assertEquals(AccentRandom.OFF, settings.randomAccent.value)
        assertEquals(AccentRandom.OFF, AppearanceSettings(preferences(), device()).randomAccent.value)
        settings.refreshRandomAccent(today = 200, newLaunch = true) { 0 }
        assertEquals(Accent.RED, settings.accent.value)
    }

    @Test
    fun defaultsToPurple() {
        assertEquals(Accent.PURPLE, AppearanceSettings(preferences(), device()).accent.value)
    }

    @Test
    fun keepsTheChosenAccent() {
        AppearanceSettings(preferences(), device()).setAccent(Accent.TEAL)

        assertEquals(Accent.TEAL, AppearanceSettings(preferences(), device()).accent.value)
    }

    @Test
    fun unknownStoredAccentFallsBackToDefault() {
        preferences().edit().putString("accent", "MAGENTA").commit()

        assertEquals(Accent.DEFAULT, AppearanceSettings(preferences(), device()).accent.value)
    }

    @Test
    fun keepsTheCustomHue() {
        AppearanceSettings(preferences(), device()).setCustomHue(42f)

        assertEquals(42f, AppearanceSettings(preferences(), device()).customHue.value, 0f)
    }

    @Test
    fun theWordsListShowsFrequencyUnlessSwitchedOff() {
        assertEquals(true, AppearanceSettings(preferences(), device()).showWordFrequency.value)

        AppearanceSettings(preferences(), device()).setShowWordFrequency(false)

        assertEquals(false, AppearanceSettings(preferences(), device()).showWordFrequency.value)
    }

    @Test
    fun exampleSentencesAreOffUntilSwitchedOn() {
        assertEquals(false, AppearanceSettings(preferences(), device()).showExamples.value)

        AppearanceSettings(preferences(), device()).setShowExamples(true)

        assertEquals(true, AppearanceSettings(preferences(), device()).showExamples.value)
    }

    @Test
    fun theLookupPanelHidesThePreviousSentenceUntilSwitchedOn() {
        assertEquals(false, AppearanceSettings(preferences(), device()).showPreviousSentence.value)

        AppearanceSettings(preferences(), device()).setShowPreviousSentence(true)

        assertEquals(true, AppearanceSettings(preferences(), device()).showPreviousSentence.value)
    }

    @Test
    fun soundsAreOffByDefault_andSwitchOnIsRemembered() {
        assertEquals(false, AppearanceSettings(preferences(), device()).sounds.value)

        AppearanceSettings(preferences(), device()).setSounds(true)

        assertEquals(true, AppearanceSettings(preferences(), device()).sounds.value)
    }
}
