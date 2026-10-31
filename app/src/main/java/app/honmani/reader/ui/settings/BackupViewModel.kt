package app.honmani.reader.ui.settings

import app.honmani.reader.diagnostics.logWarning
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.AppContainer
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.backup.BackupCodec
import app.honmani.reader.data.backup.BackupFile
import app.honmani.reader.data.backup.BackupFormatException
import app.honmani.reader.data.backup.BackupContents
import app.honmani.reader.data.backup.ReaderSettingsCheck
import app.honmani.reader.data.backup.RestoreSummary
import app.honmani.reader.data.backup.contents
import app.honmani.reader.data.backup.withoutCatalogs
import app.honmani.reader.data.fonts.FontCatalog
import app.honmani.reader.data.fonts.FontState
import app.honmani.reader.data.settings.ReaderFont
import app.honmani.reader.ui.reader.TtuLibraryException
import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupState {
    data object Idle : BackupState

    data object Working : BackupState

    data object Saved : BackupState

    /** A backup file was read; nothing is restored until the reader confirms. */
    data class Confirm(val backup: BackupFile, val contents: BackupContents) : BackupState

    data class Restored(val summary: RestoreSummary) : BackupState

    data object NotABackup : BackupState

    data object Failed : BackupState
}

/**
 * Settings → Back up and restore: writes a backup to a file the reader picks, or restores one.
 * Runs on the main thread, as the reader page (ttu's settings and positions) needs.
 */
class BackupViewModel(private val container: AppContainer, private val resolver: ContentResolver) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** The file name offered when saving. */
    fun fileName(today: LocalDate = LocalDate.now()) = "honmani-backup-$today.zip"

    fun export(uri: Uri) = launchWork {
        // ttu's settings live in the reader page; without it the backup has the rest.
        val readerSettings = try {
            container.ttuLibrary.readSettings()
        } catch (_: TtuLibraryException) {
            null
        }
        val backup = container.backup.export(readerSettings)
        withContext(Dispatchers.IO) {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
            output.use { BackupCodec.write(backup, it) }
        }
        BackupState.Saved
    }

    fun restore(uri: Uri) = launchWork {
        val backup = withContext(Dispatchers.IO) {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
            input.use { BackupCodec.read(it.buffered()) }
        }
        BackupState.Confirm(backup, backup.contents())
    }

    /** The reader confirmed the [BackupState.Confirm]; [withCatalogs] false leaves its book servers out. */
    fun confirmRestore(backup: BackupFile, withCatalogs: Boolean) = launchWork {
        restore(if (withCatalogs) backup else backup.withoutCatalogs())
    }

    /** Restores the backup the old phone left ([AutoBackup.offer]); asked once, so it is answered either way. */
    fun restoreAutoBackup() = launchWork {
        try {
            restore(container.autoBackup.read())
        } finally {
            container.autoBackup.answered()
        }
    }

    /** Merges [backup] into this phone's data and hands positions and reader settings to ttu. */
    private suspend fun restore(backup: BackupFile): BackupState {
        val summary = container.backup.restore(backup)
        container.appearanceSettings.reload()
        container.readingGoalSettings.reload()
        container.ankiSettings.reload()
        val ttu = container.ttuLibrary
        // Books in the library whose position moved forward: ttu opens them there, unless progress
        // sync has a newer one when the book opens (the position keeps the time it was read).
        val restored = container.restoredBooks
        for (book in summary.positions) {
            val ttuId = book.ttuId ?: continue
            val position = withContext(Dispatchers.IO) { restored.takePosition(book.contentHash) } ?: continue
            try {
                ttu.setBookmark(ttuId, position.characters, position.progress.toDouble(), position.savedAt)
            } catch (_: TtuLibraryException) {
                // Given to ttu when the book opens.
                withContext(Dispatchers.IO) { restored.setPosition(book.contentHash, position) }
            }
        }
        backup.readerSettings?.let { stored ->
            val values = ReaderSettingsCheck.valid(stored).toMutableMap()
            // A downloaded font that isn't on this phone (yet) would keep books waiting for it.
            values[FONT_KEY]?.let { family ->
                val font = FontCatalog.byFamily(family)
                if (font != null && container.fontStore.states.value[font.id] != FontState.Installed) {
                    values[FONT_KEY] = ReaderFont.SERIF.storage
                }
            }
            if (values.isNotEmpty()) {
                ttu.writeSettings(values)
                ttu.reload()
            }
        }
        if (summary.catalogsAdded > 0) container.opdsAutoImport.start(force = true)
        return BackupState.Restored(summary)
    }

    fun dismiss() {
        _state.value = BackupState.Idle
    }

    private fun launchWork(work: suspend () -> BackupState) {
        if (_state.value == BackupState.Working) return
        _state.value = BackupState.Working
        viewModelScope.launch {
            _state.value = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (_: BackupFormatException) {
                BackupState.NotABackup
            } catch (e: OutOfMemoryError) {
                logWarning("Honmani.backup", "Backup was too large to restore", e)
                BackupState.Failed
            } catch (e: Exception) {
                logWarning("Honmani.backup", "Backup could not be read or restored", e)
                // IOException, SecurityException, or whatever the picked file's app throws.
                BackupState.Failed
            }
        }
    }

    companion object {
        /** ttu's localStorage key for the font (see `ReaderSettings`). */
        private const val FONT_KEY = "fontFamilyGroupOne"

        val Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as HonmaniApplication
                BackupViewModel(application.container, application.contentResolver)
            }
        }
    }
}
