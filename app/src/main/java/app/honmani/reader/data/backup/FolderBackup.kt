package app.honmani.reader.data.backup

import android.content.SharedPreferences
import app.honmani.reader.diagnostics.logWarning
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The folder the reader chose, as the document provider (Android's file picker) offers it. */
interface FolderTarget {
    /** Keeps the right to read and write [folder] across restarts; throws if it can't. */
    fun grant(folder: String)

    /** Gives that right up again; never throws. */
    fun release(folder: String)

    /** The file called [name] in [folder] (its address), made if it isn't there yet. */
    fun findOrCreate(folder: String, name: String): String

    /** Opens [file] to be overwritten. */
    fun open(file: String): OutputStream
}

/** How the copy of the backup in the chosen folder stands. */
sealed interface FolderBackupState {
    data object Off : FolderBackupState

    /** A folder is chosen; [lastAt] is when the last copy was made (epoch ms), null before the first. */
    data class On(val lastAt: Long?) : FolderBackupState

    /** The last copy failed: the folder is gone, or the right to write to it. */
    data object Unavailable : FolderBackupState
}

/**
 * Also copies Honmani's own backup ([AutoBackup]) into a folder the reader chose (on the phone,
 * an SD card or a cloud app's folder), as one file with a fixed name that is overwritten. It runs
 * right after [AutoBackup] wrote a new backup, so it adds no schedule of its own. A failure only
 * shows as [FolderBackupState.Unavailable]. Which folder is tied to this phone: it lives in
 * the "device" preferences, which are never backed up.
 */
class FolderBackup(
    private val device: SharedPreferences,
    private val target: FolderTarget,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<FolderBackupState> = _state.asStateFlow()

    private fun read(): FolderBackupState = when {
        device.getString(KEY_FOLDER, null) == null -> FolderBackupState.Off
        device.getBoolean(KEY_FAILED, false) -> FolderBackupState.Unavailable
        else -> FolderBackupState.On(device.getLong(KEY_LAST, 0L).takeIf { it > 0 })
    }

    /** Starts copying into [folder] (a tree address the reader picked). Returns whether it was accepted. */
    fun choose(folder: String): Boolean {
        try {
            target.grant(folder)
        } catch (e: Exception) {
            logWarning("Honmani.backup", "Folder not accepted", e)
            return false
        }
        val earlier = device.getString(KEY_FOLDER, null)
        if (earlier != null && earlier != folder) target.release(earlier)
        device.edit()
            .putString(KEY_FOLDER, folder)
            .remove(KEY_FILE)
            .remove(KEY_LAST)
            .putBoolean(KEY_FAILED, false)
            .apply()
        _state.value = read()
        return true
    }

    /** Stops copying: gives the folder's right back and forgets it. The copy already there stays. */
    fun disable() {
        device.getString(KEY_FOLDER, null)?.let(target::release)
        device.edit().remove(KEY_FOLDER).remove(KEY_FILE).remove(KEY_LAST).remove(KEY_FAILED).apply()
        _state.value = FolderBackupState.Off
    }

    /** Copies [source], the backup just written, into the folder (if there is one); never throws. */
    suspend fun copy(source: File) {
        mutex.withLock { copyLocked(source) }
    }

    private suspend fun copyLocked(source: File) {
        val folder = device.getString(KEY_FOLDER, null) ?: return
        withContext(Dispatchers.IO) {
            val worked = try {
                write(folder, source)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Permission lost, folder deleted, storage full, a provider that misbehaves.
                logWarning("Honmani.backup", "Copy into the backup folder failed", e)
                false
            }
            val editor = device.edit().putBoolean(KEY_FAILED, !worked)
            if (worked) editor.putLong(KEY_LAST, now())
            editor.apply()
            _state.value = read()
        }
    }

    private fun write(folder: String, source: File) {
        var file = device.getString(KEY_FILE, null)
        for (attempt in 0..1) {
            val remembered = file != null
            try {
                if (file == null) {
                    file = target.findOrCreate(folder, FILE_NAME)
                    device.edit().putString(KEY_FILE, file).apply()
                }
                val output = target.open(file)
                output.use { out -> source.inputStream().use { it.copyTo(out) } }
                return
            } catch (e: IOException) {
                // The file may have been deleted since: make it once more.
                if (!remembered || attempt == 1) throw e
                file = null
                device.edit().remove(KEY_FILE).apply()
            }
        }
    }

    companion object {
        const val FILE_NAME = AutoBackup.FILE_NAME

        private const val KEY_FOLDER = "folder_backup_uri"
        private const val KEY_FILE = "folder_backup_file"
        private const val KEY_LAST = "folder_backup_last"
        private const val KEY_FAILED = "folder_backup_failed"
    }
}
