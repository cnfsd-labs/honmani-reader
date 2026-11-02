package app.honmani.reader.lookup

import android.content.Context
import java.util.BitSet

/** A kanji component of the radical search ([glyph] is its display form) with its stroke count. */
data class Radical(val glyph: String, val strokes: Int)

/** What [RadicalIndex.search] found for a set of selected radicals. */
data class RadicalMatch(
    /** The kanji made of all selected radicals, fewest strokes first; empty when none is selected. */
    val kanji: List<String>,
    /** The radicals that still lead to a kanji together with the selected ones (the selected ones too). */
    val possible: Set<String>,
)

/**
 * Multi-radical kanji lookup over EDRDG's RADKFILEX/KRADFILE data (`assets/radicals.txt`, made by
 * `scripts/radicals/build_radicals.py`; format described there).
 */
class RadicalIndex private constructor(
    val radicals: List<Radical>,
    private val kanji: List<String>,
    private val parts: List<BitSet>,
) {
    private val indexOf = radicals.withIndex().associate { it.value.glyph to it.index }
    private val everything = radicals.map { it.glyph }.toSet()

    /** The kanji with all [selected] radicals, in the file's order (strokes, then frequency). */
    fun search(selected: Collection<String>): RadicalMatch {
        val wanted = BitSet()
        for (glyph in selected) wanted.set(indexOf[glyph] ?: return RadicalMatch(emptyList(), emptySet()))
        if (wanted.isEmpty) return RadicalMatch(emptyList(), everything)
        val found = ArrayList<String>()
        val remaining = BitSet()
        for (i in kanji.indices) {
            val has = parts[i]
            if (!contains(has, wanted)) continue
            found += kanji[i]
            remaining.or(has)
        }
        val possible = HashSet<String>()
        var bit = remaining.nextSetBit(0)
        while (bit >= 0) {
            possible += radicals[bit].glyph
            bit = remaining.nextSetBit(bit + 1)
        }
        return RadicalMatch(found, possible)
    }

    private fun contains(has: BitSet, wanted: BitSet): Boolean {
        var bit = wanted.nextSetBit(0)
        while (bit >= 0) {
            if (!has[bit]) return false
            bit = wanted.nextSetBit(bit + 1)
        }
        return true
    }

    companion object {
        private const val ASSET = "radicals.txt"

        @Volatile
        private var cached: RadicalIndex? = null

        /** The index from the app's assets, read once; reads the file, so not for the main thread. */
        fun load(context: Context): RadicalIndex = cached ?: synchronized(this) {
            cached ?: context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
                .also { cached = it }
        }

        /** Parses the asset: `r<TAB>radical<TAB>strokes` lines, then one `<kanji><radicals>` line per kanji. */
        fun parse(text: String): RadicalIndex {
            val radicals = ArrayList<Radical>()
            val kanji = ArrayList<String>()
            val masks = ArrayList<BitSet>()
            val index = HashMap<Char, Int>()
            for (line in text.lineSequence()) {
                if (line.isEmpty() || line[0] == '#' || line[0] == '@') continue
                if (line.startsWith("r\t")) {
                    val fields = line.split('\t')
                    index[fields[1][0]] = radicals.size
                    radicals += Radical(fields[1], fields[2].toInt())
                    continue
                }
                val set = BitSet()
                for (i in 1 until line.length) index[line[i]]?.let(set::set)
                kanji += line.substring(0, 1)
                masks += set
            }
            return RadicalIndex(radicals, kanji, masks)
        }
    }
}
