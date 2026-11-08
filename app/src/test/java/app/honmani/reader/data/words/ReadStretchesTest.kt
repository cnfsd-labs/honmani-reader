package app.honmani.reader.data.words

import app.honmani.reader.data.difficulty.BookWords
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadStretchesTest {
    private fun stretches(vararg parts: Pair<Float, Float>): IntArray =
        parts.fold(ReadStretches.NONE) { all, (from, to) -> ReadStretches.add(all, from, to) }

    @Test
    fun stretchesMergeWhenTheyOverlapOrTouch() {
        assertArrayEquals(intArrayOf(1_000, 2_000), stretches(0.1f to 0.2f))
        // Apart, in order whatever order they were read in.
        assertArrayEquals(intArrayOf(1_000, 2_000, 5_000, 6_000), stretches(0.5f to 0.6f, 0.1f to 0.2f))
        // Overlapping, touching and inside.
        assertArrayEquals(intArrayOf(1_000, 3_000), stretches(0.1f to 0.2f, 0.15f to 0.3f))
        assertArrayEquals(intArrayOf(1_000, 3_000), stretches(0.1f to 0.2f, 0.2001f to 0.3f))
        assertArrayEquals(intArrayOf(1_000, 2_000), stretches(0.1f to 0.2f, 0.12f to 0.13f))
        // One stretch joining two.
        assertArrayEquals(intArrayOf(1_000, 6_000, 8_000, 9_000), stretches(0.1f to 0.2f, 0.5f to 0.6f, 0.8f to 0.9f, 0.2f to 0.5f))
        assertEquals(0.6f, ReadStretches.share(stretches(0.1f to 0.2f, 0.5f to 0.6f, 0.8f to 0.9f, 0.2f to 0.5f)), 0.001f)
    }

    @Test
    fun wordsCountOnlyInThePartsRead() {
        // 猫 at every tenth of the book, 犬 only at the end.
        val words = BookWords(
            keys = listOf("猫", "犬"),
            ranks = intArrayOf(900, 1_200),
            positions = listOf(ShortArray(10) { (it * 1_000).toShort() }, shortArrayOf(9_500)),
        )
        val read = HashMap<String, Int>()
        words.forEachRead(stretches(0f to 0.25f, 0.5f to 0.5f)) { key, _, reads -> read[key] = reads }
        assertEquals(mapOf("猫" to 4), read)
        read.clear()
        words.forEachRead(ReadStretches.NONE) { key, _, reads -> read[key] = reads }
        assertEquals(emptyMap<String, Int>(), read)
    }

    @Test
    fun theStoreKeepsStretchesBetweenRuns() {
        val file = File(Files.createTempDirectory("stretches").toFile(), "read-stretches.json")
        ReadStretchStore(file).apply {
            add("a", 0.1f, 0.2f)
            add("a", 0.2f, 0.3f)
            add("b", 0.5f, 0.6f)
        }
        val again = runBlocking { ReadStretchStore(file).stretches.first() }
        assertArrayEquals(intArrayOf(1_000, 3_000), again.getValue("a"))
        assertArrayEquals(intArrayOf(5_000, 6_000), again.getValue("b"))
    }

    @Test
    fun aRestoreAddsItsStretchesToWhatWasReadHere() {
        val file = File(Files.createTempDirectory("stretches").toFile(), "read-stretches.json")
        val store = ReadStretchStore(file).apply { add("a", 0.1f, 0.2f) }

        store.merge(mapOf("a" to intArrayOf(1_500, 4_000, 9_000, 20_000), "b" to intArrayOf(0, 100)))

        assertArrayEquals(intArrayOf(1_000, 4_000, 9_000, 10_000), store.all().getValue("a"))
        assertArrayEquals(intArrayOf(0, 100), store.all().getValue("b"))
        assertArrayEquals(intArrayOf(1_000, 4_000, 9_000, 10_000), ReadStretchStore(file).all().getValue("a"))
    }

    @Test
    fun aRestoredKeyWithWhitespaceCannotPlantStretchesUnderAnotherBook() {
        val file = File(Files.createTempDirectory("stretches").toFile(), "read-stretches.json")
        val store = ReadStretchStore(file)

        store.merge(mapOf("x 1 2\nreal 0 9999" to intArrayOf(0, 100), "ok" to intArrayOf(0, 100)))

        assertEquals(setOf("ok"), ReadStretchStore(file).all().keys)
    }
}
