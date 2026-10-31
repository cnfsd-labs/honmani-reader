package app.honmani.reader.data.library

/** A book read from an Aozora Bunko text file, ready to be written as an EPUB. */
data class AozoraBook(
    val title: String,
    val author: String?,
    val chapters: List<AozoraChapter>,
)

/** A part of the book that starts on a new page: at a heading or a page break. */
data class AozoraChapter(val title: String?, val blocks: List<AozoraBlock>)

sealed interface AozoraBlock {
    data class Paragraph(val inlines: List<AozoraInline>, val layout: AozoraLayout = AozoraLayout()) : AozoraBlock

    /** [level] 1 for 大見出し, 2 for 中見出し, 3 for 小見出し. */
    data class Heading(val level: Int, val inlines: List<AozoraInline>) : AozoraBlock

    /** An empty line, which Aozora texts use for spacing. */
    data object Blank : AozoraBlock
}

sealed interface AozoraInline {
    data class Text(val text: String) : AozoraInline

    /** Furigana: [reading] over [base]. */
    data class Ruby(val base: String, val reading: String) : AozoraInline

    /** Emphasis marks or a side line (傍点, 傍線), bold, italic or upright digits (縦中横). */
    data class Styled(val style: AozoraStyle, val inlines: List<AozoraInline>) : AozoraInline
}

enum class AozoraStyle(val note: String) {
    SESAME("傍点"),
    OPEN_SESAME("白ゴマ傍点"),
    CIRCLE("丸傍点"),
    OPEN_CIRCLE("白丸傍点"),
    TRIANGLE("黒三角傍点"),
    OPEN_TRIANGLE("白三角傍点"),
    DOUBLE_CIRCLE("二重丸傍点"),
    BULLSEYE("蛇の目傍点"),
    CROSS("ばつ傍点"),
    LINE("傍線"),
    DOUBLE_LINE("二重傍線"),
    DOTTED_LINE("鎖線"),
    DASHED_LINE("破線"),
    WAVY_LINE("波線"),
    BOLD("太字"),
    ITALIC("斜体"),
    UPRIGHT("縦中横"),
    ;

    companion object {
        fun of(note: String): AozoraStyle? = entries.firstOrNull { it.note == note }
    }
}

/**
 * Where a paragraph's lines start and end, in characters: [indent] from the top (字下げ),
 * [hanging] for the lines after the first (折り返して…字下げ), or set against the bottom
 * ([bottom], 地付き) with [bottomGap] characters to spare (地から…字上げ).
 */
data class AozoraLayout(
    val indent: Int = 0,
    val hanging: Int? = null,
    val bottom: Boolean = false,
    val bottomGap: Int = 0,
)

/**
 * Reads Aozora Bunko's plain-text format: furigana (漢字《かんじ》, ｜で始まる《よみ》), headings, page
 * breaks, emphasis marks and side lines, bold, italic, 縦中横, indentation (字下げ) and bottom
 * alignment (地付き, 字上げ), as notes on the text before them (［＃「…」に傍点］) or as ranges
 * (［＃傍点］…［＃傍点終わり］, ［＃ここから２字下げ］…［＃ここで字下げ終わり］). Other editor notes and
 * the notation guide are dropped; characters given as Unicode in a note (※［＃「…」、U+29E3D、…］)
 * are restored.
 */
object AozoraText {
    /**
     * Whether [text] uses Aozora Bunko's notation: an Aozora file (its notation guide or its
     * colophon), or any text with Aozora-style furigana (漢字《かんじ》), which many other
     * Japanese text files use too.
     */
    fun isAozora(text: String): Boolean =
        hasAozoraHeader(text) || RUBY_MARKUP.findAll(text).take(MIN_RUBY_MARKS).count() >= MIN_RUBY_MARKS

    /** An Aozora Bunko file proper starts with title and author lines. */
    private fun hasAozoraHeader(text: String) =
        NOTATION_GUIDE in text || ('《' in text && '》' in text && COLOPHON in text)

    fun parse(text: String, fallbackTitle: String): AozoraBook {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val dashes = lines.indices.filter { DASHES.matches(lines[it]) }.take(2)
        // Other texts with Aozora furigana have no title block: everything is book text.
        val headerEnd = if (hasAozoraHeader(text)) {
            dashes.firstOrNull() ?: lines.indexOfFirst { it.isBlank() }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        val header = lines.subList(0, headerEnd).map { it.trim() }.filter { it.isNotEmpty() }
        val bodyStart = if (headerEnd > 0 && dashes.size == 2) dashes[1] + 1 else headerEnd

        val chapters = mutableListOf<AozoraChapter>()
        var title: String? = null
        var blocks = mutableListOf<AozoraBlock>()
        fun finishChapter() {
            // Drop blank lines around the chapter; they only separated it from the one before.
            val trimmed = blocks.dropWhile { it == AozoraBlock.Blank }.dropLastWhile { it == AozoraBlock.Blank }
            if (trimmed.isNotEmpty()) chapters += AozoraChapter(title, trimmed)
            title = null
            blocks = mutableListOf()
        }

        var range = AozoraLayout()
        for (line in lines.subList(bodyStart.coerceAtMost(lines.size), lines.size)) {
            val trimmed = line.trim()
            if (PAGE_BREAK.matches(trimmed)) {
                finishChapter()
                continue
            }
            rangeLayout(trimmed, range)?.let {
                range = it
                continue
            }
            // A line holding only an editor's note is dropped.
            val block = parseLine(line, range) ?: continue
            if (block is AozoraBlock.Heading && block.level <= 2) {
                finishChapter()
                title = block.inlines.plainText()
            }
            blocks += block
        }
        finishChapter()

        return AozoraBook(
            title = header.firstOrNull() ?: fallbackTitle,
            author = author(header),
            chapters = chapters,
        )
    }

    /** The author line of the header: title, (subtitle), author, (translator). */
    private fun author(header: List<String>): String? {
        val rest = header.drop(1)
        if (rest.isEmpty()) return null
        if (rest.size == 1) return rest[0]
        val names = rest.filterNot { it.endsWith("訳") || it.endsWith("編") || it.endsWith("校訂") }
        return names.lastOrNull() ?: rest[0]
    }

    /** The layout after a line holding a range note (ここから…, ここで…終わり), or null for other lines. */
    private fun rangeLayout(line: String, current: AozoraLayout): AozoraLayout? {
        if (!line.startsWith("［＃ここ") || line.indexOf('］') != line.length - 1) return null
        val note = line.substring(2, line.length - 1)
        RANGE_INDENT.matchEntire(note)?.let { match ->
            val hanging = match.groupValues[2].ifEmpty { null }?.let(::number)
            return AozoraLayout(indent = number(match.groupValues[1]), hanging = hanging)
        }
        RANGE_HANGING.matchEntire(note)?.let { return AozoraLayout(hanging = number(it.groupValues[1])) }
        if (note == "ここから地付き") return AozoraLayout(bottom = true)
        RANGE_RAISED.matchEntire(note)?.let { return AozoraLayout(bottom = true, bottomGap = number(it.groupValues[1])) }
        if (note in RANGE_ENDS) return AozoraLayout()
        // Other range notes (字詰め, 小さな文字 …) are dropped and keep the layout.
        return current
    }

    private fun parseLine(raw: String, range: AozoraLayout): AozoraBlock? {
        if (raw.isBlank()) return AozoraBlock.Blank
        var line = raw.replace(GAIJI) { match ->
            // Up to six hex digits can name a code point past U+10FFFF, or a lone surrogate.
            UNICODE.find(match.value)?.groupValues?.get(1)?.toInt(16)
                ?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) } ?: "※"
        }

        var level = 0
        BLOCK_HEADING.find(line)?.let { match ->
            level = headingLevel(match.groupValues[1])
            line = line.replace(BLOCK_HEADING_MARK, "")
        }
        var layout = range
        // The inlines of the line, and of each style range opened on it (［＃傍点］…).
        val frames = mutableListOf(Frame(null))
        val text = StringBuilder()
        var rubyStart = -1
        var index = 0

        fun flushText(end: Int = text.length) {
            if (end > 0) frames.last().inlines += AozoraInline.Text(text.substring(0, end))
            text.delete(0, end)
        }

        fun close(frame: Frame) {
            frames.remove(frame)
            if (frame.inlines.isNotEmpty()) frames.last().inlines += AozoraInline.Styled(frame.style!!, mergeText(frame.inlines))
        }

        while (index < line.length) {
            val c = line[index]
            when {
                c == '｜' -> {
                    flushText()
                    rubyStart = 0
                    index++
                }
                c == '《' && line.indexOf('》', index) > index -> {
                    val close = line.indexOf('》', index)
                    val reading = line.substring(index + 1, close)
                    val start = if (rubyStart >= 0) rubyStart else kanjiRunStart(text)
                    if (start < text.length && reading.isNotBlank()) {
                        val base = text.substring(start)
                        flushText(start)
                        text.clear()
                        frames.last().inlines += AozoraInline.Ruby(base, reading)
                    } else {
                        text.append(line, index, close + 1)
                    }
                    rubyStart = -1
                    index = close + 1
                }
                c == '［' && line.startsWith("［＃", index) && line.indexOf('］', index) > index -> {
                    val close = line.indexOf('］', index)
                    val note = line.substring(index + 2, close)
                    val atStart = frames.size == 1 && frames[0].inlines.isEmpty() && text.isBlank()
                    flushText()
                    HEADING.matchEntire(note)?.let { level = headingLevel(it.groupValues[2]) }
                    STYLE_AFTER.matchEntire(note)?.let { match ->
                        AozoraStyle.of(match.groupValues[2])?.let { styleTail(frames.last().inlines, match.groupValues[1], it) }
                    }
                    AozoraStyle.of(note)?.let { frames += Frame(it) }
                    AozoraStyle.of(note.removeSuffix("終わり")).takeIf { note.endsWith("終わり") }?.let { style ->
                        frames.lastOrNull { it.style == style }?.let { open ->
                            while (frames.last() !== open) close(frames.last())
                            close(open)
                        }
                    }
                    if (atStart) {
                        LINE_INDENT.matchEntire(note)?.let { layout = AozoraLayout(indent = number(it.groupValues[1])) }
                        if (note == "地付き") layout = AozoraLayout(bottom = true)
                        LINE_RAISED.matchEntire(note)?.let { layout = AozoraLayout(bottom = true, bottomGap = number(it.groupValues[1])) }
                    }
                    index = close + 1
                }
                else -> {
                    text.append(c)
                    index++
                }
            }
        }
        flushText()
        // A range left open at the end of the line ends there.
        while (frames.size > 1) close(frames.last())
        val merged = mergeText(frames[0].inlines)
        if (merged.isEmpty()) return null
        // Indentation is done with full-width spaces; a heading doesn't need them.
        return if (level > 0) AozoraBlock.Heading(level, trimStart(merged)) else AozoraBlock.Paragraph(merged, layout)
    }

    private class Frame(val style: AozoraStyle?) {
        val inlines = mutableListOf<AozoraInline>()
    }

    /**
     * Styles [target], the text just before the note. It may start inside a text and take in
     * furigana and styled text (their base text counts).
     */
    private fun styleTail(inlines: MutableList<AozoraInline>, target: String, style: AozoraStyle) {
        if (target.isEmpty()) return
        var i = inlines.size
        var length = 0
        while (i > 0 && length < target.length) {
            i--
            length += plain(inlines[i]).length
        }
        if (length < target.length) return
        val first = inlines[i]
        val cut = length - target.length
        if (cut > 0 && first !is AozoraInline.Text) return
        val head = if (cut > 0) (first as AozoraInline.Text).text.substring(0, cut) else ""
        val tail = buildList {
            add(if (cut > 0) AozoraInline.Text((first as AozoraInline.Text).text.substring(cut)) else first)
            addAll(inlines.subList(i + 1, inlines.size))
        }
        if (tail.joinToString("") { plain(it) } != target) return
        while (inlines.size > i) inlines.removeAt(inlines.size - 1)
        if (head.isNotEmpty()) inlines += AozoraInline.Text(head)
        inlines += AozoraInline.Styled(style, mergeText(tail))
    }

    /** "２" or "10" → 2 or 10. */
    private fun number(digits: String): Int = digits.fold(0) { value, c -> value * 10 + c.digitToInt() }

    /** Where the run of kanji at the end of [text] starts (its length if there is none). */
    private fun kanjiRunStart(text: CharSequence): Int {
        var start = text.length
        while (start > 0) {
            val c = text[start - 1]
            val isKanji = c in '\u3400'..'\u9FFF' || c in '\uF900'..'\uFAFF' || c in "々〆ヶ〇" ||
                Character.isSurrogate(c)
            if (!isKanji) break
            start--
        }
        return start
    }

    private fun headingLevel(size: String) = when (size) {
        "大" -> 1
        "中" -> 2
        else -> 3
    }

    private fun mergeText(inlines: List<AozoraInline>): List<AozoraInline> {
        val merged = mutableListOf<AozoraInline>()
        for (inline in inlines) {
            val last = merged.lastOrNull()
            if (inline is AozoraInline.Text && last is AozoraInline.Text) {
                merged[merged.size - 1] = AozoraInline.Text(last.text + inline.text)
            } else if (inline !is AozoraInline.Text || inline.text.isNotEmpty()) {
                merged += inline
            }
        }
        return merged
    }

    private fun trimStart(inlines: List<AozoraInline>): List<AozoraInline> {
        val first = inlines.firstOrNull() as? AozoraInline.Text ?: return inlines
        val trimmed = first.text.trimStart(' ', '　')
        return if (trimmed.isEmpty()) inlines.drop(1) else listOf(AozoraInline.Text(trimmed)) + inlines.drop(1)
    }

    private fun plain(inline: AozoraInline): String = when (inline) {
        is AozoraInline.Text -> inline.text
        is AozoraInline.Ruby -> inline.base
        is AozoraInline.Styled -> inline.inlines.joinToString("") { plain(it) }
    }

    private fun List<AozoraInline>.plainText(): String = joinToString("") { plain(it) }.trim()

    private const val NOTATION_GUIDE = "【テキスト中に現れる記号について】"
    private const val MIN_RUBY_MARKS = 3
    private val RUBY_MARKUP = Regex("(｜[^《》\\n]+|[\\u3400-\\u9FFF\\uF900-\\uFAFF々〆ヶ])《[\\u3041-\\u30FF・ー]+》")
    private const val COLOPHON = "底本："
    private val DASHES = Regex("^-{20,}\\s*$")
    private val PAGE_BREAK = Regex("^［＃改(ページ|丁|段|見開き)］$")
    private val GAIJI = Regex("※［＃[^］]*］")
    private val UNICODE = Regex("U\\+([0-9A-Fa-f]{4,6})")
    private val HEADING = Regex("^「(.+)」は(大|中|小)見出し$")
    private val BLOCK_HEADING = Regex("［＃(大|中|小)見出し］")
    private val BLOCK_HEADING_MARK = Regex("［＃(大|中|小)見出し(終わり)?］")
    private val STYLE_AFTER = Regex("^「(.+)」[には](.+)$")
    private const val DIGITS = "[0-9０-９]+"
    private val LINE_INDENT = Regex("^(?:天から)?($DIGITS)字下げ$")
    private val LINE_RAISED = Regex("^地から($DIGITS)字上げ$")
    private val RANGE_INDENT = Regex("^ここから(?:天から)?($DIGITS)字下げ(?:、折り返して($DIGITS)字下げ)?$")
    private val RANGE_HANGING = Regex("^ここから改行天付き、折り返して($DIGITS)字下げ$")
    private val RANGE_RAISED = Regex("^ここから地から($DIGITS)字上げ$")
    private val RANGE_ENDS = setOf("ここで字下げ終わり", "ここで地付き終わり", "ここで字上げ終わり")
}
