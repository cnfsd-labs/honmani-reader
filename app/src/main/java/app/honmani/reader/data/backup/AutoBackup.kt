package app.honmani.reader.data.backup

import app.honmani.reader.diagnostics.logWarning
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A backup Honmani keeps of itself, so a new phone gets the saved words, progress and
 * statistics back without anyone having made a backup: the same file as Settings → Back up
 * ([BackupCodec]), kept in [dir], which Android's own backup (to the Google account, or a
 * phone-to-phone transfer) takes along (`res/xml/data_extraction_rules.xml`).
 *
 * After a new install with such a file from the old phone, the reader is asked once whether to
 * restore it ([offer]); until they answer it is not overwritten, so starting the app doesn't
 * replace the old phone's backup with an empty one.
 *
 * It is written whenever the app asks ([save]: on leaving a book, on going to the background) and
 * its content changed since the last one ([fingerprint], kept in [device]): nothing is rewritten
 * while nothing changed, whatever the time.
 *
 * A backup over [MAX_BYTES] would make Android skip all of the app's backup, so it never goes into
 * [dir]: the last smaller one stays there, and the big one is kept in [largeDir] (outside the backup
 * rules) for [FolderBackup], which has no such limit. [tooLarge] tells the reader.
 */
class AutoBackup(
    private val dir: File,
    /** This phone's preferences ("device"): whether the offer was answered. Never backed up. */
    private val device: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    /** Shared with the other writers of backup files, so they never run at once. */
    private val mutex: Mutex = Mutex(),
    /** Where a backup over [MAX_BYTES] is kept; must not be among the directories Android backs up. */
    private val largeDir: File = File(dir.parentFile, dir.name + "-large"),
) {
    val file = File(dir, FILE_NAME)

    /** The newest backup when it was too large for [file]; exists only while [tooLarge]. */
    val largeFile = File(largeDir, FILE_NAME)

    private val _tooLarge = MutableStateFlow(largeFile.isFile)

    /** Whether the newest backup is over [MAX_BYTES], so Android's copy stays at an older, smaller one. */
    val tooLarge: StateFlow<Boolean> = _tooLarge.asStateFlow()

    /** What [save] did. */
    enum class Outcome {
        /** Nothing was written (nothing changed, an offer waits, the backup couldn't be made or written). */
        Skipped,

        /** [file] is the new backup. */
        Saved,

        /** The new backup was over [MAX_BYTES]: it is in [largeFile], and [file] is unchanged. */
        Large,
    }

    private val _offer = MutableStateFlow<Long?>(null)

    /** When the backup to offer was made (epoch ms), or null when there is none to offer. */
    val offer: StateFlow<Long?> = _offer.asStateFlow()

    /** Called once when the app starts: offers a backup another phone left, if there is one. */
    suspend fun checkOffer() {
        if (device.getBoolean(KEY_ANSWERED, false)) return
        val made = withContext(Dispatchers.IO) {
            file.takeIf { it.isFile }?.let { stored ->
                // Not the file's date: Android's restore has been seen to give it a wrong one (1970).
                try {
                    stored.inputStream().buffered().use(BackupCodec::read).exportedAt
                } catch (e: IOException) {
                    stored.lastModified()
                }
            }
        }
        if (made == null) answered() else _offer.value = made
    }

    /** The reader restored the offered backup or declined it; from now on it is kept up to date. */
    fun answered() {
        device.edit().putBoolean(KEY_ANSWERED, true).apply()
        _offer.value = null
    }

    /** Reads the backup to offer; throws [BackupFormatException] or an IOException if it can't. */
    suspend fun read(): BackupFile = withContext(Dispatchers.IO) { file.inputStream().buffered().use(BackupCodec::read) }

    /** [save] as a yes or no: whether a new backup went into [file]. */
    suspend fun saveIfChanged(force: Boolean = false, make: suspend () -> BackupFile): Boolean =
        save(force, make) == Outcome.Saved

    /**
     * Writes a new backup with [make] if its content differs from the last one's (or [force]),
     * unless an offer waits for an answer. A backup over [MAX_BYTES], which Android's limit for all
     * of an app's backup would reject, does not replace [file]: it goes to [largeFile] and counts as
     * the last backup.
     */
    suspend fun save(force: Boolean = false, make: suspend () -> BackupFile): Outcome = mutex.withLock {
        if (!device.getBoolean(KEY_ANSWERED, false)) return Outcome.Skipped
        val made = try {
            make()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.backup", "Automatic backup failed", e)
            return Outcome.Skipped
        }
        // The reader page wasn't loaded, so ttu's settings are missing: keep the ones the last
        // backup had rather than dropping them.
        val backup = if (made.readerSettings != null) made else {
            val earlier = withContext(Dispatchers.IO) {
                try {
                    listOf(file, largeFile).filter { it.isFile }.maxByOrNull { it.lastModified() }?.inputStream()?.buffered()?.use(BackupCodec::read)?.readerSettings
                } catch (_: IOException) {
                    null
                } catch (_: BackupFormatException) {
                    null
                }
            }
            if (earlier != null) made.copy(readerSettings = earlier) else made
        }
        val content = fingerprint(backup)
        val stored = withContext(Dispatchers.IO) { (if (tooLarge.value) largeFile else file).isFile }
        if (!force && stored && device.getString(KEY_FINGERPRINT, null) == content) return Outcome.Skipped
        val outcome = withContext(Dispatchers.IO) {
            // A full storage must not take the app down as it goes to the background: the last
            // backup stays, and the next one is tried next time.
            val partial = File(dir, "$FILE_NAME.part")
            try {
                dir.mkdirs()
                partial.outputStream().use { BackupCodec.write(backup, it) }
                if (partial.length() > MAX_BYTES) {
                    // The last good backup stays in dir; the big one is kept for the backup folder.
                    largeDir.mkdirs()
                    if (partial.renameTo(largeFile)) {
                        largeFile.setLastModified(now())
                        _tooLarge.value = true
                        Outcome.Large
                    } else {
                        partial.delete()
                        Outcome.Skipped
                    }
                } else {
                    val renamed = partial.renameTo(file)
                    if (renamed) {
                        file.setLastModified(now())
                        largeFile.delete()
                        _tooLarge.value = false
                        Outcome.Saved
                    } else {
                        partial.delete()
                        Outcome.Skipped
                    }
                }
            } catch (_: IOException) {
                partial.delete()
                Outcome.Skipped
            }
        }
        if (outcome != Outcome.Skipped) device.edit().putString(KEY_FINGERPRINT, content).apply()
        outcome
    }

    companion object {
        /** The directory in app storage (`files/`), as the backup rules name it. */
        const val DIRECTORY = "auto-backup"

        /** Beside [DIRECTORY] (`DIRECTORY-large`), not in it: the backup rules include only [DIRECTORY]. */
        const val LARGE_DIRECTORY = "auto-backup-large"
        const val FILE_NAME = "honmani-backup.zip"

        /** Android backs up at most 25 MB per app; the settings and some room stay free. */
        const val MAX_BYTES = 10L * 1024 * 1024

        private const val KEY_ANSWERED = "auto_backup_offer_answered"

        /** [fingerprint] of the last backup written; on this phone only, like [KEY_ANSWERED]. */
        private const val KEY_FINGERPRINT = "auto_backup_fingerprint"

        /** What the backup holds, without when it was made: the same data gives the same value. */
        private fun fingerprint(backup: BackupFile): String {
            val json = BackupCodec.toJson(backup.copy(exportedAt = 0))
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
