package app.honmani.reader.data.examples

import app.honmani.reader.data.dictionary.FailureReason
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The optional example sentences file: where to get it and what it must look like. */
data class ExampleCatalog(
    val url: String,
    /** SHA-256 of the gzip file, lower-case hex. */
    val sha256: String,
    /** Size of the gzip file, for the progress bar and the question before the download. */
    val sizeBytes: Long,
) {
    companion object {
        /**
         * Tatoeba (CC BY 2.0 FR) Japanese sentences with their English translations, made by
         * `scripts/examples/build_examples.py` and published as a release asset. A new data
         * version gets a new release tag and file name; both constants change with it.
         */
        val DEFAULT = ExampleCatalog(
            url = "https://github.com/cnfsd-labs/honmani-reader/releases/download/examples-1/examples-1.db.gz",
            sha256 = "f58915b948aaaef48036cb3ef792be690c3ecda424e5d79e59a1ce6d31d29196",
            sizeBytes = 3_850_748,
        )
    }
}

/** Where the example sentences stand on this phone. */
sealed interface ExampleState {
    data object Available : ExampleState

    /** [progress] from 0 to 1. */
    data class Downloading(val progress: Float) : ExampleState

    data object Installed : ExampleState

    data class Failed(val reason: FailureReason) : ExampleState
}

/**
 * The downloaded example sentences: [file] (a SQLite database, opened read-only by
 * [ExampleSentences]). A few MB, so the download runs in the app's scope. The gzip file is checked
 * against [ExampleCatalog.sha256] before it is unpacked; an interrupted download leaves only
 * `.part` files (named per download), removed on the next try.
 */
class ExampleStore(
    private val dir: File,
    private val scope: CoroutineScope,
    /** Downloads a URL into a file, reporting bytes done and the total (-1 if unknown). */
    private val download: suspend (url: String, destination: File, onProgress: suspend (Long, Long) -> Unit) -> Unit,
    val catalog: ExampleCatalog = ExampleCatalog.DEFAULT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val file = File(dir, FILE_NAME)

    private val _state = MutableStateFlow<ExampleState>(if (file.isFile) ExampleState.Installed else ExampleState.Available)
    val state: StateFlow<ExampleState> = _state.asStateFlow()

    private var job: Job? = null

    /** Numbers the downloads, so a cancelled one still cleaning up never touches the next one's files. */
    private var runs = 0

    /** Starts downloading; does nothing while it downloads or once it is installed. */
    fun download() {
        if (_state.value is ExampleState.Downloading || _state.value == ExampleState.Installed) return
        _state.value = ExampleState.Downloading(0f)
        val run = runs++
        val gz = File(dir, "$FILE_NAME.$run.gz.part")
        val unpacked = File(dir, "$FILE_NAME.$run.part")
        job = scope.launch {
            try {
                withContext(io) {
                    dir.mkdirs()
                    // Left by a download the app was closed in (no other download ran yet in this process).
                    if (run == 0) dir.listFiles { f -> f.name.startsWith(FILE_NAME) && f.name.endsWith(".part") }?.forEach { it.delete() }
                }
                download(catalog.url, gz) { done, total ->
                    val size = if (total > 0) total else catalog.sizeBytes
                    _state.value = ExampleState.Downloading((done.toFloat() / size).coerceIn(0f, 1f))
                }
                withContext(io) {
                    if (sha256(gz) != catalog.sha256) throw IOException("The downloaded file is damaged")
                    try {
                        GZIPInputStream(gz.inputStream().buffered()).use { input ->
                            unpacked.outputStream().use { input.copyTo(it) }
                        }
                    } catch (e: ZipException) {
                        throw IOException("The downloaded file is damaged", e)
                    }
                    // A cancelled download that was busy unpacking must not install itself after all.
                    ensureActive()
                    if (!unpacked.renameTo(file)) throw IOException("Could not save the example sentences")
                    gz.delete()
                }
                _state.value = ExampleState.Installed
            } catch (e: IOException) {
                // A cancelled download is not a failure (the state is set by [delete]).
                if (isActive) _state.value = ExampleState.Failed(FailureReason.of(e))
            } finally {
                gz.delete()
                unpacked.delete()
                if (job === coroutineContext[Job]) job = null
            }
        }
    }

    /** Removes the file, or stops its download. */
    fun delete() {
        job?.cancel()
        job = null
        _state.value = ExampleState.Available
        scope.launch {
            // A stopped download removes its own partial files.
            withContext(io) { file.delete() }
        }
    }

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

    private companion object {
        const val FILE_NAME = "examples.db"
    }
}
