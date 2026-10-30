package app.honmani.reader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubPositionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var positions: EpubPositions

    @Before
    fun setUp() {
        positions = EpubPositions.read(testEpub(folder.newFile("book.epub")))!!
    }

    @Test
    fun countsAsTtuDoes() {
        // 吾輩は猫である (7) + 名前は未だ無い (7, not the reading ま) + 二 (1) + どこで生れたかとんと見当がつかぬ (16).
        assertEquals(31, positions.total)
        assertEquals(7, EpubPositions.count("吾輩は猫である。"))
        assertEquals(4, EpubPositions.count("ABC 1、。・"))
    }

    @Test
    fun readsKoreaderPositions() {
        assertEquals(18, positions.charactersAt("/body/DocFragment[2]/body/p/text().3"))
        assertEquals(12, positions.charactersAt("/body/DocFragment[1]/body/div/p[2]/text()[2].1"))
        assertEquals(7, positions.charactersAt("/body/DocFragment[1]/body/div/p[2]"))
        assertEquals(10, positions.charactersAt("/body/DocFragment[1]/body/div[1]/p[2]/ruby/text().0"))
        // Explicit first indexes, as newer KOReader writes them.
        assertEquals(18, positions.charactersAt("/body/DocFragment[2]/body[1]/p[1]/text()[1].3"))
    }

    @Test
    fun aPathThatNoLongerFits_givesNullOrTheParagraph() {
        assertNull(positions.charactersAt("/body/DocFragment[2]/body/p[9]/text().3"))
        assertNull(positions.charactersAt("/body/DocFragment[2]/body/section/p/text().3"))
        // Only the text node is off: the paragraph's start.
        assertEquals(7, positions.charactersAt("/body/DocFragment[1]/body/div/p[2]/text()[7].1"))
    }

    @Test
    fun positionsOutsideTheBookAreNull() {
        assertNull(positions.charactersAt("/body/DocFragment[3]/body/p/text().0"))
        assertNull(positions.charactersAt("/body/DocFragment[99999999999]/body/p/text().0"))
        assertNull(positions.charactersAt("42"))
        assertNull(positions.charactersAt(""))
    }

    @Test
    fun writesPositionsKoreaderReads() {
        assertEquals("/body/DocFragment[1]/body/div[1]/p[1]/text()[1].0", positions.xpointerAt(0))
        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/text()[2].1", positions.xpointerAt(12))
        assertEquals("/body/DocFragment[1]/body/div[1]/p[2]/ruby[1]/text()[1].0", positions.xpointerAt(10))
        assertEquals("/body/DocFragment[2]/body/h1[1]/text()[1].0", positions.xpointerAt(14))
        assertEquals("/body/DocFragment[2]/body/p[1]/text()[1].17", positions.xpointerAt(31))
    }

    @Test
    fun everyPositionSurvivesTheRoundTrip() {
        for (n in 0..positions.total) {
            assertEquals("position $n", n, positions.charactersAt(positions.xpointerAt(n)!!))
        }
    }

    @Test
    fun textsComeByDocument_withoutRubyReadings_markingFurigana() {
        val texts = EpubPositions.texts(folder.root.resolve("book.epub"))!!

        assertEquals(2, texts.size)
        // The word under furigana stays whole: 未だ, not 未まだ.
        assertEquals("吾輩は猫である。名前は未だ無い。", texts[0].text.filterNot { it.isWhitespace() || it == '\u00A0' })
        assertEquals("二どこで生れたかとんと見当がつかぬ。", texts[1].text.filterNot { it.isWhitespace() })
        // Each paragraph ends a line, so sentences without 。 still end there.
        assertTrue("吾輩は猫である。\n" in texts[0].text)
        // Only 未 has furigana.
        val first = texts[0]
        assertEquals(listOf("未"), first.text.indices.filter { first.ruby[it] }.map { first.text[it].toString() })
    }

    @Test
    fun notAnEpubGivesNull() {
        assertNull(EpubPositions.read(folder.newFile("empty.epub").apply { writeText("not a zip") }))
        assertNull(EpubPositions.texts(folder.root.resolve("empty.epub")))
    }
}
