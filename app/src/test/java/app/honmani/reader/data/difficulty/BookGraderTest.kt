package app.honmani.reader.data.difficulty

import app.honmani.reader.data.dictionary.DictionaryEntity
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.sync.RubyText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookGraderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun book(hash: String) =
        BookEntity(contentHash = hash, title = hash, format = BookFormat.EPUB, fileName = "$hash.epub", addedAt = 0)

    private val lexicon = Lexicon.Builder().add("猫", 0, 900).add("が", 0, 3).add("好き", 0, 200).add("です", 0, 5).build()

    private val jitendex = DictionaryEntity(id = 1, title = "Jitendex", revision = "1", formatVersion = 3, importedAt = 0, termCount = 10)
    private val jiten = DictionaryEntity(id = 2, title = "Jiten", revision = "1", formatVersion = 3, importedAt = 0, metaCount = 10)

    private var library = listOf(book("a"), book("short"))
    private var sources: LexiconSources? = LexiconSources(listOf(jitendex), jiten)
    private var lexiconsBuilt = 0
    private val texts = mapOf("a" to "猫が好きです。".repeat(200), "short" to "猫です。", "b" to "猫が好きです。".repeat(300))

    private fun grader(store: DifficultyStore) = BookGrader(
        store = store,
        books = { library },
        sources = { sources },
        lexicon = {
            lexiconsBuilt++
            lexicon
        },
        texts = { book ->
            if (book.contentHash in broken) error("unreadable")
            texts[book.contentHash]?.let { sequenceOf(RubyText(it)) }
        },
    )

    private val broken = mutableSetOf<String>()

    @Test
    fun gradesEachBookOnce_andKeepsTheGradesInFiles() = runBlocking {
        val store = DifficultyStore(folder.root)

        val grader = grader(store)
        assertEquals(2, grader.gradeAll())
        assertEquals(GradingState.IDLE, grader.state.value)
        assertEquals(1, lexiconsBuilt)
        // Too short: kept as 0, so it isn't tried on every run.
        assertEquals(0, store.grades.value.getValue("short").difficulty.score)
        // The words are kept for the vocabulary estimate.
        assertEquals(listOf("猫", "が", "好き", "です"), store.words("a")!!.keys)
        assertNull(store.words("short"))

        val reopened = DifficultyStore(folder.root).apply { load() }
        assertEquals(store.grades.value, reopened.grades.value)
        assertEquals(0, grader(reopened).gradeAll())
        // Nothing to grade: no lexicon built.
        assertEquals(1, lexiconsBuilt)
    }

    @Test
    fun aBookThatThrowsIsKeptAsZeroAndTheOthersAreStillGraded() = runBlocking {
        val store = DifficultyStore(folder.root)
        library = listOf(book("bad"), book("a"))
        broken += "bad"

        assertEquals(2, grader(store).gradeAll())

        assertEquals(0, store.grades.value.getValue("bad").difficulty.score)
        assertEquals(true, store.grades.value.getValue("a").difficulty.score > 0)
        assertEquals(0, grader(store).gradeAll())
    }

    @Test
    fun newBooksAndChangedDictionariesAreGraded_removedBooksForgotten() = runBlocking {
        val store = DifficultyStore(folder.root)
        grader(store).gradeAll()

        library = listOf(book("a"), book("b"))
        assertEquals(1, grader(store).gradeAll())
        assertNull(store.grades.value["short"])

        sources = LexiconSources(listOf(jitendex), jiten.copy(revision = "2"))
        assertEquals(2, grader(store).gradeAll())
    }

    @Test
    fun withoutDictionariesNothingIsGraded() = runBlocking {
        sources = null
        val store = DifficultyStore(folder.root)
        val grader = grader(store)

        assertEquals(0, grader.gradeAll())
        assertEquals(emptyMap<String, StoredDifficulty>(), store.grades.value)
        // The book sheet says what is missing.
        assertEquals(GradingState.NO_DICTIONARIES, grader.state.value)
    }
}
