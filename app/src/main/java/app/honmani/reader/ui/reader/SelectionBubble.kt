package app.honmani.reader.ui.reader

/**
 * What the pill above the lookup panel holds: the matched or selected text, its arrows and copy.
 * Shown only when there is something to do with it, so an ordinary tap leaves the panel plain.
 */
internal data class SelectionBubble(
    val text: String,
    val canShorten: Boolean = false,
    val canLengthen: Boolean = false,
    val canCopy: Boolean = false,
) {
    val hasArrows: Boolean get() = canShorten || canLengthen
}

/**
 * The bubble for a panel [state], or null for none: a first lookup whose match can change, or
 * anything looked up from a long-press selection (the selection is gone from the book by then, so
 * the bubble offers to copy it). A word reached from inside the panel has none.
 */
internal fun selectionBubble(state: LookupPanelState): SelectionBubble? {
    val selected = state.tap.bySelection
    return when (state) {
        is LookupPanelState.Results -> when {
            state.previous != null -> null
            selected || state.canShorten || state.canLengthen -> SelectionBubble(
                text = state.tap.text.take(state.highlight.length),
                canShorten = state.canShorten,
                canLengthen = state.canLengthen,
                canCopy = selected,
            )
            else -> null
        }
        is LookupPanelState.Kanji -> if (selected && state.previous == null) SelectionBubble(state.tap.text, canCopy = true) else null
        is LookupPanelState.NothingFound, is LookupPanelState.NoDictionaries ->
            if (selected) SelectionBubble(state.tap.text, canCopy = true) else null
    }
}

/** What "Copy word" in the panel's menu copies; null where it has no word of the tap to copy. */
internal fun copyableWord(state: LookupPanelState): String? = when (state) {
    is LookupPanelState.Results -> if (state.previous == null) state.tap.text.take(state.highlight.length) else null
    is LookupPanelState.Kanji -> null
    is LookupPanelState.NothingFound, is LookupPanelState.NoDictionaries ->
        if (state.tap.bySelection) state.tap.text else nothingFoundText(state.tap.text)
}
