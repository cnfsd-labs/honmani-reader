package app.honmani.reader.data.words

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WordRepositoryTest {
    @get:org.junit.Rule
    val fixedUpdateClock = app.honmani.reader.data.merge.FixedUpdateClock()

    private lateinit var database: HonmaniDatabase
    private lateinit var repository: WordRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WordRepository(database, now = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun lookup(
        expression: String,
        sentence: String = "${expression}だ。",
        surface: String = expression,
        surfaceStart: Int = 0,
        bookTitle: String? = null,
    ) = LookedUpWord(
        expression = expression,
        reading = "よみ",
        gloss = "gloss of $expression",
        surface = surface,
        sentence = sentence,
        surfaceStart = surfaceStart,
        bookId = bookTitle?.let { 1L },
        bookTitle = bookTitle,
    )

    private suspend fun learning() = repository.learning.first()

    @Test
    fun aPreparedWordKeepsTheBooksSentenceWithoutCountingALookup() = runTest {
        val id = repository.prepare(lookup("料理", sentence = "料理店に入った。", surface = "料理", bookTitle = "注文の多い料理店"))
        val word = repository.find(id)!!
        assertEquals(WordStatus.LEARNING, word.status)
        assertEquals(0, word.lookupCount)
        val saved = repository.latestLookups(id).single()
        assertEquals(LookupOrigin.PREPARE, saved.origin)
        assertEquals("料理店に入った。", saved.sentence)
        assertEquals(0 until 2, saved.surfaceStart until saved.surfaceEnd)
        assertEquals(0, repository.lookupCount(null).first())
        // Prepared again: the same word, one more sentence, still no lookup.
        assertEquals(id, repository.prepare(lookup("料理", sentence = "料理を出す。", bookTitle = "注文の多い料理店")))
        assertEquals(0, repository.find(id)!!.lookupCount)
    }

    @Test
    fun aNoteSurvivesLookingTheWordUpAgainAndTakingThatBack() = runTest {
        repository.record(lookup("猫", sentence = "猫がいる。"))
        val id = learning().single().id
        repository.setNote(id, "  hint\nひんと ")
        assertEquals("hint\nひんと", repository.find(id)!!.note)

        now += 60_000
        val undo = repository.record(lookup("猫", sentence = "猫が鳴く。", bookTitle = "猫"))!!
        assertEquals("hint\nひんと", repository.find(id)!!.note)
        repository.undo(undo)
        assertEquals("hint\nひんと", repository.find(id)!!.note)

        repository.markLearned(id)
        repository.setStarred(id, true)
        repository.setIgnored(id, true)
        repository.setGloss(id, "cat")
        assertEquals("hint\nひんと", repository.find(id)!!.note)

        repository.setNote(id, "")
        assertEquals("", repository.find(id)!!.note)
    }

    @Test
    fun aDeletedWordComesBackWithItsNote() = runTest {
        repository.record(lookup("猫"))
        val id = learning().single().id
        repository.setNote(id, "hint")
        val deleted = repository.delete(id)!!
        repository.restore(deleted)
        assertEquals("hint", repository.find("猫", "よみ")!!.note)
    }

    @Test
    fun aFirstLookupAddsTheWordWithItsSentence() = runTest {
        repository.record(lookup("食べる", sentence = "猫が食べました。", surface = "食べました", surfaceStart = 2, bookTitle = "猫"))

        val word = learning().single()
        assertEquals("食べる", word.expression)
        assertEquals("gloss of 食べる", word.gloss)
        assertEquals(1, word.lookupCount)
        assertEquals("猫が食べました。", word.lastSentence)
        assertEquals(2, word.lastSurfaceStart)
        assertEquals(7, word.lastSurfaceEnd)
        assertEquals("猫", word.lastBookTitle)
        val saved = repository.lookups(word.id).first().single()
        assertEquals("食べました", saved.surface)
        assertEquals(1L, saved.bookId)
    }

    @Test
    fun lookingUpAgainInAnotherSentenceCounts() = runTest {
        repository.record(lookup("猫", sentence = "猫がいる。"))
        now += 60_000
        repository.record(lookup("猫", sentence = "猫が鳴く。"))

        val word = learning().single()
        assertEquals(2, word.lookupCount)
        assertEquals("猫が鳴く。", word.lastSentence)
        assertEquals(listOf("猫が鳴く。", "猫がいる。"), repository.lookups(word.id).first().map { it.sentence })
    }

    @Test
    fun aSecondTapInTheSameSentenceSoonAfterDoesNotCount() = runTest {
        repository.record(lookup("猫"))
        now += 60_000
        repository.record(lookup("猫"))

        assertEquals(1, learning().single().lookupCount)

        // Coming back to the sentence later (re-reading) counts again.
        now += WordRepository.REPEAT_WINDOW_MS
        repository.record(lookup("猫"))
        assertEquals(2, learning().single().lookupCount)
    }

    @Test
    fun wordsAreRankedByRecentLookups() = runTest {
        // Looked up three times, five weeks ago.
        repeat(3) { i -> repository.record(lookup("昔", sentence = "昔$i。")) }
        now += 35L * 24 * 60 * 60 * 1000
        repository.record(lookup("今"))
        now += 1000
        repository.record(lookup("先"))
        repository.record(lookup("先", sentence = "先の文。"))

        assertEquals(listOf("先", "今", "昔"), learning().map { it.expression })
    }

    @Test
    fun onlyTheLatestSentencesAreKept() = runTest {
        repeat(WordRepository.KEPT_LOOKUPS + 5) { i ->
            now += 1000
            repository.record(lookup("猫", sentence = "文$i。"))
        }

        val word = learning().single()
        val sentences = repository.lookups(word.id).first().map { it.sentence }
        assertEquals(WordRepository.KEPT_LOOKUPS + 5, word.lookupCount)
        assertEquals(WordRepository.KEPT_LOOKUPS, sentences.size)
        assertEquals("文${WordRepository.KEPT_LOOKUPS + 4}。", sentences.first())
    }

    @Test
    fun aLongSentenceIsNotCutInsideASurrogatePair() = runTest {
        repository.record(lookup("猫", sentence = "あ".repeat(399) + "𠮟" + "い"))

        val sentence = repository.lookups(learning().single().id).first().single().sentence
        assertEquals("あ".repeat(399), sentence)
    }

    @Test
    fun aSurfaceOutsideTheSentenceIsClamped() = runTest {
        repository.record(lookup("猫", sentence = "猫。", surface = "猫猫猫", surfaceStart = 1))

        val word = learning().single()
        assertEquals(1, word.lastSurfaceStart)
        assertEquals(2, word.lastSurfaceEnd)
    }

    @Test
    fun starLearnedAndDelete() = runTest {
        repository.record(lookup("猫"))
        repository.record(lookup("犬"))
        val (cat, dog) = learning().sortedBy { it.expression != "猫" }

        repository.setStarred(cat.id, true)
        repository.markLearned(dog.id)

        assertEquals(listOf("猫"), repository.starred.first().map { it.expression })
        assertEquals(listOf("犬"), repository.learned.first().map { it.expression })
        assertEquals(listOf("猫"), learning().map { it.expression })

        repository.markLearning(dog.id)
        repository.delete(cat.id)
        assertEquals(listOf("犬"), learning().map { it.expression })
        assertTrue(repository.lookups(cat.id).first().isEmpty())
    }

    @Test
    fun allListsEveryWordOnceLearningFirst() = runTest {
        listOf("猫", "犬", "鳥").forEach { repository.record(lookup(it)) }
        val bird = learning().first { it.expression == "鳥" }
        repository.markLearned(bird.id)

        // One query: two flows could be caught mid-change with a word in both, which crashed the list.
        val all = repository.all.first()
        assertEquals(3, all.map { it.id }.distinct().size)
        assertEquals(listOf("鳥"), all.filter { it.status == WordStatus.LEARNED }.map { it.expression })
        assertEquals(WordStatus.LEARNED, all.last().status)
    }

    @Test
    fun aLearnedWordLookedUpAgainIsBeingLearned() = runTest {
        repository.record(lookup("猫", sentence = "一。"))
        repository.markLearned(learning().single().id)
        now += 60_000
        repository.record(lookup("猫", sentence = "二。"))

        assertEquals(listOf("猫"), learning().map { it.expression })
    }

    @Test
    fun countsFollowTheTabs() = runTest {
        repository.record(lookup("猫"))
        repository.record(lookup("犬"))
        repository.record(lookup("鳥"))
        val (cat, dog) = learning().filter { it.expression != "鳥" }.sortedBy { it.expression != "猫" }
        repository.setStarred(cat.id, true)
        repository.markLearned(dog.id)

        assertEquals(WordCounts(learning = 2, starred = 1, learned = 1), repository.counts.first())
    }

    @Test
    fun aDeletedWordCanBeRestoredWithItsSentences() = runTest {
        repository.record(lookup("猫", sentence = "一。"))
        now += 1000
        repository.record(lookup("猫", sentence = "二。"))
        val cat = learning().single()

        val deleted = repository.delete(cat.id)!!
        assertTrue(learning().isEmpty())
        repository.restore(deleted)

        assertEquals(cat, learning().single())
        assertEquals(listOf("二。", "一。"), repository.lookups(cat.id).first().map { it.sentence })
    }

    @Test
    fun restoringAfterTheWordWasLookedUpAgainKeepsTheNewOne() = runTest {
        repository.record(lookup("猫"))
        val deleted = repository.delete(learning().single().id)!!
        repository.record(lookup("猫", sentence = "新しい文。"))

        repository.restore(deleted)

        assertEquals("新しい文。", learning().single().lastSentence)
    }

    @Test
    fun everyLookupCountsForStatistics_evenPrunedAndDeletedOnes() = runTest {
        val start = now
        repeat(WordRepository.KEPT_LOOKUPS + 5) { i ->
            now += 1_000
            repository.record(lookup("猫", sentence = "文$i。", bookTitle = "猫"))
        }
        // A second tap in the same sentence is not a new lookup.
        repository.record(lookup("猫", sentence = "文${WordRepository.KEPT_LOOKUPS + 4}。", bookTitle = "猫"))
        now += 1_000
        repository.record(lookup("犬"))
        repository.delete(learning().first { it.expression == "猫" }.id)

        assertEquals(WordRepository.KEPT_LOOKUPS + 6, repository.lookupCount(null).first())
        assertEquals(WordRepository.KEPT_LOOKUPS + 5, repository.bookLookupCount(1L).first())
        assertEquals(1, repository.lookupCount(now).first())
        assertEquals(6, repository.lookupCount(start + 11_000).first())
    }

    @Test
    fun theSessionHoldsTheWordsOfTheLatestRunOfLookups() = runTest {
        repository.record(lookup("古い"))
        now += ReadingSession.GAP_MS
        repository.record(lookup("犬"))
        now += 5 * 60_000L
        repository.record(lookup("猫"))
        now += 60_000L
        // Looked up again: still one word, and the earlier session's word is not in this one.
        repository.record(lookup("犬", sentence = "別の文。"))

        val session = repository.session.first()

        assertEquals(listOf("犬", "猫"), session.words.map { it.expression })
        assertEquals(1_800_000_000_000L + ReadingSession.GAP_MS, session.start)
    }

    @Test
    fun theSessionIsEmptyWithoutLookups() = runTest {
        val session = repository.session.first()

        assertEquals(null, session.start)
        assertTrue(session.words.isEmpty())
    }

    @Test
    fun ignoredWordsLeaveTheListsAndCounts_butStayFindable() = runTest {
        repository.record(lookup("猫"))
        repository.record(lookup("犬"))
        val cat = repository.find("猫", "よみ")!!
        assertEquals(2, learning().size)

        repository.setIgnored(cat.id, true)

        assertEquals(listOf("犬"), learning().map { it.expression })
        assertEquals(listOf("猫"), repository.ignored.first().map { it.expression })
        assertEquals(listOf("猫", "犬").sorted(), repository.every.first().map { it.expression }.sorted())
        assertEquals(setOf(WordRepository.key("猫", "よみ")), repository.ignoredAmong(listOf("猫" to "よみ", "犬" to "よみ")))
        assertEquals(true, repository.find("猫", "よみ")!!.ignored)

        // Only a saved word can be ignored by its text; unsaving it again brings it back.
        repository.setIgnored("鼬", "よみ", true)
        assertEquals(null, repository.find("鼬", "よみ"))
        repository.setIgnored("猫", "よみ", false)
        assertEquals(2, learning().size)
    }

    @Test
    fun aFuriganaOverrideSurvivesLookingUpAgainAndDeletingAndRestoring_andKeepsTheReading() = runTest {
        repository.record(lookup("飛び散る", sentence = "飛び散る。"))
        val id = learning().single().id
        repository.setFurigana(id, " 飛[と]び 散[ち]る ")
        assertEquals("飛[と]び 散[ち]る", repository.find(id)!!.furigana)
        assertEquals("よみ", repository.find(id)!!.reading)

        now += 60_000
        val undo = repository.record(lookup("飛び散る", sentence = "また飛び散る。", bookTitle = "本"))!!
        assertEquals(1, learning().size)
        assertEquals("飛[と]び 散[ち]る", repository.find(id)!!.furigana)
        repository.undo(undo)
        assertEquals("飛[と]び 散[ち]る", repository.find(id)!!.furigana)

        repository.markLearned(id)
        repository.setStarred(id, true)
        repository.setGloss(id, "scatter")
        repository.setNote(id, "n")
        assertEquals("飛[と]び 散[ち]る", repository.find(id)!!.furigana)

        val deleted = repository.delete(id)!!
        repository.restore(deleted)
        assertEquals("飛[と]び 散[ち]る", repository.find("飛び散る", "よみ")!!.furigana)

        // The by-word form works too and a reset clears it.
        repository.setFurigana("飛び散る", "よみ", "")
        assertEquals("", repository.find("飛び散る", "よみ")!!.furigana)
    }

    @Test
    fun aFuriganaThatDoesNotFitTheWordIsNotStored() = runTest {
        repository.record(lookup("猫"))
        val id = learning().single().id
        repository.setFurigana(id, "犬[いぬ]")
        assertEquals("", repository.find(id)!!.furigana)
        repository.setFurigana(id, "猫[")
        assertEquals("", repository.find(id)!!.furigana)
        repository.setFurigana(id, "猫[ねこ]")
        assertEquals("猫[ねこ]", repository.find(id)!!.furigana)
        assertEquals(mapOf(WordRepository.key("猫", "よみ") to "猫[ねこ]"), repository.furiganaAmong(listOf("猫" to "よみ", "犬" to "よみ")))
    }
}
