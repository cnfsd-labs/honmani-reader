package app.honmani.reader.data.review

/**
 * The daily limit of reviews ("Reviews per day"). Of the cards due, a day shows what is left of
 * the limit after the reviews already done today, most overdue first; the rest carry over to
 * tomorrow. New cards are not counted (the new-words limit governs them), and a card reviewed
 * today that is due again (an Again) is always shown, so a session can finish what it started.
 */
object ReviewLimit {
    /** The stored value for "No limit". */
    const val NO_LIMIT = 0

    /** Reviews a day: 50, 100 (the default), 200 or no limit. */
    val CHOICES = listOf(50, 100, 200, NO_LIMIT)
    const val DEFAULT = 100

    /** [due] (most overdue first) cut to what [limit] still allows after [done], today's reviews. */
    fun <T> apply(due: List<T>, id: (T) -> Long, limit: Int, done: List<ReviewedToday>): List<T> {
        if (limit == NO_LIMIT) return due
        val today = done.mapTo(HashSet()) { it.wordId }
        var room = (limit - done.count { it.carried }).coerceAtLeast(0)
        return due.filter { card ->
            when {
                id(card) in today -> true
                room > 0 -> true.also { room-- }
                else -> false
            }
        }
    }
}
