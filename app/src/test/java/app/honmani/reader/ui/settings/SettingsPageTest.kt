package app.honmani.reader.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageTest {
    @Test
    fun theMainListHasNoParentAndEveryOtherPageLeadsBackToIt() {
        assertNull(SettingsPage.MAIN.parent)
        for (page in SettingsPage.entries - SettingsPage.MAIN) {
            var up: SettingsPage? = page
            var steps = 0
            while (up != null && up != SettingsPage.MAIN && steps < 5) {
                up = up.parent
                steps++
            }
            assertEquals("$page", SettingsPage.MAIN, up)
        }
    }

    @Test
    fun screensUnderAGroupGoBackToThatGroup() {
        assertEquals(SettingsPage.FLASHCARDS, SettingsPage.ANKI.parent)
        assertEquals(SettingsPage.HELP, SettingsPage.ABOUT.parent)
        assertEquals(SettingsPage.HELP, SettingsPage.NETWORK.parent)
    }

    @Test
    fun groupsHaveDistinctTitles() {
        val titles = SettingsPage.entries.filter { it.parent == SettingsPage.MAIN }.map { it.title }
        assertTrue(titles.size == titles.toSet().size)
    }
}
