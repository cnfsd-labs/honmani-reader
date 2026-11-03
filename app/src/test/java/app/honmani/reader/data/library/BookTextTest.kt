package app.honmani.reader.data.library

import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.data.sync.testEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookTextTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun book(vararg documents: String) = BookText(documents.toList())

    @Test
    fun hitsComeInReadingOrder_withTheContextAroundThem() {
        val results = book("吾輩は猫である。\n名前はまだ無い。", "猫の名前。").find("猫")

        val first = results.hits.first()
        assertEquals(listOf("吾輩は", "猫", "である。"), listOf(first.before, first.match, first.after))
        assertFalse(results.more)
        assertEquals(2, results.hits.size)
        // The context stops at the end of the line.
        assertEquals("", results.hits[1].before)
        assertEquals("の名前。", results.hits[1].after)
        assertTrue(results.hits[0].characters < results.hits[1].characters)
    }

    @Test
    fun charactersAreTtusCountBeforeTheHit_blanksAndPunctuationNotCounted() {
        // 「 and a space count for nothing: 吾輩は (3) come before 猫.
        val hit = book("「吾輩は 猫」").find("猫").hits.single()

        assertEquals(3, hit.characters)
    }

    @Test
    fun charactersRunOnAcrossDocuments_andGivePercent() {
        val results = book("あいうえお", "かきくけこ").find("け")

        assertEquals(8, results.hits.single().characters)
        // 10 characters in all.
        assertEquals(80, results.hits.single().percent)
    }

    @Test
    fun theContextIsCutAfterTwentyCharacters_andNeverInsideASurrogatePair() {
        val text = "あ".repeat(30) + "猫" + "🐱".repeat(15)
        val hit = book(text).find("猫").hits.single()

        assertEquals(BookText.CONTEXT, hit.before.length)
        assertEquals(BookText.CONTEXT, hit.after.length)
        // Ten cats (two chars each), not a cat cut in half.
        assertTrue(hit.after.codePoints().allMatch { it == 0x1F431 })
    }

    @Test
    fun aMatchNeverRunsAcrossParagraphs() {
        assertTrue(book("猫\nである").find("猫である").hits.isEmpty())
    }

    @Test
    fun blanksAroundTheQueryAreIgnored_andCaseDoesNotMatter() {
        val results = book("Hello World").find("  world ")

        assertEquals("World", results.hits.single().match)
        assertTrue(book("猫").find("   ").hits.isEmpty())
        assertTrue(book("猫").find("").hits.isEmpty())
    }

    @Test
    fun theListStopsAtTheLimit_andSaysThereIsMore() {
        val text = "猫".repeat(10)

        val full = book(text).find("猫", maxHits = 10)
        assertEquals(10, full.hits.size)
        assertFalse(full.more)

        val cut = book(text).find("猫", maxHits = 4)
        assertEquals(4, cut.hits.size)
        assertTrue(cut.more)
        assertEquals(listOf(0, 1, 2, 3), cut.hits.map { it.characters })
    }

    @Test
    fun matchesDoNotOverlap() {
        assertEquals(listOf(0, 2), book("ああああ").find("ああ").hits.map { it.characters })
    }

    @Test
    fun theHintIsTheLettersBeforeTheMatchWithoutBlanks() {
        val hit = book("吾輩は 猫である").find("猫").hits.single()

        assertEquals("吾輩は", hit.hint)
        assertEquals(BookText.HINT, book("あいうえおかきくけこ").find("け").hits.single().hint.length)
    }

    @Test
    fun anEpubSearchIgnoresFuriganaAndAgreesWithEpubPositions() {
        val file = testEpub(folder.newFile("book.epub"))
        val texts = EpubPositions.texts(file, withoutParentheses = true)!!.map { it.text }
        val positions = EpubPositions.read(file)!!

        // 未だ is written 未 with the reading ま over it.
        val hit = BookText(texts).find("未だ").hits.single()

        assertEquals("未だ", hit.match)
        assertEquals(10, hit.characters)
        // The same place KOReader's position for the word's start gives.
        assertEquals(hit.characters, positions.charactersAt(positions.xpointerAt(hit.characters)!!))
        assertTrue(BookText(texts).find("未ま").hits.isEmpty())
    }

    @Test
    fun around_givesTheLineTextBeforeAndAfterAPlace() {
        val text = book("あいう\nえおかきくけこ", "さしすせそ")

        // 3 characters in the first document: the place is at the start of the second line.
        assertEquals(Around("", "えおかきくけこ"), text.around(3))
        assertEquals(Around("あ", "いう"), text.around(1))
        // The next document starts at 10.
        assertEquals(Around("", "さしすせそ"), text.around(10))
        assertEquals(Around("さし", "すせそ"), text.around(12))
    }

    @Test
    fun around_hintAndMatchSkipBlanks_andThePlaceIsKeptInsideTheBook() {
        val text = book("あい うえお かきくけこさし")

        val around = text.around(5)!!
        assertEquals("いうえお", around.hint.takeLast(4))
        assertEquals("かきくけこさ", around.match)
        assertEquals(text.around(text.total), text.around(10_000))
        assertEquals(text.around(0), text.around(-4))
        assertEquals(null, book().around(0))
    }
}
