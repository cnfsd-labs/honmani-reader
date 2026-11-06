package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionBubbleTest {
    private val tap = TapLookup(text = "食べました。", sentence = "寿司を食べました。", sentenceOffset = 3)
    private val selection = tap.copy(bySelection = true)

    private fun results(
        tap: TapLookup = this.tap,
        length: Int = 2,
        longest: Int = length,
        shortest: Int = length,
        previous: LookupPanelState.Results? = null,
    ) = LookupPanelState.Results(
        tap = tap,
        groups = emptyList(),
        highlight = Highlight(lookupId = 1, length = length),
        longestMatch = longest,
        shortestMatch = shortest,
        previous = previous,
    )

    @Test
    fun aTapWhoseMatchCannotChangeHasNoBubble() {
        assertNull(selectionBubble(results()))
    }

    @Test
    fun aMatchThatCanGrowOrShrinkShowsItsTextAndOnlyTheArrowsThatWork() {
        val longer = selectionBubble(results(longest = 5))!!
        assertEquals("食べ", longer.text)
        assertTrue(longer.canLengthen)
        assertFalse(longer.canShorten)
        assertFalse(longer.canCopy)

        val shorter = selectionBubble(results(shortest = 1))!!
        assertTrue(shorter.canShorten)
        assertFalse(shorter.canLengthen)
    }

    @Test
    fun aSelectionAlwaysHasABubbleToCopyFrom() {
        val fixed = selectionBubble(results(tap = selection))!!
        assertTrue(fixed.canCopy)
        assertFalse(fixed.hasArrows)

        val nothing = selectionBubble(LookupPanelState.NothingFound(selection))!!
        assertEquals(selection.text, nothing.text)
        assertTrue(nothing.canCopy)
    }

    @Test
    fun aPlainTapWithoutResultsHasNoBubble() {
        assertNull(selectionBubble(LookupPanelState.NothingFound(tap)))
        assertNull(selectionBubble(LookupPanelState.NoDictionaries(tap)))
    }

    @Test
    fun aWordReachedFromInsideThePanelHasNoBubbleAndNoWordToCopy() {
        val nested = results(tap = selection, longest = 5, previous = results(tap = selection, longest = 5))
        assertNull(selectionBubble(nested))
        assertNull(copyableWord(nested))
    }

    @Test
    fun copyWordTakesTheMatchOrTheStartOfATapThatFoundNothing() {
        assertEquals("食べ", copyableWord(results()))
        assertEquals("食べました", copyableWord(LookupPanelState.NothingFound(tap)))
        assertEquals(selection.text, copyableWord(LookupPanelState.NothingFound(selection)))
    }
}
