package app.honmani.reader.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.review.ReviewSentence
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.FuriganaOverride
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.rememberAnkiEnabled
import app.honmani.reader.ui.anki.showAnkiResult
import app.honmani.reader.ui.reader.FuriganaDialog
import app.honmani.reader.ui.reader.LookupPanel
import app.honmani.reader.ui.reader.ReaderViewModel
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The review: one card at a time. Tap to show the answer, then Forgot or Got it (or swipe left
 * or right). Every answer is saved at once, so Back keeps what was done.
 */
@Composable
fun ReviewScreen(
    onClose: () -> Unit,
    /** Opens the book (by its library id) a card's sentence came from. */
    onOpenBook: (Long) -> Unit,
    viewModel: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
    // Its own lookup panel: a look-up on a card saves no word and counts as no lapse.
    panelModel: ReaderViewModel = viewModel(key = "review", factory = ReaderViewModel.ReviewFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val anki = rememberAnkiAccess()
    val ankiEnabled = rememberAnkiEnabled()
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val result = snackbar.showSnackbar(
                message = resources.getString(notice.message, notice.word),
                actionLabel = if (notice.undoable) resources.getString(R.string.action_undo) else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }
    LaunchedEffect(panelModel) {
        panelModel.ankiResults.collect { (expression, result) -> showAnkiResult(context, expression, result) }
    }
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                ReviewTopBar(
                    state = state,
                    viewModel = viewModel,
                    onClose = onClose,
                    onLookUp = { card -> panelModel.showSearched(card.expression, card.reading) },
                    onOpenBook = onOpenBook,
                )
            },
            // The answer buttons sit in the bottom bar so a snackbar shows above them, never over them.
            bottomBar = {
                if (state.card != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnswerButtons(
                            revealed = state.revealed,
                            onReveal = viewModel::reveal,
                            onForgot = viewModel::forgot,
                            onGotIt = viewModel::gotIt,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val card = state.card
                if (card != null) {
                    CardFace(
                        card = card,
                        revealed = state.revealed,
                        showSentence = state.showSentence,
                        onReveal = viewModel::reveal,
                        onForgot = viewModel::forgot,
                        onGotIt = viewModel::gotIt,
                        modifier = Modifier.weight(1f),
                    )
                } else if (!state.loading) {
                    Finished(state, onClose, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    val panel by panelModel.panel.collectAsStateWithLifecycle()
    val shown = panel
    if (shown != null) {
        BackHandler { if (!panelModel.back()) panelModel.dismiss() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = panelModel::dismiss),
        ) {
            LookupPanel(
                state = shown,
                availableHeight = maxHeight,
                onClose = panelModel::dismiss,
                onOpenDictionaries = panelModel::dismiss,
                onAddToAnki = { group -> anki.whenReady { panelModel.addToAnki(group) } },
                showAnki = ankiEnabled,
                onSetStarred = panelModel::setStarred,
                onListen = panelModel::listen,
                onLookupWord = panelModel::lookupWord,
                onShowKanji = panelModel::showKanji,
                onBack = { panelModel.back() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            )
        }
    }
}

@Composable
private fun ReviewTopBar(
    state: ReviewUiState,
    viewModel: ReviewViewModel,
    onClose: () -> Unit,
    onLookUp: (ReviewCardUi) -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var editingFurigana by remember { mutableStateOf(false) }
    val card = state.card
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.review_close))
            }
            Text(
                text = if (card != null) stringResource(R.string.review_left, state.remaining) else stringResource(R.string.review_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::undo, enabled = state.canUndo) {
                Text(stringResource(R.string.action_undo))
            }
            if (card != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val close = { menuOpen = false }
                        MenuItem(R.string.review_bury, close, viewModel::bury)
                        MenuItem(R.string.review_mark_known, close, viewModel::markKnown)
                        MenuItem(R.string.review_suspend, close, viewModel::suspend)
                        MenuItem(R.string.review_now, close, viewModel::reviewNow)
                        MenuItem(R.string.review_edit_meaning, close) { editing = true }
                        MenuItem(if (card.note.isEmpty()) R.string.review_add_note else R.string.review_edit_note, close) {
                            editingNote = true
                        }
                        if (FuriganaOverride.canCorrect(card.expression)) {
                            MenuItem(R.string.furigana_correct, close) { editingFurigana = true }
                        }
                        MenuItem(R.string.action_lookup_word, close) { onLookUp(card) }
                        val bookId = card.sentence?.bookId
                        if (bookId != null) MenuItem(R.string.review_open_in_book, close) { onOpenBook(bookId) }
                        MenuItem(R.string.review_not_a_word, close, viewModel::remove)
                        MenuItem(R.string.review_remove, close, viewModel::remove)
                    }
                }
            }
        }
    }
    if (editing && card != null) {
        var text by remember(card.wordId) { mutableStateOf(card.gloss) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.review_edit_title, card.expression)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.review_edit_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    viewModel.editMeaning(text.trim())
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (editingFurigana && card != null) {
        FuriganaDialog(
            expression = card.expression,
            reading = card.reading,
            furigana = card.furigana,
            onSave = {
                editingFurigana = false
                viewModel.editFurigana(it)
            },
            onDismiss = { editingFurigana = false },
        )
    }
    if (editingNote && card != null) {
        var text by remember(card.wordId) { mutableStateOf(card.note) }
        AlertDialog(
            onDismissRequest = { editingNote = false },
            title = { Text(stringResource(R.string.review_note_title, card.expression)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(WordRepository.MAX_NOTE_LENGTH) },
                    label = { Text(stringResource(R.string.review_note_label)) },
                    supportingText = { Text("${text.length}/${WordRepository.MAX_NOTE_LENGTH}") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingNote = false
                    viewModel.editNote(text)
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editingNote = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MenuItem(@androidx.annotation.StringRes label: Int, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            close()
            action()
        },
    )
}

/** The card: the front, and below it the back once shown. Swiping it (after the answer is shown) grades it. */
@Composable
private fun CardFace(
    card: ReviewCardUi,
    revealed: Boolean,
    showSentence: Boolean,
    onReveal: () -> Unit,
    onForgot: () -> Unit,
    onGotIt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sentence = card.sentence.takeIf { showSentence }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val revealedNow by rememberUpdatedState(revealed)
    val forgotNow by rememberUpdatedState(onForgot)
    val gotItNow by rememberUpdatedState(onGotIt)
    val frontText = sentence?.text ?: card.expression
    val description = if (revealed) {
        val meaning = card.gloss.ifBlank { stringResource(R.string.review_no_meaning) }
        if (card.note.isEmpty()) {
            stringResource(R.string.review_card_back, frontText, card.shownReading, meaning)
        } else {
            stringResource(R.string.review_card_back_note, frontText, card.shownReading, meaning, card.note)
        }
    } else {
        stringResource(R.string.review_card_front, frontText)
    }
    val forgotLabel = stringResource(R.string.review_forgot)
    val gotItLabel = stringResource(R.string.review_got_it)
    val showLabel = stringResource(R.string.review_show_answer)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val distance = constraints.maxWidth * SWIPE_FRACTION
        val progress = (offset.value / distance).coerceIn(-1f, 1f)
        val tint = when {
            progress < 0 -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }
        OutlinedCard(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    rotationZ = progress * 4f
                }
                .clip(MaterialTheme.shapes.medium)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val moved = offset.value
                            scope.launch {
                                when {
                                    revealedNow && moved <= -distance -> forgotNow()
                                    revealedNow && moved >= distance -> gotItNow()
                                }
                                offset.snapTo(0f)
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            if (revealedNow) scope.launch { offset.snapTo((offset.value + amount).coerceIn(-distance * 1.4f, distance * 1.4f)) }
                        },
                    )
                }
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    if (!revealed) onClick(label = showLabel) { onReveal(); true }
                    if (revealed) {
                        customActions = listOf(
                            CustomAccessibilityAction(forgotLabel) { onForgot(); true },
                            CustomAccessibilityAction(gotItLabel) { onGotIt(); true },
                        )
                    }
                },
            colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                containerColor = lerp(MaterialTheme.colorScheme.surface, tint, abs(progress) * 0.8f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!revealed) Modifier.clickable(onClick = onReveal) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (card.isNew) {
                    Text(
                        stringResource(R.string.review_new_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (sentence != null) {
                    Text(
                        marked(sentence),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(card.expression, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                }
                if (revealed) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (sentence != null) "${card.expression} · ${card.shownReading}" else card.shownReading,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        card.gloss.ifBlank { stringResource(R.string.review_no_meaning) },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (card.note.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.review_note_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            card.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    val title = card.sentence?.bookTitle
                    if (!title.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.review_from_book, title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** The sentence with the looked-up word set apart. */
@Composable
private fun marked(sentence: ReviewSentence): AnnotatedString {
    val highlight = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    )
    return buildAnnotatedString {
        append(sentence.text.substring(0, sentence.start))
        withStyle(highlight) { append(sentence.text.substring(sentence.start, sentence.end)) }
        append(sentence.text.substring(sentence.end))
    }
}

@Composable
private fun AnswerButtons(revealed: Boolean, onReveal: () -> Unit, onForgot: () -> Unit, onGotIt: () -> Unit) {
    if (!revealed) {
        Button(
            onClick = onReveal,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BUTTON_HEIGHT),
        ) { Text(stringResource(R.string.review_show_answer)) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onForgot,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = BUTTON_HEIGHT),
            ) { Text(stringResource(R.string.review_forgot), maxLines = 1) }
            Button(
                onClick = onGotIt,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = BUTTON_HEIGHT),
            ) { Text(stringResource(R.string.review_got_it), maxLines = 1) }
        }
        Text(
            stringResource(R.string.review_swipe_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Finished(state: ReviewUiState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.review_done_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.forgotten + state.remembered > 0) {
                stringResource(R.string.review_done_counts, state.forgotten, state.remembered)
            } else {
                stringResource(R.string.review_done_empty)
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose) { Text(stringResource(R.string.review_done_close)) }
    }
}

private val BUTTON_HEIGHT = 56.dp
private const val SWIPE_FRACTION = 0.3f
private const val SCRIM_ALPHA = 0.32f
