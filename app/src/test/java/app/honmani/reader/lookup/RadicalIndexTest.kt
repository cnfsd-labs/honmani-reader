package app.honmani.reader.lookup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadicalIndexTest {
    private val sample = """
        # comment
        r	一	1
        r	日	4
        r	木	4
        r	口	3
        r	⺅	2
        @4
        日日
        木木
        @5
        旧日
        @8
        林木木
        杏木口
        @9
        昔一日
        何⺅一口
    """.trimIndent()
    private val index = RadicalIndex.parse(sample)

    @Test
    fun `parses radicals with their stroke counts`() {
        assertEquals(listOf("一", "日", "木", "口", "⺅"), index.radicals.map { it.glyph })
        assertEquals(listOf(1, 4, 4, 3, 2), index.radicals.map { it.strokes })
    }

    @Test
    fun `nothing selected lists no kanji and keeps every radical possible`() {
        val match = index.search(emptyList())
        assertTrue(match.kanji.isEmpty())
        assertEquals(5, match.possible.size)
    }

    @Test
    fun `kanji with all selected radicals come in file order`() {
        assertEquals(listOf("日", "旧", "昔"), index.search(listOf("日")).kanji)
        assertEquals(listOf("昔"), index.search(listOf("日", "一")).kanji)
        assertEquals(listOf("杏"), index.search(listOf("木", "口")).kanji)
        assertTrue(index.search(listOf("日", "木")).kanji.isEmpty())
    }

    @Test
    fun `possible radicals are those left in the matching kanji`() {
        assertEquals(setOf("日", "一"), index.search(listOf("日")).possible)
        assertEquals(setOf("木", "口"), index.search(listOf("木", "口")).possible)
        assertFalse("一" in index.search(listOf("木")).possible)
        assertEquals(setOf("⺅", "一", "口"), index.search(listOf("⺅")).possible)
    }

    @Test
    fun `an unknown radical matches nothing`() {
        val match = index.search(listOf("?"))
        assertTrue(match.kanji.isEmpty())
        assertTrue(match.possible.isEmpty())
    }

    @Test
    fun `the shipped asset parses and finds the textbook cases`() {
        val real = RadicalIndex.parse(File("src/main/assets/radicals.txt").readText())
        assertEquals(253, real.radicals.size)
        val glyphs = real.radicals.map { it.glyph }
        assertEquals(glyphs.size, glyphs.toSet().size)
        assertTrue("⺅" in glyphs)
        val tree = real.search(listOf("木")).kanji
        assertTrue(tree.size > 400)
        assertEquals("木", tree.first())
        val both = real.search(listOf("木", "日")).kanji
        assertTrue("東" in both && "昔" !in both)
        assertTrue("杳" in both)
        // Narrowing never widens: every kanji of a longer selection also contains the shorter one.
        assertTrue(tree.containsAll(both))
        // 一 only matters together with 日 where such a kanji exists (昔 has both).
        assertTrue("昔" in real.search(listOf("日", "一")).kanji)
        assertTrue(real.search(listOf("木")).possible.contains("日"))
        assertFalse(real.search(listOf("日", "木")).possible.contains("雨"))
    }
}
