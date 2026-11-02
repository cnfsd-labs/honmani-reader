package app.honmani.reader.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnglishQueryTest {
    @Test
    fun normalizeDropsToArticlesBracketsAndPunctuation() {
        assertEquals("eat", EnglishQuery.normalize("to eat (e.g. a meal)"))
        assertEquals("cat", EnglishQuery.normalize("cat (esp. the domestic cat, Felis catus)"))
        assertEquals("dog", EnglishQuery.normalize("dog (Canis (lupus) familiaris)"))
        assertEquals("black cat", EnglishQuery.normalize("a Black  cat!"))
        assertEquals("can't", EnglishQuery.normalize("can't"))
        assertEquals("to", EnglishQuery.normalize("to"))
    }

    @Test
    fun parseTakesLatinTextOnly_asAPhrase() {
        assertEquals("\"cat\"", EnglishQuery.parse("Cat")!!.match)
        assertEquals("\"eat\"", EnglishQuery.parse("to eat")!!.match)
        assertEquals("\"black cat\"", EnglishQuery.parse(" black cat ")!!.match)
        assertEquals("\"can t\"", EnglishQuery.parse("can't")!!.match)
        assertNull(EnglishQuery.parse("猫"))
        assertNull(EnglishQuery.parse("たb"))
        assertNull(EnglishQuery.parse("123"))
        assertNull(EnglishQuery.parse("(?)"))
    }

    @Test
    fun fitRanksTheQueryAsAGlossOfTheFirstMeaningFirst() {
        val eat = EnglishQuery.parse("eat")!!
        assertEquals(EnglishQuery.Fit.FIRST_EXACT, eat.fit(listOf(listOf("to eat"), listOf("to live on (e.g. a salary)"))))
        assertEquals(EnglishQuery.Fit.EXACT, eat.fit(listOf(listOf("to bite"), listOf("to eat"))))
        assertEquals(EnglishQuery.Fit.FIRST_WORD, eat.fit(listOf(listOf("to eat greedily"))))
        assertEquals(EnglishQuery.Fit.OTHER, eat.fit(listOf(listOf("meal"), listOf("to eat greedily"))))
        // Words only: "eat" is not in "eaten" or "theater".
        assertEquals(EnglishQuery.Fit.OTHER, eat.fit(listOf(listOf("eaten", "theater"))))
        // Remarks in brackets don't count.
        assertEquals(EnglishQuery.Fit.OTHER, EnglishQuery.parse("cat")!!.fit(listOf(listOf("wheelbarrow (cat cart)"))))
    }
}
