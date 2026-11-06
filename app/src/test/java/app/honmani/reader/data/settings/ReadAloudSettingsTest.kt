package app.honmani.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSettingsTest {
    @Test
    fun halfSpeedIsTheSlowestAndTheSpeedsCycleFromTheFastestBackToIt() {
        assertEquals(0.5f, ReadAloudSettings.RATES.first(), 0f)
        assertTrue(ReadAloudSettings.RATES.contains(1f))
        assertEquals(0.75f, ReadAloudSettings.nextRate(0.5f), 0f)
        assertEquals(0.5f, ReadAloudSettings.nextRate(ReadAloudSettings.RATES.last()), 0f)
    }
}
