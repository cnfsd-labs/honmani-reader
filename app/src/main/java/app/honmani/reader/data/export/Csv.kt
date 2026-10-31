package app.honmani.reader.data.export

import app.honmani.reader.data.stats.DayTotal
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Comma-separated values as RFC 4180 describes them: CRLF between rows, quotes where needed. */
object Csv {
    /** [rows] as text, the first being the header. Every row ends with CRLF. */
    fun write(rows: List<List<String>>): String = buildString {
        for (row in rows) {
            row.joinTo(this, ",") { quote(it) }
            append("\r\n")
        }
    }

    /** A field is quoted when it holds a comma, quote, line break, or begins or ends with a space. */
    fun quote(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            field.startsWith(' ') || field.endsWith(' ')
        return if (needsQuotes) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }
}

/** The CSV files Honmani exports. */
object CsvExports {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    val WORD_HEADER = listOf(
        "expression", "reading", "meaning", "status", "starred", "lookups",
        "first_looked_up", "last_looked_up", "book", "sentence", "in_anki",
    )

    /** Saved [words] with their lookup history, the latest lookup first; times in [zone]. */
    fun words(words: List<WordEntity>, zone: ZoneId): String {
        fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)
        val rows = words.sortedByDescending { it.lastLookedUpAt }.map { word ->
            listOf(
                word.expression,
                word.reading,
                word.gloss,
                if (word.status == WordStatus.LEARNED) "learned" else "learning",
                if (word.starred) "yes" else "no",
                word.lookupCount.toString(),
                time(word.firstLookedUpAt),
                time(word.lastLookedUpAt),
                word.lastBookTitle.orEmpty(),
                word.lastSentence,
                if (word.ankiNoteId != null) "yes" else "no",
            )
        }
        return Csv.write(listOf(WORD_HEADER) + rows)
    }

    val READING_HEADER = listOf("date", "seconds", "characters", "characters_per_hour")

    /** Reading per day, all books together, oldest first. */
    fun reading(days: List<DayTotal>): String {
        val rows = days.sortedBy { it.day }.map { day ->
            listOf(
                day.day.toString(),
                day.seconds.toString(),
                day.characters.toString(),
                if (day.seconds > 0) (day.characters * 3600 / day.seconds).toString() else "",
            )
        }
        return Csv.write(listOf(READING_HEADER) + rows)
    }
}
