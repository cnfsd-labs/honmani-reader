package app.honmani.reader.data.difficulty

import app.honmani.reader.data.aozora.AozoraArchive
import app.honmani.reader.data.aozora.AozoraClient
import app.honmani.reader.data.dictionary.yomitan.TermMetaKind
import app.honmani.reader.data.dictionary.yomitan.YomitanDictionaryReader
import app.honmani.reader.data.library.AozoraEpub
import app.honmani.reader.data.library.AozoraText
import app.honmani.reader.data.library.TextEncoding
import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.lookup.WordClass
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-off tool, not a test: grades every work of `docs/aozora/index.json` with [Difficulty] so the
 * free books list can show a score. It does nothing unless `HONMANI_GRADER_OUT` is set; see
 * `scripts/aozora-index/README.md` for the environment and the gradle command.
 *
 * Each work is handled as the app handles an imported Aozora text: download, unzip, decode,
 * [AozoraText.parse], [AozoraEpub.write], [EpubPositions.texts], [Difficulty.measure].
 */
class AozoraIndexGrader {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun requiredFile(name: String): File {
        val path = env(name) ?: error("$name is not set")
        return File(path).also { check(it.isFile) { "$name: $path is not a file" } }
    }

    @Test
    fun grade() {
        val outPath = env("HONMANI_GRADER_OUT")
        assumeTrue("HONMANI_GRADER_OUT is not set", outPath != null)
        val out = File(outPath!!)
        val termsZip = requiredFile("HONMANI_GRADER_TERMS")
        val freqZip = requiredFile("HONMANI_GRADER_FREQ")
        val cache = File(env("HONMANI_GRADER_CACHE") ?: error("HONMANI_GRADER_CACHE is not set")).also { it.mkdirs() }
        val delayMs = env("HONMANI_GRADER_DELAY_MS")?.toLong() ?: 700L
        val limit = env("HONMANI_GRADER_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val only = env("HONMANI_GRADER_IDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val indexFile = File(env("HONMANI_GRADER_INDEX") ?: "../docs/aozora/index.json").also { check(it.isFile) { "No index at ${it.absolutePath}" } }
        out.absoluteFile.parentFile?.mkdirs()

        val started = System.nanoTime()
        val built = buildLexicon(termsZip, freqZip)
        val loadMs = (System.nanoTime() - started) / 1_000_000
        println("GRADER lexicon of ${built.lexicon.size} words built in $loadMs ms; ${built.signature}")
        val segmenter = Segmenter(built.lexicon)

        val done = HashSet<String>()
        if (out.isFile) {
            // A work that failed only for a network reason is tried again; its old line stays (the last one counts).
            out.forEachLine { line -> if (!TRANSIENT.containsMatchIn(line)) ID.find(line)?.let { done += it.groupValues[1] } }
        }
        val works = Json.parseToJsonElement(indexFile.readText()).jsonObject["works"]!!.jsonArray
            .map { it.jsonObject }
            .filter { only == null || it.string("i") in only }
        val todo = works.filter { it.string("i") !in done }.take(limit)
        println("GRADER ${works.size} works in scope, ${done.size} already in $out, ${todo.size} to do")

        val client = AozoraClient()
        val temp = File.createTempFile("grader", ".epub")
        var failures = 0
        var graded = 0
        val begin = System.nanoTime()
        out.appendText(
            JsonObject(
                mapOf(
                    "meta" to JsonPrimitive("lexicon"),
                    "version" to JsonPrimitive(Difficulty.VERSION),
                    "lexiconWords" to JsonPrimitive(built.lexicon.size),
                    "lexiconLoadMs" to JsonPrimitive(loadMs),
                    "signature" to JsonPrimitive(built.signature),
                    "files" to JsonArray(built.files.map { JsonPrimitive(it) }),
                ),
            ).toString() + "\n",
        )
        try {
            for ((n, work) in todo.withIndex()) {
                val id = work.string("i")
                val workStart = System.nanoTime()
                var score = 0
                var r95 = 0
                var characters = 0L
                var words = 0
                var error: String? = null
                var fetched = false
                try {
                    val raw = cached(cache, id, work.string("u"), client, delayMs).also { fetched = it.second }.first
                    val bytes = AozoraArchive.text(raw)
                    val charset = TextEncoding.detect(bytes)
                    val bom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
                    val text = String(bytes, bom, bytes.size - bom, charset)
                    val book = AozoraText.parse(text, fallbackTitle = work.string("t"))
                    AozoraEpub.write(book, identifier = "urn:grader:$id", target = temp)
                    val texts = EpubPositions.texts(temp)
                    val measured = texts?.let { Difficulty.measure(it.asSequence(), segmenter) }
                    if (measured == null) {
                        error = if (texts == null) "unreadable" else "too_short"
                    } else {
                        score = measured.difficulty.score
                        r95 = measured.difficulty.r95
                        characters = measured.difficulty.characters
                        words = measured.difficulty.words
                    }
                    failures = 0
                } catch (e: FetchException) {
                    error = e.code
                    if (e.counts) failures++ else failures = 0
                } catch (e: Throwable) {
                    error = "error:" + e.javaClass.simpleName
                    System.err.println("GRADER $id failed: $e")
                    failures = 0
                }
                val line = buildMap<String, kotlinx.serialization.json.JsonElement> {
                    put("id", JsonPrimitive(id))
                    put("score", JsonPrimitive(score))
                    put("r95", JsonPrimitive(r95))
                    put("characters", JsonPrimitive(characters))
                    put("words", JsonPrimitive(words))
                    put("version", JsonPrimitive(Difficulty.VERSION))
                    put("lexicon", JsonPrimitive(built.signature))
                    if (error != null) put("error", JsonPrimitive(error))
                }
                out.appendText(JsonObject(line).toString() + "\n")
                graded++
                val ms = (System.nanoTime() - workStart) / 1_000_000
                println("GRADER ${n + 1}/${todo.size} $id ${work.string("t")}: score $score r95 $r95 chars $characters words $words ${error ?: "ok"} in $ms ms${if (fetched) " (downloaded)" else ""}")
                check(failures < MAX_FAILURES) { "Stopping after $MAX_FAILURES failed downloads in a row; run again to resume" }
            }
        } finally {
            temp.delete()
            println("GRADER finished $graded works in ${(System.nanoTime() - begin) / 1_000_000} ms")
        }
    }

    /**
     * The raw file of a work: from [cache] if present, else downloaded (a pause of [delayMs]
     * before each request, backing off on 429 and 503). Second is whether it was downloaded.
     */
    private fun cached(cache: File, id: String, file: String, client: AozoraClient, delayMs: Long): Pair<ByteArray, Boolean> {
        val target = File(cache, "$id-" + file.substringAfter('/'))
        if (target.isFile && target.length() > 0) return target.readBytes() to false
        val url = client.bookUrl(file)
        var wait = BACKOFF_MS
        for (attempt in 0..RETRIES) {
            Thread.sleep(if (attempt == 0) delayMs else wait)
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = try {
                    connection.responseCode
                } catch (e: IOException) {
                    if (attempt == RETRIES) throw FetchException("network", true)
                    wait *= 2
                    continue
                }
                when (code) {
                    200 -> {
                        val bytes = try {
                            connection.inputStream.use { it.readBytes() }
                        } catch (e: IOException) {
                            if (attempt == RETRIES) throw FetchException("network", true)
                            wait *= 2
                            continue
                        }
                        if (bytes.size > MAX_RAW) throw FetchException("too_large", false)
                        val part = File(cache, target.name + ".part")
                        part.writeBytes(bytes)
                        check(part.renameTo(target)) { "Cannot move $part" }
                        return bytes to true
                    }
                    404, 410 -> throw FetchException("http$code", false)
                    429, 503 -> {
                        val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        wait = maxOf(wait * 2, (retryAfter ?: 0) * 1000)
                        println("GRADER HTTP $code for $id; backing off ${wait / 1000} s")
                        if (attempt == RETRIES) throw FetchException("http$code", true)
                    }
                    else -> throw FetchException("http$code", true)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw FetchException("network", true)
    }

    private class FetchException(val code: String, val counts: Boolean) : Exception(code)

    class BuiltLexicon(val lexicon: Lexicon, val signature: String, val files: List<String>)

    /**
     * The [Lexicon] [LexiconLoader] would build from these two Yomitan dictionaries: every term
     * and reading with its word classes, then every frequency rank through [LexiconLoader.addRank],
     * then the grammar words.
     */
    private fun buildLexicon(termsZip: File, freqZip: File): BuiltLexicon = runBlocking {
        val builder = Lexicon.Builder()
        val parts = StringBuilder("v").append(Difficulty.VERSION)
        val termsIndex = YomitanDictionaryReader.open(termsZip).use { reader ->
            for (bank in reader.termBanks) {
                reader.forEachTermChunk(bank, 20_000) { page ->
                    for (term in page) {
                        val classes = WordClass.fromRules(term.rules)
                        builder.add(term.expression, classes, 0)
                        if (term.reading.isNotEmpty() && term.reading != term.expression) builder.add(term.reading, classes, 0)
                    }
                }
            }
            reader.index
        }
        var ranks = 0
        val freqIndex = YomitanDictionaryReader.open(freqZip).use { reader ->
            check(reader.index.frequencyMode != "occurrence-based") { "${freqZip.name} is occurrence-based" }
            for (bank in reader.termMetaBanks) {
                reader.forEachTermMetaChunk(bank, 20_000) { page ->
                    for (row in page) {
                        if (row.kind != TermMetaKind.FREQUENCY || row.value == null) continue
                        LexiconLoader.addRank(builder, LexiconFrequency(0, row.expression, row.reading, row.value, row.display))
                        ranks++
                    }
                }
            }
            reader.index
        }
        parts.append("|").append(freqIndex.title).append("@").append(freqIndex.revision)
        parts.append("|").append(termsIndex.title).append("@").append(termsIndex.revision)
        println("GRADER $ranks frequency rows")
        BuiltLexicon(
            Difficulty.addGrammar(builder).build(),
            parts.toString(),
            listOf(termsZip, freqZip).map { "${it.name} (${it.length()} bytes)" },
        )
    }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private companion object {
        val ID = Regex(""""id":"(\d+)"""")
        val TRANSIENT = Regex(""""error":"(network|http429|http5\d\d)"""")
        const val USER_AGENT = "Honmani-Reader difficulty grading, https://github.com/cnfsd-labs/honmani-reader"
        const val MAX_FAILURES = 5
        const val RETRIES = 3
        const val BACKOFF_MS = 30_000L
        const val MAX_RAW = 20 * 1024 * 1024
    }
}
