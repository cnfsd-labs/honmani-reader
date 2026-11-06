package app.honmani.reader.data.stats

import java.time.LocalDate

/** How today stands against the daily reading goal. */
data class GoalProgress(
    val goalMinutes: Int,
    /** Seconds read today, all books together. */
    val secondsToday: Long,
    /**
     * Days in a row that reached the goal, up to today; if today hasn't yet, up to yesterday, so
     * the streak stays until the day is over.
     */
    val streak: Int,
    /** The longest run of days in a row that reached the goal, at least [streak]. */
    val bestStreak: Int = streak,
) {
    val goalSeconds: Long get() = goalMinutes * 60L

    val met: Boolean get() = secondsToday >= goalSeconds

    /** How far to the goal, 0 to 1. */
    val fraction: Float get() = (secondsToday.toFloat() / goalSeconds).coerceIn(0f, 1f)
}

/** The daily reading goal, computed from the per-day reading time each time; nothing is stored. */
object ReadingGoal {
    const val DEFAULT_MINUTES = 20

    /** What Settings offers. */
    val PRESETS = listOf(10, 20, 30, 45, 60)

    fun progress(rows: List<ReadingDayEntity>, today: LocalDate, goalMinutes: Int): GoalProgress {
        val minutes = goalMinutes.coerceAtLeast(1)
        val goalSeconds = minutes * 60L
        val secondsByDay = HashMap<LocalDate, Long>()
        for (row in rows) secondsByDay.merge(LocalDate.parse(row.day), row.seconds, Long::plus)
        fun met(day: LocalDate) = (secondsByDay[day] ?: 0L) >= goalSeconds
        var day = if (met(today)) today else today.minusDays(1)
        var streak = 0
        while (met(day)) {
            streak++
            day = day.minusDays(1)
        }
        // The longest run over all recorded days, counted by walking the days that reached the goal.
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (reached in secondsByDay.filterValues { it >= goalSeconds }.keys.sorted()) {
            run = if (previous != null && previous.plusDays(1) == reached) run + 1 else 1
            best = maxOf(best, run)
            previous = reached
        }
        return GoalProgress(minutes, secondsByDay[today] ?: 0L, streak, bestStreak = maxOf(best, streak))
    }
}
