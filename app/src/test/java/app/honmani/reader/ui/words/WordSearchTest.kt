package app.honmani.reader.ui.words

import app.honmani.reader.data.words.WordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class WordSearchTest {
    private fun word(id: Long, expression: String, reading: String, gloss: String) = WordEntity(
        id = id,
        expression = expression,
        reading = reading,
        gloss = gloss,
        lookupCount = 1,
        firstLookedUpAt = 0,
        lastLookedUpAt = 0,
        rankKey = 0.0,
        lastSentence = "",
        lastSurfaceStart = 0,
        lastSurfaceEnd = 0,
    )

    private val words = listOf(
        word(1, "猫", "ねこ", "Cat; feline"),
        word(2, "犬", "いぬ", "dog"),
        word(3, "名前", "なまえ", "name; full name"),
    )

    @Test
    fun emptyOrBlankQueryKeepsAll() {
        assertEquals(words, filterWords(words, ""))
        assertEquals(words, filterWords(words, "  "))
    }

    @Test
    fun matchesWordReadingOrMeaning_ignoringCase() {
        assertEquals(listOf(1L), filterWords(words, "猫").map { it.id })
        assertEquals(listOf(2L), filterWords(words, "いぬ").map { it.id })
        assertEquals(listOf(1L), filterWords(words, "CAT").map { it.id })
        assertEquals(listOf(3L), filterWords(words, " Full ").map { it.id })
        assertEquals(emptyList<Long>(), filterWords(words, "bird").map { it.id })
    }

    @Test
    fun readWordsMatchOnTheWord() {
        val read = listOf("本" to 30, "雨" to 3)
        assertEquals(listOf("雨" to 3), filterReadWords(read, "雨"))
        assertEquals(read, filterReadWords(read, ""))
    }

    @Test
    fun dictionarySearchFindsSavedWordsByRomajiReading() {
        assertEquals(listOf(words[0]), searchWords(words, "neko"))
        assertEquals(listOf(words[0]), searchWords(words, "nek"))
        assertEquals(listOf(words[1]), searchWords(words, "dog"))
        assertEquals(emptyList<WordEntity>(), searchWords(words, " "))
    }
}
