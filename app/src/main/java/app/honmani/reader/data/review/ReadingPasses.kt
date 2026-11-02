package app.honmani.reader.data.review

/**
 * Due words that passed while reading ([ReviewRepository.onRead]) since the summary was last
 * taken, for "n due words passed while reading" when a book is closed. In memory only.
 */
class ReadingPasses {
    private val passed = HashSet<Long>()

    @Synchronized
    fun add(wordIds: Collection<Long>) {
        passed += wordIds
    }

    /** How many words passed, and starts counting again. */
    @Synchronized
    fun take(): Int = passed.size.also { passed.clear() }
}
