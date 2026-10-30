package app.honmani.reader.data.backup

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A book from a backup that isn't in the library: its statistics and lookups are kept under [id],
 * a negative id no library book has, until a book with the same content hash ([key]) is added.
 * [details] is null for books the backup only knew by their statistics (removed from the library
 * before it was made); they wait for nothing, but keep their id so a second restore of the same
 * backup finds them.
 */
@Serializable
data class WaitingBook(val key: String, val id: Long, val details: BackupBookDetails? = null)

/**
 * A reading position from a backup, for ttu when the book next opens. [savedAt] (ms) is about when
 * it was read, so progress sync takes the server's position instead when that is newer.
 */
@Serializable
data class RestoredPosition(val characters: Long, val progress: Float, val savedAt: Long = 0)

/**
 * What a restore left to do once books are back: books waiting to be added again, and reading
 * positions to give ttu (which keeps its own) when each book opens. In app storage, as JSON.
 */
class RestoredBooks(private val file: File) {
    @Serializable
    private data class State(
        val waiting: List<WaitingBook> = emptyList(),
        val positions: Map<String, RestoredPosition> = emptyMap(),
    )

    private val lock = Any()
    private var loaded: State? = null
    private val changes = MutableStateFlow(0)

    /** Waiting books' difficulty by their [WaitingBook.id], for the skill until they are back. */
    val scores: Flow<Map<Long, Int>> = changes.map {
        synchronized(lock) { current() }.waiting
            .mapNotNull { book -> book.details?.score?.takeIf { it > 0 }?.let { book.id to it } }
            .toMap()
    }.flowOn(Dispatchers.IO)

    fun waiting(): List<WaitingBook> = synchronized(lock) { current().waiting }

    /** Adds [books] to the waiting ones (replacing any with the same key). */
    fun addWaiting(books: Collection<WaitingBook>) {
        if (books.isEmpty()) return
        update { state ->
            val keys = books.mapTo(HashSet()) { it.key }
            state.copy(waiting = state.waiting.filter { it.key !in keys } + books)
        }
    }

    /** The waiting book with content hash [hash], still waiting; null if there is none. */
    fun find(hash: String): WaitingBook? =
        synchronized(lock) { current().waiting.firstOrNull { it.key == hash && it.details != null } }

    /** [book] is back in the library: it no longer waits. */
    fun remove(book: WaitingBook) = update { state -> state.copy(waiting = state.waiting - book) }

    fun setPosition(hash: String, position: RestoredPosition) = update { it.copy(positions = it.positions + (hash to position)) }

    /** The position to give ttu for the book with content hash [hash], once. */
    fun takePosition(hash: String): RestoredPosition? {
        var taken: RestoredPosition? = null
        update { state ->
            taken = state.positions[hash]
            if (taken == null) state else state.copy(positions = state.positions - hash)
        }
        return taken
    }

    private fun update(change: (State) -> State) {
        val changed = synchronized(lock) {
            val before = current()
            val after = change(before)
            if (after == before) return@synchronized false
            loaded = after
            save(after)
            true
        }
        if (changed) changes.value++
    }

    /** Under [lock]. */
    private fun current(): State = loaded ?: load().also { loaded = it }

    private fun load(): State = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())

    private fun save(state: State) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(json.encodeToString(State.serializer(), state))
        temporary.renameTo(file)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
