package app.honmani.reader.data.sync

import app.honmani.reader.data.writeTextAtomically
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A reading position the sync server hasn't taken yet. */
@Serializable
data class UnsentPosition(
    val ttuId: Long,
    /** The sync id (KOReader's partial MD5). */
    val document: String,
    /** KOReader's XPointer. */
    val progress: String,
    val percentage: Double,
    /** ttu's characters, to compare with what the server has. */
    val characters: Long,
    /** When it was read, in milliseconds since 1970 (this phone's clock). */
    val readAt: Long,
    /**
     * Another device saved an earlier place in the book after this was read: not sent, the
     * reader chooses when the book opens next ([ProgressSync.pull]).
     */
    val held: Boolean = false,
)

/**
 * The newest unsent position per book, kept in a file so that pages read without a connection
 * (away from a home server, say) still reach the server when the app runs again. Not backed up:
 * the positions belong to this phone's sync account.
 */
class UnsentPositions(private val file: File) {
    private var positions: Map<String, UnsentPosition>? = null

    @Synchronized
    fun all(): List<UnsentPosition> = load().values.toList()

    @Synchronized
    fun find(document: String): UnsentPosition? = load()[document]

    @Synchronized
    fun put(position: UnsentPosition) = save(load() + (position.document to position))

    /** Removes [position], unless a newer one replaced it meanwhile. */
    @Synchronized
    fun remove(position: UnsentPosition) {
        val saved = load()
        if (saved[position.document] == position) save(saved - position.document)
    }

    /** Keeps [position] without sending it ([UnsentPosition.held]), unless a newer one replaced it. */
    @Synchronized
    fun hold(position: UnsentPosition) {
        val saved = load()
        if (saved[position.document] == position) save(saved + (position.document to position.copy(held = true)))
    }

    @Synchronized
    fun forget(document: String) {
        val saved = load()
        if (document in saved) save(saved - document)
    }

    @Synchronized
    fun clear() {
        positions = emptyMap()
        file.delete()
    }

    private fun load(): Map<String, UnsentPosition> = positions ?: try {
        if (file.isFile) json.decodeFromString<List<UnsentPosition>>(file.readText()).associateBy { it.document } else emptyMap()
    } catch (_: IOException) {
        emptyMap()
    } catch (_: SerializationException) {
        emptyMap()
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }.also { positions = it }

    private fun save(saved: Map<String, UnsentPosition>) {
        positions = saved
        try {
            if (saved.isEmpty()) {
                file.delete()
                return
            }
            writeTextAtomically(file, json.encodeToString(saved.values.toList()))
        } catch (_: IOException) {
            // Kept in memory until the app closes.
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
