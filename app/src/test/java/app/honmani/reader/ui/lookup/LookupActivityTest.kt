package app.honmani.reader.ui.lookup

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LookupActivityTest {
    @Test
    fun selectedAndSharedTextIsLookedUp_trimmedAndCut() {
        val selected = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "  猫が好き\n")
        assertEquals("猫が好き", LookupActivity.textOf(selected))

        val shared = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "あ".repeat(1_000))
        assertEquals(LookupActivity.MAX_TEXT, LookupActivity.textOf(shared).length)

        assertEquals("", LookupActivity.textOf(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun aLookupStartsAtTheTappedCharacter_withTheTextAsItsSentence() {
        val tap = lookupAt("昨日、寿司を食べさせられなかった。", 6)

        assertEquals("食べさせられなかった。", tap.text)
        assertEquals("昨日、寿司を食べさせられなかった。", tap.sentence)
        assertEquals(6, tap.sentenceOffset)
        // The reader's scan length.
        assertEquals(20, lookupAt("あ".repeat(50), 0).text.length)
    }
}
