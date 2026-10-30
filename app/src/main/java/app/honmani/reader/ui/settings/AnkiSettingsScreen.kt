package app.honmani.reader.ui.settings

import app.honmani.reader.diagnostics.logWarning
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiCard
import app.honmani.reader.anki.CardAudio
import app.honmani.reader.anki.CloudVoiceInfo
import app.honmani.reader.anki.GoogleCloudVoice
import app.honmani.reader.anki.SentenceSpeed
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.ui.HonmaniTopAppBar
import app.honmani.reader.ui.anki.label
import app.honmani.reader.ui.anki.rememberAnkiAccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Google key check says. */
private sealed interface KeyCheck {
    data object Checking : KeyCheck

    data class Works(val voices: List<CloudVoiceInfo>) : KeyCheck

    data class Failed(val message: Int) : KeyCheck
}

/** Settings' Anki row: the deck and where card audio comes from; a tap opens [AnkiSettingsScreen]. */
@Composable
fun AnkiSettingsRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.ankiSettings }
    val audio by settings.audio.collectAsStateWithLifecycle()
    val deck by settings.deck.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_anki)) },
        supportingContent = { Text(stringResource(R.string.settings_anki_summary, deck, stringResource(audio.label()))) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Anki settings: the deck, where card audio comes from (none, the phone's voice, Google Cloud),
 * the Google API key and voice, and a sample to listen to before choosing.
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
    val audio by settings.audio.collectAsStateWithLifecycle()
    val hasKey by settings.hasGoogleKey.collectAsStateWithLifecycle()
    val voice by settings.googleVoice.collectAsStateWithLifecycle()
    val deck by settings.deck.collectAsStateWithLifecycle()
    val sentenceSpeed by settings.sentenceSpeed.collectAsStateWithLifecycle()
    val anki = rememberAnkiAccess()
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<KeyCheck?>(null) }
    var sampleError by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release() } }

    fun checkKey() {
        val key = settings.googleKey() ?: return
        check = KeyCheck.Checking
        scope.launch {
            check = try {
                KeyCheck.Works(GoogleCloudVoice.voices(key))
            } catch (e: VoiceException) {
                KeyCheck.Failed(e.problem.label())
            }
        }
    }

    LaunchedEffect(audio, hasKey) {
        if (audio == CardAudio.GOOGLE && hasKey && check == null) checkKey()
    }

    val sampleText = stringResource(R.string.voice_sample_text)
    fun listen() {
        val cardVoice = settings.voice { container.phoneVoice } ?: return
        playing = true
        sampleError = null
        scope.launch {
            try {
                val file = File(context.cacheDir, "voice-sample.${cardVoice.extension}")
                // The sample is a sentence, so it plays at the sentence speed.
                cardVoice.speak(sampleText, file, sentenceSpeed.rate)
                player[0]?.release()
                player[0] = MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener { playing = false }
                    prepare()
                    start()
                }
            } catch (e: VoiceException) {
                sampleError = resources.getString(R.string.voice_sample_failed, resources.getString(e.problem.label()))
                playing = false
            } catch (e: java.io.IOException) {
                sampleError = resources.getString(R.string.voice_sample_failed, e.message.orEmpty())
                playing = false
            } catch (e: IllegalStateException) {
                // The phone's media player refused the file.
                sampleError = resources.getString(R.string.voice_sample_failed, e.message.orEmpty())
                playing = false
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
        Text(
            text = stringResource(R.string.settings_card_audio),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        for (option in CardAudio.entries) {
            ListItem(
                headlineContent = { Text(stringResource(option.label())) },
                supportingContent = { Text(stringResource(option.details())) },
                leadingContent = { RadioButton(selected = audio == option, onClick = null) },
                modifier = Modifier.selectable(
                    selected = audio == option,
                    role = Role.RadioButton,
                    onClick = { settings.setAudio(option) },
                ),
            )
        }
        if (audio == CardAudio.GOOGLE) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.google_key)) },
                    placeholder = { if (hasKey) Text(stringResource(R.string.catalog_password_kept)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(
                            enabled = keyInput.isNotBlank(),
                            onClick = {
                                settings.setGoogleKey(keyInput)
                                keyInput = ""
                                checkKey()
                            },
                        ) { Text(stringResource(R.string.action_save)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                val status = when (val current = check) {
                    null -> if (hasKey) stringResource(R.string.google_key_saved) else null
                    KeyCheck.Checking -> stringResource(R.string.google_key_checking)
                    is KeyCheck.Works -> pluralStringResource(R.plurals.google_key_ok, current.voices.size, current.voices.size)
                    is KeyCheck.Failed -> stringResource(current.message)
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (check is KeyCheck.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                (check as? KeyCheck.Works)?.voices?.takeIf { it.isNotEmpty() }?.let { voices ->
                    VoicePicker(
                        voices = voices,
                        selected = voice ?: GoogleCloudVoice.defaultVoice(voices)?.name,
                        onSelect = { settings.setGoogleVoice(it) },
                    )
                }
                Text(
                    text = stringResource(R.string.google_key_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (audio != CardAudio.OFF) {
            SentenceSpeedPicker(sentenceSpeed, onSelect = settings::setSentenceSpeed)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    enabled = !playing && (audio != CardAudio.GOOGLE || hasKey),
                    onClick = { listen() },
                ) { Text(stringResource(R.string.action_listen)) }
                if (playing) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
            sampleError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
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

/** How fast example sentences are spoken; the word itself always at normal speed. */
@Composable
private fun SentenceSpeedPicker(selected: SentenceSpeed, onSelect: (SentenceSpeed) -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Text(stringResource(R.string.sentence_speed), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.sentence_speed_details),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            SentenceSpeed.entries.forEachIndexed { index, speed ->
                SegmentedButton(
                    selected = speed == selected,
                    onClick = { onSelect(speed) },
                    shape = SegmentedButtonDefaults.itemShape(index, SentenceSpeed.entries.size),
                    label = { Text(stringResource(speed.label()), maxLines = 1) },
                )
            }
        }
    }
}

private fun SentenceSpeed.label(): Int = when (this) {
    SentenceSpeed.NORMAL -> R.string.sentence_speed_normal
    SentenceSpeed.SLOWER -> R.string.sentence_speed_slower
    SentenceSpeed.SLOW -> R.string.sentence_speed_slow
}

/** Picks a Google voice; listed as "Aoede · Chirp3-HD · female". */
@Composable
private fun VoicePicker(voices: List<CloudVoiceInfo>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.name == selected } ?: voices.first()
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(stringResource(R.string.google_voice) + ": " + voiceLabel(current))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (voice in voices) {
                DropdownMenuItem(
                    text = { Text(voiceLabel(voice)) },
                    onClick = {
                        onSelect(voice.name)
                        open = false
                    },
                )
            }
        }
    }
}

private fun voiceLabel(voice: CloudVoiceInfo): String =
    listOfNotNull(voice.shortName, voice.model, voice.gender?.lowercase()).joinToString(" · ")

private fun CardAudio.label(): Int = when (this) {
    CardAudio.OFF -> R.string.card_audio_off
    CardAudio.PHONE -> R.string.card_audio_phone
    CardAudio.GOOGLE -> R.string.card_audio_google
}

private fun CardAudio.details(): Int = when (this) {
    CardAudio.OFF -> R.string.card_audio_off_details
    CardAudio.PHONE -> R.string.card_audio_phone_details
    CardAudio.GOOGLE -> R.string.card_audio_google_details
}
