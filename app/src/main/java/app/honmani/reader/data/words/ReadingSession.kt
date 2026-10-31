package app.honmani.reader.data.words

/** The words looked up in the latest reading session; [start] is null when nothing was looked up yet. */
data class SessionWords(val start: Long?, val words: List<WordEntity>)

/**
 * A reading session is a run of lookups where no two are [GAP_MS] or more apart. The latest
 * session is worked out from the lookups already recorded, so it needs no state of its own and
 * survives the app being closed. It stays "the latest" until the next lookup starts a new one.
 */
object ReadingSession {
    /** A pause this long (or longer) between two lookups ends a session. */
    const val GAP_MS = 30 * 60 * 1000L

    /** How many of the latest lookups are looked at; a session longer than this starts at the oldest of them. */
    const val MAX_LOOKUPS = 2000

    /** When the latest session began, from lookup times [newestFirst]; null if there are none. */
    fun start(newestFirst: List<Long>): Long? {
        var start = newestFirst.firstOrNull() ?: return null
        for (time in newestFirst.drop(1)) {
            if (start - time >= GAP_MS) break
            start = minOf(start, time)
        }
        return start
    }
}
