package app.honmani.reader.lookup

/** Furigana in the looked-up text: [reading] is given for the characters [start] until [end]. */
data class RubySpan(val start: Int, val end: Int, val reading: String)

/** Part of a written form, with the [ruby] to show above it (null for kana, which need none). */
data class RubySegment(val text: String, val ruby: String? = null)

/** Uses the book's furigana to tell which reading of a written form the author meant. */
object Furigana {
    /**
     * [expression] split into kana and the rest, with each non-kana run's part of [reading]
     * as ruby: 取り扱う read とりあつかう gives 取(と) り 扱(あつか) う. If the kana of the expression
     * can't be found in the reading, the whole reading goes over the whole expression.
     */
    fun segments(expression: String, reading: String): List<RubySegment> {
        val runs = runs(expression)
        if (runs.all { isKana(it.first()) } || reading.isEmpty()) return listOf(RubySegment(expression))
        val pattern = runs.joinToString("", "^", "$") {
            if (isKana(it.first())) Regex.escape(JapaneseText.toHiragana(it)) else "(.+?)"
        }
        // toHiragana keeps the length, so match indexes are also indexes into the reading.
        val match = Regex(pattern).find(JapaneseText.toHiragana(reading))
            ?: return listOf(RubySegment(expression, reading))
        var group = 1
        return runs.map { run ->
            if (isKana(run.first())) {
                RubySegment(run)
            } else {
                val range = match.groups[group++]!!.range
                RubySegment(run, reading.substring(range.first, range.last + 1))
            }
        }
    }

    /** [expression] cut into runs of kana and of anything else, in order. */
    fun runs(expression: String): List<String> {
        val runs = mutableListOf<String>()
        for (c in expression) {
            if (runs.isNotEmpty() && isKana(runs.last().last()) == isKana(c)) {
                runs[runs.size - 1] = runs.last() + c
            } else {
                runs += c.toString()
            }
        }
        return runs
    }

    /** Hiragana, katakana and the long vowel mark, as in the kana runs of [segments]. */
    fun isKana(c: Char) = c in '\u3041'..'\u309F' || c in '\u30A0'..'\u30FF'

    /**
     * What to show for a word: the saved correction ([override], see [FuriganaOverride]) if it
     * still fits [expression], the automatic [segments] otherwise.
     */
    fun shown(expression: String, reading: String, override: String): List<RubySegment> =
        FuriganaOverride.parse(override, expression) ?: segments(expression, reading)

    /**
     * The reading of [source] up to the end of its last ruby base, with each base replaced by its
     * furigana, in hiragana; null if [source] holds no whole ruby base. For 食べました with た over
     * 食 that is "た". [ruby] must be sorted and non-overlapping, with indexes into the text that
     * [source] is a prefix of.
     */
    fun readingPrefix(source: String, ruby: List<RubySpan>): String? {
        val covered = ruby.filter { it.end <= source.length }
        if (covered.isEmpty()) return null
        val reading = buildString {
            var index = 0
            for (span in covered) {
                append(source, index, span.start)
                append(span.reading)
                index = span.end
            }
        }
        return JapaneseText.toHiragana(reading)
    }

    /**
     * Whether an entry read [reading] agrees with [readingPrefix]. Inflection only changes the
     * end of a word, after the last ruby base, so comparing the start is enough.
     */
    fun agrees(readingPrefix: String, reading: String): Boolean =
        JapaneseText.toHiragana(reading).startsWith(readingPrefix)
}

/**
 * The reader's correction of a word's furigana, kept in `word.furigana` in Anki's bracket form
 * without HTML escaping: `飛[と]び 散[ち]る`. A ruby'd part is its base text, then the reading in
 * square brackets; a space stands before every ruby'd part that is not first (so
 * `AnkiCard.plainFurigana` gives the same text); the spaces are not part of the word. Empty means
 * no correction.
 */
object FuriganaOverride {
    /** Longest value kept; a word has at most a few readings. */
    const val MAX_LENGTH = 200

    /** [segments] in the stored form. */
    fun format(segments: List<RubySegment>): String = buildString {
        for (segment in segments) {
            val ruby = segment.ruby
            if (ruby == null || ruby == segment.text) {
                append(segment.text)
            } else {
                if (isNotEmpty()) append(' ')
                append(segment.text).append('[').append(ruby).append(']')
            }
        }
    }

    /**
     * The segments [value] stands for, or null if it is empty, malformed, or its base text (the
     * brackets, readings and separating spaces taken out) is no longer [expression].
     */
    fun parse(value: String, expression: String): List<RubySegment>? {
        if (value.isEmpty() || value.length > MAX_LENGTH) return null
        val segments = mutableListOf<RubySegment>()
        val text = StringBuilder()
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '[' -> {
                    val close = value.indexOf(']', i)
                    if (close < 0 || text.isEmpty()) return null
                    val ruby = value.substring(i + 1, close)
                    if (ruby.isEmpty() || '[' in ruby) return null
                    segments += RubySegment(text.toString(), ruby)
                    text.clear()
                    i = close
                }
                ']' -> return null
                ' ' -> {
                    if (text.isNotEmpty()) segments += RubySegment(text.toString())
                    text.clear()
                }
                else -> text.append(c)
            }
            i++
        }
        if (text.isNotEmpty()) segments += RubySegment(text.toString())
        if (segments.joinToString("") { it.text } != expression) return null
        return segments
    }

    /** Whether [expression] has anything but kana to read, so a correction makes sense. */
    fun canCorrect(expression: String): Boolean = expression.any { !Furigana.isKana(it) }

    /** Kana only (hiragana, katakana, the long vowel mark), at least one character. */
    fun isReading(text: String): Boolean = text.isNotEmpty() && text.all { Furigana.isKana(it) }

    /**
     * The readings the editor starts with, one per non-kana run of [expression] in order: the
     * ruby of what is shown now. Empty strings if the shown ruby does not follow the runs.
     */
    fun prefill(expression: String, shown: List<RubySegment>): List<String> {
        val runs = Furigana.runs(expression)
        val aligned = shown.size == runs.size && shown.indices.all { shown[it].text == runs[it] }
        return runs.indices.filter { !Furigana.isKana(runs[it].first()) }
            .map { if (aligned) shown[it].ruby.orEmpty() else "" }
    }

    /**
     * The stored value for [readings] (one per non-kana run, as [prefill]), or null if one is not
     * kana only or the count is wrong.
     */
    fun build(expression: String, readings: List<String>): String? {
        val runs = Furigana.runs(expression)
        val queue = readings.map { it.trim() }.iterator()
        if (readings.size != runs.count { !Furigana.isKana(it.first()) }) return null
        val segments = runs.map { run ->
            if (Furigana.isKana(run.first())) {
                RubySegment(run)
            } else {
                val reading = queue.next()
                if (!isReading(reading)) return null
                RubySegment(run, reading)
            }
        }
        return format(segments).takeIf { it.length <= MAX_LENGTH }
    }

    /** What the reader would read aloud: the kana and rubies of [segments] together. */
    fun reading(segments: List<RubySegment>): String = segments.joinToString("") { it.ruby ?: it.text }
}
