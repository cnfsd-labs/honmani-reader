package app.honmani.reader.data.words

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpellingVariantsTest {
    private fun SpellingVariants.sorted(key: String) = of(key).sorted()

    @Test
    fun spellingsOfOneEntryAreVariantsOfEachOther() {
        val variants = SpellingVariants.Builder()
            .add(1, 100, "分かる").add(1, 100, "判る").add(1, 100, "解る")
            .add(1, 200, "当たり前").add(1, 200, "当り前")
            .build()
        assertEquals(listOf("判る", "解る"), variants.sorted("分かる"))
        assertEquals(listOf("当たり前"), variants.sorted("当り前"))
        assertEquals(emptyList<String>(), variants.sorted("猫"))
    }

    @Test
    fun singleCharactersKanaAndEntriesWithoutSequenceAreNotLinked() {
        val variants = SpellingVariants.Builder()
            // 生 is also うぶ, in the entry of 初 and 初心.
            .add(1, 300, "初").add(1, 300, "初心").add(1, 300, "生")
            .add(1, 400, "わかる").add(1, 400, "分かる")
            .add(1, 0, "早い").add(1, 0, "速い")
            .build()
        assertEquals(emptyList<String>(), variants.sorted("生"))
        assertEquals(emptyList<String>(), variants.sorted("初心"))
        assertEquals(emptyList<String>(), variants.sorted("分かる"))
        assertEquals(emptyList<String>(), variants.sorted("早い"))
    }

    @Test
    fun aSpellingInSeveralEntriesLinksToAllOfThem_dictionariesApart() {
        val variants = SpellingVariants.Builder()
            .add(1, 10, "会う").add(1, 10, "逢う")
            .add(1, 11, "会う").add(1, 11, "遭う")
            // The same sequence in another dictionary is another entry.
            .add(2, 10, "早い").add(2, 10, "速い")
            .build()
        assertEquals(listOf("逢う", "遭う"), variants.sorted("会う"))
        assertEquals(listOf("会う"), variants.sorted("逢う"))
        assertEquals(listOf("速い"), variants.sorted("早い"))
    }

    @Test
    fun anOversizedEntryLinksNothing() {
        val builder = SpellingVariants.Builder()
        for (i in 0 until 40) builder.add(1, 5, "語$i")
        assertEquals(0, builder.build().size)
    }

    @Test
    fun theCacheKeepsTheEntriesForTheSameDictionariesOnly() {
        val cache = File(Files.createTempDirectory("variants").toFile(), "spelling-variants.txt")
        val groups = SpellingVariants.Builder().add(1, 1, "分かる").add(1, 1, "判る").add(1, 2, "当たり前").add(1, 2, "当り前").groups()
        SpellingVariants.write(cache, "4@2026-10-03", groups)
        val read = SpellingVariants.read(cache, "4@2026-10-03")!!
        assertEquals(listOf("判る"), read.sorted("分かる"))
        assertEquals(listOf("当たり前"), read.sorted("当り前"))
        // Another dictionary revision: worked out again.
        assertNull(SpellingVariants.read(cache, "4@2026-11-01"))
        assertNull(SpellingVariants.read(File(cache.parentFile, "missing.txt"), "4@2026-10-03"))
    }
}
