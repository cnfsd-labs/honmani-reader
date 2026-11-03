package app.honmani.reader.ui.reader

import kotlin.math.roundToLong

/** Places in a book as ttu counts them: the reading position is the number of characters before it. */
object BookPlace {
    /** The place at [fraction] (0 to 1) of a book of [total] characters. */
    fun charactersAt(fraction: Float, total: Int): Long =
        (fraction.coerceIn(0f, 1f).toDouble() * total).roundToLong().coerceIn(0L, total.toLong().coerceAtLeast(0))

    /** How far in the book [characters] of [total] is, 0 to 100. */
    fun percent(characters: Long, total: Long): Int =
        if (total <= 0) 0 else (characters * 100 / total).toInt().coerceIn(0, 100)

    /** How far (0 to 1) [characters] of [total] is, for the slider. */
    fun fraction(characters: Long, total: Int): Float =
        if (total <= 0) 0f else (characters.toDouble() / total).toFloat().coerceIn(0f, 1f)

    /** A bookmark's name from the text at its place: the first [length] characters, blanks tidied; null if there is none. */
    fun defaultName(after: String, length: Int = NAME_LENGTH): String? {
        val tidy = after.replace(Regex("\\s+"), " ").trim()
        if (tidy.isEmpty()) return null
        return tidy.substring(0, tidy.offsetByCodePoints(0, minOf(length, tidy.codePointCount(0, tidy.length))))
    }

    const val NAME_LENGTH = 20
}
