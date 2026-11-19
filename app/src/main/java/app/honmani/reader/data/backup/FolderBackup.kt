package app.honmani.reader.data.backup

import android.content.SharedPreferences
import app.honmani.reader.diagnostics.logWarning
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FolderFile(val address: String, val name: String, val size: Long, val modified: Long)

/** The folder the reader chose, as the document provider (Android's file picker) offers it. */
interface FolderTarget {
    /** Keeps the right to read and write [folder] across restarts; throws if it can't. */
    fun grant(folder: String)

    /** Gives that right up again; never throws. */
    fun release(folder: String)

    /**
     * The file called [name] in [folder] (its address), or null if there is none. Throws when the
     * provider can't say for sure (it is gone or busy, or a cloud folder is still loading).
     */
    fun find(folder: String, name: String): String?

    /** Makes a new, empty file called [name] in [folder]; the provider may alter the name. */
    fun create(folder: String, name: String): String

    /** Opens [file] to be overwritten. */
    fun open(file: String): OutputStream

    /** Deletes [file]; returns whether it is gone. */
    fun delete(file: String): Boolean

    /** The files directly in [folder]. */
    fun list(folder: String): List<FolderFile>

    /** Opens [file] to be read. */
    fun read(file: String): InputStream

    /** Whether the provider can rename [file]. */
    fun canRename(file: String): Boolean

    /** Renames [file] to [name] (which must be free) and returns its new address; throws if it can't. */
    fun rename(file: String, name: String): String

    /** The folder's name as the reader knows it, or null if the provider doesn't say. */
    fun displayName(folder: String): String? = null
}

/**
 * Writes [name] into [folder] so that an interruption (storage full, the card pulled, the process
 * killed) leaves the earlier file untouched: [write] fills a finished temporary file called
 * [tempName] first (its name must end the way the provider expects for a zip), and only then is the
 * old file deleted and the new one renamed. A provider that can't rename gets the old file
 * overwritten in place instead, while the finished temporary file still exists. A temporary file
 * left by an earlier run (possibly the only finished copy) is deleted only once this one succeeded.
 */
internal fun FolderTarget.replaceFile(folder: String, name: String, tempName: String, write: (OutputStream) -> Unit) {
    val leftover = find(folder, tempName)
    // The provider gives the new file a free name next to the leftover one.
    val temp = create(folder, tempName)
    var complete = false
    var overwriting = false
    try {
        open(temp).use(write)
        complete = true
        val old = find(folder, name)
        if (canRename(temp)) {
            if (old != null && !delete(old)) throw IOException("Cannot replace $old")
            rename(temp, name)
        } else {
            overwriting = true
            open(old ?: create(folder, name)).use(write)
            delete(temp)
        }
    } catch (e: Exception) {
        // A finished temporary file may be all that is left if the swap failed: keep it then, also
        // when the overwrite in place died half way and left a damaged old file.
        runCatching {
            if (!complete || (!overwriting && find(folder, name) != null)) delete(temp)
        }
        throw e
    }
    if (leftover != null && leftover != temp) runCatching { delete(leftover) }
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
 * A backup that was already in the chosen folder and not written by this install (another phone,
 * or Honmani before it was installed again): kept as [name] so this install's backup doesn't
 * replace it, and offered for restoring. [exportedAt] is when it was made.
 */
data class FoundBackup(val file: String, val name: String, val exportedAt: Long)

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
    /** Shared with the other writers of backup files, so they never run at once. */
    private val mutex: Mutex = Mutex(),
) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<FolderBackupState> = _state.asStateFlow()

    private val _found = MutableStateFlow(readFound())

    /** A backup another install left in the folder, to offer for restoring; null when there is none. */
    val found: StateFlow<FoundBackup?> = _found.asStateFlow()

    private fun readFound(): FoundBackup? {
        val file = device.getString(KEY_FOUND_FILE, null) ?: return null
        val name = device.getString(KEY_FOUND_NAME, null) ?: return null
        return FoundBackup(file, name, device.getLong(KEY_FOUND_AT, 0L))
    }

    /**
     * The reader restored the [found] backup or declined it: it is not offered again (the file stays),
     * nor are later ones found in this folder (another phone sharing it leaves a new one at almost
     * every copy) until a folder is chosen again.
     */
    fun dismissFound() {
        device.edit().remove(KEY_FOUND_FILE).remove(KEY_FOUND_NAME).remove(KEY_FOUND_AT)
            .putString(KEY_ANSWERED, device.getString(KEY_FOLDER, null)).apply()
        _found.value = null
    }

    /** Reads the [found] backup; throws [BackupFormatException] or an IOException if it can't. */
    suspend fun read(found: FoundBackup): BackupFile = withContext(Dispatchers.IO) {
        target.read(found.file).buffered().use(BackupCodec::read)
    }

    private fun read(): FolderBackupState = when {
        device.getString(KEY_FOLDER, null) == null -> FolderBackupState.Off
        device.getBoolean(KEY_FAILED, false) -> FolderBackupState.Unavailable
        else -> FolderBackupState.On(device.getLong(KEY_LAST, 0L).takeIf { it > 0 })
    }

    /** The folder copied into (a tree address), or null when it is off. */
    val folder: String? get() = device.getString(KEY_FOLDER, null)

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
            .remove(KEY_LAST)
            .remove(KEY_ANSWERED)
            .putBoolean(KEY_FAILED, false)
            .apply()
        _state.value = read()
        return true
    }

    /** Stops copying: gives the folder's right back and forgets it. The copy already there stays. */
    fun disable() {
        device.getString(KEY_FOLDER, null)?.let(target::release)
        device.edit().remove(KEY_FOLDER).remove(KEY_LAST).remove(KEY_FAILED).apply()
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
        // At every copy, not only the first: another phone may share the folder (a synced cloud
        // folder), and a folder that couldn't be read for sure fails here instead of being taken
        // as empty.
        keepExisting(folder)
        val hash = source.inputStream().use(::sha256)
        // Noted before the copy (and stored at once): killed before it is confirmed as written,
        // the next copy still knows the file in the folder as this install's own.
        device.edit().putString(KEY_WRITING, hash).commit()
        target.replaceFile(folder, FILE_NAME, TEMP_NAME) { out -> source.inputStream().use { it.copyTo(out) } }
        device.edit().putString(KEY_WRITTEN, hash).remove(KEY_WRITING).apply()
    }

    /**
     * A backup file already in [folder] that this install didn't write last (its content differs
     * from the last copy's, [KEY_WRITTEN], and from one under way, [KEY_WRITING]) is renamed to a dated name, or copied there if the provider can't rename,
     * before anything overwrites it. Throws if it can't be kept: then nothing is written.
     */
    private fun keepExisting(folder: String) {
        val existing = target.find(folder, FILE_NAME) ?: return
        val hash = target.read(existing).use(::sha256)
        if (hash == device.getString(KEY_WRITTEN, null) || hash == device.getString(KEY_WRITING, null)) return
        val made = try {
            target.read(existing).buffered().use(BackupCodec::read).exportedAt
        } catch (_: IOException) {
            // Not a backup (BackupFormatException is one), or one this version can't read.
            null
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(made ?: now()))
        val base = FILE_NAME.removeSuffix(".zip")
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) "$base-$stamp.zip" else "$base-$stamp-$it.zip" }
            .first { target.find(folder, it) == null }
        val kept = if (target.canRename(existing)) {
            target.rename(existing, name)
        } else {
            val copy = target.create(folder, name)
            try {
                target.open(copy).use { out -> target.read(existing).use { it.copyTo(out) } }
            } catch (e: Exception) {
                runCatching { target.delete(copy) }
                throw e
            }
            copy
        }
        dropPreviousKept(folder)
        device.edit().putString(KEY_KEPT_IN, folder).putString(KEY_KEPT_FILE, kept).putString(KEY_KEPT_NAME, name).apply()
        // Only a readable backup is offered, and only until the reader answered an offer for this
        // folder; anything else is just kept.
        if (made != null && device.getString(KEY_ANSWERED, null) != folder) {
            device.edit().putString(KEY_FOUND_FILE, kept).putString(KEY_FOUND_NAME, name).putLong(KEY_FOUND_AT, made).apply()
            _found.value = FoundBackup(kept, name, made)
        }
    }

    /**
     * Two phones sharing the folder each keep the other's backup under a dated name at almost every
     * copy, so only the newest one this install kept is left: the dated copy it kept before (in
     * this folder, still under the name it gave it) is deleted. Files this install didn't name and
     * its own current file are never touched. A failure only leaves the older copy behind.
     */
    private fun dropPreviousKept(folder: String) {
        if (device.getString(KEY_KEPT_IN, null) != folder) return
        val previous = device.getString(KEY_KEPT_FILE, null) ?: return
        val name = device.getString(KEY_KEPT_NAME, null) ?: return
        val dropped = runCatching { target.find(folder, name) == previous && target.delete(previous) }
            .onFailure { logWarning("Honmani.backup", "An older kept backup could not be deleted", it) }
            .getOrDefault(false)
        if (dropped && _found.value?.file == previous) {
            device.edit().remove(KEY_FOUND_FILE).remove(KEY_FOUND_NAME).remove(KEY_FOUND_AT).apply()
            _found.value = null
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FILE_NAME = AutoBackup.FILE_NAME
        private const val TEMP_NAME = "tmp-$FILE_NAME"

        private const val KEY_FOLDER = "folder_backup_uri"
        private const val KEY_LAST = "folder_backup_last"
        private const val KEY_FAILED = "folder_backup_failed"

        /**
         * SHA-256 of the last file this install copied into a folder; kept when switched off. A file
         * there with other content is another install's and is kept, never overwritten.
         */
        private const val KEY_WRITTEN = "folder_backup_written"

        /** The hash of a copy started but not confirmed (the process may have been killed meanwhile). */
        private const val KEY_WRITING = "folder_backup_writing"
        private const val KEY_FOUND_FILE = "folder_backup_found_file"
        private const val KEY_FOUND_NAME = "folder_backup_found_name"
        private const val KEY_FOUND_AT = "folder_backup_found_at"

        /** The folder in which the reader last answered an offer: later backups found there aren't offered. */
        private const val KEY_ANSWERED = "folder_backup_answered"

        /** The dated copy this install kept last, and in which folder: replaced by the next one. */
        private const val KEY_KEPT_IN = "folder_backup_kept_in"
        private const val KEY_KEPT_FILE = "folder_backup_kept_file"
        private const val KEY_KEPT_NAME = "folder_backup_kept_name"
    }
}
