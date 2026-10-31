package app.honmani.reader.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryCatalogTest {
    @Test
    fun entriesAreDistinctHttpsLinksWithOneRecommendationPerKind() {
        val dictionaries = DictionaryCatalog.dictionaries
        assertEquals(dictionaries.size, dictionaries.map { it.id }.toSet().size)
        assertTrue(dictionaries.all { it.url.startsWith("https://") })
        for (kind in CatalogDictionary.Kind.entries) {
            // Japanese-only definitions are for advanced learners, not recommended to everyone.
            val expected = if (kind == CatalogDictionary.Kind.JAPANESE || kind == CatalogDictionary.Kind.NAMES) 0 else 1
            assertEquals(kind.name, expected, dictionaries.count { it.kind == kind && it.recommended })
        }
    }

    @Test
    fun installedDictionariesAreRecognizedByTitlePrefix() {
        val jitendex = DictionaryCatalog.dictionaries.single { it.id == "jitendex" }
        val jmdict = DictionaryCatalog.dictionaries.single { it.id == "jmdict-english" }
        val installed = listOf("Jitendex.org [2026-10-03]")

        assertTrue(DictionaryCatalog.isInstalled(jitendex, installed))
        assertFalse(DictionaryCatalog.isInstalled(jmdict, installed))
        assertTrue(DictionaryCatalog.isInstalled(jmdict, listOf("JMdict [2026-10-13]")))

        // Jiten (frequency) is not Jitendex, and the other way round.
        val jiten = DictionaryCatalog.dictionaries.single { it.id == "jiten-frequency" }
        assertFalse(DictionaryCatalog.isInstalled(jiten, installed))
        assertTrue(DictionaryCatalog.isInstalled(jiten, listOf("Jiten")))
        assertFalse(DictionaryCatalog.isInstalled(jitendex, listOf("Jiten")))

        val wiktionary = DictionaryCatalog.dictionaries.single { it.id == "wiktionary-japanese" }
        assertTrue(DictionaryCatalog.isInstalled(wiktionary, listOf("wty-ja-ja")))
        assertFalse(DictionaryCatalog.isInstalled(wiktionary, listOf("wty-ja-en")))
    }

    @Test
    fun namesDictionaryIsOptionalAndRecognizedByItsRealTitle() {
        val names = DictionaryCatalog.dictionaries.single { it.id == "jmnedict" }
        val jmdict = DictionaryCatalog.dictionaries.single { it.id == "jmdict-english" }
        assertEquals(CatalogDictionary.Kind.NAMES, names.kind)
        // The title in the published index.json.
        assertTrue(DictionaryCatalog.isInstalled(names, listOf("JMnedict [2026-10-30]")))
        assertTrue(DictionaryCatalog.isNames("JMnedict [2026-10-30]"))
        assertFalse(DictionaryCatalog.isInstalled(jmdict, listOf("JMnedict [2026-10-30]")))
        assertFalse(DictionaryCatalog.isInstalled(names, listOf("JMdict [2026-10-13]")))
        assertFalse(DictionaryCatalog.isNames("JMdict [2026-10-13]"))
        assertFalse(DictionaryCatalog.starterPack(japanese = false).any { it.id == names.id })
        assertFalse(DictionaryCatalog.starterPack(japanese = true).any { it.id == names.id })
    }

    @Test
    fun starterPacks() {
        assertEquals(
            listOf("jitendex", "kanjidic-english", "jiten-frequency", "kanjium-pitch"),
            DictionaryCatalog.starterPack(japanese = false).map { it.id },
        )
        // Japanese definitions first, English ones after them.
        assertEquals(
            listOf("wiktionary-japanese", "jitendex", "kanjidic-english", "jiten-frequency", "kanjium-pitch"),
            DictionaryCatalog.starterPack(japanese = true).map { it.id },
        )
    }
}
