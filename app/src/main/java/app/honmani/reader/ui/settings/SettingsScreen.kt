package app.honmani.reader.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.library.SampleBook
import app.honmani.reader.data.opds.OpdsCatalog
import app.honmani.reader.data.settings.Accent
import app.honmani.reader.data.settings.customAccentColor
import app.honmani.reader.ui.dictionaries.DictionariesScreen
import app.honmani.reader.ui.theme.HonmaniTheme
import kotlinx.coroutines.launch

/**
 * Settings: the accent colour, book servers, Anki cards, the way into ttu's reader settings,
 * backups and diagnostics.
 */
@Composable
fun SettingsScreen(
    bottomBar: @Composable () -> Unit,
    onOpenReaderSettings: () -> Unit,
    onShareDiagnostics: () -> Unit,
    /** The Dictionaries screen, kept by the caller so a lookup without dictionaries can open it. */
    showingDictionaries: Boolean = false,
    onShowDictionaries: (Boolean) -> Unit = {},
    /** Adds a sample book to the library (if it isn't there) and opens it. */
    onOpenSample: (SampleBook) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val appearance = container.appearanceSettings
    val accent by appearance.accent.collectAsStateWithLifecycle()
    val customHue by appearance.customHue.collectAsStateWithLifecycle()
    val showDifficulty by appearance.showDifficulty.collectAsStateWithLifecycle()
    val showPreviousSentence by appearance.showPreviousSentence.collectAsStateWithLifecycle()
    val showWordFrequency by appearance.showWordFrequency.collectAsStateWithLifecycle()
    val catalogs by container.opdsRepository.catalogs.collectAsStateWithLifecycle(emptyList())
    val dictionaries by container.dictionaryRepository.dictionaries.collectAsStateWithLifecycle(emptyList())
    val dictionaryUpdates by remember { container.dictionaryUpdateChecker.updates }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    }
    var showingAbout by rememberSaveable { mutableStateOf(false) }
    if (showingAbout) {
        AboutScreen(version, onBack = { showingAbout = false })
        return
    }
    var showingAnki by rememberSaveable { mutableStateOf(false) }
    if (showingAnki) {
        AnkiSettingsScreen(onBack = { showingAnki = false })
        return
    }
    if (showingDictionaries) {
        DictionariesScreen(onBack = { onShowDictionaries(false) })
        return
    }
    SettingsContent(
        version = version,
        accent = accent,
        onAccentChange = appearance::setAccent,
        customHue = customHue,
        onCustomHueChange = appearance::setCustomHue,
        showDifficulty = showDifficulty,
        onShowDifficultyChange = appearance::setShowDifficulty,
        showPreviousSentence = showPreviousSentence,
        onShowPreviousSentenceChange = appearance::setShowPreviousSentence,
        showWordFrequency = showWordFrequency,
        onShowWordFrequencyChange = appearance::setShowWordFrequency,
        catalogs = catalogs,
        onAutoImportChange = { catalog, enabled ->
            scope.launch {
                container.opdsRepository.setAutoImport(catalog.id, enabled)
                if (enabled) container.opdsAutoImport.start(force = true)
            }
        },
        bottomBar = bottomBar,
        dictionaries = dictionaries.size,
        dictionaryUpdates = dictionaryUpdates.size,
        onOpenDictionaries = { onShowDictionaries(true) },
        onOpenReaderSettings = onOpenReaderSettings,
        onShareDiagnostics = onShareDiagnostics,
        onOpenAbout = { showingAbout = true },
        onOpenSample = onOpenSample,
        anki = { AnkiSettingsRow(onClick = { showingAnki = true }) },
        sync = { ProgressSyncSection() },
        backup = { BackupSection() },
    )
}

@Composable
private fun SettingsContent(
    version: String,
    accent: Accent,
    onAccentChange: (Accent) -> Unit,
    customHue: Float,
    onCustomHueChange: (Float) -> Unit,
    showDifficulty: Boolean,
    onShowDifficultyChange: (Boolean) -> Unit,
    showPreviousSentence: Boolean,
    onShowPreviousSentenceChange: (Boolean) -> Unit,
    showWordFrequency: Boolean,
    onShowWordFrequencyChange: (Boolean) -> Unit,
    catalogs: List<OpdsCatalog>,
    onAutoImportChange: (OpdsCatalog, Boolean) -> Unit,
    bottomBar: @Composable () -> Unit,
    dictionaries: Int,
    dictionaryUpdates: Int,
    onOpenDictionaries: () -> Unit,
    onOpenReaderSettings: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSample: (SampleBook) -> Unit = {},
    anki: @Composable () -> Unit = {},
    sync: @Composable () -> Unit = {},
    backup: @Composable () -> Unit = {},
) {
    var showingSamples by rememberSaveable { mutableStateOf(false) }
    if (showingSamples) {
        SampleBooksDialog(
            onOpen = {
                showingSamples = false
                onOpenSample(it)
            },
            onDismiss = { showingSamples = false },
        )
    }
    // No title: the bottom bar names the tab.
    Scaffold(
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionHeading(R.string.settings_section_reading) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_reader)) },
                    supportingContent = { Text(stringResource(R.string.settings_reader_details)) },
                    modifier = Modifier.clickable(onClick = onOpenReaderSettings),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dictionaries_title)) },
                    supportingContent = {
                        if (dictionaryUpdates > 0) {
                            Text(
                                pluralStringResource(R.plurals.settings_dictionary_updates, dictionaryUpdates, dictionaryUpdates),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (dictionaries == 0) {
                            Text(stringResource(R.string.settings_dictionaries_none))
                        } else {
                            Text(pluralStringResource(R.plurals.settings_dictionaries_installed, dictionaries, dictionaries))
                        }
                    },
                    trailingContent = { if (dictionaryUpdates > 0) Badge() },
                    modifier = Modifier.clickable(onClick = onOpenDictionaries),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_previous_sentence)) },
                    supportingContent = { Text(stringResource(R.string.settings_previous_sentence_details)) },
                    trailingContent = { Switch(checked = showPreviousSentence, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        showPreviousSentence,
                        role = Role.Switch,
                        onValueChange = onShowPreviousSentenceChange,
                    ),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_word_frequency)) },
                    supportingContent = { Text(stringResource(R.string.settings_word_frequency_details)) },
                    trailingContent = { Switch(checked = showWordFrequency, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        showWordFrequency,
                        role = Role.Switch,
                        onValueChange = onShowWordFrequencyChange,
                    ),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_sample_books)) },
                    supportingContent = { Text(stringResource(R.string.settings_sample_books_details)) },
                    modifier = Modifier.clickable { showingSamples = true },
                )
            }
            item { SectionHeading(R.string.settings_section_appearance) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_accent)) },
                    supportingContent = { AccentPicker(accent, onAccentChange, customHue, onCustomHueChange) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_cover_difficulty)) },
                    supportingContent = { Text(stringResource(R.string.settings_cover_difficulty_details)) },
                    trailingContent = { Switch(checked = showDifficulty, onCheckedChange = null) },
                    modifier = Modifier.toggleable(showDifficulty, role = Role.Switch, onValueChange = onShowDifficultyChange),
                )
            }
            item { SectionHeading(R.string.settings_section_flashcards) }
            item { anki() }
            item { SectionHeading(R.string.settings_section_books) }
            items(catalogs, key = { it.id }) { catalog ->
                ListItem(
                    headlineContent = { Text(catalog.title) },
                    supportingContent = { Text(stringResource(R.string.catalog_auto_import_details)) },
                    trailingContent = {
                        Switch(checked = catalog.autoImport, onCheckedChange = null)
                    },
                    modifier = Modifier.toggleable(catalog.autoImport, role = Role.Switch) { onAutoImportChange(catalog, it) },
                )
            }
            item { sync() }
            item { SectionHeading(R.string.settings_section_data) }
            item { backup() }
            item { SectionHeading(R.string.settings_section_help) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_share_diagnostics)) },
                    supportingContent = { Text(stringResource(R.string.settings_diagnostics_details)) },
                    modifier = Modifier.clickable(onClick = onShareDiagnostics),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_about_details, version)) },
                    modifier = Modifier.clickable(onClick = onOpenAbout),
                )
            }
        }
    }
}

/** A group's title in the settings list. */
@Composable
private fun SectionHeading(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** The books that come with the app, roughly easiest first; a tap opens one. */
@Composable
private fun SampleBooksDialog(onOpen: (SampleBook) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sample_books)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sample_books_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn {
                    items(SampleBook.entries) { sample ->
                        ListItem(
                            headlineContent = { Text(sample.title) },
                            supportingContent = { Text(sample.author) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(sample) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(
    selected: Accent,
    onSelect: (Accent) -> Unit,
    customHue: Float,
    onCustomHueChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = SWATCHES_PER_ROW,
        ) {
            for (accent in Accent.entries) {
                val swatch = when {
                    accent == Accent.CUSTOM -> Color(customAccentColor(customHue))
                    accent.color != null -> Color(accent.color)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
                    else -> continue
                }
                AccentSwatch(
                    color = swatch,
                    label = stringResource(accent.label()),
                    isSelected = accent == selected,
                    onClick = { onSelect(accent) },
                    adjustable = accent == Accent.CUSTOM,
                )
            }
        }
        if (selected == Accent.CUSTOM) HueSlider(customHue, onCustomHueChange)
    }
}

/** A slider over a rainbow for the custom accent's hue. */
@Composable
private fun HueSlider(hue: Float, onHueChange: (Float) -> Unit) {
    val label = stringResource(R.string.accent_custom_hue)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(12.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(rainbow(steps = 12))),
        )
        Slider(
            value = hue,
            onValueChange = onHueChange,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color(customAccentColor(hue)),
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

/** The custom accent's colours around the hue circle, [steps] of them plus the first again. */
private fun rainbow(steps: Int): List<Color> = (0..steps).map { Color(customAccentColor(it * 360f / steps)) }

@Composable
private fun AccentSwatch(
    color: Color,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    /** The custom swatch: a tune icon on its colour, so it reads as "your own colour". */
    adjustable: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = 2.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .semantics { contentDescription = label }
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
    ) {
        val tint = if (color.luminance() > 0.4f) Color.Black else Color.White
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = tint)
        } else if (adjustable) {
            Icon(
                painterResource(R.drawable.ic_tune),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun Accent.label(): Int = when (this) {
    Accent.PURPLE -> R.string.accent_purple
    Accent.BLUE -> R.string.accent_blue
    Accent.TEAL -> R.string.accent_teal
    Accent.GREEN -> R.string.accent_green
    Accent.ORANGE -> R.string.accent_orange
    Accent.PINK -> R.string.accent_pink
    Accent.RED -> R.string.accent_red
    Accent.AMBER -> R.string.accent_amber
    Accent.INDIGO -> R.string.accent_indigo
    Accent.SLATE -> R.string.accent_slate
    Accent.SYSTEM -> R.string.accent_system
    Accent.CUSTOM -> R.string.accent_custom
}

@Preview
@Composable
private fun SettingsPreview() {
    HonmaniTheme {
        SettingsContent(
            version = "0.1",
            accent = Accent.PURPLE,
            onAccentChange = {},
            customHue = 300f,
            onCustomHueChange = {},
            showDifficulty = true,
            onShowDifficultyChange = {},
            showPreviousSentence = false,
            onShowPreviousSentenceChange = {},
            showWordFrequency = true,
            onShowWordFrequencyChange = {},
            catalogs = listOf(OpdsCatalog(1, "Calibre-Web", "https://books.example/opds", "me", true)),
            onAutoImportChange = { _, _ -> },
            bottomBar = {},
            dictionaries = 3,
            dictionaryUpdates = 0,
            onOpenDictionaries = {},
            onOpenReaderSettings = {},
            onShareDiagnostics = {},
            onOpenAbout = {},
        )
    }
}

private const val SWATCHES_PER_ROW = 6
