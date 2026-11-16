package app.honmani.reader.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewSwipeHintTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("review-swipe-hint-test", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        preferences().edit().clear().commit()
    }

    @Test
    fun shownForTheFirstThreeSessions_evenAfterARestart() {
        repeat(ReviewSwipeHint.SESSIONS) {
            assertTrue(ReviewSwipeHint(preferences()).shown)
            ReviewSwipeHint(preferences()).sessionFinished()
        }
        assertFalse(ReviewSwipeHint(preferences()).shown)
    }

    @Test
    fun aSwipeEndsItAtOnce() {
        ReviewSwipeHint(preferences()).swiped()
        assertFalse(ReviewSwipeHint(preferences()).shown)
    }
}
