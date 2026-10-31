package app.honmani.reader.data.stats

import java.time.LocalDate

/** Reading on one day (or, in [PeriodSummary.chart] for all time, one week), all books together. */
data class DayTotal(val day: LocalDate, val seconds: Long, val characters: Long)

/** The time span the statistics screen shows. */
enum class StatsPeriod { TODAY, WEEK, ALL }

/** Reading characters per hour in one week, for the speed trend; null for weeks with too little reading. */
data class WeekSpeed(val start: LocalDate, val charactersPerHour: Long?)

/** What the statistics screen shows for one [StatsPeriod]. */
data class PeriodSummary(
    val period: StatsPeriod,
    val seconds: Long,
    val characters: Long,
    /** Days with reading in the period. */
    val daysRead: Int,
    /** Days in a row with reading, up to today (or yesterday, if today has none yet). */
    val streak: Int,
    /**
     * Characters per hour: today's, the last 7 days', or (for all time) the last
     * [ReadingStats.RECENT_DAYS] days': speed counts what was read recently. Null until there
     * is enough reading to say.
     */
    val charactersPerHour: Long?,
    /** The speed to compare with: the 7 days before today or before the last 7 days; for all time, the first weeks read. */
    val earlierCharactersPerHour: Long?,
    /** Bars, oldest first: the last 7 days, or for all time the last [ReadingStats.TREND_WEEKS] weeks. Empty for today. */
    val chart: List<DayTotal>,
    /** All time only: speed per week over the last [ReadingStats.TREND_WEEKS] weeks, oldest first. */
    val speedTrend: List<WeekSpeed>,
    /** Today only: reading per book (book id to its total), most time first. */
    val books: List<Pair<Long, DayTotal>>,
    /** Whether anything at all has been counted, in any period. */
    val anyReading: Boolean,
)

/** One book's reading. */
data class BookReading(
    val seconds: Long,
    val characters: Long,
    val daysRead: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val charactersPerHour: Long?,
)

object ReadingStats {
    /** Speed for all time counts the last two weeks: how fast the reader reads now. */
    const val RECENT_DAYS = 14

    const val TREND_WEEKS = 12

    /** A day counts as a reading day from one minute or any characters. */
    private const val MIN_DAY_SECONDS = 60L

    /** Speed is shown from ten minutes of reading. */
    private const val MIN_SPEED_SECONDS = 600L

    fun period(rows: List<ReadingDayEntity>, today: LocalDate, period: StatsPeriod): PeriodSummary {
        val byDay = rows.groupBy { LocalDate.parse(it.day) }.mapValues { (day, list) ->
            DayTotal(day, list.sumOf { it.seconds }, list.sumOf { it.characters })
        }
        fun total(day: LocalDate) = byDay[day] ?: DayTotal(day, 0, 0)

        /** The [count] days ending [daysAgo] days before today, oldest first. */
        fun days(count: Int, daysAgo: Long = 0) = (count - 1 downTo 0).map { total(today.minusDays(it + daysAgo)) }
        fun speedOf(days: List<DayTotal>) = speed(days.sumOf { it.seconds }, days.sumOf { it.characters })

        val inPeriod = when (period) {
            StatsPeriod.TODAY -> listOf(total(today))
            StatsPeriod.WEEK -> days(7)
            StatsPeriod.ALL -> byDay.values.sortedBy { it.day }
        }
        val (speedNow, speedBefore) = when (period) {
            StatsPeriod.TODAY -> speedOf(inPeriod) to speedOf(days(7, daysAgo = 1))
            StatsPeriod.WEEK -> speedOf(inPeriod) to speedOf(days(7, daysAgo = 7))
            StatsPeriod.ALL -> speedOf(days(RECENT_DAYS)) to firstSpeed(byDay.values.sortedBy { it.day })
        }
        val trend = if (period == StatsPeriod.ALL) {
            (TREND_WEEKS - 1 downTo 0).map { week -> days(7, daysAgo = week * 7L) }
        } else {
            emptyList()
        }
        return PeriodSummary(
            period = period,
            seconds = inPeriod.sumOf { it.seconds },
            characters = inPeriod.sumOf { it.characters },
            daysRead = inPeriod.count(::isReadingDay),
            streak = streak(byDay, today),
            charactersPerHour = speedNow,
            // For all time, only once the first weeks and the recent ones don't overlap.
            earlierCharactersPerHour = speedBefore.takeIf {
                // No days yet (nothing read): nothing to compare, and no first day.
                period != StatsPeriod.ALL || byDay.keys.minOrNull()?.let { it <= today.minusDays(2L * RECENT_DAYS - 1) } == true
            },
            chart = when (period) {
                StatsPeriod.TODAY -> emptyList()
                StatsPeriod.WEEK -> inPeriod
                StatsPeriod.ALL -> trend.map { week -> DayTotal(week.first().day, week.sumOf { it.seconds }, week.sumOf { it.characters }) }
            },
            speedTrend = trend.map { week -> WeekSpeed(week.first().day, speedOf(week)) },
            books = if (period == StatsPeriod.TODAY) {
                rows.filter { LocalDate.parse(it.day) == today && (it.seconds > 0 || it.characters > 0) }
                    .groupBy { it.bookId }
                    .map { (book, list) -> book to DayTotal(today, list.sumOf { it.seconds }, list.sumOf { it.characters }) }
                    .sortedByDescending { it.second.seconds }
            } else {
                emptyList()
            },
            anyReading = rows.any { it.seconds > 0 || it.characters > 0 },
        )
    }

    fun book(rows: List<ReadingDayEntity>): BookReading {
        val days = rows.filter { it.seconds >= MIN_DAY_SECONDS || it.characters > 0 }.map { LocalDate.parse(it.day) }
        val seconds = rows.sumOf { it.seconds }
        val characters = rows.sumOf { it.characters }
        return BookReading(
            seconds = seconds,
            characters = characters,
            daysRead = days.distinct().size,
            firstDay = days.minOrNull(),
            lastDay = days.maxOrNull(),
            charactersPerHour = speed(seconds, characters),
        )
    }

    /** The speed over the first [RECENT_DAYS] days from the first day read. */
    private fun firstSpeed(days: List<DayTotal>): Long? {
        val start = days.firstOrNull()?.day ?: return null
        val first = days.takeWhile { it.day < start.plusDays(RECENT_DAYS.toLong()) }
        return speed(first.sumOf { it.seconds }, first.sumOf { it.characters })
    }

    internal fun isReadingDay(total: DayTotal) = total.seconds >= MIN_DAY_SECONDS || total.characters > 0

    private fun streak(byDay: Map<LocalDate, DayTotal>, today: LocalDate): Int {
        fun read(day: LocalDate) = byDay[day]?.let(::isReadingDay) == true
        var day = if (read(today)) today else today.minusDays(1)
        var count = 0
        while (read(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    internal fun speed(seconds: Long, characters: Long): Long? =
        if (seconds >= MIN_SPEED_SECONDS && characters > 0) {
            // Under one character an hour rounds to 0, which is no speed.
            (characters * 3_600 / seconds).takeIf { it > 0 }
        } else {
            null
        }
}
