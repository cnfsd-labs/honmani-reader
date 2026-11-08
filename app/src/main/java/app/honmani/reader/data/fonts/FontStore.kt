package app.honmani.reader.data.fonts

import app.honmani.reader.data.dictionary.FailureReason
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a catalog font stands on this phone. */
sealed interface FontState {
    data object Available : FontState

    /** [progress] from 0 to 1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : FontState

    data object Installed : FontState

    data class Failed(val reason: FailureReason) : FontState
}

/**
 * The downloaded reader fonts, in [dir] under their catalog file names (served to the reader by
 * `FontPathHandler`). A few MB each, so they download in the app's scope rather than as
 * WorkManager jobs; an interrupted download leaves only a `.part` file, removed on the next try.
 */
class FontStore(
    private val dir: File,
    private val scope: CoroutineScope,
    /** Downloads a URL into a file, reporting bytes done and the total (-1 if unknown). */
    private val download: suspend (url: String, destination: File, onProgress: suspend (Long, Long) -> Unit) -> Unit,
    private val catalog: List<CatalogFont> = FontCatalog.fonts,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _states = MutableStateFlow(catalog.associate { it.id to stateOnDisk(it) })
    val states: StateFlow<Map<String, FontState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    fun file(font: CatalogFont): File = File(dir, font.fileName)

    /** The catalog fonts that are on this phone. */
    fun installed(): List<CatalogFont> = catalog.filter { _states.value[it.id] == FontState.Installed }

    /** Starts downloading [font]; does nothing while it downloads or once it is installed. */
    fun download(font: CatalogFont) {
        val state = _states.value[font.id]
        if (state is FontState.Downloading || state == FontState.Installed) return
        set(font, FontState.Downloading(null))
        jobs[font.id] = scope.launch {
            val part = File(dir, font.fileName + ".part")
            try {
                withContext(io) { dir.mkdirs() }
                download(font.url, part) { done, total ->
                    val size = if (total > 0) total else font.sizeBytes
                    set(font, FontState.Downloading((done.toFloat() / size).coerceIn(0f, 1f)))
                }
                withContext(io) {
                    if (sha256(part) != font.sha256) throw IOException("The downloaded file is damaged")
                    if (!part.renameTo(file(font))) throw IOException("Could not save the font")
                }
                set(font, FontState.Installed)
            } catch (e: IOException) {
                part.delete()
                // A cancelled download (the font was deleted meanwhile) is not a failure.
                if (isActive) set(font, FontState.Failed(FailureReason.of(e)))
            } finally {
                jobs.remove(font.id)
            }
        }
    }

    /** Removes [font] (or stops its download). */
    fun delete(font: CatalogFont) {
        jobs.remove(font.id)?.cancel()
        scope.launch {
            withContext(io) {
                file(font).delete()
                File(dir, font.fileName + ".part").delete()
            }
            set(font, FontState.Available)
        }
    }

    private fun set(font: CatalogFont, state: FontState) = _states.update { it + (font.id to state) }

    private fun stateOnDisk(font: CatalogFont): FontState =
        if (file(font).isFile) FontState.Installed else FontState.Available

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
