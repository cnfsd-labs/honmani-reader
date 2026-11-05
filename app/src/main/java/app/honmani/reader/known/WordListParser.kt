package app.honmani.reader.known

import java.io.InputStream

/** One line of a word list: the word as written, and the reading if the list gives one. */
data class WordListEntry(val word: String, val reading: String? = null)

/** What [WordListParser] read: the entries (without repeats) and the lines it could not use. */
data class ParsedWordList(val entries: List<WordListEntry>, val skippedLines: Int)

/** A word list file bigger than [WordListParser.MAX_BYTES]. */
class WordListTooLargeException : Exception("The word list is larger than ${WordListParser.MAX_BYTES / (1024 * 1024)} MB")

/**
 * Reads a plain text or CSV word list: UTF-8 with or without a BOM, one word per line, the word
 * in the first column and an optional reading in the second (columns split by a tab, else by a
 * comma). Empty lines and lines starting with `#` are ignored; a line whose word is far longer
 * than any word (a pasted paragraph) is skipped and counted.
 */
object WordListParser {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_WORD_LENGTH = 40
    const val MAX_LINE_LENGTH = 1_000

    fun parse(text: String): ParsedWordList {
        val seen = HashSet<String>()
        val entries = ArrayList<WordListEntry>()
        var skipped = 0
        for (raw in text.lineSequence()) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.length > MAX_LINE_LENGTH) {
                skipped++
                continue
            }
            val columns = (if ('\t' in line) line.split('\t') else line.split(',')).map { unquote(it.trim()) }
            val word = columns[0].replace("　", "").trim()
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH) {
                skipped++
                continue
            }
            val reading = columns.getOrNull(1)?.let(::readingOf)
            if (seen.add("$word\u0000${reading.orEmpty()}")) entries += WordListEntry(word, reading)
        }
        return ParsedWordList(entries, skipped)
    }

    /** Reads at most [MAX_BYTES]; throws [WordListTooLargeException] beyond that. */
    fun read(input: InputStream): ParsedWordList {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw WordListTooLargeException()
            out.write(buffer, 0, n)
        }
        return parse(out.toString(Charsets.UTF_8.name()))
    }

    private fun unquote(cell: String): String =
        if (cell.length >= 2 && cell.startsWith('"') && cell.endsWith('"')) cell.substring(1, cell.length - 1) else cell

    /** The reading in hiragana, or null if the cell is empty or not kana. */
    private fun readingOf(cell: String): String? {
        val kana = cell.replace("　", "").replace(" ", "")
        if (kana.isEmpty() || !kana.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it == 'ー' }) return null
        return buildString { for (c in kana) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
    }
}
