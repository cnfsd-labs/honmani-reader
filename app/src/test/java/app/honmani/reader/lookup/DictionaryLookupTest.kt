package app.honmani.reader.lookup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.DictionaryFileFormat
import app.honmani.reader.data.dictionary.DictionaryImporter
import app.honmani.reader.data.dictionary.LOOKUP_SQL_PLAN_CHECK
import app.honmani.reader.data.dictionary.index
import app.honmani.reader.data.dictionary.writeZip
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictionaryLookupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: HonmaniDatabase
    private lateinit var lookup: DictionaryLookup

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
        ).allowMainThreadQueries().build()
        lookup = DictionaryLookup(
            database.dictionaryDao(),
            database.termDao(),
            database.termMetaDao(),
            database.kanjiDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Imports a format 3 dictionary; each row is expression, reading, rules, score, gloss. */
    private suspend fun install(title: String, vararg rows: List<Any>): Long {
        val bank = rows.withIndex().joinToString(",", "[", "]") { (i, row) ->
            val (expression, reading, rules, score, gloss) = row
            """["$expression","$reading","","$rules",$score,["$gloss"],${i + 1},""]"""
        }
        val files = mapOf("index.json" to index(title), "term_bank_1.json" to bank)
        return DictionaryImporter(database).import(writeZip(temp.root, files, "$title.zip")).id
    }

    private suspend fun jmdict() = install(
        "JMdict",
        listOf("食べる", "たべる", "v1", 10, "to eat"),
        listOf("食べ物", "たべもの", "", 5, "food"),
        listOf("書く", "かく", "v5", 10, "to write"),
        listOf("高い", "たかい", "adj-i", 10, "high"),
        listOf("猫", "ねこ", "", 10, "cat"),
        listOf("ねこ", "ねこ", "", 0, "cat (kana)"),
        listOf("帰る", "かえる", "v5", 10, "to return"),
        listOf("変える", "かえる", "v1", 10, "to change"),
        listOf("来る", "くる", "vk", 10, "to come"),
        listOf("勉強", "べんきょう", "vs", 10, "study"),
    )

    private fun LookupResult.headwords() = entries.map { it.term.expression }

    @Test
    fun frequencyRanksTheCommonWordFirst_andPitchAndFrequencyReachTheGroups() = runTest {
        jmdict()
        // かえる by reading: 帰る and 変える tie on everything a dictionary of terms says.
        val files = mapOf(
            "index.json" to index("Jiten", extra = ",\"frequencyMode\":\"rank-based\""),
            "term_meta_bank_1.json" to """[
                ["帰る","freq",{"reading":"かえる","frequency":{"value":300,"displayValue":"300"}}],
                ["変える","freq",{"reading":"かえる","frequency":{"value":200,"displayValue":"200"}}],
                ["変える","freq",{"reading":"かわる","frequency":{"value":1,"displayValue":"1"}}]
            ]""",
        )
        DictionaryImporter(database).import(writeZip(temp.root, files, "jiten.zip"))
        val pitch = temp.newFile("accents.txt").apply { writeText("帰る\tかえる\t1\n変える\tかえる\t0\n") }
        DictionaryImporter(database).import(pitch, DictionaryFileFormat.KANJIUM_ACCENTS)

        val result = lookup.lookup("かえる")

        assertEquals(listOf("変える", "帰る"), result.headwords().take(2))
        val groups = LookupGroups.from(result)
        assertEquals(listOf(Frequency("Jiten", "200", 200.0)), groups[0].frequencies)
        assertEquals(listOf(0), groups[0].pitches)
        assertEquals(listOf(1), groups[1].pitches)
    }

    @Test
    fun aFrequencyDictionaryAloneFindsNothing() = runTest {
        val files = mapOf("index.json" to index("Jiten"), "term_meta_bank_1.json" to """[["猫","freq",5]]""")
        DictionaryImporter(database).import(writeZip(temp.root, files, "jiten.zip"))

        assertFalse(lookup.lookup("猫").dictionariesAvailable)
    }

    @Test
    fun namesRankBelowOrdinaryWordsOfTheSameMatch_evenWhenInstalledFirst() = runTest {
        // Installed first, so it is the first dictionary on the Dictionaries screen.
        install(
            "JMnedict [2026-10-30]",
            listOf("猫", "ねこ", "surname", 0, "Neko (surname)"),
            listOf("猫田", "ねこた", "surname", 0, "Nekota (surname)"),
        )
        jmdict()

        val same = lookup.lookup("猫です")
        assertEquals(listOf("猫", "猫"), same.headwords())
        assertEquals(listOf("JMdict", "JMnedict [2026-10-30]"), same.entries.take(2).map { it.dictionary.title })

        // A longer match is still the longer match, and a word that is only a name is found.
        assertEquals("猫田", lookup.lookup("猫田です").entries.first().term.expression)
        assertEquals("猫田", lookup.lookup("猫田").entries.first().term.expression)
    }

    @Test
    fun deinflectsTheLongestMatch() = runTest {
        jmdict()

        val result = lookup.lookup("食べさせられなかった。名前は")

        val first = result.entries.first()
        assertEquals("食べる", first.term.expression)
        assertEquals("食べさせられなかった", first.source)
        assertEquals(10, result.matchedLength)
        assertEquals(
            listOf(Inflection.CAUSATIVE, Inflection.POTENTIAL_OR_PASSIVE, Inflection.NEGATIVE, Inflection.PAST),
            first.inflections,
        )
    }

    @Test
    fun longerMatchesComeFirst() = runTest {
        jmdict()

        val result = lookup.lookup("食べ物を買う")

        // 食べ物 (3 chars) before 食べる from the stem 食べ (2 chars).
        assertEquals(listOf("食べ物", "食べる"), result.headwords())
        assertEquals(listOf(Inflection.STEM), result.entries[1].inflections)
    }

    @Test
    fun wordClassMustFitTheDeinflection() = runTest {
        jmdict()

        // かえった is the past of a godan verb: 帰る (v5) fits, 変える (v1) does not. (The prefix
        // かえ can still be the stem of 変える, a shorter match.)
        val result = lookup.lookup("かえった")

        val full = result.entries.filter { it.source == "かえった" }
        assertEquals(listOf("帰る"), full.map { it.term.expression })
        assertFalse(full.single().matchedExpression)
        assertEquals("帰る", result.entries.first().term.expression)
    }

    @Test
    fun suruNounsAndKuru() = runTest {
        jmdict()

        assertEquals("勉強", lookup.lookup("勉強しています").entries.first().term.expression)
        assertEquals("勉強しています", lookup.lookup("勉強しています").entries.first().source)
        assertEquals("来る", lookup.lookup("来なかった").entries.first().term.expression)
    }

    @Test
    fun katakanaIsAlsoLookedUpAsHiragana_andWrittenFormsRankFirst() = runTest {
        jmdict()

        val result = lookup.lookup("ネコが")

        // ねこ matches the written form of ねこ but only the reading of 猫.
        assertEquals(listOf("ねこ", "猫"), result.headwords())
        assertEquals("ネコ", result.entries.first().source)
    }

    @Test
    fun dictionaryPriorityThenScoreOrderEqualMatches() = runTest {
        jmdict()
        val second = install("Second", listOf("猫", "ねこ", "", 99, "neko"))
        database.openHelper.writableDatabase.execSQL("UPDATE dictionary SET priority = 1 WHERE id = $second")

        val result = lookup.lookup("猫")

        assertEquals(listOf("JMdict", "Second"), result.entries.map { it.dictionary.title })
    }

    @Test
    fun furiganaRanksTheEntryWithThatReadingFirst() = runTest {
        install(
            "JMdict",
            listOf("今日", "きょう", "", 10, "today"),
            listOf("今日", "こんにち", "", 5, "nowadays"),
        )

        assertEquals("きょう", lookup.lookup("今日は").entries.first().term.reading)
        val result = lookup.lookup("今日は", ruby = listOf(RubySpan(0, 2, "こんにち")))
        assertEquals(listOf("こんにち", "きょう"), result.entries.map { it.term.reading })
        assertEquals(listOf(true, false), result.entries.map { it.matchesFurigana })
    }

    @Test
    fun furiganaPicksTheReadingOfAnInflectedWord() = runTest {
        install(
            "JMdict",
            listOf("行く", "いく", "v5", 10, "to go"),
            listOf("行う", "おこなう", "v5", 10, "to carry out"),
        )

        assertEquals("行く", lookup.lookup("行った").headwords().first())
        val result = lookup.lookup("行った", ruby = listOf(RubySpan(0, 1, "おこな")))
        assertEquals(listOf("行う", "行く"), result.headwords())
        assertEquals("行った", result.entries.first().source)
    }

    @Test
    fun aLongerMatchTheFuriganaContradictsRanksBelowOneItAgreesWith() = runTest {
        install(
            "JMdict",
            listOf("誰", "だれ", "", 10, "who"),
            listOf("誰が", "たが", "", 10, "whose (archaic)"),
            listOf("上", "うえ", "", 10, "above"),
            listOf("上野", "こうずけ", "", 10, "Kōzuke"),
        )

        assertEquals(listOf("誰が", "誰"), lookup.lookup("誰が来た").headwords())
        val who = lookup.lookup("誰が来た", ruby = listOf(RubySpan(0, 1, "だれ")))
        assertEquals(listOf("誰", "誰が"), who.headwords())
        assertEquals(1, who.matchedLength)

        // With no entry agreeing (a name read うえの), the longest match still comes first.
        val ueno = lookup.lookup("上野に", ruby = listOf(RubySpan(0, 2, "うえの")))
        assertEquals("上野", ueno.headwords().first())
        assertEquals(2, ueno.matchedLength)
    }

    @Test
    fun disabledDictionariesAreSkipped() = runTest {
        val id = jmdict()
        database.dictionaryDao().setEnabled(id, false)

        assertEquals(emptyList<LookupEntry>(), lookup.lookup("猫").entries)
    }

    @Test
    fun noMatchGivesAnEmptyResult() = runTest {
        jmdict()

        val result = lookup.lookup("ゑゐ")

        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.matchedLength)
    }

    @Test
    fun lookupQueryUsesTheIndexes() {
        val plan = database.query(LOOKUP_SQL_PLAN_CHECK, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString("\n")

        assertTrue(plan, plan.contains("index_term_expression"))
        assertTrue(plan, plan.contains("index_term_reading"))
        assertFalse(plan, Regex("""SCAN (TABLE )?term\b(?! USING)""").containsMatchIn(plan))
    }

    @Test
    fun kanjiComeFromTheFirstKanjiDictionary_andOnlyForKanji() = runTest {
        suspend fun installKanji(title: String, meaning: String): Long {
            val bank = """[["食","ショク","た.べる","jouyou",["$meaning"],{"strokes":"9","grade":"2","freq":"328"}]]"""
            val files = mapOf("index.json" to index(title), "kanji_bank_1.json" to bank)
            return DictionaryImporter(database).import(writeZip(temp.root, files, "$title.zip")).id
        }
        val first = installKanji("KANJIDIC", "eat")
        installKanji("Other kanji", "meal")
        database.dictionaryDao().setEnabledAndPriority(first, enabled = true, priority = -1)

        val kanji = lookup.kanji("食べ物を食べる")

        assertEquals(setOf("食"), kanji.keys)
        val info = kanji.getValue("食")
        assertEquals(listOf("eat"), info.meanings)
        assertEquals(listOf("ショク"), info.onyomi)
        assertEquals(listOf("た.べる"), info.kunyomi)
        assertEquals(9, info.strokes)
        assertEquals(KanjiInfo.Level.SCHOOL, info.level)
        assertEquals(328, info.frequency)
        assertEquals("KANJIDIC", info.dictionaryTitle)
        assertTrue(lookup.kanji("たべる々").isEmpty())

        database.dictionaryDao().setEnabled(first, false)
        assertEquals(listOf("meal"), lookup.kanji("食").getValue("食").meanings)
    }

    /** Pointer entries as Jitendex (link plus Yomitan's form-of item) and JMdict write them. */
    private suspend fun installWithPointers(title: String, jitendexStyle: Boolean): Long {
        val pointer = if (jitendexStyle) {
            """[{"type":"structured-content","content":{"tag":"div","content":["⟶",""" +
                """{"tag":"a","href":"?query=%E7%94%9F%E3%81%BE%E3%82%8C%E3%82%8B&wildcards=off","content":"生まれる"}]}},""" +
                """["生まれる",["redirected from 生れる"]]]"""
        } else {
            """[{"type":"structured-content","content":{"tag":"span","content":["⟶",""" +
                """{"tag":"a","href":"?query=生まれる&wildcards=off","content":"生まれる"}]}}]"""
        }
        val bank = """[
            ["生まれる","うまれる","","v1",200,["to be born"],1378690,""],
            ["産まれる","うまれる","","v1",99,["to be born (of a child)"],1378690,""],
            ["生れる","","","v1",-102,$pointer,-1378690,""],
            ["生れ","","","",-102,[{"type":"structured-content","content":{"tag":"a","href":"?query=missing","content":"missing"}}],-42,""],
            ["生","なま","","",10,["raw"],7,""]
        ]"""
        val files = mapOf("index.json" to index(title), "term_bank_1.json" to bank)
        return DictionaryImporter(database).import(writeZip(temp.root, files, "$title.zip")).id
    }

    @Test
    fun pointerEntriesShowTheWordTheyPointTo() = runTest {
        for (jitendexStyle in listOf(true, false)) {
            val id = installWithPointers(if (jitendexStyle) "Jitendex" else "JMdict", jitendexStyle)

            val result = lookup.lookup("生れた")

            val first = result.entries.first()
            assertEquals("生まれる", first.term.expression)
            assertEquals("うまれる", first.term.reading)
            assertEquals("生れた", first.source)
            assertEquals(listOf(Inflection.PAST), first.inflections)
            assertEquals("[\"to be born\"]", first.term.glossary.json)
            assertFalse(result.headwords().contains("生れる"))
            database.dictionaryDao().setEnabled(id, false)
        }
    }

    @Test
    fun aPointerWithoutItsTargetRanksBelowRealEntries() = runTest {
        installWithPointers("Jitendex", jitendexStyle = true)
        install("Other", listOf("生れ", "うまれ", "", 1, "birth"))

        val result = lookup.lookup("生れ")

        assertEquals(
            listOf("Other 生れ", "Jitendex 生まれる", "Jitendex 生れ"),
            result.entries.filter { it.source == "生れ" }.map { "${it.dictionary.title} ${it.term.expression}" },
        )
    }

    @Test
    fun formOfEntriesGiveWayToTheirLemma() = runTest {
        // As in the Japanese Wiktionary (wty): conjugated forms as entries that only name the lemma.
        val bank = """[
            ["食べる","たべる","","v1",0,["食物を口に入れる。"],0,""],
            ["食べた","食べた","non-lemma","v",0,[["食べる",["past"]]],0,""],
            ["ふん","ふん","","",0,["hmph"],0,""],
            ["ふふん","ふふん","non-lemma","",0,[["ふん",["reduplication"]]],0,""]
        ]"""
        val files = mapOf("index.json" to index("wty-ja-ja"), "term_bank_1.json" to bank)
        DictionaryImporter(database).import(writeZip(temp.root, files, "wty.zip"))

        val eaten = lookup.lookup("食べた")
        assertEquals(listOf("食べる"), eaten.headwords())
        assertEquals(listOf(Inflection.PAST), eaten.entries.single().inflections)

        // A lemma only the dictionary knows is still shown, for the whole form.
        val hmph = lookup.lookup("ふふん")
        assertEquals("ふん", hmph.headwords().first())
        assertEquals("ふふん", hmph.entries.first().source)
        assertFalse(hmph.headwords().contains("ふふん"))
    }

    @Test
    fun repeatMarksVariationSelectorsAndWidthsAreNormalized() = runTest {
        install(
            "JMdict",
            listOf("こころ", "こころ", "", 10, "heart"),
            listOf("いすず", "いすず", "", 10, "Isuzu"),
            listOf("葛", "くず", "", 10, "kudzu"),
            listOf("ガラス", "がらす", "", 10, "glass"),
            listOf("居る", "いる", "v1", 10, "to be"),
        )

        val heart = lookup.lookup("こゝろ")
        assertEquals("こころ", heart.headwords().first())
        assertEquals("こゝろ", heart.entries.first().source)
        assertEquals("いすず", lookup.lookup("いすゞ").headwords().first())
        // The selector stays part of the matched text, so the mark covers it.
        val kudzu = lookup.lookup("葛󠄀の")
        assertEquals("葛", kudzu.headwords().first())
        assertEquals(3, kudzu.matchedLength)
        assertEquals("ガラス", lookup.lookup("ｶﾞﾗｽ").headwords().first())
        // ゐ of the old kana spelling.
        assertEquals("居る", lookup.lookup("ゐる").headwords().first())
    }

    @Test
    fun stretchedAndClippedWordsAreFound() = runTest {
        install(
            "JMdict",
            listOf("凄い", "すごい", "adj-i", 10, "amazing"),
            listOf("ねえ", "ねえ", "", 10, "hey"),
            listOf("ね", "ね", "", 5, "right?"),
            listOf("やば", "やば", "", 10, "yikes"),
            listOf("ラーメン", "らーめん", "", 10, "ramen"),
            listOf("らめん", "らめん", "", 10, "not a word"),
        )

        val amazing = lookup.lookup("すごーい！")
        assertEquals("凄い", amazing.headwords().first())
        assertEquals("すごーい", amazing.entries.first().source)
        assertEquals("凄い", lookup.lookup("すごぉい").headwords().first())
        val hey = lookup.lookup("ねぇ、")
        assertEquals("ねえ", hey.headwords().first())
        assertEquals("ねぇ", hey.entries.first().source)
        val yikes = lookup.lookup("やばっ！")
        assertEquals("やば", yikes.headwords().first())
        assertEquals("やばっ", yikes.entries.first().source)
        // っ before more kana is no clipped word.
        assertEquals("やば", lookup.lookup("やばっか").entries.first().source)
        // Katakana keeps its long-vowel mark.
        assertEquals(listOf("ラーメン"), lookup.lookup("ラーメン").headwords())
    }

    @Test
    fun katakanaAfterKanjiIsNotReadAsOkurigana() = runTest {
        install(
            "JMdict",
            listOf("赤", "あか", "", 10, "red"),
            listOf("赤い", "あかい", "adj-i", 10, "red"),
            listOf("赤する", "あかする", "vs", 10, "not a word"),
        )

        val result = lookup.lookup("赤シャツ")
        assertEquals(1, result.matchedLength)
        assertEquals("赤", result.headwords().first())
    }

    @Test
    fun extraOkuriganaOfOlderBooksIsDropped() = runTest {
        install(
            "JMdict",
            listOf("驚く", "おどろく", "v5", 10, "to be surprised"),
            listOf("見る", "みる", "v1", 10, "to see"),
            listOf("見える", "みえる", "v1", 10, "to be seen"),
            listOf("私", "わたし", "", 10, "I"),
            listOf("私か", "わたしか", "", 10, "not a word"),
        )

        val surprised = lookup.lookup("驚ろいて、")
        assertEquals("驚く", surprised.headwords().first())
        assertEquals("驚ろいて", surprised.entries.first().source)
        assertEquals(listOf(Inflection.TE), surprised.entries.first().inflections)
        // Only when nothing longer than the kanji matched.
        assertEquals("見える", lookup.lookup("見える").headwords().first())
        // A particle is no okurigana.
        assertEquals(listOf("私"), lookup.lookup("私はか").headwords())
    }

    @Test
    fun furiganaThatIsNoReadingOfTheWordLooksUpTheWordItNames() = runTest {
        install(
            "JMdict",
            listOf("宅", "たく", "", 10, "house (formal)"),
            // A word written in kana and read like the furigana, before 家 in the dictionary.
            listOf("うち", "うち", "", 10, "emphasis prefix"),
            listOf("家", "うち", "", 10, "home"),
            listOf("失策", "しっさく", "vs", 10, "blunder"),
            listOf("仕舞う", "しまう", "v5", 10, "to finish"),
            listOf("島", "しま", "", 10, "island"),
            listOf("停車場", "ていしゃじょう", "", 10, "station"),
            listOf("ステーション", "ステーション", "", 10, "station"),
        )

        val station = lookup.lookup("停車場へ", ruby = listOf(RubySpan(0, 3, "ステーション")))
        assertEquals(listOf("ステーション", "停車場"), station.headwords())

        // Hiragana furigana over a kanji names a word written in kanji first.
        val home = lookup.lookup("宅へ", ruby = listOf(RubySpan(0, 1, "うち")))
        assertEquals(listOf("家", "うち", "宅"), home.headwords())
        assertEquals(listOf("宅", "宅", "宅"), home.entries.map { it.source })
        assertTrue(home.entries.first().matchesFurigana)

        // The kana after the base belong to the furigana's word: 失策った read しまった.
        val blunder = lookup.lookup("失策った", ruby = listOf(RubySpan(0, 2, "しま")))
        assertEquals("仕舞う", blunder.headwords().first())
        assertEquals("失策った", blunder.entries.first().source)
        assertFalse("島" in blunder.headwords())

        // Furigana that agrees with an entry looks up nothing more.
        val formal = lookup.lookup("宅へ", ruby = listOf(RubySpan(0, 1, "たく")))
        assertEquals(listOf("宅"), formal.headwords())
    }
}
