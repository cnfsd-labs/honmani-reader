package app.honmani.reader.readaloud

/** A subtitle with where it is heard ([startMs] until [endMs]) and where it is in the book, if found. */
data class AlignedCue(val startMs: Long, val endMs: Long, val span: CueSpan?)

/** The subtitles of one audiobook matched to the book. */
class AudiobookAlignment(val cues: List<AlignedCue>) {
    val total: Int get() = cues.size
    val matched: Int by lazy { cues.count { it.span != null } }

    /** Fewer than half of the subtitles found: the files probably are not for this book. */
    val doubtful: Boolean get() = total > 0 && matched * 2 < total

    /** The index of the last cue that starts at or before [positionMs], or -1 before the first. */
    fun cueAt(positionMs: Long): Int {
        var low = 0
        var high = cues.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    /** The index of the first cue after [index] that was found in the book, or -1. */
    fun nextMatched(index: Int): Int = (index + 1 until cues.size).firstOrNull { cues[it].span != null } ?: -1

    /** The index of the last cue before [index] that was found in the book, or -1. */
    fun previousMatched(index: Int): Int = (minOf(index, cues.size) - 1 downTo 0).firstOrNull { cues[it].span != null } ?: -1

    /** For the cache file: one line per cue, "start end bookStart bookEnd" or "start end -". */
    fun serialize(): String = buildString {
        append(FORMAT).append('\n')
        for (cue in cues) {
            append(cue.startMs).append(' ').append(cue.endMs)
            if (cue.span == null) append(" -") else append(' ').append(cue.span.start).append(' ').append(cue.span.end)
            append('\n')
        }
    }

    companion object {
        private const val FORMAT = "honmani-audiobook-alignment 1"

        /** Aligns [cues] to the book's counted characters ([book] folded as [CueAligner.align] wants). */
        fun of(book: IntArray, cues: List<Cue>): AudiobookAlignment {
            val spans = CueAligner.align(book, cues.map { it.text })
            return AudiobookAlignment(cues.mapIndexed { i, cue -> AlignedCue(cue.startMs, cue.endMs, spans[i]) })
        }

        /** The cache file's content read back; null if it is not one of ours or damaged. */
        fun parse(text: String): AudiobookAlignment? {
            val lines = text.split('\n')
            if (lines.firstOrNull() != FORMAT) return null
            val cues = ArrayList<AlignedCue>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val parts = line.split(' ')
                val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
                val end = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val span = when {
                    parts.size == 3 && parts[2] == "-" -> null
                    parts.size == 4 -> {
                        val a = parts[2].toIntOrNull() ?: return null
                        val b = parts[3].toIntOrNull() ?: return null
                        CueSpan(a, b)
                    }
                    else -> return null
                }
                cues += AlignedCue(start, end, span)
            }
            return AudiobookAlignment(cues)
        }
    }
}
