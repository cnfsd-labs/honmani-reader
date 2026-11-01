package app.honmani.reader.ui.reader

/** Collapsed and expanded height of the lookup panel, in the unit of the available height (pixels). */
internal data class PanelHeights(val collapsed: Float, val expanded: Float) {
    /** How far the panel grows from collapsed to expanded. */
    val extra: Float get() = expanded - collapsed
}

/** The lookup panel's heights and snapping: fixed, so its top edge stays put from word to word. */
internal object PanelSizing {
    const val COLLAPSED_FRACTION = 0.4f
    const val EXPANDED_FRACTION = 0.88f

    /**
     * Collapsed is [COLLAPSED_FRACTION] of [available], but in a short (landscape) area at least
     * [minCollapsed], never more than 60% of it; expanded is [EXPANDED_FRACTION].
     */
    fun heights(available: Float, minCollapsed: Float): PanelHeights {
        val collapsed = maxOf(available * COLLAPSED_FRACTION, minOf(available * 0.6f, minCollapsed))
        return PanelHeights(collapsed, maxOf(collapsed, available * EXPANDED_FRACTION))
    }

    /**
     * Whether a released drag settles expanded: a fling upward (negative [velocity]) faster than
     * [flingVelocity] opens, downward closes, otherwise the nearer end of [maxExtra] wins.
     */
    fun settlesExpanded(extra: Float, maxExtra: Float, velocity: Float, flingVelocity: Float): Boolean = when {
        velocity < -flingVelocity -> true
        velocity > flingVelocity -> false
        else -> extra > maxExtra / 2f
    }
}
