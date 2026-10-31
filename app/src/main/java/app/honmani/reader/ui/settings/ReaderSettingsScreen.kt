package app.honmani.reader.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.fonts.CatalogFont
import app.honmani.reader.data.fonts.FontCatalog
import app.honmani.reader.data.fonts.FontState
import app.honmani.reader.data.fonts.FontStyle
import app.honmani.reader.data.settings.ReaderFont
import app.honmani.reader.data.settings.ReaderSettings
import app.honmani.reader.data.settings.ReaderTheme
import app.honmani.reader.data.words.WordMarksMode
import app.honmani.reader.ui.HonmaniTopAppBar
import java.io.File
import kotlin.math.roundToInt

/** ttu's settings page, for everything this screen leaves out. */
private const val TTU_SETTINGS_PATH = "/settings"

/**
 * The reader settings that matter while reading, as Honmani screens look: colours, font, text
 * size, line spacing, direction, page turns and a few switches. Everything else stays in ttu's
 * full settings (the last row). Changes apply when the screen closes.
 */
@Composable
fun ReaderSettingsScreen(
    onClose: () -> Unit,
    viewModel: ReaderSettingsViewModel = viewModel(factory = ReaderSettingsViewModel.Factory),
) {
    LaunchedEffect(viewModel) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fontStates by viewModel.fontStates.collectAsStateWithLifecycle()
    val close = {
        viewModel.close()
        onClose()
    }
    BackHandler(onBack = close)
    Scaffold(topBar = { HonmaniTopAppBar(title = stringResource(R.string.reader_settings_title), onBack = close) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                ReaderSettingsState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                ReaderSettingsState.Unavailable -> Text(
                    stringResource(R.string.reader_settings_unavailable),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is ReaderSettingsState.Loaded -> ReaderSettingsContent(
                    settings = current.settings,
                    fonts = ReaderFonts(
                        states = fontStates,
                        file = viewModel::fontFile,
                        onDownload = viewModel::downloadFont,
                        onDelete = viewModel::deleteFont,
                    ),
                    onChange = viewModel::update,
                    onOpenAll = {
                        viewModel.openAll(TTU_SETTINGS_PATH)
                        onClose()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsContent(
    settings: ReaderSettings,
    fonts: ReaderFonts,
    onChange: (ReaderSettings) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Preview(settings, fonts)

        Section(stringResource(R.string.reader_settings_theme)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (theme in ReaderTheme.entries) {
                    ThemeSwatch(theme, selected = settings.theme == theme) { onChange(settings.copy(theme = theme)) }
                }
            }
        }
        Section(stringResource(R.string.reader_settings_font)) {
            FontList(settings.font, fonts) { onChange(settings.copy(font = it)) }
        }
        Section(stringResource(R.string.reader_settings_text_size, settings.fontSize)) {
            Slider(
                value = settings.fontSize.toFloat(),
                onValueChange = { onChange(settings.copy(fontSize = it.roundToInt())) },
                valueRange = ReaderSettings.FONT_SIZES.first.toFloat()..ReaderSettings.FONT_SIZES.last.toFloat(),
                steps = (ReaderSettings.FONT_SIZES.last - ReaderSettings.FONT_SIZES.first) / 2 - 1,
            )
        }
        Section(stringResource(R.string.reader_settings_line_spacing, String.format(LocalConfiguration.current.locales[0], "%.1f", settings.lineHeight))) {
            Slider(
                value = settings.lineHeight,
                onValueChange = { onChange(settings.copy(lineHeight = (it * 10).roundToInt() / 10f)) },
                valueRange = ReaderSettings.MIN_LINE_HEIGHT..ReaderSettings.MAX_LINE_HEIGHT,
                steps = ((ReaderSettings.MAX_LINE_HEIGHT - ReaderSettings.MIN_LINE_HEIGHT) * 10).roundToInt() - 1,
            )
        }
        Section(stringResource(R.string.reader_settings_direction)) {
            Choice(
                options = listOf(true to R.string.reader_settings_vertical, false to R.string.reader_settings_horizontal),
                selected = settings.vertical,
                onSelect = { onChange(settings.copy(vertical = it)) },
            )
        }
        Section(stringResource(R.string.reader_settings_page_turns)) {
            Choice(
                options = listOf(true to R.string.reader_settings_pages, false to R.string.reader_settings_scrolling),
                selected = settings.paginated,
                onSelect = { onChange(settings.copy(paginated = it)) },
            )
        }
        Section(stringResource(R.string.reader_settings_word_marks)) {
            Choice(
                options = listOf(
                    WordMarksMode.OFF to R.string.reader_settings_word_marks_off,
                    WordMarksMode.LEARNING to R.string.reader_settings_word_marks_learning,
                    WordMarksMode.NEW to R.string.reader_settings_word_marks_new,
                ),
                selected = settings.wordMarks,
                onSelect = { onChange(settings.copy(wordMarks = it)) },
            )
            Text(
                stringResource(
                    when (settings.wordMarks) {
                        WordMarksMode.OFF -> R.string.reader_settings_word_marks_off_details
                        WordMarksMode.LEARNING -> R.string.reader_settings_word_marks_learning_details
                        WordMarksMode.NEW -> R.string.reader_settings_word_marks_new_details
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SwitchRow(R.string.reader_settings_furigana, null, settings.furigana) { onChange(settings.copy(furigana = it)) }
        SwitchRow(R.string.reader_settings_progress, R.string.reader_settings_progress_details, settings.showProgress) {
            onChange(settings.copy(showProgress = it))
        }
        SwitchRow(R.string.reader_settings_progress_bar, R.string.reader_settings_progress_bar_details, settings.progressBar) {
            onChange(settings.copy(progressBar = it))
        }
        SwitchRow(R.string.reader_settings_keep_screen_on, null, settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) }
        SwitchRow(R.string.reader_settings_volume_keys, R.string.reader_settings_volume_keys_details, settings.volumeKeys) {
            onChange(settings.copy(volumeKeys = it))
        }
        SwitchRow(R.string.reader_settings_selection_lookup, R.string.reader_settings_selection_lookup_details, settings.selectionLookup) {
            onChange(settings.copy(selectionLookup = it))
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.reader_settings_all)) },
            supportingContent = { Text(stringResource(R.string.reader_settings_all_details)) },
            modifier = Modifier.clickable(onClick = onOpenAll),
        )
        Text(
            stringResource(R.string.reader_settings_applied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
        )
    }
}

/** How the text will look: the theme's colours, the font, size and line spacing. */
@Composable
private fun Preview(settings: ReaderSettings, fonts: ReaderFonts) {
    val (background, text) = colorsOf(settings.theme ?: ReaderTheme.AUTO)
    val density = LocalDensity.current
    // ttu sizes text in CSS pixels, which are dp in the WebView.
    val fontSize = with(density) { settings.fontSize.dp.toSp() }
    Text(
        text = stringResource(R.string.reader_settings_preview),
        color = text,
        fontSize = fontSize,
        lineHeight = fontSize * settings.lineHeight,
        fontFamily = fonts.familyOf(settings.font),
        maxLines = 3,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
    )
}

/** The downloadable fonts for the screen: their states, files and actions. */
class ReaderFonts(
    val states: Map<String, FontState>,
    val file: (CatalogFont) -> File,
    val onDownload: (CatalogFont) -> Unit,
    val onDelete: (CatalogFont) -> Unit,
) {
    /** How text in [font] (ttu's stored family) looks here; the phone's serif for unknown ones. */
    fun familyOf(font: String): FontFamily {
        if (font == ReaderFont.SANS.storage) return FontFamily.SansSerif
        val downloaded = FontCatalog.byFamily(font)?.takeIf { states[it.id] == FontState.Installed }
        return downloaded?.let { fileFamily(file(it)) } ?: FontFamily.Serif
    }
}

/** Compose reads a font file once per file. */
private val fileFamilies = mutableMapOf<String, FontFamily>()

private fun fileFamily(file: File): FontFamily =
    fileFamilies.getOrPut(file.path) { runCatching { FontFamily(Font(file)) }.getOrDefault(FontFamily.Serif) }

/**
 * The phone's serif and sans-serif fonts, then the downloadable ones: each name in its own font
 * once downloaded, otherwise with its size and a download button.
 */
@Composable
private fun FontList(selected: String, fonts: ReaderFonts, onSelect: (String) -> Unit) {
    Column {
        FontRow(
            name = stringResource(R.string.reader_settings_font_serif),
            details = stringResource(R.string.reader_settings_font_phone),
            family = FontFamily.Serif,
            selected = selected == ReaderFont.SERIF.storage,
            onSelect = { onSelect(ReaderFont.SERIF.storage) },
        )
        FontRow(
            name = stringResource(R.string.reader_settings_font_sans),
            details = stringResource(R.string.reader_settings_font_phone),
            family = FontFamily.SansSerif,
            selected = selected == ReaderFont.SANS.storage,
            onSelect = { onSelect(ReaderFont.SANS.storage) },
        )
        for (font in FontCatalog.fonts) {
            val state = fonts.states[font.id] ?: FontState.Available
            val installed = state == FontState.Installed
            FontRow(
                name = font.name,
                details = when (state) {
                    is FontState.Failed -> stringResource(R.string.reader_settings_font_failed, state.message)
                    else -> stringResource(fontStyleLabel(font.style)) + " · " +
                        stringResource(R.string.reader_settings_font_size, font.sizeBytes / 1_000_000.0)
                },
                family = if (installed) fileFamily(fonts.file(font)) else FontFamily.Default,
                selected = installed && selected == font.family,
                onSelect = if (installed) ({ onSelect(font.family) }) else null,
                trailing = {
                    when (state) {
                        FontState.Installed -> TextButton(onClick = { fonts.onDelete(font) }) {
                            Text(stringResource(R.string.action_delete))
                        }
                        is FontState.Downloading -> CircularProgressIndicator(
                            progress = { state.progress ?: 0f },
                            modifier = Modifier.size(24.dp),
                        )
                        else -> TextButton(onClick = { fonts.onDownload(font) }) {
                            Text(stringResource(R.string.action_download))
                        }
                    }
                },
            )
        }
        Text(
            stringResource(R.string.reader_settings_font_licence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FontRow(
    name: String,
    details: String,
    family: FontFamily,
    selected: Boolean,
    onSelect: (() -> Unit)?,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (onSelect != null) {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = onSelect != null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, fontFamily = family, style = MaterialTheme.typography.bodyLarge)
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

private fun fontStyleLabel(style: FontStyle) = when (style) {
    FontStyle.MINCHO -> R.string.reader_settings_font_mincho
    FontStyle.GOTHIC -> R.string.reader_settings_font_gothic
    FontStyle.ROUNDED -> R.string.reader_settings_font_rounded
    FontStyle.HANDWRITTEN -> R.string.reader_settings_font_handwritten
}

/** ttu's theme colours (background, text); following the system shows the app's own. */
@Composable
private fun colorsOf(theme: ReaderTheme): Pair<Color, Color> = when (theme) {
    ReaderTheme.AUTO -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    ReaderTheme.LIGHT -> Color.White to Color(0xDE000000)
    ReaderTheme.SEPIA -> Color(0xFFF7F6EB) to Color(0xDE000000)
    ReaderTheme.GRAY -> Color(0xFF23272A) to Color(0xDEFFFFFF)
    ReaderTheme.BLACK -> Color.Black to Color(0xDEFFFFFF)
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(4.dp),
    ) {
        val fill = if (theme == ReaderTheme.AUTO) {
            Modifier.background(Brush.linearGradient(0.5f to Color.White, 0.5f to Color.Black))
        } else {
            Modifier.background(colorsOf(theme).first)
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(fill)
                .border(if (selected) 3.dp else 1.dp, outline, CircleShape),
        )
        Text(stringResource(themeName(theme)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun themeName(theme: ReaderTheme) = when (theme) {
    ReaderTheme.AUTO -> R.string.reader_settings_theme_auto
    ReaderTheme.LIGHT -> R.string.reader_settings_theme_light
    ReaderTheme.SEPIA -> R.string.reader_settings_theme_sepia
    ReaderTheme.GRAY -> R.string.reader_settings_theme_gray
    ReaderTheme.BLACK -> R.string.reader_settings_theme_black
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T?, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: Int, details: Int?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = if (details != null) {
            { Text(stringResource(details)) }
        } else {
            null
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) },
    )
}
