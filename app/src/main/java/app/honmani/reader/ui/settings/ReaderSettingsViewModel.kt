package app.honmani.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.fonts.CatalogFont
import app.honmani.reader.data.fonts.FontState
import app.honmani.reader.data.fonts.FontStore
import app.honmani.reader.data.settings.ReaderFont
import app.honmani.reader.data.settings.ReaderSettings
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuLibraryException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface ReaderSettingsState {
    data object Loading : ReaderSettingsState

    data class Loaded(val settings: ReaderSettings) : ReaderSettingsState

    /** The reader page didn't answer (still loading, say). */
    data object Unavailable : ReaderSettingsState
}

/**
 * Honmani's reader settings screen: reads ttu's stored settings, stores changes right away and
 * reloads the reader once the screen closes, so ttu applies them (it reads its settings when
 * the page loads). Runs on the main thread, as the reader page needs.
 */
class ReaderSettingsViewModel(
    private val ttu: TtuLibrary,
    private val fonts: FontStore,
    /** True only the first time ever that edge taps are turned on ([app.honmani.reader.data.settings.EdgeTapsHint]). */
    private val takeEdgeTapsHint: () -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderSettingsState>(ReaderSettingsState.Loading)
    val state: StateFlow<ReaderSettingsState> = _state.asStateFlow()

    private val _edgeTapsHint = MutableStateFlow(false)

    /** Whether to show the one-time hint about the edge-tap zones, just turned on for the first time. */
    val edgeTapsHint: StateFlow<Boolean> = _edgeTapsHint.asStateFlow()

    /** The edge-taps hint was shown. */
    fun edgeTapsHintShown() {
        _edgeTapsHint.value = false
    }

    /** What ttu has stored, as last read or written. */
    private var stored: Map<String, String> = emptyMap()
    private var changed = false

    /** The downloadable fonts and where each stands. */
    val fontStates: StateFlow<Map<String, FontState>> = fonts.states

    init {
        // A font finished downloading or was deleted: ttu's list changes, and the page reloads
        // when the screen closes.
        viewModelScope.launch {
            fonts.states
                .map { states -> states.filterValues { it == FontState.Installed }.keys }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    writeFonts()
                    changed = true
                }
        }
    }

    fun fontFile(font: CatalogFont) = fonts.file(font)

    fun downloadFont(font: CatalogFont) = fonts.download(font)

    /** Deletes [font]; if the reader uses it, the reader goes back to the serif font. */
    fun deleteFont(font: CatalogFont) {
        val current = (_state.value as? ReaderSettingsState.Loaded)?.settings
        if (current?.font == font.family) update(current.copy(font = ReaderFont.SERIF.storage))
        fonts.delete(font)
    }

    private fun writeFonts() = ttu.writeFonts(fonts.installed().associate { it.family to it.fileName })

    /** Reads ttu's settings; called whenever the screen opens. */
    fun load() {
        _state.value = ReaderSettingsState.Loading
        changed = false
        viewModelScope.launch {
            _state.value = try {
                val settings = ReaderSettings.fromStorage(ttu.readSettings())
                // ttu's list may have been reset (its data cleared); this changes nothing otherwise.
                writeFonts()
                stored = settings.toStorage()
                ReaderSettingsState.Loaded(settings)
            } catch (_: TtuLibraryException) {
                ReaderSettingsState.Unavailable
            }
        }
    }

    /** Shows and stores [settings]; only the values that differ are written. */
    fun update(settings: ReaderSettings) {
        val previous = (_state.value as? ReaderSettingsState.Loaded)?.settings ?: return
        if (settings.edgeTaps && !previous.edgeTaps && takeEdgeTapsHint()) _edgeTapsHint.value = true
        _state.value = ReaderSettingsState.Loaded(settings)
        val values = settings.toStorage()
        val differing = values.filter { (key, value) -> stored[key] != value }
        if (differing.isEmpty()) return
        ttu.writeSettings(differing)
        stored = values
        changed = true
    }

    /** The screen closes: the reader reloads if anything changed. */
    fun close() {
        if (changed) ttu.reload()
        changed = false
    }

    /** Opens ttu's own settings page, with the changes made here. */
    fun openAll(path: String) {
        if (changed) ttu.reload(path) else ttu.show(path)
        changed = false
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as HonmaniApplication).container
                ReaderSettingsViewModel(container.ttuLibrary, container.fontStore, container.edgeTapsHint::take)
            }
        }
    }
}
