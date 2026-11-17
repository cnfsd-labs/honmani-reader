package app.honmani.reader.ui.reader

import app.honmani.reader.data.library.HIGHLIGHT_TEXT_MAX
import app.honmani.reader.data.library.HighlightEntity

/**
 * The plain text sent to the share sheet for highlights: each passage in 「」, then the book's title
 * after a dash (once at the end when several are shared, separated by blank lines). A passage
 * kept only in part (see [HIGHLIGHT_TEXT_MAX]) ends in "…".
 */
internal object HighlightShareText {
    fun one(highlight: HighlightEntity, title: String?): String = all(listOf(highlight), title)

    fun all(highlights: List<HighlightEntity>, title: String?): String {
        val quotes = highlights.map { quote(it.text) }
        val source = title?.trim()?.takeIf { it.isNotEmpty() }?.let { "— $it" }
        return (quotes + listOfNotNull(source)).joinToString(if (highlights.size > 1) "\n\n" else "\n")
    }

    private fun quote(text: String): String {
        val passage = text.trim()
        return if (text.length >= HIGHLIGHT_TEXT_MAX) "「$passage…」" else "「$passage」"
    }
}
