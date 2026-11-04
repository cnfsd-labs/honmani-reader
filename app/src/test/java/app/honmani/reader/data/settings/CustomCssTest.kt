package app.honmani.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCssTest {
    @Test
    fun ordinaryRulesHaveNoProblem() {
        assertNull(CustomCss.problem(""))
        assertNull(CustomCss.problem("p { line-height: 3 }"))
        assertNull(CustomCss.problem("@media (min-width: 1px) { ruby { color: red } }\nrt::after { content: \"}\" } /* a { */"))
        assertNull(CustomCss.problem("p { font-family: 'Noto Serif JP', serif }"))
    }

    @Test
    fun aStrayOrMissingBraceIsAProblem() {
        assertEquals(CustomCss.Problem.UNBALANCED, CustomCss.problem("p { color: red } } body { display: none }"))
        assertEquals(CustomCss.Problem.UNBALANCED, CustomCss.problem("p { color: red"))
        assertEquals(CustomCss.Problem.UNBALANCED, CustomCss.problem("p { background: url(x }"))
        assertEquals(CustomCss.Problem.UNBALANCED, CustomCss.problem("p { content: \"abc }"))
        assertEquals(CustomCss.Problem.UNBALANCED, CustomCss.problem("p { } /* never closed"))
    }

    @Test
    fun theCapCountsBytes() {
        assertNull(CustomCss.problem("a".repeat(CustomCss.MAX_BYTES)))
        assertEquals(CustomCss.Problem.TOO_LONG, CustomCss.problem("a".repeat(CustomCss.MAX_BYTES + 1)))
        // 3 bytes each in UTF-8.
        assertEquals(CustomCss.Problem.TOO_LONG, CustomCss.problem("あ".repeat(CustomCss.MAX_BYTES / 3 + 1)))
        assertTrue(CustomCss.fits("あ".repeat(CustomCss.MAX_BYTES / 3)))
        assertFalse(CustomCss.fits("a\u0000"))
    }
}
