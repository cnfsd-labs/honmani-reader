package app.honmani.reader.ui.library

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * A book's title in the library. When it doesn't fit and ends in a volume number, the number moves
 * to the front, so the volumes of a long series title stay apart ("10 無職転生 ～異世界…").
 */
@Composable
internal fun BookTitle(title: String, style: TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    val volume = remember(title) { VolumeTitle.split(title) }
    var volumeFirst by remember(title) { mutableStateOf(false) }
    val shown = if (volume != null && volumeFirst) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(volume.number) }
            append(" ")
            append(volume.rest)
        }
    } else {
        AnnotatedString(title)
    }
    Text(
        text = shown,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { if (volume != null && !volumeFirst && it.hasVisualOverflow) volumeFirst = true },
    )
}

/** Finds the volume number at the end of a title; pure, so it can be tested. */
internal object VolumeTitle {
    data class Split(val number: String, val rest: String)

    /**
     * "無職転生 ～異世界行ったら本気だす～ 01 (MFブックス)" → 1 and the title without it. Labels in
     * brackets at the end may follow the number; "第3巻", "3巻", "Vol. 3" and "（3）" count too. A bare
     * number needs a space or a closing bracket before it, so "ナンバー23" keeps its number.
     */
    fun split(title: String): Split? {
        val trimmed = title.trim()
        val tailStart = TAIL.find(trimmed)?.range?.first ?: trimmed.length
        val body = trimmed.substring(0, tailStart)
        val tail = trimmed.substring(tailStart).trim()
        END_NUMBER.find(body)?.let { match ->
            val before = match.groupValues[1]
            val marked = match.groupValues[2].isNotEmpty() || match.groupValues[4].isNotEmpty()
            val separated = before.lastOrNull()?.let { it.isWhitespace() || it in CLOSING } == true
            if (before.isNotBlank() && (marked || separated)) {
                return split(match.groupValues[3], listOf(before.trim(), tail))
            }
        }
        // "本好きの下剋上 （3）": the number is the first bracket of the tail.
        BRACKETED_NUMBER.find(tail)?.let { match ->
            if (body.isNotBlank()) return split(match.groupValues[1], listOf(body.trim(), tail.substring(match.value.length).trim()))
        }
        return null
    }

    private fun split(digits: String, parts: List<String>): Split? {
        val number = digits.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("").toInt()
        if (number < 1) return null
        return Split(number.toString(), parts.filter { it.isNotEmpty() }.joinToString(" "))
    }

    private const val DIGITS = "[0-9０-９]{1,3}"
    private const val CLOSING = "～〜】」』）)］]〕"

    /** Labels in brackets at the end: "(MFブックス)", "【電子版】". */
    private val TAIL = Regex("""(?:\s*[(（\[［【〔][^()（）\[\]［］【】〔〕]*[)）\]］】〕])+\s*$""")

    /** Group 1 the text before, 2 "第" or "Vol.", 3 the digits, 4 "巻". */
    private val END_NUMBER = Regex("""^(.*?)(第\s*|[Vv]ol\.?\s*)?($DIGITS)\s*(巻?)\s*$""")

    private val BRACKETED_NUMBER = Regex("""^[(（]\s*($DIGITS)\s*[)）]""")
}
