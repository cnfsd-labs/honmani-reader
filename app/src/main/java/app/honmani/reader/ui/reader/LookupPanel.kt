package app.honmani.reader.ui.reader

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.data.settings.AppearanceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.DictionarySection
import app.honmani.reader.lookup.Frequency
import app.honmani.reader.lookup.Furigana
import app.honmani.reader.lookup.FuriganaOverride
import app.honmani.reader.lookup.GlossLine
import app.honmani.reader.lookup.Inflection
import app.honmani.reader.lookup.JapaneseText
import app.honmani.reader.lookup.KanjiInfo
import app.honmani.reader.lookup.LookupGroup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.lookup.PitchAccent
import app.honmani.reader.lookup.PitchMora
import app.honmani.reader.lookup.RubySegment
import app.honmani.reader.lookup.Sense
import app.honmani.reader.ui.theme.HonmaniTheme
import app.honmani.reader.ui.theme.LocalMotionEnabled
import app.honmani.reader.ui.theme.motionSpring
import app.honmani.reader.ui.theme.motionTween

/**
 * Dictionary results for the last tap, at the bottom of the reader. The book stays tappable.
 * The panel always opens at the same height ([PanelSizing]: about half of [availableHeight]) so its
 * top edge stays put from word to word; the content scrolls inside it. Dragging the handle or
 * header up expands it (about 88%), tapping the handle toggles. Swiping down collapses and then
 * closes it: on the header row, or on the results when they are already scrolled to the top when
 * the swipe starts (so scrolling back up never closes it by accident). A new tap opens collapsed;
 * back, kanji and longer or shorter keep the state.
 */
@Composable
fun LookupPanel(
    state: LookupPanelState,
    /** The height the panel may use, e.g. the reader area; the panel sizes itself from it. */
    availableHeight: Dp,
    onClose: () -> Unit,
    onOpenDictionaries: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToAnki: (LookupGroup) -> Unit = {},
    /** Whether words offer Add to Anki (see [app.honmani.reader.anki.AnkiSettings.isEnabled]). */
    showAnki: Boolean = true,
    /** The star of a word: star it (true) or remove the star. Words reached from a reference have no star. */
    onSetStarred: (LookupGroup, Boolean) -> Unit = { _, _ -> },
    /** "Correct furigana" in a word's overflow menu: save the corrected furigana (blank removes it); null shows no such entry. */
    onSetFurigana: ((LookupGroup, String) -> Unit)? = null,
    /** "Ignore word" in a word's overflow menu: ignore it (true) or stop ignoring it. */
    onSetIgnored: (LookupGroup, Boolean) -> Unit = { _, _ -> },
    /** Opens the search screen with a text filled in, when nothing was found; null shows no such button. */
    onEditLookup: ((String) -> Unit)? = null,
    /** The listen button of a word: say it. */
    onListen: (LookupGroup) -> Unit = {},
    /** A word referenced in a definition was tapped. */
    onLookupWord: (String) -> Unit = {},
    /** A kanji of a word was tapped; see [LookupPanelState.Results.kanji]. */
    onShowKanji: (String) -> Unit = {},
    /** Back to the results before [onLookupWord] or [onShowKanji]; shown when there are some. */
    onBack: () -> Unit = {},
    /** The matched text of a tap made one character shorter. */
    onShorterMatch: () -> Unit = {},
    /** The matched text of a tap made one character longer. */
    onLongerMatch: () -> Unit = {},
    /** The sentence before the tapped one, shown dimmed above the results; blank to show none. */
    previousSentence: String = "",
    /** The read-aloud button: say the tapped sentence. */
    onReadSentence: () -> Unit = {},
    /** "+ Learn" of a word found by a search: save it to Words; null (a tap in a book, which saves by itself) shows none. */
    onLearn: ((LookupGroup) -> Unit)? = null,
    /** "Don't count": takes back what this tap changed (see [ReaderViewModel.dontCount]); null when it changed nothing. */
    onDontCount: (() -> Unit)? = null,
    /** The highlight swatches (a selection's lookup, or a tap on a highlighted word); null shows none. */
    highlight: HighlightControls? = null,
) {
    // Moved synchronously while dragging; an earlier version queued each move as a coroutine,
    // and moves that ran after the release cancelled the closing animation, leaving the panel
    // half pushed down (it looked smaller instead of closing).
    var offset by remember { mutableFloatStateOf(0f) }
    // How far the panel is grown from its collapsed height, 0..heights.extra.
    var extra by remember { mutableFloatStateOf(0f) }
    val height = remember { floatArrayOf(0f) }
    val density = LocalDensity.current
    val appearance = rememberAppearanceSettings()
    val showPitch by (appearance?.showPitch ?: DEFAULT_SHOW_PITCH).collectAsStateWithLifecycle()
    val textPercent by (appearance?.lookupTextPercent ?: DEFAULT_TEXT_PERCENT).collectAsStateWithLifecycle()
    val closeDistance = with(density) { CLOSE_DISTANCE.toPx() }
    val closeVelocity = with(density) { CLOSE_VELOCITY.toPx() }
    val heights = PanelSizing.heights(
        available = with(density) { availableHeight.toPx() },
        minCollapsed = with(density) { MIN_COLLAPSED.toPx() },
        roomAbove = with(density) { (BUBBLE_TOUCH_HEIGHT - BUBBLE_INSET + BUBBLE_GAP).toPx() },
    )
    val maxExtra = rememberUpdatedState(heights.extra)
    // A new tap opens collapsed; navigation inside the panel keeps the tap and so the state.
    val shownTap = remember { arrayOf(state.tap) }
    if (shownTap[0] != state.tap) {
        shownTap[0] = state.tap
        extra = 0f
    }
    val scope = rememberCoroutineScope()
    // The drag's settle: the usual spring, a jump in E-ink mode.
    val settleSpec = motionSpring<Float>(stiffness = Spring.StiffnessMedium)
    val listState = rememberLazyListState()
    val swipe = remember { SwipeState() }
    // A new word (a referenced one, or back) starts at the top.
    val groups = (state as? LookupPanelState.Results)?.groups
    LaunchedEffect(groups) { if (groups != null) listState.scrollToItem(0) }
    // How much of each result is shown, by position; reset for every new word.
    val details = remember(groups) { mutableStateMapOf<Int, Detail>() }
    // The word whose furigana is being corrected.
    var correcting by remember { mutableStateOf<LookupGroup?>(null) }
    val correct = correcting
    if (correct != null && state is LookupPanelState.Results) {
        FuriganaDialog(
            expression = correct.expression,
            reading = correct.reading,
            furigana = state.furigana[WordRepository.key(correct.expression, correct.reading)].orEmpty(),
            onSave = {
                correcting = null
                onSetFurigana?.invoke(correct, it)
            },
            onDismiss = { correcting = null },
        )
    }

    // Up grows the panel (after bringing it back to its place), down shrinks it, then pushes it away.
    fun dragBy(delta: Float) {
        if (delta < 0f) {
            val back = maxOf(delta, -offset)
            offset += back
            extra = (extra - (delta - back)).coerceAtMost(maxExtra.value)
        } else {
            val shrink = minOf(delta, extra)
            extra -= shrink
            offset += delta - shrink
        }
    }

    suspend fun settleExtra(velocity: Float) {
        val target = if (PanelSizing.settlesExpanded(extra, maxExtra.value, velocity, closeVelocity)) maxExtra.value else 0f
        animate(extra, target, initialVelocity = -velocity, animationSpec = settleSpec) { value, _ -> extra = value }
    }

    suspend fun settle(velocity: Float) {
        if (offset <= 0f) {
            settleExtra(velocity)
            return
        }
        val closes = offset > closeDistance || offset > height[0] * CLOSE_FRACTION ||
            (velocity > closeVelocity && offset > 0f)
        if (closes) {
            animate(offset, maxOf(height[0], offset), initialVelocity = maxOf(velocity, 0f), animationSpec = settleSpec) { value, _ ->
                offset = value
            }
            onClose()
        } else {
            animate(offset, 0f, initialVelocity = velocity, animationSpec = settleSpec) { value, _ -> offset = value }
        }
    }

    val listConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up first returns the panel to its place.
                if (available.y < 0f && offset > 0f) {
                    val used = maxOf(available.y, -offset)
                    dragBy(used)
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput && swipe.startedAtTop) {
                    dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f && (extra <= 0f || extra >= maxExtra.value)) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    // Opening: slides up from its own height with a quick fade, by a graphics layer only (no layout
    // change, so the page behind never moves). Plays when the panel is composed, not when the word
    // changes; closing is the drag's own animation. Not at all in E-ink mode.
    val motion = LocalMotionEnabled.current
    val entered = remember { Animatable(if (motion) 0f else 1f) }
    val enterSpec = motionTween<Float>(ENTER_MS, LinearOutSlowInEasing)
    LaunchedEffect(Unit) { entered.animateTo(1f, enterSpec) }
    val copy = rememberCopy()
    // Outside the sheet, so its rounded clip can't cut the bubble; the empty space beside it takes no touches.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offset.roundToInt()) }
            .graphicsLayer {
                val progress = entered.value
                if (progress < 1f) {
                    translationY = (1f - progress) * size.height
                    alpha = progress
                }
            },
    ) {
    selectionBubble(state)?.let { bubble ->
        SelectionBubbleRow(
            bubble = bubble,
            onShorter = onShorterMatch,
            onLonger = onLongerMatch,
            onCopy = { copy(bubble.text) },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = BUBBLE_GAP - BUBBLE_INSET),
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { (heights.collapsed + extra).toDp() })
            .onSizeChanged { height[0] = it.height.toFloat() },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Box(modifier = Modifier.navigationBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // One thin row: the handle in the middle, back on the left, menu and close on the right.
                // All of it drags the panel; the tapped sentence is not repeated (it is on screen in the book).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HEADER_HEIGHT)
                        .draggable(
                            state = rememberDraggableState { dragBy(it) },
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> settle(velocity) },
                        ),
                ) {
                    val expanded = extra > maxExtra.value / 2f
                    val handleLabel = stringResource(if (expanded) R.string.lookup_panel_collapse else R.string.lookup_panel_expand)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(onClickLabel = handleLabel, role = Role.Button) {
                                scope.launch {
                                    animate(extra, if (expanded) 0f else maxExtra.value) { value, _ -> extra = value }
                                }
                            }
                            .semantics { contentDescription = handleLabel },
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
                    )
                    val canGoBack = when (state) {
                        is LookupPanelState.Results -> state.previous != null
                        is LookupPanelState.Kanji -> state.previous != null
                        else -> false
                    }
                    if (canGoBack) {
                        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.lookup_back),
                            )
                        }
                    }
                    Row(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                        PanelMenu(
                            onReadSentence = onReadSentence.takeIf { state.tap.sentence.isNotBlank() },
                            onDontCount = onDontCount.takeIf { state is LookupPanelState.Results && state.previous == null },
                            onCopyWord = copyableWord(state)?.let { word -> { copy(word) } },
                            onCopySentence = if (state.tap.sentence.isNotBlank()) {
                                { copy(state.tap.sentence) }
                            } else {
                                null
                            },
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lookup_close))
                        }
                    }
                }
                if (highlight != null && (state as? LookupPanelState.Results)?.previous == null) HighlightBar(highlight)
                if (previousSentence.isNotBlank() && state is LookupPanelState.Results && state.previous == null) {
                    Text(
                        text = previousSentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
                // Only the results scale, not the header, the bubble or the highlight bar (their height is fixed):
                // the font scale is multiplied, so dp sizes and the panel's height stay.
                val scaled = Density(density.density, density.fontScale * textPercent / 100f)
                CompositionLocalProvider(LocalShowPitch provides showPitch, LocalDensity provides scaled) {
                    when (state) {
                        is LookupPanelState.Results -> LazyColumn(
                            state = listState,
                            // Fills the panel below the header; a short list leaves empty space.
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        swipe.startedAtTop = !listState.canScrollBackward
                                    }
                                }
                                .nestedScroll(listConnection),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                items = state.groups,
                                key = { i, group -> "$i:${group.expression}:${group.reading}" },
                            ) { i, group ->
                                if (i > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                                // The best match opens with its first meanings; the others are folded
                                // to a line each until tapped.
                                val detail = details[i] ?: if (i == 0) Detail.BRIEF else Detail.FOLDED
                                LookupGroupItem(
                                    group = group,
                                    detail = detail,
                                    onDetail = { details[i] = it },
                                    inAnki = WordRepository.key(group.expression, group.reading) in state.inAnki,
                                    adding = WordRepository.key(group.expression, group.reading) in state.addingToAnki,
                                    onAddToAnki = { onAddToAnki(group) },
                                    showAnki = showAnki,
                                    starred = WordRepository.key(group.expression, group.reading) in state.starred,
                                    onSetStarred = if (state.previous == null) {
                                        { onSetStarred(group, it) }
                                    } else {
                                        null
                                    },
                                    ignored = WordRepository.key(group.expression, group.reading) in state.ignored,
                                    onSetIgnored = if (state.previous == null) {
                                        { onSetIgnored(group, it) }
                                    } else {
                                        null
                                    },
                                    onListen = { onListen(group) },
                                    onLookupWord = onLookupWord,
                                    onLearn = if (onLearn != null && state.previous == null) {
                                        { onLearn(group) }
                                    } else {
                                        null
                                    },
                                    saved = WordRepository.key(group.expression, group.reading) in state.saved,
                                    kanji = state.kanji,
                                    onShowKanji = onShowKanji,
                                    furigana = state.furigana[WordRepository.key(group.expression, group.reading)].orEmpty(),
                                    onCorrectFurigana = if (onSetFurigana != null && state.previous == null && FuriganaOverride.canCorrect(group.expression)) {
                                        { correcting = group }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        is LookupPanelState.Kanji -> KanjiCard(
                            kanji = state.kanji,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                        )
                        is LookupPanelState.NothingFound -> Column(modifier = Modifier.weight(1f)) {
                            Message(
                                stringResource(R.string.lookup_nothing_found, nothingFoundText(state.tap.text)),
                                bottomPadding = 0.dp,
                            )
                            if (onEditLookup != null) {
                                TextButton(onClick = { onEditLookup(state.tap.text) }, modifier = Modifier.padding(start = 8.dp)) {
                                    Text(stringResource(R.string.lookup_edit))
                                }
                            }
                        }
                        is LookupPanelState.NoDictionaries -> Column(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) {
                            Message(stringResource(R.string.lookup_no_dictionaries))
                            TextButton(onClick = onOpenDictionaries, modifier = Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.lookup_open_dictionaries))
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Whether words draw their pitch accent (Settings → Reading); results in a panel read it here. */
private val LocalShowPitch = compositionLocalOf { true }

private val DEFAULT_SHOW_PITCH = MutableStateFlow(true)

private val DEFAULT_TEXT_PERCENT = MutableStateFlow(AppearanceSettings.LOOKUP_TEXT_DEFAULT)

/** The app's appearance settings, or null where there is no app (a preview). */
@Composable
private fun rememberAppearanceSettings(): AppearanceSettings? {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as? HonmaniApplication)?.container?.appearanceSettings }
}

private const val MAX_SHOWN_TEXT = 8

/** The start of the tapped text for "No entry for": up to [MAX_SHOWN_TEXT] characters, cut before the first punctuation mark or space. */
internal fun nothingFoundText(text: String): String {
    val head = text.take(MAX_SHOWN_TEXT)
    val end = head.indexOfFirst { it.isWhitespace() || Character.getType(it) in PUNCTUATION_TYPES }
    return if (end > 0) head.substring(0, end) else head
}

private val PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
).map { it.toInt() }.toSet()

/**
 * What the lookup panel offers for highlighting. [current] is the colour (0-3, see [HIGHLIGHT_SWATCHES])
 * of the highlight a tap landed on, or null for a new selection; [onPick] saves a new highlight in
 * a colour or changes the colour of the tapped one; [onRemove] is only there for a tapped highlight.
 */
data class HighlightControls(
    val current: Int?,
    /** Saves a new highlight in the colour over the extent chosen, or recolours the tapped one (extent unused). */
    val onPick: (Int, HighlightExtent) -> Unit,
    val onRemove: (() -> Unit)?,
    /** For a new highlight: the extents to choose from with their text; empty for a tapped one. */
    val extents: Map<HighlightExtent, String> = emptyMap(),
    /** The extent the reader chose last (see [HighlightExtent.initial]) and what remembers a chip they tap. */
    val rememberedExtent: String? = null,
    val onExtentChosen: (HighlightExtent) -> Unit = {},
)

/** A label, the four colours and (for a highlight that exists) "Remove highlight". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighlightBar(controls: HighlightControls) {
    val names = listOf(
        R.string.highlight_yellow, R.string.highlight_green, R.string.highlight_blue, R.string.highlight_pink,
    ).map { stringResource(it) }
    // What a new highlight covers: the reader's last choice if offered, else a sentence unless the page found none.
    var extent by remember(controls.extents.keys) {
        mutableStateOf(HighlightExtent.initial(controls.extents.keys, controls.rememberedExtent))
    }
    if (controls.extents.size > 1) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in controls.extents.keys) {
                FilterChip(
                    selected = extent == option,
                    onClick = {
                        extent = option
                        controls.onExtentChosen(option)
                    },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    HighlightExtent.Selection -> R.string.highlight_extent_selection
                                    HighlightExtent.Sentence -> R.string.highlight_extent_sentence
                                    HighlightExtent.Paragraph -> R.string.highlight_extent_paragraph
                                },
                            ),
                        )
                    },
                )
            }
        }
        Text(
            text = controls.extents[extent].orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        )
    }
    FlowRow(
        modifier = Modifier.padding(start = 20.dp, end = 12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (controls.current == null) R.string.highlight_add else R.string.highlight_change),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        HIGHLIGHT_SWATCHES.forEachIndexed { index, color ->
            // 48 dp to touch, a smaller dot to look at.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = names[index]) { controls.onPick(index, extent) }
                    .semantics { contentDescription = names[index] },
            ) {
                Box(
                    modifier = Modifier
                        .size(if (controls.current == index) 30.dp else 26.dp)
                        .background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (controls.current == index) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (controls.onRemove != null) {
            TextButton(onClick = controls.onRemove) { Text(stringResource(R.string.highlight_remove)) }
        }
    }
}

/** Copies a text to the clipboard, with a toast where Android doesn't confirm it itself. */
@Composable
private fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { text ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
            // Android 13 and later confirm a copy themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.lookup_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * The tinted pill above the panel, see [SelectionBubble]: unlike a result's headword on purpose. An
 * arrow that can't do anything is dimmed, so the bubble keeps its shape while the match changes.
 */
@Composable
private fun SelectionBubbleRow(
    bubble: SelectionBubble,
    onShorter: () -> Unit,
    onLonger: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The pill is BUBBLE_HEIGHT tall; its buttons take touches over the full BUBBLE_TOUCH_HEIGHT.
    Box(modifier = modifier.height(BUBBLE_TOUCH_HEIGHT)) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = BUBBLE_INSET)
                .shadow(3.dp, CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
            Row(
                modifier = Modifier.height(BUBBLE_TOUCH_HEIGHT).padding(horizontal = if (bubble.hasArrows) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bubble.hasArrows) {
                    BubbleButton(
                        label = stringResource(R.string.lookup_match_shorter),
                        enabled = bubble.canShorten,
                        onClick = onShorter,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) }
                }
                Text(
                    text = bubble.text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 32.dp)
                        .padding(horizontal = if (bubble.hasArrows) 0.dp else 8.dp),
                )
                if (bubble.hasArrows) {
                    BubbleButton(
                        label = stringResource(R.string.lookup_match_longer),
                        enabled = bubble.canLengthen,
                        onClick = onLonger,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
                }
                if (bubble.canCopy) {
                    BubbleButton(label = stringResource(R.string.lookup_copy_word), onClick = onCopy) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** A button of the bubble: a full touch target, over the bubble's height and a little beyond. */
@Composable
private fun BubbleButton(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 48.dp, height = BUBBLE_TOUCH_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else 0.3f),
    ) {
        icon()
    }
}

/** The panel's overflow menu; a null action leaves its entry out, and without any there is no menu. */
@Composable
private fun PanelMenu(
    onReadSentence: (() -> Unit)?,
    onDontCount: (() -> Unit)?,
    onCopyWord: (() -> Unit)?,
    onCopySentence: (() -> Unit)?,
) {
    val entries = listOfNotNull(
        onReadSentence?.let { R.string.lookup_read_sentence to it },
        onDontCount?.let { R.string.lookup_dont_count to it },
        onCopyWord?.let { R.string.lookup_copy_word to it },
        onCopySentence?.let { R.string.lookup_copy_sentence to it },
    )
    if (entries.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.action_more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, action) in entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** The header row: the height of its buttons, for the thumb at the bottom of a tall phone. */
private val HEADER_HEIGHT = 48.dp
private val BUBBLE_HEIGHT = 40.dp
private val BUBBLE_TOUCH_HEIGHT = 48.dp
private val BUBBLE_INSET = (BUBBLE_TOUCH_HEIGHT - BUBBLE_HEIGHT) / 2
private val BUBBLE_GAP = 6.dp

/** In a short (landscape) area the collapsed panel is at least this tall, up to 60% of the area. */
private val MIN_COLLAPSED = 220.dp
private val CLOSE_DISTANCE = 56.dp
private val CLOSE_VELOCITY = 600.dp

/** Pushed down by this share of its height, the panel closes on release. */
private const val CLOSE_FRACTION = 0.25f
private const val ENTER_MS = 180

/** Whether the current touch on the results began with them scrolled to the top. */
private class SwipeState {
    var startedAtTop = false
}

@Composable
private fun Message(text: String, bottomPadding: Dp = 16.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = bottomPadding),
    )
}

/** How much of a result the panel shows. */
private enum class Detail {
    /** The word and its first meaning on one line. */
    FOLDED,

    /** The first meanings of the first dictionary, without examples. */
    BRIEF,

    /** Everything. */
    FULL,
}

@Composable
private fun LookupGroupItem(
    group: LookupGroup,
    detail: Detail,
    onDetail: (Detail) -> Unit,
    inAnki: Boolean,
    adding: Boolean,
    onAddToAnki: () -> Unit,
    starred: Boolean,
    onSetStarred: ((Boolean) -> Unit)?,
    onLookupWord: (String) -> Unit,
    showAnki: Boolean = true,
    ignored: Boolean = false,
    onSetIgnored: ((Boolean) -> Unit)? = null,
    onListen: () -> Unit = {},
    onLearn: (() -> Unit)? = null,
    saved: Boolean = false,
    kanji: Map<String, KanjiInfo> = emptyMap(),
    onShowKanji: (String) -> Unit = {},
    /** The reader's furigana correction of this word (see [FuriganaOverride]); empty if none. */
    furigana: String = "",
    /** Opens the furigana editor; null (a word reached from a reference, or kana only) shows no such entry. */
    onCorrectFurigana: (() -> Unit)? = null,
) {
    val folded = detail == Detail.FOLDED
    val expandLabel = stringResource(R.string.lookup_expand)
    Column(
        modifier = if (folded) Modifier.clickable(onClickLabel = expandLabel) { onDetail(Detail.BRIEF) } else Modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (folded) {
            // A line to scan: the word only. Its pitch, frequency and buttons come when it is opened.
            HeadwordWithPitch(group, furigana, starred = starred, plain = true)
        } else {
            Row(
                // Tapping the word of an open result folds it again.
                modifier = Modifier.clickable { onDetail(Detail.FOLDED) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HeadwordWithPitch(group, furigana, kanji.keys, onShowKanji, starred = starred)
                    group.frequencies.firstOrNull()?.let { FrequencyBadge(it, quiet = true) }
                }
                IconButton(onClick = onListen) {
                    Icon(
                        painter = painterResource(R.drawable.ic_listen),
                        contentDescription = stringResource(R.string.action_listen),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showAnki) {
                    IconButton(onClick = onAddToAnki, enabled = !adding) {
                        if (adding) {
                            // Making the card (with audio) takes a few seconds.
                            val addingText = stringResource(R.string.anki_adding)
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).semantics { contentDescription = addingText },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(if (inAnki) R.drawable.ic_in_anki else R.drawable.ic_add_card),
                                contentDescription = stringResource(
                                    if (inAnki) R.string.word_in_anki else R.string.action_add_to_anki,
                                ),
                                tint = if (inAnki) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (onSetStarred != null || onSetIgnored != null || onCorrectFurigana != null) {
                    // Used now and then, so out of the way: the star shows beside the word once set.
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.action_more_options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (onSetStarred != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (starred) R.string.action_unstar_word else R.string.action_star_word)) },
                                    onClick = {
                                        menu = false
                                        onSetStarred(!starred)
                                    },
                                )
                            }
                            if (onCorrectFurigana != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.furigana_correct)) },
                                    onClick = {
                                        menu = false
                                        onCorrectFurigana()
                                    },
                                )
                            }
                            if (onSetIgnored != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (ignored) R.string.action_unignore_word else R.string.action_ignore_word)) },
                                    onClick = {
                                        menu = false
                                        onSetIgnored(!ignored)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (folded) {
            FoldedMeaning(group)
            return@Column
        }
        if (onLearn != null) LearnButton(saved, onLearn)
        val brief = detail == Detail.BRIEF
        // Grammar ("te-form · teiru") and dictionary names only in the full view.
        if (!brief) InflectionLabels(group)
        val sections = if (brief) group.sections.take(1) else group.sections
        var hidden = brief && (group.sections.size > 1 || LookupGroups.visibleInflections(group.inflections).isNotEmpty())
        sections.forEach { section ->
            val lines = if (detail == Detail.BRIEF) LookupGroups.brief(section.lines) else section.lines
            val senses = if (detail == Detail.BRIEF) section.senses.take(LookupGroups.BRIEF_SENSES) else section.senses
            // Lines are drawn when there are any; plain senses otherwise.
            val cut = if (section.lines.isNotEmpty()) lines.size < section.lines.size else senses.size < section.senses.size
            if (cut) hidden = true
            DictionarySectionItem(
                title = section.dictionaryTitle.takeUnless { brief },
                lines = lines,
                senses = senses,
                senseCount = section.senses.size,
                onLookupWord = onLookupWord,
            )
        }
        if (!brief) KanjiOfWord(group.expression, kanji, onShowKanji)
        when {
            brief && hidden -> DetailLink(stringResource(R.string.lookup_show_more)) { onDetail(Detail.FULL) }
            detail == Detail.FULL -> DetailLink(stringResource(R.string.lookup_show_less)) { onDetail(Detail.BRIEF) }
        }
        ExamplesRow(group.expression, group.reading, onLookupWord)
    }
}

/** "+ Learn" for a word found by a search, or that it is in Words already. */
@Composable
private fun LearnButton(saved: Boolean, onLearn: () -> Unit) {
    if (saved) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.lookup_in_words),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        FilledTonalButton(onClick = onLearn) {
            Text(stringResource(R.string.lookup_learn))
        }
    }
}

/**
 * The word with furigana over its kanji, so it can be read at a glance. Kanji in [tappable]
 * are underlined in the accent colour and open their entry through [onKanji].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Headword(
    segments: List<RubySegment>,
    tappable: Set<String> = emptySet(),
    onKanji: (String) -> Unit = {},
) {
    // Every segment reserves the ruby line, so kana and kanji share one baseline.
    val rubyStyle = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp)
    // Wraps between segments, so a long expression stays readable at a large font size.
    val showKanji = stringResource(R.string.lookup_show_kanji)
    FlowRow(itemVerticalAlignment = Alignment.Bottom) {
        segments.forEach { segment ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = segment.ruby.orEmpty(),
                    style = rubyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Row {
                    val underline = MaterialTheme.colorScheme.primary
                    val stroke = with(LocalDensity.current) { 1.5.dp.toPx() }
                    characters(segment.text).forEach { character ->
                        Text(
                            text = character,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = if (character in tappable) {
                                Modifier
                                    // Padding the glyph to 48 dp would push the word apart.
                                    .clickable(onClickLabel = showKanji, role = Role.Button) { onKanji(character) }
                                    .drawBehind {
                                        val y = size.height - stroke
                                        drawLine(underline, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), stroke)
                                    }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The word, and its reading with the pitch accent drawn over it beside the word when there is
 * room (below it otherwise).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadwordWithPitch(
    group: LookupGroup,
    furigana: String,
    tappableKanji: Set<String> = emptySet(),
    onKanji: (String) -> Unit = {},
    starred: Boolean = false,
    /** Only the word (and its star): a folded result. */
    plain: Boolean = false,
) {
    val reading = group.reading.ifEmpty { group.expression }
    val patterns = remember(reading, group.pitches) {
        group.pitches.map { PitchAccent.pattern(reading, it) }.filter { it.isNotEmpty() }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        val segments = remember(group.expression, group.reading, furigana) {
            Furigana.shown(group.expression, group.reading, furigana)
        }
        Headword(segments, tappableKanji, onKanji)
        if (starred) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.word_starred),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp).size(16.dp),
            )
        }
        if (plain) return@FlowRow
        if (LocalShowPitch.current) patterns.forEach { PitchReading(it, Modifier.padding(bottom = 4.dp)) }
        // Readings merged into this entry (same meanings), after the main one.
        if (group.otherReadings.isNotEmpty()) {
            Text(
                text = group.otherReadings.joinToString("・", prefix = "・"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** [text] split into characters (code points, so rare kanji outside the BMP stay whole). */
private fun characters(text: String): List<String> =
    text.codePoints().toArray().map { String(Character.toChars(it)) }

/** The kanji of the word with their meanings, in the full view; each opens its entry. */
@Composable
private fun KanjiOfWord(expression: String, kanji: Map<String, KanjiInfo>, onShowKanji: (String) -> Unit) {
    val shown = remember(expression, kanji) { JapaneseText.kanjiIn(expression).mapNotNull { kanji[it] } }
    if (shown.isEmpty()) return
    val showKanji = stringResource(R.string.lookup_show_kanji)
    Column {
        Text(
            text = stringResource(R.string.lookup_kanji_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        shown.forEach { info ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = showKanji, role = Role.Button) { onShowKanji(info.character) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(info.character, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = info.meanings.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One kanji: meanings, readings, stroke count, school grade and how common it is. */
@Composable
private fun KanjiCard(kanji: KanjiInfo, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(kanji.character, fontSize = 64.sp, lineHeight = 72.sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(kanji.meanings.joinToString(", "), style = MaterialTheme.typography.titleMedium)
                val facts = listOfNotNull(
                    kanji.strokes?.let { pluralStringResource(R.plurals.kanji_strokes, it, it) },
                    when (kanji.level) {
                        KanjiInfo.Level.SCHOOL -> stringResource(R.string.kanji_grade, kanji.grade ?: 0)
                        KanjiInfo.Level.JOUYOU -> stringResource(R.string.kanji_jouyou)
                        KanjiInfo.Level.NAMES -> stringResource(R.string.kanji_names)
                        null -> null
                    },
                    kanji.frequency?.let { stringResource(R.string.kanji_frequency, it) },
                )
                if (facts.isNotEmpty()) {
                    Text(
                        text = facts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        KanjiReadings(stringResource(R.string.kanji_onyomi), kanji.onyomi.map { AnnotatedString(it) })
        val okurigana = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
        KanjiReadings(
            stringResource(R.string.kanji_kunyomi),
            kanji.kunyomi.map { reading ->
                // "た.べる": the kanji reads た, べる is written after it in kana.
                buildAnnotatedString {
                    val dot = reading.indexOf('.')
                    if (dot < 0) {
                        append(reading)
                    } else {
                        append(reading.substring(0, dot))
                        withStyle(okurigana) { append(reading.substring(dot + 1)) }
                    }
                }
            },
        )
        Text(
            text = LookupGroups.shortTitle(kanji.dictionaryTitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun KanjiReadings(label: String, readings: List<AnnotatedString>) {
    if (readings.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .widthIn(min = 32.dp)
                .padding(top = 2.dp),
        )
        Text(
            text = buildAnnotatedString {
                readings.forEachIndexed { i, reading ->
                    if (i > 0) append("、")
                    append(reading)
                }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DetailLink(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A folded result: its first meaning on one line. */
@Composable
private fun FoldedMeaning(group: LookupGroup) {
    val meaning = group.sections.firstNotNullOfOrNull { section ->
        section.senses.firstOrNull()?.glosses?.joinToString("; ")
    }.orEmpty()
    Text(
        text = meaning,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * How common the word is ("#727"), from the first frequency dictionary. Tapping it explains
 * the number. [quiet] is plain small text, for under a headword with buttons beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrequencyBadge(frequency: Frequency, quiet: Boolean = false) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(
                    if (frequency.rank != null) {
                        stringResource(R.string.lookup_frequency_explained, frequency.dictionaryTitle, frequency.display)
                    } else {
                        stringResource(R.string.lookup_frequency_count_explained, frequency.dictionaryTitle, frequency.display)
                    },
                )
            }
        },
        state = tooltip,
    ) {
        if (quiet) {
            Text(
                text = frequency.badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                    .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
            )
        } else {
            Surface(
                onClick = { scope.launch { tooltip.show() } },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = frequency.badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** What was undone from the tapped form to the word: "te-form · teiru". */
@Composable
private fun InflectionLabels(group: LookupGroup) {
    val inflections = LookupGroups.visibleInflections(group.inflections)
    if (inflections.isEmpty()) return
    Text(
        text = inflections.map { stringResource(it.labelRes()) }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Kana with a line above the high morae and a step down after the downstep. */
@Composable
private fun PitchReading(pattern: List<PitchMora>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { 1.5.dp.toPx() }
    Row(modifier = modifier.padding(top = 3.dp)) {
        pattern.forEachIndexed { i, mora ->
            val rises = mora.high && i > 0 && !pattern[i - 1].high
            Text(
                text = mora.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.drawBehind {
                    val top = -stroke
                    if (mora.high) drawLine(color, Offset(0f, top), Offset(size.width, top), stroke)
                    if (rises) drawLine(color, Offset(0f, top), Offset(0f, size.height * 0.4f), stroke)
                    if (mora.dropsAfter) {
                        drawLine(color, Offset(size.width, top), Offset(size.width, size.height * 0.4f), stroke)
                    }
                },
            )
        }
    }
}

@Composable
private fun DictionarySectionItem(
    /** Null in the short view. */
    title: String?,
    lines: List<GlossLine>,
    senses: List<Sense>,
    senseCount: Int,
    onLookupWord: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title != null) {
            Text(
                text = LookupGroups.shortTitle(title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (lines.isNotEmpty()) {
            GlossLines(lines, onLookup = onLookupWord)
            return@Column
        }
        senses.forEachIndexed { i, sense ->
            val glosses = sense.glosses.joinToString("; ")
            ScanText(
                text = AnnotatedString(if (senseCount > 1) "${i + 1}. $glosses" else glosses),
                style = MaterialTheme.typography.bodyMedium,
                onScan = onLookupWord,
            )
        }
    }
}

@Preview
@Composable
private fun LookupPanelPreview() {
    val tap = TapLookup(text = "食べました。", sentence = "昨日、寿司を食べました。", sentenceOffset = 6)
    HonmaniTheme {
        LookupPanel(
            state = LookupPanelState.Results(
                tap = tap,
                groups = listOf(
                    LookupGroup(
                        expression = "食べる",
                        reading = "たべる",
                        source = "食べました",
                        inflections = listOf(Inflection.STEM, Inflection.POLITE, Inflection.PAST),
                        frequencies = listOf(Frequency("Jiten", "395", 395.0)),
                        pitches = listOf(2),
                        sections = listOf(
                            DictionarySection(
                                "Jitendex.org [2026-10-03]",
                                listOf(
                                    Sense(listOf("to eat")),
                                    Sense(listOf("to live on (e.g. a salary)", "to subsist on")),
                                ),
                            ),
                        ),
                    ),
                ),
                highlight = Highlight(lookupId = 1, length = 5),
            ),
            availableHeight = 700.dp,
            onClose = {},
            onOpenDictionaries = {},
        )
    }
}
