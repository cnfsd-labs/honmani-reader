package app.honmani.reader.data.stats

import androidx.annotation.StringRes
import app.honmani.reader.R

/** A name for each five levels of [Progression]: only a title, nothing is locked by level. */
object LevelTitles {
    /** The last level that has a title of its own; every level from here on is a Honmani Legend. */
    const val LAST = 100

    private val TITLES = listOf(
        R.string.level_title_1, R.string.level_title_5, R.string.level_title_10, R.string.level_title_15,
        R.string.level_title_20, R.string.level_title_25, R.string.level_title_30, R.string.level_title_35,
        R.string.level_title_40, R.string.level_title_45, R.string.level_title_50, R.string.level_title_55,
        R.string.level_title_60, R.string.level_title_65, R.string.level_title_70, R.string.level_title_75,
        R.string.level_title_80, R.string.level_title_85, R.string.level_title_90, R.string.level_title_95,
        R.string.level_title_100,
    )

    /** The level [level]'s title begins at: 1, 5, 10, ... 100. */
    fun startOf(level: Int): Int = if (level < 5) 1 else minOf(level / 5 * 5, LAST)

    private fun indexOf(level: Int) = if (level < 5) 0 else minOf(level / 5, LAST / 5)

    @StringRes
    fun titleFor(level: Int): Int = TITLES[indexOf(level)]

    /** The level at which the next title comes, or null once the last one is reached. */
    fun nextStart(level: Int): Int? = if (level < 5) 5 else (startOf(level) + 5).takeIf { startOf(level) < LAST }

    /** The title that comes at [nextStart]; null once the last one is reached. */
    @StringRes
    fun nextTitleFor(level: Int): Int? = nextStart(level)?.let(::titleFor)
}
