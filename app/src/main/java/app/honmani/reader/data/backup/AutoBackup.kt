package app.honmani.reader.data.backup

import app.honmani.reader.diagnostics.logWarning
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
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
 */
class AutoBackup(
    private val dir: File,
    /** This phone's preferences ("device"): whether the offer was answered. Never backed up. */
    private val device: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val file = File(dir, FILE_NAME)
    private val mutex = Mutex()

    private val _offer = MutableStateFlow<Long?>(null)

    /** When the backup to offer was made (epoch ms), or null when there is none to offer. */
    val offer: StateFlow<Long?> = _offer.asStateFlow()

    /** Called once when the app starts: offers a backup another phone left, if there is one. */
    suspend fun checkOffer() {
        if (device.getBoolean(KEY_ANSWERED, false)) return
        val made = withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.lastModified() }
        if (made == null) answered() else _offer.value = made
    }

    /** The reader restored the offered backup or declined it; from now on it is kept up to date. */
    fun answered() {
        device.edit().putBoolean(KEY_ANSWERED, true).apply()
        _offer.value = null
    }

    /** Reads the backup to offer; throws [BackupFormatException] or an IOException if it can't. */
    suspend fun read(): BackupFile = withContext(Dispatchers.IO) { file.inputStream().buffered().use(BackupCodec::read) }

    /**
     * Writes a new backup with [make] if the last one is older than [MIN_INTERVAL_MS] (or [force]),
     * unless an offer waits for an answer. Too large a backup (over [MAX_BYTES], which Android's
     * limit for all of an app's backup would reject) is not kept. Returns whether it wrote one.
     */
    suspend fun saveIfDue(force: Boolean = false, make: suspend () -> BackupFile): Boolean = mutex.withLock {
        if (!device.getBoolean(KEY_ANSWERED, false)) return false
        val last = withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.lastModified() }
        if (!force && last != null && now() - last < MIN_INTERVAL_MS) return false
        val made = try {
            make()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.backup", "Automatic backup failed", e)
            return false
        }
        // The reader page wasn't loaded, so ttu's settings are missing: keep the ones the last
        // backup had rather than dropping them.
        val backup = if (made.readerSettings != null) made else {
            val earlier = withContext(Dispatchers.IO) {
                try {
                    file.takeIf { it.isFile }?.inputStream()?.buffered()?.use(BackupCodec::read)?.readerSettings
                } catch (_: IOException) {
                    null
                } catch (_: BackupFormatException) {
                    null
                }
            }
            if (earlier != null) made.copy(readerSettings = earlier) else made
        }
        withContext(Dispatchers.IO) {
            // A full storage must not take the app down as it goes to the background: the last
            // backup stays, and the next one is tried next time.
            val partial = File(dir, "$FILE_NAME.part")
            try {
                dir.mkdirs()
                partial.outputStream().use { BackupCodec.write(backup, it) }
                if (partial.length() > MAX_BYTES) {
                    partial.delete()
                    file.delete()
                    false
                } else {
                    val renamed = partial.renameTo(file)
                    if (renamed) file.setLastModified(now()) else partial.delete()
                    renamed
                }
            } catch (_: IOException) {
                partial.delete()
                false
            }
        }
    }

    companion object {
        /** The directory in app storage (`files/`), as the backup rules name it. */
        const val DIRECTORY = "auto-backup"
        const val FILE_NAME = "honmani-backup.zip"
        const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** Android backs up at most 25 MB per app; the settings and some room stay free. */
        const val MAX_BYTES = 10L * 1024 * 1024

        private const val KEY_ANSWERED = "auto_backup_offer_answered"
    }
}
