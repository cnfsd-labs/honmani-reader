package app.honmani.reader.ui.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.settings.HighlightSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HighlightExtentTest {
    private val all = setOf(HighlightExtent.Selection, HighlightExtent.Sentence, HighlightExtent.Paragraph)

    @Test
    fun withoutAChoiceASentenceIsTakenIfThePageFoundOne_elseTheSelection() {
        assertEquals(HighlightExtent.Sentence, HighlightExtent.initial(all, null))
        assertEquals(HighlightExtent.Selection, HighlightExtent.initial(setOf(HighlightExtent.Selection), null))
        assertEquals(HighlightExtent.Selection, HighlightExtent.initial(setOf(HighlightExtent.Selection, HighlightExtent.Paragraph), null))
    }

    @Test
    fun theChoiceIsTakenWhereOffered_andAnUnavailableOneFallsBackToTheDefault() {
        assertEquals(HighlightExtent.Paragraph, HighlightExtent.initial(all, "Paragraph"))
        assertEquals(HighlightExtent.Selection, HighlightExtent.initial(all, "Selection"))
        assertEquals(HighlightExtent.Sentence, HighlightExtent.initial(setOf(HighlightExtent.Selection, HighlightExtent.Sentence), "Paragraph"))
        assertEquals(HighlightExtent.Selection, HighlightExtent.initial(setOf(HighlightExtent.Selection), "Paragraph"))
        assertEquals(HighlightExtent.Sentence, HighlightExtent.initial(all, "nonsense"))
    }

    @Test
    fun theChoiceIsKeptBetweenLaunchesAndReadAgainAfterARestore() {
        val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("highlight-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = HighlightSettings(preferences)
        assertNull(settings.extent.value)

        settings.setExtent(HighlightExtent.Paragraph.name)

        assertEquals("Paragraph", HighlightSettings(preferences).extent.value)
        preferences.edit().putString(HighlightSettings.KEY_EXTENT, "Selection").commit()
        settings.reload()
        assertEquals("Selection", settings.extent.value)
    }
}
