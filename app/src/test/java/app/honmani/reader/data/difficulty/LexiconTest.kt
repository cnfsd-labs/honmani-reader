package app.honmani.reader.data.difficulty

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LexiconTest {
    @Test
    fun aSavedLexiconReadsBackTheSame_forItsSignatureOnly() {
        val lexicon = Lexicon.Builder()
            .add("猫", 0, 900)
            .add("食べる", 1, 187)
            .add("当たり前", 0, 698)
            .add("ねこ", 0, 0)
            .build()
        val file = File(Files.createTempDirectory("lexicon").toFile(), "lexicon.bin")
        lexicon.write(file, "v8|Jiten@1|Jitendex@2")

        val read = Lexicon.read(file, "v8|Jiten@1|Jitendex@2")!!
        assertEquals(lexicon.size, read.size)
        for (key in listOf("猫", "食べる", "当たり前", "ねこ")) {
            val index = read.find(key)
            assertEquals(lexicon.find(key), index)
            assertEquals(lexicon.rank(lexicon.find(key)), read.rank(index))
            assertEquals(lexicon.classes(lexicon.find(key)), read.classes(index))
        }
        assertEquals(-1, read.find("犬"))

        assertNull(Lexicon.read(file, "v9|Jiten@1|Jitendex@2"))
        file.writeText("damaged")
        assertNull(Lexicon.read(file, "v8|Jiten@1|Jitendex@2"))
    }
}
