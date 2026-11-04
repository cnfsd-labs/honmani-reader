package app.honmani.reader.ui.reader

import androidx.compose.ui.graphics.Color
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.sync.SectionStarts
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The colours of the reader's highlights, in the order of [HighlightEntity.color]: yellow, green,
 * blue, pink. `assets/honmani/highlights.js` draws them with the same hues.
 */
val HIGHLIGHT_SWATCHES = listOf(Color(0xFFFFD500), Color(0xFF3CC864), Color(0xFF4696FF), Color(0xFFFF5AA0))

/**
 * What the page needs to draw a book's highlights (`highlights.js`): where each of its documents
 * begins, and the highlights as positions in the book. The page ignores an answer for another book.
 */
fun highlightsMessage(bookId: Long, sections: SectionStarts, highlights: List<HighlightEntity>): String =
    buildJsonObject {
        put("type", "highlights")
        put("bookId", bookId)
        putJsonArray("sections") {
            for ((id, start) in sections.starts) addJsonArray { add(kotlinx.serialization.json.JsonPrimitive(id)); add(kotlinx.serialization.json.JsonPrimitive(start)) }
        }
        put("total", sections.total)
        putJsonArray("items") {
            for (highlight in highlights) {
                addJsonObject {
                    put("id", highlight.id)
                    put("start", highlight.start)
                    put("end", highlight.end)
                    put("color", highlight.color)
                }
            }
        }
    }.toString()
