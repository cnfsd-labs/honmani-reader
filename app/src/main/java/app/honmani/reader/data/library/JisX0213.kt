package app.honmani.reader.data.library

import java.io.DataInputStream

/**
 * JIS X 0213 kanji by plane, row and cell (Aozora's 第3水準1-15-62, 第4水準2-12-34), from the kJIS0213
 * field of Unicode's Unihan database (Unicode License v3). The table `jisx0213.bin` is a Java
 * resource built by `scripts/jisx0213/build_jisx0213.py`; it is read once, on first use.
 */
object JisX0213 {
    private const val CELLS = 94
    private const val POINT_BITS = 18

    private val planes: Array<IntArray> by lazy { load() }

    /** The character at [plane]-[row]-[cell], or null when it is not a kanji of the table. */
    fun character(plane: Int, row: Int, cell: Int): String? {
        if (plane !in 1..2 || row !in 1..CELLS || cell !in 1..CELLS) return null
        val records = planes[plane - 1]
        val key = (row - 1) * CELLS + cell - 1
        var low = 0
        var high = records.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val found = records[middle] ushr POINT_BITS
            when {
                found < key -> low = middle + 1
                found > key -> high = middle - 1
                else -> return String(Character.toChars(records[middle] and ((1 shl POINT_BITS) - 1)))
            }
        }
        return null
    }

    private fun load(): Array<IntArray> {
        val stream = checkNotNull(JisX0213::class.java.getResourceAsStream("jisx0213.bin")) { "jisx0213.bin missing" }
        DataInputStream(stream.buffered()).use { input ->
            while (input.readUnsignedByte() != '\n'.code) Unit // the header line
            val firstPlane = input.readUnsignedShort()
            val records = generateSequence { if (input.available() > 0) input.readInt() else null }.toList()
            return arrayOf(
                records.subList(0, firstPlane).toIntArray(),
                records.subList(firstPlane, records.size).toIntArray(),
            )
        }
    }
}
