package app.honmani.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.honmani.reader.lookup.GlossItem
import app.honmani.reader.lookup.GlossLine
import app.honmani.reader.lookup.GlossStyle
import app.honmani.reader.lookup.NestedLookup

/**
 * A dictionary's definitions as laid out by [app.honmani.reader.lookup.GlossLayout]: meanings
 * with their labels, example sentences with furigana and the word marked, notes and references.
 * A tap on a referenced word looks it up ([onLookup]); so does a tap on any other Japanese
 * character when [scanTaps] is on, with the text from there on (see [NestedLookup]).
 */
@Composable
fun GlossLines(
    lines: List<GlossLine>,
    onLookup: (String) -> Unit,
    modifier: Modifier = Modifier,
    scanTaps: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line -> GlossLineRow(line.tagsLast(), onLookup, scanTaps) }
    }
}

@Composable
private fun GlossLineRow(line: GlossLine, onLookup: (String) -> Unit, scanTaps: Boolean) {
    val style = line.style.textStyle()
    val color = line.style.color()
    val top = if (line.style == GlossStyle.EXAMPLE) 2.dp else 0.dp
    Row(modifier = Modifier.padding(start = INDENT * maxOf(line.depth - 1, 0), top = top)) {
        line.marker?.let {
            Text(text = it, style = style, color = color, modifier = Modifier.widthIn(min = MARKER_WIDTH))
        }
        // Examples and notes sit under the meaning they belong to.
        val inset = if (line.marker == null && line.depth > 0) MARKER_WIDTH else 0.dp
        if (line.hasRuby) {
            RubyFlow(line.items, style, color, onLookup, scanTaps, Modifier.padding(start = inset))
        } else {
            ScanText(
                text = annotated(line.items, onLookup),
                style = style,
                color = color,
                onScan = if (scanTaps) onLookup else null,
                modifier = Modifier.padding(start = inset),
            )
        }
    }
}

/**
 * Text without furigana: one wrapping text, labels as grey chips (set apart from the meaning
 * and from the accent-coloured references), references as links.
 */
@Composable
private fun annotated(items: List<GlossItem>, onLookup: (String) -> Unit): AnnotatedString {
    val tag = SpanStyle(
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 0.8.em,
    )
    val keyword = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    return buildAnnotatedString {
        items.forEachIndexed { i, item ->
            when (item) {
                is GlossItem.Tag -> {
                    // A label after text is set off by a space.
                    if (i > 0 && items[i - 1] !is GlossItem.Tag) append(' ')
                    withStyle(tag) { append(" ${item.text} ") }
                    if (items.getOrNull(i + 1) != null) append(' ')
                }
                is GlossItem.Text -> {
                    val styled: AnnotatedString.Builder.() -> Unit = {
                        when {
                            item.keyword -> withStyle(keyword) { append(item.text) }
                            item.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(item.text) }
                            else -> append(item.text)
                        }
                    }
                    val query = item.query
                    if (query != null) {
                        withLink(LinkAnnotation.Clickable(query, link) { onLookup(query) }) { styled() }
                    } else {
                        styled()
                    }
                }
                is GlossItem.Ruby -> append(item.base) // only in lines without furigana, so not reached
            }
        }
    }
}

/**
 * Text with furigana: each kanji group carries its reading above it, and the text between
 * wraps by character, as Japanese does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RubyFlow(
    items: List<GlossItem>,
    style: TextStyle,
    color: Color,
    onLookup: (String) -> Unit,
    scanTaps: Boolean,
    modifier: Modifier = Modifier,
) {
    val lookUp by rememberUpdatedState(onLookup)
    // The line as plain text, to scan from a tapped character on (a label counts as a space).
    val plain = remember(items) { plainText(items) }
    var position = 0
    val keywordColor = MaterialTheme.colorScheme.primary
    val rubyStyle = style.copy(fontSize = (style.fontSize.value * RUBY_SCALE).sp, lineHeight = (style.fontSize.value * RUBY_SCALE * 1.1f).sp)
    FlowRow(modifier = modifier, itemVerticalAlignment = Alignment.Bottom) {
        items.forEach { item ->
            val query = (item as? GlossItem.Text)?.query ?: (item as? GlossItem.Ruby)?.query
            val clickable = if (query != null) Modifier.clickable { onLookup(query) } else Modifier
            // A character without a reference scans the line from itself on.
            fun scanning(at: Int): Modifier {
                val scan = if (scanTaps && query == null) NestedLookup.scanText(plain, at) else null
                return if (scan == null) Modifier else Modifier.pointerInput(scan) { onTapDo { lookUp(scan) } }
            }
            val start = position
            position += when (item) {
                is GlossItem.Ruby -> item.base.length
                is GlossItem.Text -> item.text.length
                is GlossItem.Tag -> 1
            }
            when (item) {
                is GlossItem.Ruby -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = clickable.then(scanning(start))) {
                    Text(item.reading, style = rubyStyle, color = color, maxLines = 1, softWrap = false)
                    Text(
                        item.base,
                        style = style,
                        color = if (item.keyword || query != null) keywordColor else color,
                        fontWeight = if (item.keyword) FontWeight.Bold else null,
                    )
                }
                is GlossItem.Text -> tokens(item.text).let { tokens ->
                    var at = start
                    tokens.map { token -> (at to token).also { at += token.length } }
                }.forEach { (tokenStart, token) ->
                    Column(modifier = clickable.then(scanning(tokenStart))) {
                        // An empty ruby line keeps every token on the same baseline.
                        Text("", style = rubyStyle)
                        Text(
                            token,
                            style = style,
                            color = if (item.keyword || query != null) keywordColor else color,
                            fontWeight = if (item.keyword) FontWeight.Bold else if (item.bold) FontWeight.SemiBold else null,
                        )
                    }
                }
                is GlossItem.Tag -> Column {
                    Text("", style = rubyStyle)
                    Text(
                        item.text,
                        style = style.copy(fontSize = (style.fontSize.value * 0.8f).sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The line's text for scanning: a label is one space, so a match never runs across it. */
private fun plainText(items: List<GlossItem>): String = buildString {
    items.forEach { item ->
        when (item) {
            is GlossItem.Ruby -> append(item.base)
            is GlossItem.Text -> append(item.text)
            is GlossItem.Tag -> append(' ')
        }
    }
}

/**
 * Text whose Japanese characters look themselves up on a tap ([onScan] gets the text from the
 * tapped character on); Latin text and links are left alone (a link keeps its own action).
 */
@Composable
internal fun ScanText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color = Color.Unspecified,
    onScan: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scan by rememberUpdatedState(onScan)
    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = if (onScan == null) modifier else modifier.pointerInput(text) {
            onTapAnywhere { position ->
                val result = layout ?: return@onTapAnywhere false
                val offset = result.getOffsetForPosition(position)
                // The offset is the nearest gap between characters: take the one the finger is on.
                val index = listOf(offset, offset - 1).firstOrNull {
                    it in text.text.indices && result.getBoundingBox(it).contains(position)
                } ?: return@onTapAnywhere false
                if (text.getLinkAnnotations(index, index + 1).isNotEmpty()) return@onTapAnywhere false
                val target = NestedLookup.scanText(text.text, index) ?: return@onTapAnywhere false
                scan?.invoke(target)
                true
            }
        },
    )
}

/** A tap that [handle]s itself is consumed; any other passes on to what lies beneath. */
private suspend fun PointerInputScope.onTapAnywhere(handle: (Offset) -> Boolean) {
    awaitEachGesture {
        awaitFirstDown()
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        if (handle(up.position)) up.consume()
    }
}

private suspend fun PointerInputScope.onTapDo(action: () -> Unit) = onTapAnywhere { action(); true }

/** Japanese wraps between any two characters; Latin text between words. */
private fun tokens(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val word = StringBuilder()
    for (char in text) {
        if (char.code < 0x3000) {
            word.append(char)
            if (char == ' ') {
                tokens += word.toString()
                word.clear()
            }
        } else {
            if (word.isNotEmpty()) {
                tokens += word.toString()
                word.clear()
            }
            tokens += char.toString()
        }
    }
    if (word.isNotEmpty()) tokens += word.toString()
    return tokens
}

@Composable
private fun GlossStyle.textStyle(): TextStyle = when (this) {
    GlossStyle.GLOSS, GlossStyle.EXAMPLE -> MaterialTheme.typography.bodyMedium
    GlossStyle.TRANSLATION, GlossStyle.NOTE -> MaterialTheme.typography.bodySmall
}

@Composable
private fun GlossStyle.color(): Color = when (this) {
    GlossStyle.GLOSS, GlossStyle.EXAMPLE -> MaterialTheme.colorScheme.onSurface
    GlossStyle.TRANSLATION, GlossStyle.NOTE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val INDENT = 12.dp
private val MARKER_WIDTH = 20.dp
private const val RUBY_SCALE = 0.55f
