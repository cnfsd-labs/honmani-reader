package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSizingTest {
    @Test
    fun portraitUsesFractions() {
        val h = PanelSizing.heights(available = 2000f, minCollapsed = 500f)
        assertEquals(1000f, h.collapsed, 0.01f)
        assertEquals(1760f, h.expanded, 0.01f)
        assertEquals(760f, h.extra, 0.01f)
    }

    @Test
    fun shortAreaGetsTheMinimumButNotMoreThanSixtyPercent() {
        val landscape = PanelSizing.heights(available = 800f, minCollapsed = 500f)
        assertEquals(480f, landscape.collapsed, 0.01f)
        assertEquals(704f, landscape.expanded, 0.01f)
        val roomy = PanelSizing.heights(available = 900f, minCollapsed = 500f)
        assertEquals(500f, roomy.collapsed, 0.01f)
    }

    @Test
    fun expandedIsNeverBelowCollapsed() {
        val h = PanelSizing.heights(available = 100f, minCollapsed = 500f)
        assertTrue(h.expanded >= h.collapsed)
    }

    @Test
    fun releaseSnapsByVelocityThenByHalf() {
        assertTrue(PanelSizing.settlesExpanded(10f, 100f, velocity = -900f, flingVelocity = 600f))
        assertFalse(PanelSizing.settlesExpanded(90f, 100f, velocity = 900f, flingVelocity = 600f))
        assertTrue(PanelSizing.settlesExpanded(60f, 100f, velocity = 0f, flingVelocity = 600f))
        assertFalse(PanelSizing.settlesExpanded(40f, 100f, velocity = 100f, flingVelocity = 600f))
    }
}
