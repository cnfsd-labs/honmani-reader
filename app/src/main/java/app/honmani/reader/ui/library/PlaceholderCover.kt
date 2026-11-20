package app.honmani.reader.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A cover for books without one, like a Japanese stitched book (和本): a cloth binding in a
 * traditional colour picked by the title, the stitching on the right, and the title on a cream
 * slip (題簽) at the top left, written vertically when it is Japanese.
 */
@Composable
internal fun PlaceholderCover(title: String, modifier: Modifier = Modifier) {
    val binding = CoverTitle.binding(title)
    BoxWithConstraints(
        // Decorative, like a cover image: every place showing a cover shows the title as text too,
        // and TalkBack would read the slip's vertical columns character by character.
        modifier = modifier.clearAndSetSemantics {}.background(
            Brush.verticalGradient(listOf(binding, lerp(binding, Color.Black, 0.28f))),
        ),
    ) {
        val width = maxWidth
        val height = maxHeight
        Canvas(Modifier.fillMaxSize()) { drawBinding(binding) }
        // Too small for words (the "Continue reading" bubble): just the slip.
        val withText = width >= 48.dp
        val vertical = CoverTitle.isVertical(title)
        val slipTop = height * 0.07f
        val slipStart = width * 0.1f
        if (vertical) {
            val slipHeight = height * 0.64f
            val columns = CoverTitle.columns(title, maxColumns = 3)
            val perColumn = columns.maxOf { it.length }.coerceAtLeast(1)
            // Characters as large as the slip allows, but never more than a fifth of the cover.
            val glyph = min((slipHeight * 0.86f / perColumn).value, (width * 0.2f).value).dp
            val slipWidth = glyph * columns.size * 1.12f + glyph * 0.5f
            Slip(
                modifier = Modifier
                    .offset(slipStart, slipTop)
                    .size(slipWidth.coerceAtLeast(width * 0.16f), slipHeight),
            ) {
                if (withText) {
                    // Columns run from right to left.
                    Row(horizontalArrangement = Arrangement.spacedBy(glyph * 0.12f)) {
                        for (column in columns.asReversed()) {
                            Text(
                                text = CoverTitle.glyphs(column).joinToString("\n"),
                                style = slipTextStyle(glyph),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(glyph * 1.05f),
                            )
                        }
                    }
                }
            }
        } else {
            Slip(
                modifier = Modifier
                    .offset(slipStart, slipTop)
                    .width(width * 0.66f),
            ) {
                if (withText) {
                    Text(
                        text = title,
                        style = slipTextStyle((width.value * 0.1f).dp).copy(
                            lineHeightStyle = LineHeightStyle.Default,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = width * 0.04f, vertical = width * 0.06f),
                    )
                } else {
                    Box(Modifier.size(width * 0.66f, height * 0.3f))
                }
            }
        }
    }
}

/** The cream title slip with a thin inner rule. */
@Composable
private fun Slip(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(SLIP, RoundedCornerShape(1.dp))
            .padding(2.dp)
            .background(SLIP_RULE, RoundedCornerShape(1.dp))
            .padding(1.dp)
            .background(SLIP, RoundedCornerShape(1.dp)),
    ) {
        content()
    }
}

@Composable
private fun slipTextStyle(glyph: Dp): TextStyle {
    val size = with(LocalDensity.current) { glyph.toSp() }
    return TextStyle(
        color = INK,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = size,
        lineHeight = size * 1.02f,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
}

/** Four-hole stitching (四つ目綴じ) on the right, over a slightly darker spine. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBinding(binding: Color) {
    val w = size.width
    val h = size.height
    val spine = w * 0.11f
    drawRect(lerp(binding, Color.Black, 0.18f), topLeft = Offset(w - spine, 0f), size = androidx.compose.ui.geometry.Size(spine, h))
    drawLine(Color.Black.copy(alpha = 0.25f), Offset(w - spine, 0f), Offset(w - spine, h), strokeWidth = w * 0.008f)
    val thread = SLIP.copy(alpha = 0.75f)
    val stroke = (w * 0.014f).coerceAtLeast(1f)
    val x = w - spine * 0.5f
    val holes = listOf(0.1f, 0.36f, 0.64f, 0.9f).map { it * h }
    drawLine(thread, Offset(x, holes.first()), Offset(x, holes.last()), strokeWidth = stroke)
    for (y in holes) {
        drawLine(thread, Offset(x, y), Offset(w, y), strokeWidth = stroke)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = stroke * 1.4f, center = Offset(x, y))
        drawCircle(thread, radius = stroke * 1.4f, center = Offset(x, y), style = Stroke(stroke * 0.6f))
    }
    // Thread round the top and bottom edges.
    drawLine(thread, Offset(x, holes.first()), Offset(x, 0f), strokeWidth = stroke)
    drawLine(thread, Offset(x, holes.last()), Offset(x, h), strokeWidth = stroke)
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

private val SLIP = Color(0xFFF2EAD3)
private val SLIP_RULE = Color(0xFF8A7F68)
private val INK = Color(0xFF26221C)

/** Text decisions for [PlaceholderCover]; pure, so they can be tested. */
internal object CoverTitle {
    /** Traditional binding colours (藍, 紺, 臙脂, 鳶, 海松, 千歳緑, 江戸紫, 鉄), all dark enough for the slip. */
    private val BINDINGS = longArrayOf(
        0xFF165E83, 0xFF223A70, 0xFF9E3D3F, 0xFF8C4A3A,
        0xFF5E5A2E, 0xFF316745, 0xFF5B3F86, 0xFF2E4D4C,
    )

    /** The same title always gets the same colour; different titles usually differ. */
    fun bindingIndex(title: String): Int = Math.floorMod(title.hashCode(), BINDINGS.size)

    fun binding(title: String): Color = Color(BINDINGS[bindingIndex(title)])

    /** Mostly Japanese text is set vertically, like the book itself. */
    fun isVertical(title: String): Boolean {
        val letters = title.filterNot { it.isWhitespace() }
        if (letters.isEmpty()) return false
        return letters.count(::isJapanese) * 10 >= letters.length * 6
    }

    /**
     * The title as vertical columns, first column first: punctuation in its vertical forms,
     * spaces dropped, split into at most [maxColumns] even columns (the last ends in "︙" if
     * the title is longer than [MAX_PER_COLUMN] per column).
     */
    fun columns(title: String, maxColumns: Int): List<String> {
        val text = glyphs(title.filterNot { it.isWhitespace() }.map { VERTICAL_FORMS[it] ?: it }.joinToString(""))
        if (text.isEmpty()) return listOf("")
        val count = ((text.size + SHORT_COLUMN - 1) / SHORT_COLUMN).coerceIn(1, maxColumns)
        val perColumn = ((text.size + count - 1) / count).coerceAtMost(MAX_PER_COLUMN)
        val columns = text.chunked(perColumn).take(count)
        val shown = columns.sumOf { it.size }
        val kept = if (shown < text.size) columns.dropLast(1) + listOf(columns.last().dropLast(1) + "︙") else columns
        return kept.map { it.joinToString("") }
    }

    /**
     * The characters as they are set, one per line in a column: a character outside the BMP (𠮟)
     * stays whole, and combining marks, variation selectors and joined characters stay with
     * the one before.
     */
    fun glyphs(text: String): List<String> {
        val glyphs = mutableListOf<StringBuilder>()
        var joinNext = false
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val attaches = glyphs.isNotEmpty() && (joinNext || attachesToPrevious(codePoint))
            if (attaches) glyphs.last().appendCodePoint(codePoint) else glyphs.add(StringBuilder().appendCodePoint(codePoint))
            joinNext = codePoint == ZERO_WIDTH_JOINER
            i += Character.charCount(codePoint)
        }
        return glyphs.map { it.toString() }
    }

    private fun attachesToPrevious(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF || codePoint == ZERO_WIDTH_JOINER
    }

    private const val ZERO_WIDTH_JOINER = 0x200D

    /** Up to this many characters stay in one column. */
    private const val SHORT_COLUMN = 8
    private const val MAX_PER_COLUMN = 10

    private fun isJapanese(c: Char): Boolean =
        c in '　'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' || c in '＀'..'￯'

    private val VERTICAL_FORMS = mapOf(
        'ー' to '︱', '—' to '︱', '―' to '︱', '－' to '︱', '-' to '︱',
        '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄',
        '（' to '︵', '）' to '︶', '(' to '︵', ')' to '︶',
        '【' to '︻', '】' to '︼', '〈' to '︿', '〉' to '﹀', '《' to '︽', '》' to '︾',
        '、' to '︑', '。' to '︒', '…' to '︙', '‥' to '︰',
        '：' to '︓', '；' to '︔', '！' to '︕', '？' to '︖', '～' to '≀', '〜' to '≀',
    )
}
