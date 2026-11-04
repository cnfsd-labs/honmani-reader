package app.honmani.reader.readaloud

/** One subtitle: shown from [startMs] to [endMs] (milliseconds into the audio), with its [text]. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Reads SRT subtitles, and WebVTT too, since its cues only differ in the header, a dot before the
 * milliseconds and an optional hours part. Forgiving, as subtitle files in the wild are: a byte
 * order mark, CR, LF or CRLF line ends, hours over 99, a comma or a dot before the milliseconds,
 * tags and style codes in the text, a missing blank line between two cues. A block whose time
 * line can't be read, or that has no text, is skipped. The cues come back sorted by start time.
 */
object Srt {
    private val TIME_LINE = Regex("""^\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})(?:\s.*)?$""")
    private val TAG = Regex("""<[^>]*>|\{\\[^}]*}""")
    private val NUMBER = Regex("""^\s*\d+\s*$""")

    fun parse(source: String): List<Cue> {
        val lines = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var start = 0L
        var end = 0L
        var text: MutableList<String>? = null

        fun close() {
            val body = text ?: return
            text = null
            val joined = body.joinToString("\n") { clean(it) }.trim()
            if (joined.isNotEmpty()) cues += Cue(start, maxOf(start, end), joined)
        }

        for (line in lines) {
            val marker = line.contains("-->")
            val time = if (marker) TIME_LINE.matchEntire(line) else null
            when {
                line.isBlank() -> close()
                time != null -> {
                    // No blank line before this cue: the previous one ended with this cue's number.
                    val previous = text
                    if (previous != null && previous.size > 1 && NUMBER.matches(previous.last())) previous.removeAt(previous.lastIndex)
                    close()
                    val g = time.groupValues
                    start = millis(g[1], g[2], g[3], g[4])
                    end = millis(g[5], g[6], g[7], g[8])
                    text = ArrayList()
                }
                marker -> text = null
                else -> text?.add(line)
            }
        }
        close()
        return cues.sortedBy { it.startMs }
    }

    private fun millis(hours: String, minutes: String, seconds: String, fraction: String): Long =
        ((hours.toLongOrNull() ?: 0L) * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000 + fraction.padEnd(3, '0').toLong()

    private fun clean(line: String): String =
        line.replace(TAG, "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .trim()
}
