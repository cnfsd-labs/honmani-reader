package app.honmani.reader.data.words

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnownBatchRepositoryTest {
    private lateinit var database: HonmaniDatabase
    private lateinit var words: WordRepository
    private lateinit var known: KnownBatchRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HonmaniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        words = WordRepository(database, now = { now })
        known = KnownBatchRepository(database, now = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun candidate(expression: String) = KnownCandidate(expression, "よみ", "gloss of $expression")

    private suspend fun lookUp(expression: String) = words.record(
        LookedUpWord(expression, "よみ", "gloss", expression, "${expression}だ。", 0),
    )

    private suspend fun find(expression: String) = words.find(expression, "よみ")

    @Test
    fun aBatchAddsNewWordsAsKnownWithoutLookups_andMakesSavedWordsKnown() = runTest {
        lookUp("猫")
        lookUp("犬")
        words.markLearned(find("犬")!!.id)

        val (id, change) = known.apply(KnownSource.WORD_LIST, "list.txt", listOf(candidate("猫"), candidate("犬"), candidate("鳥"), candidate("鳥")))

        assertNotNull(id)
        assertEquals(KnownChange(created = 1, upgraded = 1, unchanged = 1), change)
        assertEquals(WordStatus.LEARNED, find("猫")!!.status)
        val bird = find("鳥")!!
        assertEquals(WordStatus.LEARNED, bird.status)
        assertEquals(0, bird.lookupCount)
        assertEquals(0L, bird.lastLookedUpAt)
        assertEquals("gloss of 鳥", bird.gloss)
        val batch = known.batches.first().single()
        assertEquals(KnownSource.WORD_LIST, batch.source)
        assertEquals("list.txt", batch.detail)
        assertEquals(2, batch.count)
    }

    @Test
    fun aBatchThatChangesNothingIsNotSaved() = runTest {
        known.apply(KnownSource.TOP_N, "500", listOf(candidate("猫")))

        val (id, change) = known.apply(KnownSource.TOP_N, "1000", listOf(candidate("猫")))

        assertNull(id)
        assertEquals(KnownChange(unchanged = 1), change)
        assertEquals(1, known.batches.first().size)
    }

    @Test
    fun previewCountsWithoutChangingAnything() = runTest {
        lookUp("猫")

        val change = known.preview(listOf(candidate("猫"), candidate("鳥")))

        assertEquals(KnownChange(created = 1, upgraded = 1), change)
        assertEquals(WordStatus.LEARNING, find("猫")!!.status)
        assertNull(find("鳥"))
    }

    @Test
    fun undoRemovesAddedWordsAndRestoresSavedOnes() = runTest {
        lookUp("猫")
        val (id, _) = known.apply(KnownSource.ANKI_DECK, "Core", listOf(candidate("猫"), candidate("鳥")))

        val undo = known.undo(id!!)

        assertEquals(KnownUndo(removed = 1, restored = 1, leftAlone = 0), undo)
        assertEquals(WordStatus.LEARNING, find("猫")!!.status)
        assertNull(find("鳥"))
        assertEquals(emptyList<KnownBatchEntity>(), known.batches.first())
    }

    @Test
    fun undoNeverTouchesWordsKnownBeforeTheBatch() = runTest {
        lookUp("猫")
        words.markLearned(find("猫")!!.id)
        val (id, _) = known.apply(KnownSource.TOP_N, "500", listOf(candidate("猫"), candidate("鳥")))

        known.undo(id!!)

        assertEquals(WordStatus.LEARNED, find("猫")!!.status)
        assertNull(find("鳥"))
    }

    @Test
    fun undoLeavesWordsTheReaderChangedAfterTheBatch() = runTest {
        lookUp("猫")
        lookUp("犬")
        val (id, _) = known.apply(KnownSource.TOP_N, "500", listOf(candidate("猫"), candidate("犬"), candidate("鳥"), candidate("魚"), candidate("馬")))
        now += 2 * 3_600_000 // past the window in which a repeated lookup counts as one
        lookUp("猫") // looked up again: learning again
        words.markLearning(find("鳥")!!.id) // unmarked by hand
        database.wordDao().update(find("魚")!!.copy(starred = true))
        database.wordDao().update(find("馬")!!.copy(ignored = true))

        val undo = known.undo(id!!)

        assertEquals(KnownUndo(removed = 0, restored = 1, leftAlone = 4), undo)
        assertEquals(WordStatus.LEARNING, find("猫")!!.status)
        assertEquals(2, find("猫")!!.lookupCount)
        assertEquals(WordStatus.LEARNING, find("鳥")!!.status)
        assertEquals(WordStatus.LEARNED, find("魚")!!.status)
        assertEquals(WordStatus.LEARNED, find("馬")!!.status)
        assertEquals(WordStatus.LEARNING, find("犬")!!.status)
    }

    @Test
    fun undoDoesNotCountWordsTheReaderDeleted_andTheBatchWordsGoWithThem() = runTest {
        val (id, _) = known.apply(KnownSource.TOP_N, "500", listOf(candidate("猫"), candidate("鳥")))
        words.delete(find("猫")!!.id)

        val undo = known.undo(id!!)

        assertEquals(KnownUndo(removed = 1), undo)
    }

    @Test
    fun aLookedUpKnownWordGetsItsFirstLookupTime() = runTest {
        known.apply(KnownSource.TOP_N, "500", listOf(candidate("猫")))
        now += 5000

        lookUp("猫")

        val word = find("猫")!!
        assertEquals(now, word.firstLookedUpAt)
        assertEquals(now, word.lastLookedUpAt)
        assertEquals(WordStatus.LEARNING, word.status)
    }
}
