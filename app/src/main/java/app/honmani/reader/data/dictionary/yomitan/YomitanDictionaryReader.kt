package app.honmani.reader.data.dictionary.yomitan

import app.honmani.reader.data.TooLargeException
import app.honmani.reader.data.readBytesAtMost
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The dictionary file is not a valid Yomitan dictionary. [message] says why. */
class InvalidDictionaryException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class YomitanIndex(
    val title: String,
    val revision: String,
    val format: Int,
    val author: String?,
    val attribution: String?,
    /** Whether the publisher offers updates at [indexUrl] / [downloadUrl]. */
    val isUpdatable: Boolean = false,
    val indexUrl: String? = null,
    val downloadUrl: String? = null,
    /** "rank-based" or "occurrence-based", for frequency dictionaries. */
    val frequencyMode: String? = null,
)

/** A frequency or pitch row of a `term_meta_bank`; see [TermMetaParser]. */
data class YomitanTermMeta(
    val expression: String,
    /** Null when the row applies to every reading. */
    val reading: String?,
    val kind: TermMetaKind,
    val value: Double? = null,
    val display: String? = null,
    /** Downstep positions in morae, for pitch rows. */
    val pitches: List<Int> = emptyList(),
)

enum class TermMetaKind { FREQUENCY, PITCH }

data class YomitanTerm(
    val expression: String,
    /** Empty in the file when the reading equals the expression; here it never is. */
    val reading: String,
    val definitionTags: String?,
    val rules: String,
    val score: Double,
    /** Definitions as a JSON array string. */
    val glossary: String,
    val sequence: Long?,
    val termTags: String?,
)

/** One character of a `kanji_bank`. */
data class YomitanKanji(
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meanings: List<String>,
    /** Numbers and codes by tag name ("strokes" to "9"); empty in format 1. */
    val stats: Map<String, String>,
)

data class YomitanTag(
    val name: String,
    val category: String,
    val order: Double,
    val notes: String,
    val score: Double,
)

/**
 * Reads a Yomitan dictionary zip: `index.json`, `term_bank_N.json`, `term_meta_bank_N.json`
 * (frequency and pitch accent), `kanji_bank_N.json` and `tag_bank_N.json`. Banks are streamed,
 * so large dictionaries do not need to fit in memory.
 *
 * Kanji meta banks (kanji frequency) and media files are not read yet.
 */
class YomitanDictionaryReader private constructor(
    private val zip: ZipFile,
    /** Directory inside the zip that holds `index.json` ("" or "name/"). */
    private val root: String,
) : Closeable {

    val index: YomitanIndex = parseIndex(readJson("index.json"))

    /** Term bank file names in bank order. */
    val termBanks: List<String> = bankNames("term_bank_")

    /** Frequency and pitch bank file names in bank order. */
    val termMetaBanks: List<String> = bankNames("term_meta_bank_")

    /** Kanji bank file names in bank order. */
    val kanjiBanks: List<String> = bankNames("kanji_bank_")

    private val tagBanks: List<String> = bankNames("tag_bank_")

    /** Uncompressed size of [bank] in bytes (at least 1), to measure import progress. */
    fun bankSize(bank: String): Long {
        val entry = entry(bank) ?: return 1
        return maxOf(entry.size, entry.compressedSize, 1)
    }

    /**
     * Streams the frequency and pitch rows of [bank], in lists of up to [chunkSize], to [action].
     * Rows of other kinds (such as IPA) are skipped; malformed rows throw
     * [InvalidDictionaryException]. [onRead] gets the bytes of the bank read so far after each chunk.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun forEachTermMetaChunk(
        bank: String,
        chunkSize: Int,
        onRead: suspend (bytes: Long) -> Unit = {},
        action: suspend (List<YomitanTermMeta>) -> Unit,
    ) {
        val entry = entry(bank) ?: throw InvalidDictionaryException("$bank is missing")
        val counting = CountingInputStream(zip.getInputStream(entry))
        counting.buffered().use { stream ->
            val elements = decoding(bank) {
                json.decodeToSequence<JsonElement>(stream, DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            }
            val chunk = ArrayList<YomitanTermMeta>(chunkSize)
            var position = 0
            while (decoding(bank) { elements.hasNext() }) {
                val element = decoding(bank) { elements.next() }
                val row = try {
                    TermMetaParser.parse(element)
                } catch (e: IllegalArgumentException) {
                    throw InvalidDictionaryException("$bank, entry ${position + 1}: ${e.message}", e)
                }
                position++
                chunk += row
                if (chunk.size >= chunkSize) {
                    action(chunk.toList())
                    chunk.clear()
                    onRead(counting.count)
                }
            }
            if (chunk.isNotEmpty()) action(chunk.toList())
            onRead(counting.count)
        }
    }

    /**
     * Streams the entries of [bank] in file order, in lists of up to [chunkSize], to [action].
     * Throws [InvalidDictionaryException] for malformed JSON or entries. [onRead] gets the bytes
     * of the bank read so far after each chunk.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun forEachTermChunk(
        bank: String,
        chunkSize: Int,
        onRead: suspend (bytes: Long) -> Unit = {},
        action: suspend (List<YomitanTerm>) -> Unit,
    ) {
        val entry = entry(bank) ?: throw InvalidDictionaryException("$bank is missing")
        val counting = CountingInputStream(zip.getInputStream(entry))
        counting.buffered().use { stream ->
            val elements = decoding(bank) {
                json.decodeToSequence<JsonElement>(stream, DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            }
            val chunk = ArrayList<YomitanTerm>(chunkSize)
            var position = 0
            while (decoding(bank) { elements.hasNext() }) {
                val element = decoding(bank) { elements.next() }
                chunk += parseTerm(element, bank, position++)
                if (chunk.size == chunkSize) {
                    action(chunk.toList())
                    chunk.clear()
                    onRead(counting.count)
                }
            }
            if (chunk.isNotEmpty()) action(chunk.toList())
            onRead(counting.count)
        }
    }

    /**
     * Streams the characters of kanji [bank], in lists of up to [chunkSize], to [action]. Throws
     * [InvalidDictionaryException] for malformed JSON or entries. [onRead] gets the bytes of the
     * bank read so far after each chunk.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun forEachKanjiChunk(
        bank: String,
        chunkSize: Int,
        onRead: suspend (bytes: Long) -> Unit = {},
        action: suspend (List<YomitanKanji>) -> Unit,
    ) {
        val entry = entry(bank) ?: throw InvalidDictionaryException("$bank is missing")
        val counting = CountingInputStream(zip.getInputStream(entry))
        counting.buffered().use { stream ->
            val elements = decoding(bank) {
                json.decodeToSequence<JsonElement>(stream, DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            }
            val chunk = ArrayList<YomitanKanji>(chunkSize)
            var position = 0
            while (decoding(bank) { elements.hasNext() }) {
                val element = decoding(bank) { elements.next() }
                chunk += parseKanji(element, bank, position++)
                if (chunk.size == chunkSize) {
                    action(chunk.toList())
                    chunk.clear()
                    onRead(counting.count)
                }
            }
            if (chunk.isNotEmpty()) action(chunk.toList())
            onRead(counting.count)
        }
    }

    /** All tag definitions: `tagMeta` from the index first, then the tag banks in order. */
    fun readTags(): List<YomitanTag> {
        val fromIndex = (readJson("index.json") as? JsonObject)?.get("tagMeta") as? JsonObject
        val metaTags = fromIndex?.map { (name, value) -> parseTagMeta(name, value) }.orEmpty()
        val bankTags = tagBanks.flatMap { bank ->
            val rows = readJson(bank) as? JsonArray
                ?: throw InvalidDictionaryException("$bank is not a JSON array")
            rows.mapIndexed { i, row -> parseTag(row, bank, i) }
        }
        return metaTags + bankTags
    }

    override fun close() = zip.close()

    private fun entry(name: String): ZipEntry? = zip.getEntry(root + name)

    private fun readJson(name: String): JsonElement {
        val entry = entry(name) ?: throw InvalidDictionaryException("$name is missing")
        val text = try {
            zip.getInputStream(entry).use { it.readBytesAtMost(MAX_JSON_BYTES) }.toString(Charsets.UTF_8)
        } catch (e: TooLargeException) {
            throw InvalidDictionaryException("$name is too large", e)
        }
        return decoding(name) { json.parseToJsonElement(text) }
    }

    private fun parseTerm(element: JsonElement, bank: String, position: Int): YomitanTerm {
        fun invalid(reason: String): Nothing =
            throw InvalidDictionaryException("$bank, entry ${position + 1}: $reason")

        val row = element as? JsonArray ?: invalid("not an array")
        fun stringAt(i: Int): String =
            (row.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalid("field ${i + 1} is not a string")
        fun optionalStringAt(i: Int): String? = when (val value = row.getOrNull(i)) {
            null, JsonNull -> null
            is JsonPrimitive ->
                if (value.isString) value.content.ifEmpty { null } else invalid("field ${i + 1} is not a string")
            else -> invalid("field ${i + 1} is not a string")
        }
        fun numberAt(i: Int): Double =
            (row.getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: invalid("field ${i + 1} is not a number")

        val expression = stringAt(0)
        if (expression.isEmpty()) invalid("empty term")
        val reading = stringAt(1).ifEmpty { expression }
        val definitionTags = optionalStringAt(2)
        val rules = stringAt(3)
        val score = numberAt(4)

        if (index.format == 1) {
            val definitions = row.drop(5).mapIndexed { offset, value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }
                    ?: invalid("field ${offset + 6} is not a string")
            }
            return YomitanTerm(
                expression = expression,
                reading = reading,
                definitionTags = definitionTags,
                rules = rules,
                score = score,
                glossary = JsonArray(definitions).toString(),
                sequence = null,
                termTags = null,
            )
        }

        if (row.size != 8) invalid("expected 8 fields, found ${row.size}")
        val definitions = row[5] as? JsonArray ?: invalid("field 6 is not an array")
        val sequence = (row[6] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: invalid("field 7 is not an integer")
        return YomitanTerm(
            expression = expression,
            reading = reading,
            definitionTags = definitionTags,
            rules = rules,
            score = score,
            glossary = definitions.toString(),
            sequence = sequence,
            termTags = optionalStringAt(7),
        )
    }

    private fun parseKanji(element: JsonElement, bank: String, position: Int): YomitanKanji {
        fun invalid(reason: String): Nothing =
            throw InvalidDictionaryException("$bank, entry ${position + 1}: $reason")

        val row = element as? JsonArray ?: invalid("not an array")
        fun stringAt(i: Int): String =
            (row.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalid("field ${i + 1} is not a string")
        fun strings(values: List<JsonElement>, firstField: Int): List<String> =
            values.mapIndexed { offset, value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: invalid("field ${firstField + offset + 1} is not a string")
            }

        val character = stringAt(0)
        if (character.isEmpty()) invalid("empty character")
        val onyomi = stringAt(1)
        val kunyomi = stringAt(2)
        val tags = stringAt(3)
        if (index.format == 1) {
            return YomitanKanji(character, onyomi, kunyomi, tags, strings(row.drop(4), 4), emptyMap())
        }
        if (row.size != 6) invalid("expected 6 fields, found ${row.size}")
        val meanings = row[4] as? JsonArray ?: invalid("field 5 is not an array")
        val stats = row[5] as? JsonObject ?: invalid("field 6 is not an object")
        return YomitanKanji(
            character = character,
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = tags,
            meanings = meanings.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: invalid("field 5 holds a meaning that is not a string")
            },
            stats = stats.mapValues { (key, value) ->
                (value as? JsonPrimitive)?.content ?: invalid("stat \"$key\" is not a string")
            },
        )
    }

    private fun bankNames(prefix: String): List<String> {
        val pattern = Regex("""${Regex.escape(prefix)}(\d+)\.json""")
        return zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith(root) }
            .mapNotNull { name ->
                val file = name.removePrefix(root)
                pattern.matchEntire(file)?.let { file to it.groupValues[1].toLong() }
            }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
    }

    companion object {
        private val json = Json

        /** Opens [file]. Throws [InvalidDictionaryException] if it is not a usable dictionary. */
        fun open(file: File): YomitanDictionaryReader {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw InvalidDictionaryException("The file is not a zip archive", e)
            } catch (e: IOException) {
                throw InvalidDictionaryException("The file could not be read", e)
            }
            try {
                return YomitanDictionaryReader(zip, findRoot(zip))
            } catch (e: Throwable) {
                zip.close()
                throw e
            }
        }

        /** Dictionaries zipped with their folder have everything under one directory. */
        private fun findRoot(zip: ZipFile): String {
            if (zip.getEntry("index.json") != null) return ""
            val nested = zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith("/index.json") && it.count { c -> c == '/' } == 1 }
                .toList()
            return nested.singleOrNull()?.removeSuffix("index.json")
                ?: throw InvalidDictionaryException("index.json is missing")
        }

        private fun parseIndex(element: JsonElement): YomitanIndex {
            val index = element as? JsonObject
                ?: throw InvalidDictionaryException("index.json is not a JSON object")
            val title = index.string("title")
                ?: throw InvalidDictionaryException("index.json has no title")
            val revision = index.string("revision")
                ?: throw InvalidDictionaryException("index.json has no revision")
            val format = (index["format"] ?: index["version"])?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: throw InvalidDictionaryException("index.json has no format")
            if (format !in 1..3) {
                throw InvalidDictionaryException("Unsupported dictionary format $format")
            }
            if (title.isBlank()) throw InvalidDictionaryException("index.json has an empty title")
            return YomitanIndex(
                title = title,
                revision = revision,
                format = format,
                author = index.string("author"),
                attribution = index.string("attribution"),
                isUpdatable = (index["isUpdatable"] as? JsonPrimitive)?.booleanOrNull ?: false,
                indexUrl = index.string("indexUrl"),
                downloadUrl = index.string("downloadUrl"),
                frequencyMode = index.string("frequencyMode"),
            )
        }

        /** Runs [block], reporting malformed JSON in [name] as an invalid dictionary. */
        /** index.json and tag banks are small; a zip entry can claim to inflate to gigabytes. */
        private const val MAX_JSON_BYTES = 32 * 1024 * 1024

        private inline fun <T> decoding(name: String, block: () -> T): T = try {
            block()
        } catch (e: SerializationException) {
            throw InvalidDictionaryException("$name is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidDictionaryException("$name is not valid JSON", e)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun parseTag(element: JsonElement, bank: String, i: Int): YomitanTag {
            fun invalid(reason: String): Nothing =
                throw InvalidDictionaryException("$bank, entry ${i + 1}: $reason")

            val row = element as? JsonArray ?: invalid("not an array")
            if (row.size != 5) invalid("expected 5 fields, found ${row.size}")
            fun stringAt(position: Int) =
                (row[position] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: invalid("field ${position + 1} is not a string")
            fun numberAt(position: Int) =
                (row[position] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    ?: invalid("field ${position + 1} is not a number")
            return YomitanTag(stringAt(0), stringAt(1), numberAt(2), stringAt(3), numberAt(4))
        }

        private fun parseTagMeta(name: String, element: JsonElement): YomitanTag {
            val meta = element as? JsonObject
                ?: throw InvalidDictionaryException("index.json, tagMeta \"$name\" is not an object")
            fun number(key: String) = (meta[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            return YomitanTag(
                name = name,
                category = meta.string("category").orEmpty(),
                order = number("order"),
                notes = meta.string("notes").orEmpty(),
                score = number("score"),
            )
        }
    }
}

/** Counts the bytes read through it. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
