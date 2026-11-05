package app.honmani.reader.data.backup

import android.content.SharedPreferences
import app.honmani.reader.diagnostics.logWarning
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How this phone's sync file stands (Settings → Sync folder). */
sealed interface SyncFolderState {
    data object Off : SyncFolderState

    /** A folder is chosen ([name] as the provider calls it); [lastAt] is when the file was last written (epoch ms). */
    data class On(
        val name: String?,
        val lastAt: Long?,
        /** The phone whose file was merged last, and when (epoch ms). */
        val syncedWith: String? = null,
        val syncedAt: Long? = null,
        /** A phone whose file this Honmani is too old to read. */
        val newerDevice: String? = null,
    ) : SyncFolderState

    /** The last write failed: the folder is gone, or the right to write to it. */
    data class Unavailable(val name: String?) : SyncFolderState
}

/**
 * Keeps this phone in step with the reader's other phones through a folder they chose (a Syncthing,
 * Nextcloud or Drive folder). Each phone writes one file of its own, `honmani-sync-<installationId>.zip`,
 * and reads the others' ([SyncMerge]). One writer per file, so folder sync tools never see a conflict.
 * It is a [BackupFile] with the sync additions and without what stays on the phone (most settings,
 * book servers, passwords). Honmani makes no network call; moving the files is the folder app's job.
 *
 * Off by default. [sync] reads and writes when the app comes to the front and right after the folder
 * is chosen; [write] only writes, when it goes to the background. A file is merged once, unless it
 * changes; one that fails (still being copied, say) is tried again next time. The own file is written
 * only if the content changed. A failure only shows as [SyncFolderState.Unavailable]. The folder is
 * tied to this phone ("device" preferences, not backed up).
 */
class SyncFolder(
    private val device: SharedPreferences,
    private val target: FolderTarget,
    private val installationId: () -> String,
    private val deviceName: String,
    /** Builds the file's content for this phone (see [Backup.export]). */
    private val export: suspend (SyncIdentity) -> BackupFile,
    /** Takes another phone's file into this phone's data. */
    private val merge: suspend (BackupFile) -> Unit,
    /** Drops deletion markers deleted before the given time (epoch ms). */
    private val pruneTombstones: suspend (before: Long) -> Unit,
    /** Shared with the other writers of backup files, so they never run at once. */
    private val mutex: Mutex = Mutex(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SyncFolderState> = _state.asStateFlow()

    /** The folder written into (a tree address), or null when sync is off. */
    val folder: String? get() = device.getString(KEY_FOLDER, null)

    private fun read(): SyncFolderState {
        val folder = device.getString(KEY_FOLDER, null) ?: return SyncFolderState.Off
        val name = device.getString(KEY_NAME, null)
        return if (device.getBoolean(KEY_FAILED, false)) {
            SyncFolderState.Unavailable(name)
        } else {
            SyncFolderState.On(
                name,
                device.getLong(KEY_LAST, 0L).takeIf { it > 0 },
                device.getString(KEY_SYNCED_WITH, null),
                device.getLong(KEY_SYNCED_AT, 0L).takeIf { it > 0 },
                device.getString(KEY_NEWER, null),
            )
        }
    }

    /** Starts syncing into [folder] (a tree address the reader picked). Returns whether it was accepted. */
    fun choose(folder: String): Boolean {
        try {
            target.grant(folder)
        } catch (e: Exception) {
            logWarning("Honmani.sync", "Folder not accepted", e)
            return false
        }
        val earlier = device.getString(KEY_FOLDER, null)
        if (earlier != null && earlier != folder) target.release(earlier)
        device.edit()
            .putString(KEY_FOLDER, folder)
            .putString(KEY_NAME, target.displayName(folder))
            .remove(KEY_LAST)
            .remove(KEY_FINGERPRINT)
            .remove(KEY_SEEN)
            .remove(KEY_SYNCED_WITH)
            .remove(KEY_SYNCED_AT)
            .remove(KEY_NEWER)
            .putBoolean(KEY_FAILED, false)
            .apply()
        _state.value = read()
        return true
    }

    /** Stops syncing: gives the folder's right back and forgets it. The file already there stays. */
    fun disable() {
        device.getString(KEY_FOLDER, null)?.let(target::release)
        device.edit().remove(KEY_FOLDER).remove(KEY_NAME).remove(KEY_LAST).remove(KEY_FINGERPRINT).remove(KEY_FAILED)
            .remove(KEY_SEEN).remove(KEY_SYNCED_WITH).remove(KEY_SYNCED_AT).remove(KEY_NEWER).apply()
        _state.value = SyncFolderState.Off
    }

    /** Writes the file if sync is on and something changed; never throws. */
    suspend fun write() {
        mutex.withLock { writeLocked() }
    }

    /** Merges the other phones' changed files, then writes this phone's, if sync is on; never throws. */
    suspend fun sync() {
        mutex.withLock {
            val folder = device.getString(KEY_FOLDER, null) ?: return
            withContext(Dispatchers.IO) {
                try {
                    readOthers(folder)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The write below finds out the same and shows it.
                    logWarning("Honmani.sync", "Reading the sync folder failed", e)
                }
            }
            writeLocked()
        }
    }

    private suspend fun readOthers(folder: String) {
        val id = installationId()
        val seen = device.getStringSet(KEY_SEEN, null).orEmpty()
        val nowSeen = HashSet<String>()
        var newer: String? = null
        for (file in target.list(folder)) {
            if (!OTHER_FILE.matches(file.name) || file.name == fileName(id)) continue
            val stamp = "${file.address}|${file.size}|${file.modified}"
            if (stamp in seen) {
                nowSeen += stamp
                continue
            }
            try {
                val backup = target.read(file.address).use { BackupCodec.read(it) }
                when {
                    backup.syncFormat == null || backup.deviceId == id -> nowSeen += stamp
                    backup.syncFormat > FORMAT -> newer = backup.deviceName ?: ANOTHER_PHONE
                    else -> {
                        merge(backup)
                        nowSeen += stamp
                        device.edit()
                            .putString(KEY_SYNCED_WITH, backup.deviceName ?: ANOTHER_PHONE)
                            .putLong(KEY_SYNCED_AT, now())
                            .apply()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NewerBackupException) {
                newer = e.deviceName ?: ANOTHER_PHONE
            } catch (e: Exception) {
                // Half copied, or a merge that failed: not marked as seen, so it is tried again.
                logWarning("Honmani.sync", "Merging a sync file failed", e)
            }
        }
        device.edit().putStringSet(KEY_SEEN, nowSeen).putString(KEY_NEWER, newer).apply()
    }

    private suspend fun writeLocked() {
        val folder = device.getString(KEY_FOLDER, null) ?: return
        withContext(Dispatchers.IO) {
            val worked = try {
                writeFile(folder)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Permission lost, folder deleted, storage full, a provider that misbehaves.
                logWarning("Honmani.sync", "Writing the sync file failed", e)
                false
            }
            device.edit().putBoolean(KEY_FAILED, !worked).apply()
            _state.value = read()
        }
    }

    private suspend fun writeFile(folder: String) {
        pruneTombstones(now() - TOMBSTONE_KEEP_MS)
        val id = installationId()
        val file = export(SyncIdentity(id, deviceName))
        val fingerprint = fingerprint(file)
        val name = fileName(id)
        // The file may have been deleted from the folder since: then it is written again.
        if (device.getString(KEY_FINGERPRINT, null) == fingerprint && target.find(folder, name) != null) return
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(file, it) }.toByteArray()
        target.replaceFile(folder, name, tempName(id)) { it.write(bytes) }
        device.edit().putString(KEY_FINGERPRINT, fingerprint).putLong(KEY_LAST, now()).apply()
    }

    companion object {
        /** The first sync file format ([BackupFile.syncFormat]). */
        const val FORMAT = 1

        /** A deletion marker has reached every phone long before this. */
        const val TOMBSTONE_KEEP_MS = 365L * 24 * 60 * 60 * 1000

        fun fileName(installationId: String) = "honmani-sync-$installationId.zip"

        /**
         * The temporary file (the provider wants a zip's name to end in .zip, or it adds it itself).
         * A reader of the folder ignores names that start with a dot.
         */
        fun tempName(installationId: String) = ".honmani-sync-$installationId.part.zip"

        /** What the content is, without when it was written: the same data gives the same value. */
        private fun fingerprint(file: BackupFile): String {
            val json = BackupCodec.toJson(file.copy(exportedAt = 0))
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /** Another phone's file: no temporary file or a folder app's conflict copy ("name (1).zip", "name.sync-conflict-….zip"). */
        private val OTHER_FILE = Regex("^honmani-sync-[^. ]+\\.zip$")

        private const val ANOTHER_PHONE = "Another phone"
        private const val KEY_SEEN = "sync_folder_seen"
        private const val KEY_SYNCED_WITH = "sync_folder_synced_with"
        private const val KEY_SYNCED_AT = "sync_folder_synced_at"
        private const val KEY_NEWER = "sync_folder_newer"
        private const val KEY_FOLDER = "sync_folder_uri"
        private const val KEY_NAME = "sync_folder_name"
        private const val KEY_LAST = "sync_folder_last"
        private const val KEY_FAILED = "sync_folder_failed"
        private const val KEY_FINGERPRINT = "sync_folder_fingerprint"
    }
}
