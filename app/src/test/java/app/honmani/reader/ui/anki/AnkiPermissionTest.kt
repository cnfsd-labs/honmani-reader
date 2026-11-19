package app.honmani.reader.ui.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiPermissionTest {
    @Test
    fun firstDismissalDoesNotOpenSettings() {
        assertFalse(deniedForGood(rationaleBefore = false, rationaleAfter = false, refusedWithoutRationaleBefore = false))
    }

    @Test
    fun firstDenyDoesNotOpenSettings() {
        assertFalse(deniedForGood(rationaleBefore = false, rationaleAfter = true, refusedWithoutRationaleBefore = false))
    }

    @Test
    fun secondDenyOpensSettings() {
        assertTrue(deniedForGood(rationaleBefore = true, rationaleAfter = false, refusedWithoutRationaleBefore = false))
    }

    @Test
    fun refusalWithoutDialogOpensSettings() {
        assertTrue(deniedForGood(rationaleBefore = false, rationaleAfter = false, refusedWithoutRationaleBefore = true))
    }
}
