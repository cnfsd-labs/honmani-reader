package app.honmani.reader.lookup

import app.honmani.reader.data.dictionary.DictionaryDao
import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.dictionary.Glossary
import app.honmani.reader.data.dictionary.GlossaryPresetEntity
import app.honmani.reader.data.dictionary.TermDao
import app.honmani.reader.data.dictionary.TermEntity
import app.honmani.reader.data.dictionary.TermKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Taps through the sample books word by word, as a reader would, against a real dictionary, and
 * writes the taps that found nothing and the furigana no entry agreed with to
 * `build/lookup-coverage/report.txt`. Skipped unless `build/lookup-coverage/terms.tsv` exists
 * (`scripts/lookup-coverage/export_terms.py` makes it from a phone's database); it reports and
 * asserts nothing, so a person reads the report.
 */
class LookupCoverageCheck {
    private val dir = File("build/lookup-coverage")

    @Test
    fun tapThroughTheSampleBooks() {
        val terms = File(dir, "terms.tsv")
        assumeTrue("no $terms", terms.isFile)
        val dao = TsvTermDao(terms)
        val lookup = DictionaryLookup(TsvDictionaryDao(dao.dictionaryIds), dao)

        val misses = HashMap<String, Int>()
        val overlong = HashMap<String, Int>()
        val disagreements = HashMap<String, Int>()
        var taps = 0
        val books = File("src/main/assets/samples").listFiles { file -> file.extension == "epub" }.orEmpty().sorted()
        runBlocking {
            for (book in books) for (paragraph in paragraphs(book)) {
                val text = paragraph.text
                var position = 0
                var previous: String? = null
                while (position < text.length) {
                    if (!isJapanese(text[position])) {
                        position++
                        previous = null
                        continue
                    }
                    val end = minOf(text.length, position + SCAN_LENGTH)
                    val ruby = paragraph.ruby
                        .filter { it.start >= position && it.end <= end }
                        .map { RubySpan(it.start - position, it.end - position, it.reading) }
                    val result = lookup.lookup(text.substring(position, end), ruby)
                    taps++
                    val length = result.matchedLength
                    if (length == 0) {
                        val before = text.substring(maxOf(0, position - 4), position)
                        val context = "$before|${text.substring(position, minOf(text.length, position + 5))}"
                        // No word starts with a small kana: the match before took part of this one.
                        if (previous != null && text[position] in MID_WORD) {
                            overlong.merge("$previous  ($context)", 1, Int::plus)
                        } else {
                            misses.merge(context, 1, Int::plus)
                        }
                        position++
                        previous = null
                        continue
                    }
                    val covered = ruby.filter { it.start == 0 && it.end <= length }
                    val first = result.entries.first()
                    previous = "${first.source} -> ${first.term.expression}【${first.term.reading}】 ${first.inflections}"
                    if (covered.isNotEmpty() && result.entries.none { it.source.length == length && it.matchesFurigana }) {
                        val base = text.substring(position, position + covered.maxOf { it.end })
                        val readings = covered.joinToString("") { it.reading }
                        disagreements.merge("$base($readings) -> ${first.term.expression}【${first.term.reading}】", 1, Int::plus)
                    }
                    position += length
                }
            }
        }
        dir.mkdirs()
        File(dir, "report.txt").writeText(
            buildString {
                appendLine("${books.size} books, $taps taps, ${misses.values.sum() + overlong.values.sum()} without a match")
                appendLine("\n== Taps without a match (count, 4 characters before | 5 from the tap)")
                misses.entries.sortedByDescending { it.value }.forEach { appendLine("${it.value}\t${it.key}") }
                appendLine("\n== Matches that took part of the next word (${overlong.values.sum()})")
                overlong.entries.sortedByDescending { it.value }.forEach { appendLine("${it.value}\t${it.key}") }
                appendLine("\n== Furigana no entry agreed with (${disagreements.values.sum()})")
                disagreements.entries.sortedByDescending { it.value }.forEach { appendLine("${it.value}\t${it.key}") }
            },
        )
    }

    private class Paragraph(val text: String, val ruby: List<RubySpan>)

    /** The book's paragraphs (a `<br/>` also ends one), without furigana but with its spans. */
    private fun paragraphs(book: File): List<Paragraph> = ZipFile(book).use { zip ->
        zip.entries().toList()
            .filter { it.name.endsWith(".xhtml") && !it.name.endsWith("nav.xhtml") }
            .sortedBy { it.name }
            .flatMap { entry ->
                val html = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                html.substringAfter("<body").split(Regex("</p>|<br\\s*/>|</h\\d>|</div>")).map(::paragraph)
            }
            .filter { it.text.isNotBlank() }
    }

    private fun paragraph(html: String): Paragraph {
        val text = StringBuilder()
        val ruby = ArrayList<RubySpan>()
        var rubyStart = -1
        var reading: StringBuilder? = null
        var skip = false
        for (match in Regex("<[^>]*>|[^<]+").findAll(html)) {
            val token = match.value
            when {
                token.startsWith("<ruby") -> rubyStart = text.length
                token.startsWith("<rt") -> reading = StringBuilder()
                token == "</rt>" -> {
                    if (rubyStart >= 0) ruby += RubySpan(rubyStart, text.length, reading.toString())
                    rubyStart = text.length
                    reading = null
                }
                token.startsWith("<rp") -> skip = true
                token == "</rp>" -> skip = false
                token == "</ruby>" -> rubyStart = -1
                token.startsWith("<") -> Unit
                skip -> Unit
                reading != null -> reading.append(unescape(token))
                else -> text.append(unescape(token))
            }
        }
        return Paragraph(text.toString(), ruby.filter { it.end > it.start && it.reading.isNotEmpty() })
    }

    private fun unescape(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private fun isJapanese(c: Char) =
        c in 'ぁ'..'ゟ' || c in 'ァ'..'ヿ' || c in '一'..'鿿' || c == '々' || c == '〆'

    private class TsvTermDao(file: File) : TermDao {
        private val byText = HashMap<String, MutableList<TermEntity>>()
        private val all = ArrayList<TermEntity>()
        val dictionaryIds = HashSet<Long>()

        init {
            val empty = Glossary.plain("[]")
            file.forEachLine { line ->
                val f = line.split('\t')
                val term = TermEntity(
                    id = f[0].toLong(),
                    dictionaryId = f[1].toLong(),
                    expression = f[2],
                    reading = f[3],
                    definitionTags = null,
                    rules = f[4],
                    score = f[5].toDouble(),
                    // Only pointer entries come with their glossary, which names the target.
                    glossary = f.getOrNull(7)?.takeIf { it.isNotEmpty() }?.let(Glossary::plain) ?: empty,
                    sequence = f[6].toLongOrNull(),
                    termTags = null,
                )
                dictionaryIds += term.dictionaryId
                all += term
                byText.getOrPut(term.expression) { ArrayList(1) } += term
                if (term.reading.isNotEmpty() && term.reading != term.expression) {
                    byText.getOrPut(term.reading) { ArrayList(1) } += term
                }
            }
        }

        override suspend fun findByExpressionOrReading(texts: List<String>): List<TermEntity> =
            texts.flatMap { byText[it].orEmpty() }

        private fun keys(limit: Int, test: (TermEntity) -> Boolean) =
            all.asSequence().filter(test).take(limit).map { TermKey(it.id, it.dictionaryId, it.expression, it.reading, it.score) }.toList()

        private fun glob(pattern: String) =
            Regex(Regex.escape(pattern).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q").replace("[[]", "["))

        override suspend fun keysByExpressionRange(from: String, to: String, limit: Int) =
            keys(limit) { it.expression >= from && it.expression < to }

        override suspend fun keysByReadingRange(from: String, to: String, limit: Int) =
            keys(limit) { it.reading >= from && it.reading < to }

        override suspend fun keysByExpressionPattern(pattern: String, limit: Int) =
            glob(pattern).let { regex -> keys(limit) { regex.matches(it.expression) } }

        override suspend fun keysByReadingPattern(pattern: String, limit: Int) =
            glob(pattern).let { regex -> keys(limit) { regex.matches(it.reading) } }

        override suspend fun findByIds(ids: List<Long>): List<TermEntity> =
            ids.toSet().let { wanted -> all.filter { it.id in wanted } }

        override suspend fun glossaryPreset(dictionaryId: Long): ByteArray? = null
        override suspend fun insertAll(terms: List<TermEntity>) = error("read only")
        override suspend fun insertPreset(preset: GlossaryPresetEntity) = error("read only")
        override suspend fun countForDictionary(dictionaryId: Long) = error("not needed")
        override suspend fun allForDictionary(dictionaryId: Long) = error("not needed")
    }

    private class TsvDictionaryDao(ids: Set<Long>) : DictionaryDao {
        private val dictionaries = ids.map {
            DictionaryEntity(id = it, title = "Dictionary $it", revision = "", formatVersion = 3, importedAt = 0, termCount = 1)
        }

        override suspend fun enabledReady() = dictionaries
        override suspend fun allReady() = dictionaries
        override fun observeReady(): Flow<List<DictionaryEntity>> = flowOf(dictionaries)
        override suspend fun findByTitle(title: String) = dictionaries.find { it.title == title }
        override suspend fun findById(id: Long) = dictionaries.find { it.id == id }
        override suspend fun insert(dictionary: DictionaryEntity): Long = error("read only")
        override suspend fun markReady(id: Long, termCount: Int, metaCount: Int, kanjiCount: Int) = error("read only")
        override suspend fun setEnabled(id: Long, enabled: Boolean) = error("read only")
        override suspend fun setEnabledAndPriority(id: Long, enabled: Boolean, priority: Int) = error("read only")
        override suspend fun setPriority(id: Long, priority: Int) = error("read only")
        override suspend fun nextPriority(): Int = error("read only")
        override suspend fun delete(id: Long) = error("read only")
        override suspend fun deleteIncomplete() = error("read only")
    }

    private companion object {
        const val SCAN_LENGTH = 20
        const val MID_WORD = "っゃゅょぁぃぅぇぉッャュョァィゥェォー"
    }
}
