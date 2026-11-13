package app.honmani.reader.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinflectorTest {
    private data class Case(val inflected: String, val base: String, val wordClass: String)

    private val corpus: List<Case> =
        javaClass.classLoader!!.getResourceAsStream("deinflection-corpus.tsv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (inflected, base, wordClass) = line.split('\t')
                Case(inflected, base, wordClass)
            }

    @Test
    fun corpusIsLargeEnough() {
        assertTrue("corpus has ${corpus.size} cases", corpus.size >= 300)
    }

    @Test
    fun everyCorpusFormDeinflectsToItsDictionaryForm() {
        val failures = corpus.filterNot { case ->
            val wordClass = WordClass.fromRules(case.wordClass)
            Deinflector.deinflect(case.inflected).any {
                it.text == case.base && it.conditions and wordClass != 0
            }
        }
        assertEquals(
            "Not deinflected (${failures.size} of ${corpus.size}):\n" +
                failures.joinToString("\n") { "${it.inflected} -> ${it.base} (${it.wordClass})" },
            emptyList<Case>(),
            failures,
        )
    }

    @Test
    fun onlyHonorificVerbsHaveAnIStem() {
        fun bases(text: String) = Deinflector.deinflect(text).map { it.text }
        assertTrue("くださる" in bases("ください"))
        assertTrue("いらっしゃる" in bases("いらっしゃいます"))
        assertTrue("ござる" in bases("ございます"))
        assertTrue("仰る" in bases("仰います"))
        // Not 知る (しる): 発見されたら|しい instead of 発見された|らしい.
        assertTrue("しる" !in bases("しい"))
    }

    @Test
    fun inflectionsAreListedFromTheDictionaryFormOutwards() {
        val result = Deinflector.deinflect("食べさせられなかった")
            .single { it.text == "食べる" && it.conditions and WordClass.V1 != 0 }

        assertEquals(
            listOf(
                Inflection.CAUSATIVE,
                Inflection.POTENTIAL_OR_PASSIVE,
                Inflection.NEGATIVE,
                Inflection.PAST,
            ),
            result.inflections,
        )
    }

    @Test
    fun theInputItselfIsACandidateMatchingAnyClass() {
        val first = Deinflector.deinflect("猫").first()
        assertEquals(Deinflection("猫", WordClass.ANY, emptyList()), first)
    }

    @Test
    fun conditionsKeepImpossibleChainsOut() {
        // 書けば -> 書く (godan). The adjective rule く -> い only applies to word-final forms, so
        // the godan 書く must not continue to an "adjective" 書い.
        val candidates = Deinflector.deinflect("書けば")
        assertTrue(candidates.any { it.text == "書く" && it.conditions == WordClass.V5 })
        assertTrue(candidates.none { it.text == "書い" })
    }

    @Test
    fun intermediateFormsNeverMatchDictionaryEntries() {
        // 書いている -> 書いて is a て-form, not a word.
        val te = Deinflector.deinflect("書いている").filter { it.text == "書いて" }
        assertTrue(te.isNotEmpty())
        assertTrue(te.all { it.conditions and WordClass.DICTIONARY_FORMS == 0 })
    }

    @Test
    fun rulesOfYomitanTermsMapToClasses() {
        assertEquals(WordClass.V5, WordClass.fromRules("v5"))
        assertEquals(WordClass.V1 or WordClass.VS, WordClass.fromRules("v1 vs"))
        assertEquals(0, WordClass.fromRules(""))
        assertEquals(0, WordClass.fromRules("unknown"))
    }
}
