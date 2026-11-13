package app.honmani.reader.data.dictionary

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadFallbackTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val primary = "https://github.com/p/d/releases/latest/download/d.zip"
    private val fallback = "https://github.com/p/d/releases/download/2026-11-11/d.zip"

    @Test
    fun aZipWithOnlyIndexAndTags_hasNoContentBank() {
        val broken = writeZip(
            temp.root,
            mapOf("index.json" to index(), "tag_bank_1.json" to "[]"),
            name = "broken.zip",
        )
        assertFalse(DownloadCheck.hasContentBank(broken))
    }

    @Test
    fun anyContentBankCounts_alsoInsideAFolder() {
        assertTrue(DownloadCheck.hasContentBank(writeZip(temp.root, sampleDictionary(), name = "terms.zip")))
        for (bank in listOf("term_meta_bank_1.json", "kanji_bank_3.json", "kanji_meta_bank_1.json")) {
            val zip = writeZip(temp.root, mapOf("d/index.json" to index(), "d/$bank" to "[]"), name = "$bank.zip")
            assertTrue(bank, DownloadCheck.hasContentBank(zip))
        }
    }

    @Test
    fun aFileThatIsNoZip_isLeftToTheImport() {
        val file = File(temp.root, "not.zip").apply { writeText("<html>") }
        assertTrue(DownloadCheck.hasContentBank(file))
    }

    @Test
    fun aGoodPrimary_isTheOnlyAttempt() = runTest {
        val tried = mutableListOf<String>()
        assertEquals("ok", withReleaseFallback("D", primary, fallback) { tried += it; "ok" })
        assertEquals(listOf(primary), tried)
    }

    @Test
    fun anIncompletePrimary_usesTheFallback() = runTest {
        for (failure in listOf(IncompleteReleaseException("D"), NoTermsException("D []"))) {
            val tried = mutableListOf<String>()
            val result = withReleaseFallback("D", primary, fallback) { url ->
                tried += url
                if (url == primary) throw failure
                "ok"
            }
            assertEquals("ok", result)
            assertEquals(listOf(primary, fallback), tried)
        }
    }

    @Test
    fun bothIncomplete_orNoFallback_giveTheClearError() = runTest {
        val tried = mutableListOf<String>()
        val both = runCatching {
            withReleaseFallback<Unit>("KANJIDIC (English)", primary, fallback) { url ->
                tried += url
                throw NoTermsException("KANJIDIC []")
            }
        }.exceptionOrNull()
        assertEquals("KANJIDIC (English)", (both as IncompleteReleaseException).name)
        assertEquals(listOf(primary, fallback), tried)

        val none = runCatching {
            withReleaseFallback<Unit>("Jiten", primary, null) { throw IncompleteReleaseException("Jiten") }
        }.exceptionOrNull()
        assertEquals("Jiten", (none as IncompleteReleaseException).name)
    }

    @Test
    fun otherFailures_passThroughWithoutAFallback() = runTest {
        val tried = mutableListOf<String>()
        val e = runCatching {
            withReleaseFallback<Unit>("D", primary, fallback) { url ->
                tried += url
                throw DownloadException("offline", reason = FailureReason.OFFLINE)
            }
        }.exceptionOrNull()
        assertTrue(e is DownloadException)
        assertEquals(listOf(primary), tried)
    }

    @Test
    fun theYomidevsEntriesHaveAPinnedFallbackOnTheSameHost() {
        val withFallback = DictionaryCatalog.dictionaries.filter { it.fallbackUrl != null }
        assertEquals(setOf("jmdict-english", "jmnedict", "kanjidic-english"), withFallback.map { it.id }.toSet())
        for (dictionary in withFallback) {
            val url = dictionary.fallbackUrl!!
            assertTrue(url, url.startsWith("https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-11-11/"))
            assertEquals(dictionary.url.substringAfterLast('/'), url.substringAfterLast('/'))
        }
    }
}
