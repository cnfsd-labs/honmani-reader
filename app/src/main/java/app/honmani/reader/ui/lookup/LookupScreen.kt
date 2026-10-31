package app.honmani.reader.ui.lookup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.showAnkiResult
import app.honmani.reader.ui.anki.showVoiceProblem
import app.honmani.reader.ui.reader.LookupPanel
import app.honmani.reader.ui.reader.ReaderViewModel
import app.honmani.reader.ui.reader.TapLookup

/**
 * A search field over the screen, the text's characters to tap (a lookup starts at the one
 * tapped, as in a book) and the lookup panel below. Lookups run on search or a tap only, not
 * while typing: each one is saved to Words, and half-typed words don't belong there.
 */
@Composable
fun LookupScreen(
    initialText: String,
    onDone: () -> Unit,
    viewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory),
) {
    val context = LocalContext.current
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    val anki = rememberAnkiAccess()
    var query by rememberSaveable { mutableStateOf(initialText) }
    // The text looked up and where in it; -1 before the first lookup.
    var looked by rememberSaveable { mutableStateOf(initialText) }
    var start by rememberSaveable { mutableIntStateOf(-1) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    fun lookUp(text: String, at: Int) {
        if (text.isBlank()) return
        looked = text
        start = at
        keyboard?.hide()
        viewModel.onTap(lookupAt(text, at))
    }

    LaunchedEffect(Unit) {
        if (initialText.isNotEmpty()) {
            if (start < 0) lookUp(initialText, 0)
        } else {
            focus.requestFocus()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.ankiResults.collect { (expression, result) -> showAnkiResult(context, expression, result) }
    }
    LaunchedEffect(viewModel) {
        viewModel.voiceProblems.collect { showVoiceProblem(context, it) }
    }
    BackHandler { if (!viewModel.back()) onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .imePadding(),
    ) {
        Surface(
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.lookup_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                focus.requestFocus()
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { lookUp(query.trim(), 0) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (start >= 0 && looked.length > 1) {
                    TextCharacters(looked, selected = start, onTap = { lookUp(looked, it) })
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // A tap beside the panel closes the lookup, as one beside a dialog does.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        ) {
            val state = panel
            if (state != null) {
                LookupPanel(
                    state = state,
                    onClose = onDone,
                    onAddToAnki = { group -> anki.whenReady { viewModel.addToAnki(group) } },
                    onListen = viewModel::listen,
                    onLookupWord = viewModel::lookupWord,
                    onShowKanji = viewModel::showKanji,
                    onBack = { viewModel.back() },
                    onShorterMatch = { viewModel.changeMatch(longer = false) },
                    onLongerMatch = { viewModel.changeMatch(longer = true) },
                    onOpenDictionaries = onDone,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .heightIn(max = maxHeight)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                )
            }
        }
    }
}

/** The looked-up text, one tappable character each, scrolling sideways; [selected] is marked. */
@Composable
private fun TextCharacters(text: String, selected: Int, onTap: (Int) -> Unit) {
    val description = stringResource(R.string.lookup_from_here)
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        var index = 0
        while (index < text.length) {
            val at = index
            val character = String(Character.toChars(text.codePointAt(at)))
            index += character.length
            if (character.isBlank()) continue
            val isSelected = at == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(CHARACTER_SIZE)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(role = Role.Button) { onTap(at) }
                    .semantics { contentDescription = "$character, $description" },
            ) {
                Text(
                    character,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A lookup of [text] from [at] on, like a tap there in a book: the text is its sentence. */
internal fun lookupAt(text: String, at: Int): TapLookup {
    val from = at.coerceIn(0, text.length)
    return TapLookup(
        text = text.substring(from).take(SCAN_LENGTH),
        sentence = text,
        sentenceOffset = from,
    )
}

/** The reader's scan length: characters from the tapped one that a lookup tries. */
private const val SCAN_LENGTH = 20
private const val SCRIM_ALPHA = 0.32f
private val CHARACTER_SIZE = 44.dp
