package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EdgeTapsHintTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("edge-taps-hint-test", Context.MODE_PRIVATE)

    @Test
    fun shownOnce_thenNeverAgain_evenAfterARestart() {
        assertTrue(EdgeTapsHint(preferences()).take())
        assertFalse(EdgeTapsHint(preferences()).take())
        assertFalse(EdgeTapsHint(preferences()).take())
    }
}
