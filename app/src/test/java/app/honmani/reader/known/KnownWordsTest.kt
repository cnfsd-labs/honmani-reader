package app.honmani.reader.known

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.DictionaryImporter
import app.honmani.reader.data.dictionary.index
import app.honmani.reader.data.dictionary.writeZip
import app.honmani.reader.data.difficulty.LexiconSources
import app.honmani.reader.data.words.KnownBatchRepository
import app.honmani.reader.data.words.KnownCandidate
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.lookup.DictionaryLookup
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnownWordsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var known: KnownWords

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        val lookup = DictionaryLookup(database.dictionaryDao(), database.termDao(), database.termMetaDao(), database.kanjiDao())
        known = KnownWords(
            resolver = KnownWordResolver(lookup),
            batches = KnownBatchRepository(database),
            frequencyDictionary = {
                LexiconSources.of(database.dictionaryDao().enabledReady()) { database.difficultyDao().hasFrequencies(it.id) }
                    ?.frequency?.let { it.id to it.title }
            },
            topWords = { dictionary, maxRank ->
                database.difficultyDao().topFrequencies(dictionary, maxRank.toDouble()).map { it.expression to it.reading }
            },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Each row is expression, reading, rules, score, gloss. */
    private suspend fun install(title: String, vararg rows: List<Any>) {
        val bank = rows.withIndex().joinToString(",", "[", "]") { (i, row) ->
            val (expression, reading, rules, score, gloss) = row
            """["$expression","$reading","","$rules",$score,["$gloss"],${i + 1},""]"""
        }
        val files = mapOf("index.json" to index(title), "term_bank_1.json" to bank)
        DictionaryImporter(database).import(writeZip(temp.root, files, "$title.zip"))
    }

    private suspend fun jmdict() = install(
        "JMdict",
        listOf("食べる", "たべる", "v1", 10, "to eat"),
        listOf("高い", "たかい", "adj-i", 10, "high"),
        listOf("猫", "ねこ", "", 10, "cat"),
        listOf("の", "の", "prt", 10, "of"),
        listOf("帰る", "かえる", "v5", 10, "to return"),
        listOf("変える", "かえる", "v1", 10, "to change"),
        listOf("犬", "いぬ", "", 10, "dog"),
    )

    private suspend fun jiten(vararg ranks: Triple<String, String, Int>) {
        val rows = ranks.joinToString(",", "[", "]") { (word, reading, rank) ->
            """["$word","freq",{"reading":"$reading","frequency":{"value":$rank,"displayValue":"$rank"}}]"""
        }
        val files = mapOf("index.json" to index("Jiten", extra = ",\"frequencyMode\":\"rank-based\""), "term_meta_bank_1.json" to rows)
        DictionaryImporter(database).import(writeZip(temp.root, files, "jiten.zip"))
    }

    private suspend fun statusOf(expression: String) =
        database.backupDao().words().first { it.expression == expression }.status

    @Test
    fun aWordListIsMatchedByFormReadingAndInflection_namesAndStrangersAreCounted() = runTest {
        jmdict()
        install("JMnedict", listOf("山田", "やまだ", "surname", 0, "Yamada (surname)"))
        val list = WordListParser.parse("# my words\n食べた\nねこ\n帰る\tかえる\n山田\nzzz\n")

        val plan = known.planWordList("words.txt", list)

        assertEquals(listOf("食べる", "猫", "帰る"), plan.resolved.words.map { it.expression })
        assertEquals("to eat", plan.resolved.words[0].gloss)
        assertEquals(listOf("zzz"), plan.resolved.unmatched)
        assertEquals(1, plan.resolved.names)
        assertEquals(3, plan.change.created)
    }

    @Test
    fun aGivenReadingPicksThatHomophone_otherwiseTheCommonOneAloneWhenMeaningsDiffer() = runTest {
        jmdict()
        val asked = known.planWordList("l", WordListParser.parse("変える\tかえる\n")).resolved.words
        assertEquals(listOf("変える" to "かえる"), asked.map { it.expression to it.reading })

        // Reading-only text with two different words behind it: one of them, never both.
        val byReading = known.planWordList("l", WordListParser.parse("かえる\n")).resolved.words
        assertEquals(1, byReading.size)
    }

    @Test
    fun confirmedWordListIsOneBatchThatUndoRemoves() = runTest {
        jmdict()
        val plan = known.planWordList("words.txt", WordListParser.parse("食べる\n猫\n"))

        val (id, change) = known.confirm(plan)

        assertEquals(2, change.created)
        assertEquals(WordStatus.LEARNED, statusOf("食べる"))
        assertEquals(KnownSource.WORD_LIST, database.knownBatchDao().batch(id!!)!!.source)
        assertEquals(2, known.batches.undo(id).removed)
        assertTrue(database.backupDao().words().isEmpty())
    }

    @Test
    fun topNTakesTheMostCommonWords_particlesIn_namesOut() = runTest {
        jmdict()
        install("JMnedict", listOf("山田", "やまだ", "surname", 0, "Yamada (surname)"))
        jiten(Triple("の", "の", 1), Triple("山田", "やまだ", 2), Triple("猫", "ねこ", 3), Triple("zzz", "zzz", 4), Triple("犬", "いぬ", 5), Triple("高い", "たかい", 900))

        val result = known.planTopN(4) as TopNResult.Ready

        assertEquals("Jiten", result.dictionary)
        assertEquals(listOf("の", "猫"), result.plan.resolved.words.map { it.expression })
        assertEquals(1, result.plan.resolved.names)
        assertEquals(listOf("zzz"), result.plan.resolved.unmatched)
        assertEquals(KnownSource.TOP_N, result.plan.source)
        assertEquals("4", result.plan.detail)
    }

    @Test
    fun topNWithoutAFrequencyDictionaryAsksForOne() = runTest {
        jmdict()

        assertEquals(TopNResult.NoFrequencyDictionary, known.planTopN(500))
    }

    @Test
    fun anAnkiDeckUpgradesWhatWasLookedUp_addsTheRestAsNew_andSkipsWhatIsKnown() = runTest {
        jmdict()
        WordRepository(database).record(LookedUpWord("猫", "ねこ", "cat", "猫", "猫だ。", 0))
        known.confirm(known.planWordList("l", WordListParser.parse("犬\n")))

        val plan = known.planDeck("Core", listOf("猫", "犬", "食べる", "qqq"), skippedNotes = 2)

        assertEquals(1, plan.change.created)
        assertEquals(1, plan.change.upgraded)
        assertEquals(1, plan.change.unchanged)
        assertEquals(listOf("qqq"), plan.resolved.unmatched)
        assertEquals(2, plan.skipped)
        val (id, _) = known.confirm(plan)
        assertEquals(KnownSource.ANKI_DECK, database.knownBatchDao().batch(id!!)!!.source)
        assertEquals(WordStatus.LEARNED, statusOf("猫"))
    }

    @Test
    fun quizPlanTakesTheCutoffWords_dropsDontKnowAnswers_addsKnownBeyondTheCutoff() = runTest {
        jmdict()
        jiten(Triple("の", "の", 1), Triple("猫", "ねこ", 2), Triple("犬", "いぬ", 3), Triple("食べる", "たべる", 4), Triple("高い", "たかい", 900))
        val cat = QuizQuestion(2, KnownCandidate("猫", "ねこ", "cat"))
        val tall = QuizQuestion(900, KnownCandidate("高い", "たかい", "high"))

        val result = known.planQuiz(4, listOf(cat to false, tall to true)) as TopNResult.Ready

        assertEquals(listOf("の", "犬", "食べる", "高い"), result.plan.resolved.words.map { it.expression })
        assertEquals(KnownSource.QUIZ, result.plan.source)
        assertEquals("4", result.plan.detail)
        val (id, change) = known.confirm(result.plan)
        assertEquals(4, change.created)
        assertEquals(KnownSource.QUIZ, database.knownBatchDao().batch(id!!)!!.source)
        assertEquals(4, known.batches.undo(id).removed)
    }

    @Test
    fun quizNeedsAFrequencyDictionaryWithEnoughWords() = runTest {
        jmdict()
        assertNull(known.prepareQuiz())
        jiten(Triple("の", "の", 1), Triple("猫", "ねこ", 2))
        assertNull(known.prepareQuiz())
    }
}
