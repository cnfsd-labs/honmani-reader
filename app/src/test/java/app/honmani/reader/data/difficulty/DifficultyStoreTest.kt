package app.honmani.reader.data.difficulty

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DifficultyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun grade() = StoredDifficulty(
        BookDifficulty(Difficulty.VERSION, score = 40, r95 = 100, kanjiShare = 0.3f, sentenceLength = 20f, words = 10, distinctWords = 5, unknownShare = 0.1f),
        signature = "s",
    )

    private val words = BookWords(listOf("猫"), intArrayOf(5), listOf(shortArrayOf(1, 2)))

    @Test
    fun forgettingABookDropsItsGradeAndWordsOnly() {
        val store = DifficultyStore(folder.root)
        for (hash in listOf("a", "b")) {
            store.saveWords(hash, words)
            store.save(hash, grade())
        }

        store.forget("a")

        assertEquals(setOf("b"), store.grades.value.keys)
        assertFalse(File(folder.root, "a.json").exists())
        assertFalse(File(folder.root, "a.words").exists())
        assertNull(store.words("a"))
        assertNotNull(store.words("b"))
        val reloaded = DifficultyStore(folder.root).also { it.load() }
        assertEquals(setOf("b"), reloaded.grades.value.keys)
    }
}
