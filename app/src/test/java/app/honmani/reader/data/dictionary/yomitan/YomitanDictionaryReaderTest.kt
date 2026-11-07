package app.honmani.reader.data.dictionary.yomitan

import app.honmani.reader.data.dictionary.index
import app.honmani.reader.data.dictionary.sampleDictionary
import app.honmani.reader.data.dictionary.writeZip
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YomitanDictionaryReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun open(files: Map<String, String>) =
        YomitanDictionaryReader.open(writeZip(temp.root, files))

    private suspend fun YomitanDictionaryReader.allTerms(chunkSize: Int = 100): List<YomitanTerm> {
        val terms = mutableListOf<YomitanTerm>()
        for (bank in termBanks) forEachTermChunk(bank, chunkSize) { terms += it }
        return terms
    }

    @Test
    fun readsIndex() {
        open(sampleDictionary()).use { reader ->
            assertEquals(YomitanIndex("Test Dictionary", "2026.10", 3, "Honmani", null), reader.index)
        }
    }

    @Test
    fun readsUpdateInformation() {
        val files = mapOf(
            "index.json" to index(
                extra = ""","isUpdatable":true,"indexUrl":"https://example.org/index.json",""" +
                    """"downloadUrl":"https://example.org/dict.zip"""",
            ),
        )
        open(files).use { reader ->
            assertEquals(true, reader.index.isUpdatable)
            assertEquals("https://example.org/index.json", reader.index.indexUrl)
            assertEquals("https://example.org/dict.zip", reader.index.downloadUrl)
        }
    }

    @Test
    fun anArchiveWithTooManyFilesIsRefused() {
        val files = sampleDictionary() + (1..20).associate { "media/$it.txt" to "x" }
        val zip = writeZip(temp.root, files)

        val e = assertThrows(InvalidDictionaryException::class.java) { YomitanDictionaryReader.open(zip, maxEntries = 10) }
        assertEquals("The file contains too many files to be a dictionary", e.message)
        YomitanDictionaryReader.open(zip, maxEntries = 1_000).close()
    }

    @Test
    fun anArchiveThatUnpacksToTooMuchIsRefused() {
        val zip = writeZip(temp.root, sampleDictionary() + ("media/big.bin" to "x".repeat(50_000)))

        val e = assertThrows(InvalidDictionaryException::class.java) {
            YomitanDictionaryReader.open(zip, maxUnpackedBytes = 10_000)
        }
        assertEquals("The file is too large when unpacked to be a dictionary", e.message)
    }

    @Test
    fun anEntryThatInflatesBeyondItsDeclaredSizeIsStopped() {
        val stream = LimitedInputStream(ByteArray(100).inputStream(), "term_bank_1.json", 60)

        val e = assertThrows(InvalidDictionaryException::class.java) { stream.readBytes() }
        assertEquals("term_bank_1.json is larger than the archive says", e.message)
        assertEquals(60, LimitedInputStream(ByteArray(60).inputStream(), "x", 60).readBytes().size)
    }

    @Test
    fun legacyVersionKeyIsAcceptedAsFormat() {
        val files = mapOf("index.json" to """{"title":"Old","revision":"1","version":3}""")
        open(files).use { assertEquals(3, it.index.format) }
    }

    @Test
    fun readsFormat3Terms() = runTest {
        open(sampleDictionary()).use { reader ->
            val terms = reader.allTerms()

            assertEquals(listOf("猫", "食べる", "ねこ", "読む"), terms.map { it.expression })
            assertEquals(
                YomitanTerm("猫", "ねこ", "n", "", 100.0, """["cat"]""", 1, "P"),
                terms[0],
            )
            // Empty reading means "same as the expression"; empty tag strings mean no tags.
            assertEquals("ねこ", terms[2].reading)
            assertNull(terms[1].termTags)
            assertNull(terms[3].definitionTags)
            // Structured content is kept as JSON for rendering later.
            assertEquals("""[{"type":"structured-content","content":"cat (kana)"}]""", terms[2].glossary)
        }
    }

    @Test
    fun banksAreReadInNumericOrder() = runTest {
        val files = mapOf(
            "index.json" to index(),
            "term_bank_10.json" to """[["十","じゅう","","",0,["ten"],10,""]]""",
            "term_bank_2.json" to """[["二","に","","",0,["two"],2,""]]""",
        )
        open(files).use { reader ->
            assertEquals(listOf("term_bank_2.json", "term_bank_10.json"), reader.termBanks)
            assertEquals(listOf("二", "十"), reader.allTerms().map { it.expression })
        }
    }

    @Test
    fun termsArrivesInChunks() = runTest {
        open(sampleDictionary()).use { reader ->
            val sizes = mutableListOf<Int>()
            reader.forEachTermChunk("term_bank_1.json", chunkSize = 1) { sizes += it.size }
            assertEquals(listOf(1, 1), sizes)
        }
    }

    @Test
    fun readsFormat1Terms() = runTest {
        val files = mapOf(
            "index.json" to index(format = 1),
            "term_bank_1.json" to """[["猫","ねこ","n","",5,"cat","pussycat"],["犬","いぬ","n","",0]]""",
        )
        open(files).use { reader ->
            val terms = reader.allTerms()
            assertEquals(YomitanTerm("猫", "ねこ", "n", "", 5.0, """["cat","pussycat"]""", null, null), terms[0])
            assertEquals("[]", terms[1].glossary)
        }
    }

    @Test
    fun readsTagsFromTagMetaAndTagBanks() {
        val files = sampleDictionary() + mapOf(
            "index.json" to index(extra = ""","tagMeta":{"v1":{"category":"partOfSpeech","order":1,"notes":"ichidan verb","score":0}}"""),
        )
        open(files).use { reader ->
            assertEquals(
                listOf(
                    YomitanTag("v1", "partOfSpeech", 1.0, "ichidan verb", 0.0),
                    YomitanTag("n", "partOfSpeech", -3.0, "noun", 0.0),
                    YomitanTag("P", "popular", -10.0, "popular term", 10.0),
                ),
                reader.readTags(),
            )
        }
    }

    @Test
    fun dictionaryZippedWithItsFolderIsFound() = runTest {
        val nested = sampleDictionary().mapKeys { (path, _) -> "jmdict/$path" }
        open(nested).use { reader ->
            assertEquals("Test Dictionary", reader.index.title)
            assertEquals(4, reader.allTerms().size)
        }
    }

    @Test
    fun rejectsFilesThatAreNotDictionaries() {
        val notZip = File(temp.root, "book.epub").apply { writeText("not a zip") }
        assertMessage("The file is not a zip archive") { YomitanDictionaryReader.open(notZip) }
        assertMessage("index.json is missing") { open(mapOf("term_bank_1.json" to "[]")) }
        assertMessage("index.json is not valid JSON") { open(mapOf("index.json" to "{")) }
        assertMessage("index.json has no title") {
            open(mapOf("index.json" to """{"revision":"1","format":3}"""))
        }
        assertMessage("Unsupported dictionary format 4") { open(mapOf("index.json" to index(format = 4))) }
    }

    @Test
    fun malformedTermsNameTheBankAndEntry() = runTest {
        val wrongFieldCount = mapOf(
            "index.json" to index(),
            "term_bank_1.json" to """[["猫","ねこ","","",0,["cat"],1,""],["犬","いぬ","","",0,["dog"],2]]""",
        )
        val wrongType = mapOf(
            "index.json" to index(),
            "term_bank_1.json" to """[["猫","ねこ","","","high",["cat"],1,""]]""",
        )
        val brokenJson = mapOf("index.json" to index(), "term_bank_1.json" to "[[\"猫\",")

        assertEquals(
            "term_bank_1.json, entry 2: expected 8 fields, found 7",
            invalidTermsMessage(wrongFieldCount),
        )
        assertEquals("term_bank_1.json, entry 1: field 5 is not a number", invalidTermsMessage(wrongType))
        assertEquals("term_bank_1.json is not valid JSON", invalidTermsMessage(brokenJson))
    }

    private suspend fun invalidTermsMessage(files: Map<String, String>): String? =
        open(files).use { reader ->
            try {
                reader.allTerms()
                null
            } catch (e: InvalidDictionaryException) {
                e.message
            }
        }

    private fun assertMessage(expected: String, block: () -> Unit) {
        val e = assertThrows(InvalidDictionaryException::class.java) { block() }
        assertEquals(expected, e.message)
    }
}
