package app.honmani.reader.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.DataFormatException

class GlossaryPackerTest {
    private fun entry(i: Int) =
        """[{"type":"structured-content","content":[{"tag":"span","data":{"code":"n"},"title":"noun (common) (futsuumeishi)",""" +
            """"style":{"fontSize":"0.8em","borderRadius":"0.3em"},"content":"noun"},{"tag":"ul","lang":"en","content":""" +
            """[{"tag":"li","content":"meaning number $i, with some English"},{"tag":"li","content":"例文$i。"}]}]}]"""

    @Test
    fun roundTrips_andTheStructureCostsAlmostNothing() {
        val samples = (1..300).map(::entry)
        GlossaryPacker(GlossaryPacker.preset(samples)).use { packer ->
            for (json in listOf(entry(1), entry(1_000), "[\"猫\"]", "[]", entry(7).repeat(20))) {
                val packed = packer.pack(json)
                assertEquals(json, GlossaryPacker.unpack(packed, packer.preset))
            }
            val packed = packer.pack(entry(4_242))
            assertTrue(packed.isPacked)
            // About 300 bytes of JSON; what differs from the preset is a few words.
            assertTrue("${packed.bytes.size} bytes", packed.bytes.size < entry(4_242).length / 5)
        }
    }

    @Test
    fun aPresetIsSmall_andPlainGlossariesNeedNone() {
        val preset = GlossaryPacker.preset((1..1_000).map(::entry))
        assertEquals(GlossaryPacker.PRESET_SIZE, preset.size)
        assertTrue(GlossaryPacker.preset(listOf("[\"a\"]")).isNotEmpty())

        val plain = Glossary.plain("[\"to eat\"]")
        assertFalse(plain.isPacked)
        assertEquals("[\"to eat\"]", GlossaryPacker.unpack(plain, null))
        assertEquals(plain, Glossary(plain.bytes.copyOf()))
    }

    @Test
    fun aGlossaryThatInflatesBeyondTheCap_failsCleanly() {
        val samples = (1..300).map(::entry)
        GlossaryPacker(GlossaryPacker.preset(samples)).use { packer ->
            // Some 6 KB of JSON that packs to a few hundred bytes, like a deflate bomb in small.
            val json = entry(7).repeat(20)
            val packed = packer.pack(json)
            assertTrue(packed.isPacked)
            assertThrows(DataFormatException::class.java) {
                GlossaryPacker.unpack(packed, packer.preset, maxBytes = json.length / 2)
            }
            assertEquals(json, GlossaryPacker.unpack(packed, packer.preset, maxBytes = json.toByteArray().size))
        }
    }
}
