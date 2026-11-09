package app.honmani.reader.readaloud.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAudiobookTest {
    private val chirp = "ja-JP-Chirp3-HD-Aoede"

    @Test
    fun pricesFollowTheModelOfTheVoice() {
        assertEquals(30.0, GeneratedAudiobook.pricePerMillion(chirp)!!, 0.0)
        assertEquals(16.0, GeneratedAudiobook.pricePerMillion("ja-JP-Neural2-B")!!, 0.0)
        assertEquals(4.0, GeneratedAudiobook.pricePerMillion("ja-JP-Wavenet-A")!!, 0.0)
        assertEquals(4.0, GeneratedAudiobook.pricePerMillion("ja-JP-Standard-D")!!, 0.0)
        assertNull(GeneratedAudiobook.pricePerMillion("ja-JP-Chirp-HD-F"))
        assertNull(GeneratedAudiobook.pricePerMillion("ja-JP-Something-Else-A"))
    }

    @Test
    fun freeAllowanceFollowsTheModelOfTheVoice() {
        assertEquals(1, GeneratedAudiobook.freeMonthlyMillions(chirp))
        assertEquals(1, GeneratedAudiobook.freeMonthlyMillions("ja-JP-Neural2-B"))
        assertEquals(4, GeneratedAudiobook.freeMonthlyMillions("ja-JP-Wavenet-A"))
        assertEquals(4, GeneratedAudiobook.freeMonthlyMillions("ja-JP-Standard-D"))
        assertNull(GeneratedAudiobook.freeMonthlyMillions("ja-JP-Something-Else-A"))
    }

    @Test
    fun everyPricedModelHasAnAllowance() {
        assertEquals(GeneratedAudiobook.PRICES.map { it.first }, GeneratedAudiobook.FREE_MONTHLY_MILLIONS.map { it.first })
    }

    @Test
    fun estimateIsCharactersTimesPrice() {
        assertEquals(30.0, GeneratedAudiobook.estimateUsd(1_000_000, chirp)!!, 1e-9)
        assertEquals(0.06, GeneratedAudiobook.estimateUsd(2_000, chirp)!!, 1e-9)
        assertEquals(1.6, GeneratedAudiobook.estimateUsd(100_000, "ja-JP-Neural2-B")!!, 1e-9)
        assertEquals(0.0, GeneratedAudiobook.estimateUsd(0, chirp)!!, 0.0)
        assertNull(GeneratedAudiobook.estimateUsd(5_000, "ja-JP-Unknown-A"))
    }

    @Test
    fun lengthAndSizeAreEstimatedAt32Kbps() {
        // 252,000 characters: 36,000 seconds (10 hours), 144 MB at 4,000 bytes a second.
        assertEquals(36_000L, GeneratedAudiobook.estimatedSeconds(252_000))
        assertEquals(144_000_000L, GeneratedAudiobook.estimatedBytes(252_000))
        assertEquals(172_800_000L, GeneratedAudiobook.spaceNeededBytes(252_000))
        assertEquals(0L, GeneratedAudiobook.estimatedBytes(0))
    }

    @Test
    fun defaultCapIsTheEstimatePlusTenPercentRoundedUp() {
        assertEquals(33.0, GeneratedAudiobook.defaultCapUsd(1_000_000, chirp)!!, 0.0)
        // 0.06 * 1.1 = 0.066: up to 0.07.
        assertEquals(0.07, GeneratedAudiobook.defaultCapUsd(2_000, chirp)!!, 0.0)
        assertNull(GeneratedAudiobook.defaultCapUsd(2_000, "ja-JP-Unknown-A"))
    }

    @Test
    fun noStartWhenTheEstimateIsOverTheCap() {
        assertTrue(GeneratedAudiobook.canStart(1_000_000, chirp, 30.0))
        assertTrue(GeneratedAudiobook.canStart(1_000_000, chirp, 33.0))
        assertFalse(GeneratedAudiobook.canStart(1_000_000, chirp, 29.99))
        assertTrue(GeneratedAudiobook.exceedsCap(1_000_001, chirp, 30.0))
        assertFalse(GeneratedAudiobook.exceedsCap(1_000_000, chirp, 30.0))
        // The default cap always lets the job start.
        for (chars in listOf(1L, 999L, 2_000L, 123_457L, 1_000_000L)) {
            assertTrue(GeneratedAudiobook.canStart(chars, chirp, GeneratedAudiobook.defaultCapUsd(chars, chirp)))
        }
    }

    @Test
    fun aCapIsRequiredAlsoForAnUnknownModel() {
        assertFalse(GeneratedAudiobook.canStart(5_000, chirp, null))
        assertFalse(GeneratedAudiobook.canStart(5_000, chirp, 0.0))
        assertFalse(GeneratedAudiobook.canStart(5_000, "ja-JP-Unknown-A", null))
        assertTrue(GeneratedAudiobook.canStart(5_000, "ja-JP-Unknown-A", 1.0))
    }
}
