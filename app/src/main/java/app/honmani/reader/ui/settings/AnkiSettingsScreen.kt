package app.honmani.reader.ui.settings

import app.honmani.reader.diagnostics.logWarning
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiCard
import app.honmani.reader.anki.AnkiNoteTypeInfo
import app.honmani.reader.anki.CardStyle
import app.honmani.reader.data.words.Sentences
import app.honmani.reader.ui.HonmaniTopAppBar
import app.honmani.reader.ui.anki.KnownFromAnkiDialogs
import app.honmani.reader.ui.anki.label
import app.honmani.reader.ui.anki.rememberKnownFromAnki
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.rememberAnkiEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings' Anki row: the switch and the deck; a tap opens [AnkiSettingsScreen]. */
@Composable
fun AnkiSettingsRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.ankiSettings }
    val deck by settings.deck.collectAsStateWithLifecycle()
    val enabled = rememberAnkiEnabled()
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_anki_cards)) },
            supportingContent = { Text(stringResource(R.string.settings_anki_cards_details)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
            modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = settings::setEnabled),
        )
        if (enabled) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_anki)) },
                supportingContent = { Text(stringResource(R.string.settings_anki_summary, deck)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
            )
        }
    }
}

/**
 * Anki settings: the deck and the card type. Where card audio comes from is in [VoiceSettingsScreen].
 */
@Composable
fun AnkiSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { HonmaniTopAppBar(title = stringResource(R.string.settings_anki), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            AnkiSettingsContent()
        }
    }
}

@Composable
private fun AnkiSettingsContent() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val settings = container.ankiSettings
    val deck by settings.deck.collectAsStateWithLifecycle()
    val audiobookSentences by settings.audiobookSentences.collectAsStateWithLifecycle()
    val cardStyle by settings.cardStyle.collectAsStateWithLifecycle()
    val sentenceEnds by settings.sentenceEnds.collectAsStateWithLifecycle()
    val customName by settings.customNoteTypeName.collectAsStateWithLifecycle()
    var customTypes by remember { mutableStateOf<List<AnkiNoteTypeInfo>?>(null) }
    val anki = rememberAnkiAccess()
    val knownFromAnki = rememberKnownFromAnki()
    val scope = rememberCoroutineScope()

    fun openCustomNoteType() {
        anki.whenReady {
            scope.launch {
                customTypes = withContext(Dispatchers.IO) {
                    try {
                        container.ankiDroid.noteTypes()
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logWarning("Honmani.anki", "Could not list the AnkiDroid note types", e)
                        // Another app's provider: its problems must not close Honmani.
                        emptyList()
                    }
                }
            }
        }
    }


    Column {
        Text(
            text = stringResource(R.string.settings_anki_details),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        DeckPicker(
            deck = deck,
            onOpen = { show ->
                anki.whenReady {
                    scope.launch {
                        val names = withContext(Dispatchers.IO) {
                            try {
                                container.ankiDroid.deckNames()
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logWarning("Honmani.anki", "Could not list the AnkiDroid decks", e)
                                // Another app's provider: its problems must not close Honmani.
                                emptyList()
                            }
                        }
                        show(names)
                    }
                }
            },
            onSelect = settings::setDeck,
        )
        CardStylePicker(
            cardStyle,
            onSelect = { if (it == CardStyle.CUSTOM) openCustomNoteType() else settings.setCardStyle(it) },
        )
        if (cardStyle == CardStyle.CUSTOM) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.custom_note_type)) },
                supportingContent = {
                    Text(
                        customName?.let { stringResource(R.string.custom_note_type_current, it) }
                            ?: stringResource(R.string.custom_note_type_none_chosen),
                    )
                },
                modifier = Modifier.clickable(role = Role.Button, onClick = ::openCustomNoteType),
            )
        }
        customTypes?.let { types ->
            CustomNoteTypeDialog(types, settings, onDismiss = { customTypes = null })
        }
        SentenceEndsRow(sentenceEnds, onChange = settings::setSentenceEnds)
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_mark_known_from_anki)) },
            supportingContent = { Text(stringResource(R.string.anki_known_summary)) },
            modifier = Modifier.clickable(role = Role.Button, onClick = knownFromAnki::start),
        )
        KnownFromAnkiDialogs(knownFromAnki)
        ListItem(
            headlineContent = { Text(stringResource(R.string.anki_audiobook_sentences)) },
            supportingContent = { Text(stringResource(R.string.anki_audiobook_sentences_details)) },
            trailingContent = { Switch(checked = audiobookSentences, onCheckedChange = null) },
            modifier = Modifier.toggleable(audiobookSentences, role = Role.Switch, onValueChange = settings::setAudiobookSentences),
        )
    }
}

/**
 * The deck new cards go to: Honmani's own, or one of AnkiDroid's, listed when opened ([onOpen]
 * asks AnkiDroid and calls back with its deck names).
 */
@Composable
private fun DeckPicker(
    deck: String,
    onOpen: (show: (List<String>) -> Unit) -> Unit,
    onSelect: (String) -> Unit,
) {
    var decks by remember { mutableStateOf<List<String>?>(null) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.anki_deck)) },
        supportingContent = { Text(stringResource(R.string.anki_deck_details)) },
        trailingContent = {
            Box {
                OutlinedButton(onClick = { onOpen { names -> decks = names } }) { Text(deck) }
                DropdownMenu(expanded = decks != null, onDismissRequest = { decks = null }) {
                    val others = (decks.orEmpty() + deck).filter { it != AnkiCard.DECK }.distinct().sorted()
                    for (name in listOf(AnkiCard.DECK) + others) {
                        DropdownMenuItem(
                            text = {
                                Text(if (name == AnkiCard.DECK) stringResource(R.string.anki_deck_honmani, name) else name)
                            },
                            onClick = {
                                onSelect(name)
                                decks = null
                            },
                        )
                    }
                }
            }
        },
    )
}

/** Which note type new cards use: Honmani's own, or the sentence-only one. */
@Composable
private fun CardStylePicker(selected: CardStyle, onSelect: (CardStyle) -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Text(stringResource(R.string.card_style), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.card_style_details),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            CardStyle.entries.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = style == selected,
                    onClick = { onSelect(style) },
                    shape = SegmentedButtonDefaults.itemShape(index, CardStyle.entries.size),
                    label = {
                        val label = when (style) {
                            CardStyle.HONMANI -> R.string.card_style_honmani
                            CardStyle.SENTENCE -> R.string.card_style_sentence
                            CardStyle.CUSTOM -> R.string.card_style_custom
                        }
                        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

/** The characters that end a sentence; a tap opens a field with Save, Default and Cancel. */
@Composable
private fun SentenceEndsRow(ends: String, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.sentence_ends)) },
        supportingContent = { Text(ends) },
        modifier = Modifier.clickable(role = Role.Button) { open = true },
    )
    if (open) {
        var draft by rememberSaveable { mutableStateOf(ends) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.sentence_ends)) },
            text = {
                Column {
                    Text(stringResource(R.string.sentence_ends_details), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onChange(draft); open = false }) { Text(stringResource(R.string.sentence_ends_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(Sentences.DEFAULT_ENDS); open = false }) {
                        Text(stringResource(R.string.sentence_ends_default))
                    }
                    TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
                }
            },
        )
    }
}
