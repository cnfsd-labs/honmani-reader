package app.honmani.reader.data.stats

/** A level the reader just reached, to announce once; [newTitle] when it also begins a new title. */
data class LevelMoment(val level: Int, val newTitle: Boolean)

/** What to do about the reading level: [remember] it silently, and/or [announce] a level-up. */
data class LevelDecision(val remember: Int? = null, val announce: LevelMoment? = null)

object LevelMoments {
    /**
     * [celebrated] is the last level announced (null before the first run, or on a fresh restore:
     * that level is taken as already known, so an upgrade or a restore announces nothing).
     * Several levels at once make one announcement for the current level.
     */
    fun decide(celebrated: Int?, current: Int): LevelDecision = when {
        celebrated == null -> LevelDecision(remember = current)
        current <= celebrated -> LevelDecision()
        else -> LevelDecision(announce = LevelMoment(current, newTitle = LevelTitles.startOf(current) > celebrated))
    }
}
