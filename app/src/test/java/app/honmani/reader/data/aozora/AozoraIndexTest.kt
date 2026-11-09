package app.honmani.reader.data.aozora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal fun indexJson(version: Int = 1, works: String): String =
    """{"version":$version,"generated":"2026-11-07","authors":[["夏目漱石","なつめそうせき"],["芥川龍之介","あくたがわりゅうのすけ"]],"works":[$works]}"""

internal fun work(
    id: String,
    title: String,
    reading: String,
    author: Int,
    script: String,
    file: String = "000148/${id}_ruby.zip",
    difficulty: Int? = null,
) = """{"i":"$id","t":"$title","r":"$reading","a":$author,"s":"$script","p":"2000-01-01","u":"$file"${difficulty?.let { ""","d":$it""" }.orEmpty()}}"""

internal fun parse(json: String): AozoraIndex = AozoraIndexParser.parse(json.byteInputStream())

class AozoraIndexTest {
    private val catalogue = parse(
        indexJson(
            works = listOf(
                work("000789", "吾輩は猫である", "わがはいはねこである", 0, "nn"),
                work("000101", "こころ", "こころ", 0, "nn"),
                work("000102", "こゝろ", "こころ", 0, "no"),
                work("000103", "羅生門", "らしょうもん", 1, ""),
                work("000104", "羅生門", "らしょうもん", 1, "nn", "000879/104_ruby.zip"),
            ).joinToString(","),
        ),
    )

    @Test
    fun readsWorksWithTheirAuthors() {
        val first = catalogue.works.first()
        assertEquals("2026-11-07", catalogue.generated)
        assertEquals("000789", first.id)
        assertEquals("夏目漱石", first.author)
        assertEquals("なつめそうせき", first.authorReading)
        assertEquals("000148/000789_ruby.zip", first.file)
        assertEquals(5, catalogue.works.size)
    }

    @Test
    fun duplicateTitlesStayDistinctWorks() {
        val same = catalogue.works.filter { it.title == "羅生門" }
        assertEquals(2, same.size)
        assertEquals(listOf("000103", "000104"), same.map { it.id })
    }

    @Test
    fun oldKanaIsOnlyTheSecondLetter() {
        assertFalse(catalogue.works[0].oldKana)
        assertTrue(catalogue.works[2].oldKana)
        // An unknown script is not counted as old.
        assertFalse(catalogue.works[3].oldKana)
        assertFalse(catalogue.works[3].oldKanji)
    }

    @Test
    fun leavesOutWorksWithoutAUsableTitleIdOrFile() {
        val list = parse(
            indexJson(
                works = listOf(
                    work("000001", "良い", "よい", 0, "nn"),
                    work("000002", "", "", 0, "nn"),
                    work("", "題", "だい", 0, "nn"),
                    work("000003", "悪い道", "わるいみち", 0, "nn", "../../etc/passwd.zip"),
                    work("000004", "悪い道2", "わるいみち", 0, "nn", "000148/a/b.zip"),
                    work("000005", "悪い道3", "わるいみち", 0, "nn", "000148/file.exe"),
                    work("000006", "悪い道4", "わるいみち", 0, "nn", "000148/sp ace.zip"),
                    work("000007", "悪い道5", "わるいみち", 0, "nn", "https://evil.example/x.zip"),
                    work("000008", "良い2", "よい", 0, "nn", "000148/file.TXT"),
                ).joinToString(","),
            ),
        )
        assertEquals(listOf("000001", "000008"), list.works.map { it.id })
    }

    @Test
    fun anAuthorIndexOutOfRangeLeavesTheAuthorEmpty() {
        val list = parse(indexJson(works = work("000001", "題", "だい", 9, "nn")))
        assertEquals("", list.works.single().author)
    }

    @Test
    fun difficultyIsOptional() {
        val list = parse(
            indexJson(
                works = listOf(
                    work("1", "易しい", "やさしい", 0, "nn", difficulty = 35),
                    work("2", "無印", "むじるし", 0, "nn"),
                    work("3", "上限", "じょうげん", 0, "nn", difficulty = 100),
                ).joinToString(","),
            ),
        )
        assertEquals(listOf(35, null, 100), list.works.map { it.difficulty })
    }

    @Test
    fun aDifficultyOutsideTheScaleIsIgnored() {
        val list = parse(
            indexJson(
                works = listOf(
                    work("1", "零", "ぜろ", 0, "nn", difficulty = 0),
                    work("2", "負", "ふ", 0, "nn", difficulty = -3),
                    work("3", "超", "ちょう", 0, "nn", difficulty = 101),
                ).joinToString(","),
            ),
        )
        assertEquals(listOf(null, null, null), list.works.map { it.difficulty })
    }

    @Test
    fun anEmptyListIsFine() {
        assertEquals(0, parse(indexJson(works = "")).works.size)
    }

    @Test
    fun aNewerVersionIsTooNew() {
        assertReason(AozoraException.Reason.TOO_NEW) { parse(indexJson(version = 2, works = "")) }
    }

    @Test
    fun noVersionOrGarbageIsBadData() {
        assertReason(AozoraException.Reason.BAD_DATA) { parse("""{"works":[]}""") }
        assertReason(AozoraException.Reason.BAD_DATA) { parse("not json") }
        assertReason(AozoraException.Reason.BAD_DATA) { parse("") }
        assertReason(AozoraException.Reason.BAD_DATA) { parse("""{"version":1,"works":"x"}""") }
    }

    private fun assertReason(reason: AozoraException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: AozoraException) {
            assertEquals(reason, e.reason)
        }
    }
}

class AozoraSearchTest {
    private val works = parse(
        indexJson(
            works = listOf(
                work("1", "吾輩は猫である", "わがはいはねこである", 0, "nn"),
                work("2", "こゝろ", "こころ", 0, "no"),
                work("3", "羅生門", "らしょうもん", 1, "nn"),
                work("4", "蜘蛛の糸", "くものいと", 1, ""),
                work("5", "ＡＢＣ Story", "えーびーしー", 1, "nn"),
            ).joinToString(","),
        ),
    ).works

    private fun ids(query: String, old: Boolean = false) = AozoraSearch.filter(works, query, old).map { it.id }

    @Test
    fun noWordsShowsEverythingExceptOldKana() {
        assertEquals(listOf("1", "3", "4", "5"), ids(""))
        assertEquals(listOf("1", "3", "4", "5"), ids("  　 "))
        assertEquals(listOf("1", "2", "3", "4", "5"), ids("", old = true))
    }

    @Test
    fun hiraganaAndKatakanaAreTheSame() {
        assertEquals(listOf("1"), ids("ネコ"))
        assertEquals(listOf("1"), ids("ねこ"))
        assertEquals(listOf("3"), ids("ラショウモン"))
    }

    @Test
    fun matchesTitleAuthorAndReadings() {
        assertEquals(listOf("1"), ids("吾輩"))
        assertEquals(listOf("1"), ids("夏目"))
        assertEquals(listOf("1"), ids("なつめ"))
        assertEquals(listOf("3", "4", "5"), ids("芥川"))
        assertEquals(listOf("3", "4", "5"), ids("アクタガワ"))
    }

    @Test
    fun everyWordMustMatch_inAnyOrder() {
        assertEquals(listOf("4"), ids("芥川 蜘蛛"))
        assertEquals(listOf("4"), ids("蜘蛛　芥川"))
        assertEquals(emptyList<String>(), ids("夏目 蜘蛛"))
    }

    @Test
    fun caseAndSpacesDoNotMatter() {
        assertEquals(listOf("5"), ids("story"))
        assertEquals(listOf("5"), ids("STORY"))
    }

    @Test
    fun oldKanaWorksAppearOnlyWithTheSwitch() {
        assertEquals(emptyList<String>(), ids("こゝろ"))
        assertEquals(listOf("2"), ids("こゝろ", old = true))
    }

    @Test
    fun worksWithoutAScriptAreShownEvenWithTheSwitchOff() {
        assertEquals(listOf("4"), ids("蜘蛛", old = false))
    }
}

class AozoraSortTest {
    private fun w(id: String, reading: String, difficulty: Int?, title: String = reading) =
        parse(indexJson(works = work(id, title, reading, 0, "nn", difficulty = difficulty))).works.single()

    private val works = listOf(
        w("a", "ゆき", 40),
        w("b", "あめ", 70),
        w("c", "かぜ", null),
        w("d", "そら", 45),
        w("e", "くも", 70),
        w("f", "いし", null),
        w("g", "つき", 20),
    )

    private fun ids(sort: AozoraSort, skill: Int? = null) = sort.apply(works, skill).map { it.id }

    @Test
    fun alphabeticalIsByTitleReadingIgnoringDifficulty() {
        assertEquals(listOf("b", "f", "c", "e", "d", "g", "a"), ids(AozoraSort.ALPHABETICAL))
    }

    @Test
    fun alphabeticalComparesKatakanaAsHiragana() {
        val list = listOf(w("1", "ウミ", null), w("2", "あめ", null), w("3", "カワ", null), w("4", "いし", null))
        assertEquals(listOf("2", "4", "1", "3"), AozoraSort.ALPHABETICAL.apply(list, null).map { it.id })
    }

    @Test
    fun alphabeticalFallsBackToTheTitleWithoutReadingAndThenTheId() {
        val list = listOf(w("2", "", null, title = "ぶ"), w("3", "あ", null, title = "z"), w("1", "あ", null, title = "z"))
        assertEquals(listOf("1", "3", "2"), AozoraSort.ALPHABETICAL.apply(list, null).map { it.id })
    }

    @Test
    fun easiestFirstPutsWorksWithoutDifficultyLast() {
        // Equal difficulties (b, e) and the ungraded keep alphabetical order.
        assertEquals(listOf("g", "a", "d", "b", "e", "f", "c"), ids(AozoraSort.EASIEST))
    }

    @Test
    fun hardestFirstPutsWorksWithoutDifficultyLast() {
        assertEquals(listOf("b", "e", "d", "a", "g", "f", "c"), ids(AozoraSort.HARDEST))
    }

    @Test
    fun bestFitIsNearestToTheSkillPlusAStretch() {
        // Skill 41: target 43 (Skill.STRETCH is 2): d 45 (2), a 40 (3), g 20 (23), b/e 70 (27).
        assertEquals(listOf("d", "a", "g", "b", "e", "f", "c"), ids(AozoraSort.BEST_FIT, 41))
    }

    @Test
    fun bestFitTiesBetweenAboveAndBelowAreAlphabetical() {
        // Target 50: 47 and 53 are both 3 away.
        val list = listOf(w("1", "ろ", 53), w("2", "い", 47), w("3", "は", 50))
        assertEquals(listOf("3", "2", "1"), AozoraSort.BEST_FIT.apply(list, 48).map { it.id })
    }

    @Test
    fun bestFitWithoutASkillIsAlphabetical() {
        assertEquals(ids(AozoraSort.ALPHABETICAL), ids(AozoraSort.BEST_FIT, null))
    }

    @Test
    fun bestFitWithoutAnyDifficultyIsAlphabeticalToo() {
        val plain = listOf(w("2", "ろ", null), w("1", "い", null))
        assertEquals(listOf("1", "2"), AozoraSort.BEST_FIT.apply(plain, 50).map { it.id })
    }

    @Test
    fun filterAppliesTheSortAfterTheSearch() {
        val found = AozoraSearch.filter(works, "", false, AozoraSort.EASIEST, null).map { it.id }
        assertEquals(ids(AozoraSort.EASIEST), found)
        // Without a sort the list's order stays.
        assertEquals(works.map { it.id }, AozoraSearch.filter(works, "", false).map { it.id })
    }
}
