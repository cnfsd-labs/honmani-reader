package app.honmani.reader.data.stats

import java.time.LocalDate
import kotlin.math.sqrt

/** The reading level: earned with characters read, so it goes up with reading and down only when a book's statistics are deleted. */
data class ReadingLevel(
    val level: Int,
    val characters: Long,
    /** Characters at which [level] began and at which the next one begins. */
    val levelStart: Long,
    val nextLevelAt: Long,
) {
    /** How far into the level, 0 to 1. */
    val fraction: Float get() = ((characters - levelStart).toFloat() / (nextLevelAt - levelStart)).coerceIn(0f, 1f)
}

enum class MilestoneKind {
    CHARACTERS,
    HOURS,
    BOOKS,
    DAYS,
    WORDS_LEARNED,
    ANKI,

    /** Characters per hour over [Progression.SPEED_DAYS] days. */
    SPEED,
}

/** One goal: reached once [current] is at least [goal]. */
data class Milestone(
    val kind: MilestoneKind,
    val goal: Long,
    val current: Long,
    /** When it was reached: from the data where it tells, else the day the app first saw it reached. */
    val reachedOn: LocalDate?,
) {
    /** Stable, for the seen dates kept on the phone. */
    val id: String get() = "${kind.name.lowercase()}_$goal"

    val reached: Boolean get() = current >= goal

    val fraction: Float get() = (current.toFloat() / goal).coerceIn(0f, 1f)
}

/** What the milestones are computed from. */
data class ProgressionData(
    /** Reading per day, all books together, in any order. */
    val days: List<DayTotal>,
    /** One entry per finished book: the day it was finished, null where that isn't known. */
    val finished: List<LocalDate?>,
    val wordsLearned: Int,
    val ankiCards: Int,
)

/**
 * The reading level and milestones, computed from the reading data each time; nothing here is
 * stored except, for milestones the data has no date for, the day they were first seen.
 */
object Progression {
    const val SPEED_DAYS = 14

    private const val LEVEL_STEP = 2_000L

    val TIERS: Map<MilestoneKind, List<Long>> = mapOf(
        MilestoneKind.CHARACTERS to listOf(10_000L, 100_000, 250_000, 500_000, 1_000_000, 2_000_000, 5_000_000),
        MilestoneKind.HOURS to listOf(1L, 10, 50, 100, 250, 500),
        MilestoneKind.BOOKS to listOf(1L, 5, 10, 25, 50, 100),
        MilestoneKind.DAYS to listOf(7L, 30, 100, 365),
        MilestoneKind.WORDS_LEARNED to listOf(10L, 100, 500, 1_000),
        MilestoneKind.ANKI to listOf(1L),
        MilestoneKind.SPEED to listOf(5_000L, 10_000, 15_000, 20_000),
    )

    /** Characters needed for [level]: 2,000 · (level − 1)², so level 25 is about a million. */
    fun charactersFor(level: Int): Long = LEVEL_STEP * (level - 1).toLong() * (level - 1)

    fun level(characters: Long): ReadingLevel {
        val total = characters.coerceAtLeast(0)
        var level = sqrt(total.toDouble() / LEVEL_STEP).toInt() + 1
        // The square root may round either way at a level's exact start.
        while (charactersFor(level + 1) <= total) level++
        while (level > 1 && charactersFor(level) > total) level--
        return ReadingLevel(level, total, charactersFor(level), charactersFor(level + 1))
    }

    /**
     * Every milestone of every kind, in [TIERS] order. [seen] holds the days milestones were first
     * seen reached; it dates those the data can't, and a reached one missing from it counts as
     * reached [today].
     */
    fun milestones(data: ProgressionData, seen: Map<String, LocalDate>, today: LocalDate): List<Milestone> {
        val days = data.days.groupBy { it.day }
            .map { (day, list) -> DayTotal(day, list.sumOf { it.seconds }, list.sumOf { it.characters }) }
            .sortedBy { it.day }
        val readingDays = days.filter(ReadingStats::isReadingDay).map { it.day }
        val finishedDates = data.finished.filterNotNull().sorted()
        val speeds = speeds(days, today)

        return TIERS.flatMap { (kind, goals) ->
            goals.map { goal ->
                val (current, fromData) = when (kind) {
                    MilestoneKind.CHARACTERS -> days.sumOf { it.characters } to firstCumulative(days, goal) { it.characters }
                    MilestoneKind.HOURS -> days.sumOf { it.seconds } / 3_600 to firstCumulative(days, goal * 3_600) { it.seconds }
                    MilestoneKind.BOOKS -> data.finished.size.toLong() to
                        // Only datable when enough books have a date; the others count, undated.
                        finishedDates.getOrNull(goal.toInt() - 1)?.takeIf { finishedDates.size == data.finished.size }
                    MilestoneKind.DAYS -> readingDays.size.toLong() to readingDays.getOrNull(goal.toInt() - 1)
                    MilestoneKind.WORDS_LEARNED -> data.wordsLearned.toLong() to null
                    MilestoneKind.ANKI -> data.ankiCards.toLong() to null
                    MilestoneKind.SPEED -> (speeds.maxOfOrNull { it.second } ?: 0L) to
                        speeds.firstOrNull { it.second >= goal }?.first
                }
                val milestone = Milestone(kind, goal, current, reachedOn = null)
                if (milestone.reached) milestone.copy(reachedOn = fromData ?: seen[milestone.id] ?: today) else milestone
            }
        }
    }

    /** The next goal of each kind, closest to done first. */
    fun next(milestones: List<Milestone>, count: Int = 3): List<Milestone> =
        milestones.filterNot { it.reached }
            .groupBy { it.kind }
            .map { (_, goals) -> goals.minBy { it.goal } }
            .sortedByDescending { it.fraction }
            .take(count)

    /**
     * Columns for [count] tiles, at most [maxColumns], so that the rows come out as even as they
     * can: seven goals in four columns rather than six and one. A single tile takes half a row.
     */
    fun columns(count: Int, maxColumns: Int): Int {
        if (count <= 1) return (maxColumns / 2).coerceAtLeast(1)
        val rows = (count + maxColumns - 1) / maxColumns
        return (count + rows - 1) / rows
    }

    /** The first day on which the running total of [value] reached [goal]. */
    private fun firstCumulative(days: List<DayTotal>, goal: Long, value: (DayTotal) -> Long): LocalDate? {
        var total = 0L
        for (day in days) {
            total += value(day)
            if (total >= goal) return day.day
        }
        return null
    }

    /**
     * For each day from the first reading day to [today], the speed over the [SPEED_DAYS] days
     * ending that day, where there was enough reading to tell.
     */
    private fun speeds(days: List<DayTotal>, today: LocalDate): List<Pair<LocalDate, Long>> {
        val first = days.firstOrNull()?.day ?: return emptyList()
        val byDay = days.associateBy { it.day }
        val result = mutableListOf<Pair<LocalDate, Long>>()
        var seconds = 0L
        var characters = 0L
        var day = first
        val last = maxOf(today, days.last().day)
        while (day <= last) {
            byDay[day]?.let {
                seconds += it.seconds
                characters += it.characters
            }
            byDay[day.minusDays(SPEED_DAYS.toLong())]?.let {
                seconds -= it.seconds
                characters -= it.characters
            }
            ReadingStats.speed(seconds, characters)?.let { result += day to it }
            day = day.plusDays(1)
        }
        return result
    }
}
