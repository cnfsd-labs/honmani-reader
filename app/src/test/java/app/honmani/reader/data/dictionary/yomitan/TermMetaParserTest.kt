package app.honmani.reader.data.dictionary.yomitan

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TermMetaParserTest {
    private fun parse(row: String) = TermMetaParser.parse(Json.parseToJsonElement(row))

    private fun assertInvalid(row: String) {
        try {
            parse(row)
            fail("accepted: $row")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun readsANumericFrequency() {
        val meta = parse("""["猫","freq",395]""").single()
        assertEquals(YomitanTermMeta("猫", null, TermMetaKind.FREQUENCY, 395.0, null), meta)
    }

    @Test
    fun readsATextFrequencyWithItsLeadingNumber() {
        val meta = parse("""["猫","freq","1,234㋕"]""").single()
        assertEquals(1234.0, meta.value!!, 0.0)
        assertEquals("1,234㋕", meta.display)
        assertNull(parse("""["猫","freq","㋕"]""").single().value)
    }

    @Test
    fun readsAFrequencyForOneReading() {
        val meta = parse("""["猫","freq",{"reading":"ねこ","frequency":{"value":12,"displayValue":"12k"}}]""").single()
        assertEquals("ねこ", meta.reading)
        assertEquals(12.0, meta.value!!, 0.0)
        assertEquals("12k", meta.display)
        assertNull(parse("""["猫","freq",{"reading":"","frequency":7}]""").single().reading)
    }

    @Test
    fun readsAFrequencyObjectWithOnlyADisplayValue() {
        val meta = parse("""["猫","freq",{"displayValue":"5"}]""").single()
        assertEquals(5.0, meta.value!!, 0.0)
    }

    @Test
    fun skipsFrequenciesItCannotRead() {
        assertTrue(parse("""["猫","freq",null]""").isEmpty())
        assertTrue(parse("""["猫","freq",[1]]""").isEmpty())
        assertTrue(parse("""["猫","freq",{}]""").isEmpty())
        assertTrue(parse("""["猫","freq","  "]""").isEmpty())
        assertTrue(parse("""["猫","freq",{"reading":"ねこ","frequency":true}]""").isEmpty())
    }

    @Test
    fun readsPitchPositionsAsNumbersAndLevels() {
        val meta = parse("""["猫","pitch",{"reading":"ねこ","pitches":[{"position":1},{"position":"LHH"},{"position":1},{"position":"HLL"}]}]""").single()
        assertEquals(TermMetaKind.PITCH, meta.kind)
        assertEquals("ねこ", meta.reading)
        assertEquals(listOf(1, 0), meta.pitches)
    }

    @Test
    fun dropsBadPitchPositions() {
        assertTrue(parse("""["猫","pitch",{"reading":"ねこ","pitches":[{"position":-1},{"position":"X"},{"position":null},{"position":[1]},{}, 3]}]""").isEmpty())
        assertTrue(parse("""["猫","pitch",{"reading":"ねこ"}]""").isEmpty())
        assertEquals(listOf(2), parse("""["猫","pitch",{"reading":"ねこ","pitches":[{"position":-1},{"position":2}]}]""").single().pitches)
    }

    @Test
    fun levelPatternsGiveTheDownstep() {
        assertEquals(3, TermMetaParser.downstepOf("LHHL"))
        assertEquals(0, TermMetaParser.downstepOf("LHHH"))
        assertEquals(1, TermMetaParser.downstepOf("hl"))
        assertNull(TermMetaParser.downstepOf(""))
        assertNull(TermMetaParser.downstepOf("LHX"))
    }

    @Test
    fun ignoresKindsHonmaniDoesNotUse() {
        assertTrue(parse("""["猫","ipa",{"reading":"neko","transcriptions":[]}]""").isEmpty())
        assertTrue(parse("""["猫",5,1]""").isEmpty())
    }

    @Test
    fun rejectsMalformedRows() {
        assertInvalid("""{"a":1}""")
        assertInvalid("""5""")
        assertInvalid("""["猫","freq"]""")
        assertInvalid("""[1,"freq",3]""")
        assertInvalid("""["","freq",3]""")
        assertInvalid("""["猫","pitch",5]""")
        assertInvalid("""["猫","pitch",{"pitches":[{"position":1}]}]""")
        assertInvalid("""["猫","pitch",{"reading":5,"pitches":[{"position":1}]}]""")
    }
}
