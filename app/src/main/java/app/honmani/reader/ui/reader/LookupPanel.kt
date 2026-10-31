package app.honmani.reader.ui.reader

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.lookup.DictionarySection
import app.honmani.reader.lookup.Frequency
import app.honmani.reader.lookup.Furigana
import app.honmani.reader.lookup.GlossLine
import app.honmani.reader.lookup.Inflection
import app.honmani.reader.lookup.JapaneseText
import app.honmani.reader.lookup.KanjiInfo
import app.honmani.reader.lookup.LookupGroup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.lookup.PitchAccent
import app.honmani.reader.lookup.PitchMora
import app.honmani.reader.lookup.Sense
import app.honmani.reader.ui.theme.HonmaniTheme

/**
 * Dictionary results for the last tap, at the bottom of the reader. The book stays tappable.
 * Swiping down closes it: on the header row, or on the results when they are already scrolled to
 * the top when the swipe starts (so scrolling back up never closes it by accident).
 */
@Composable
fun LookupPanel(
    state: LookupPanelState,
    onClose: () -> Unit,
    onOpenDictionaries: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToAnki: (LookupGroup) -> Unit = {},
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
) {
    // Moved synchronously while dragging; an earlier version queued each move as a coroutine,
    // and moves that ran after the release cancelled the closing animation, leaving the panel
    // half pushed down (it looked smaller instead of closing).
    var offset by remember { mutableFloatStateOf(0f) }
    val height = remember { floatArrayOf(0f) }
    val closeDistance = with(LocalDensity.current) { CLOSE_DISTANCE.toPx() }
    val closeVelocity = with(LocalDensity.current) { CLOSE_VELOCITY.toPx() }
    val listState = rememberLazyListState()
    val swipe = remember { SwipeState() }
    // A new word (a referenced one, or back) starts at the top.
    val groups = (state as? LookupPanelState.Results)?.groups
    LaunchedEffect(groups) { if (groups != null) listState.scrollToItem(0) }
    // How much of each result is shown, by position; reset for every new word.
    val details = remember(groups) { mutableStateMapOf<Int, Detail>() }

    fun dragBy(delta: Float) {
        offset = (offset + delta).coerceAtLeast(0f)
    }

    suspend fun settle(velocity: Float) {
        val closes = offset > closeDistance || offset > height[0] * CLOSE_FRACTION ||
            (velocity > closeVelocity && offset > 0f)
        if (closes) {
            animate(offset, maxOf(height[0], offset), initialVelocity = maxOf(velocity, 0f)) { value, _ ->
                offset = value
            }
            onClose()
        } else {
            animate(offset, 0f, initialVelocity = velocity) { value, _ -> offset = value }
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
                if (offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offset.roundToInt()) }
            .onSizeChanged { height[0] = it.height.toFloat() },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = rememberDraggableState { dragBy(it) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> settle(velocity) },
                    )
                    .padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canGoBack = when (state) {
                    is LookupPanelState.Results -> state.previous != null
                    is LookupPanelState.Kanji -> state.previous != null
                    else -> false
                }
                // The tapped sentence is not repeated here: it is on screen in the book (only the
                // sentence before it, if the reader asked, see [previousSentence]). Back sits
                // beside close, where the thumb already is.
                if (state is LookupPanelState.Results && state.previous == null) {
                    MatchControls(
                        matched = state.tap.text.take(state.highlight.length),
                        canShorten = state.canShorten,
                        canLengthen = state.canLengthen,
                        onShorter = onShorterMatch,
                        onLonger = onLongerMatch,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (state.tap.sentence.isNotBlank()) {
                    IconButton(onClick = onReadSentence, modifier = Modifier.size(HEADER_BUTTON)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_listen),
                            contentDescription = stringResource(R.string.lookup_read_sentence),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canGoBack) {
                    IconButton(onClick = onBack, modifier = Modifier.size(HEADER_BUTTON)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lookup_back),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(HEADER_BUTTON)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lookup_close))
                }
            }
            if (previousSentence.isNotBlank() && state is LookupPanelState.Results && state.previous == null) {
                Text(
                    text = previousSentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            when (state) {
                is LookupPanelState.Results -> LazyColumn(
                    state = listState,
                    // Shares the panel's maximum height with the header row.
                    modifier = Modifier
                        .weight(1f, fill = false)
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
                        if (i > 0) HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
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
                            onListen = { onListen(group) },
                            onLookupWord = onLookupWord,
                            kanji = state.kanji,
                            onShowKanji = onShowKanji,
                        )
                    }
                }
                is LookupPanelState.Kanji -> KanjiCard(
                    kanji = state.kanji,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
                is LookupPanelState.NothingFound -> Message(
                    stringResource(R.string.lookup_nothing_found, state.tap.text.take(MAX_SHOWN_TEXT)),
                )
                is LookupPanelState.NoDictionaries -> Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Message(stringResource(R.string.lookup_no_dictionaries))
                    TextButton(onClick = onOpenDictionaries, modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.lookup_open_dictionaries))
                    }
                }
            }
        }
    }
}

private const val MAX_SHOWN_TEXT = 8

/**
 * The matched text of a tap with buttons to make it one character shorter or longer; a button
 * that can't do anything is left out (its place stays, so the text doesn't jump).
 */
@Composable
private fun MatchControls(
    matched: String,
    canShorten: Boolean,
    canLengthen: Boolean,
    onShorter: () -> Unit,
    onLonger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (canShorten) {
            IconButton(onClick = onShorter, modifier = Modifier.size(HEADER_BUTTON)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.lookup_match_shorter),
                )
            }
        } else {
            Spacer(modifier = Modifier.size(HEADER_BUTTON))
        }
        Text(
            text = matched,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (canLengthen) {
            IconButton(onClick = onLonger, modifier = Modifier.size(HEADER_BUTTON)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.lookup_match_longer),
                )
            }
        } else {
            Spacer(modifier = Modifier.size(HEADER_BUTTON))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/** The header's buttons, a little smaller than the usual 48 dp so the header stays slim. */
private val HEADER_BUTTON = 40.dp
private val CLOSE_DISTANCE = 56.dp
private val CLOSE_VELOCITY = 600.dp

/** Pushed down by this share of its height, the panel closes on release. */
private const val CLOSE_FRACTION = 0.25f

/** Whether the current touch on the results began with them scrolled to the top. */
private class SwipeState {
    var startedAtTop = false
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
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
    onLookupWord: (String) -> Unit,
    onListen: () -> Unit = {},
    kanji: Map<String, KanjiInfo> = emptyMap(),
    onShowKanji: (String) -> Unit = {},
) {
    val folded = detail == Detail.FOLDED
    Column(
        modifier = if (folded) Modifier.clickable { onDetail(Detail.BRIEF) } else Modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            // Tapping the word of an open result folds it again.
            modifier = if (folded) Modifier else Modifier.clickable { onDetail(Detail.FOLDED) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // Kanji open their entry once the result is open (a folded one opens first).
                HeadwordWithPitch(group, if (folded) emptySet() else kanji.keys, onShowKanji)
            }
            group.frequencies.firstOrNull()?.let { FrequencyBadge(it) }
            IconButton(onClick = onListen) {
                Icon(
                    painter = painterResource(R.drawable.ic_listen),
                    contentDescription = stringResource(R.string.action_listen),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAddToAnki, enabled = !adding) {
                if (adding) {
                    // Making the card (with audio) takes a few seconds.
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
        if (folded) {
            FoldedMeaning(group)
            return@Column
        }
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
    }
}

/**
 * The word with furigana over its kanji, so it can be read at a glance. Kanji in [tappable]
 * are underlined in the accent colour and open their entry through [onKanji].
 */
@Composable
private fun Headword(
    expression: String,
    reading: String,
    tappable: Set<String> = emptySet(),
    onKanji: (String) -> Unit = {},
) {
    val segments = remember(expression, reading) { Furigana.segments(expression, reading) }
    // Every segment reserves the ruby line, so kana and kanji share one baseline.
    val rubyStyle = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp)
    Row(verticalAlignment = Alignment.Bottom) {
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
                                    .clickable { onKanji(character) }
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
    tappableKanji: Set<String> = emptySet(),
    onKanji: (String) -> Unit = {},
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
        Headword(group.expression, group.reading, tappableKanji, onKanji)
        patterns.forEach { PitchReading(it, Modifier.padding(bottom = 4.dp)) }
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
                    .clickable { onShowKanji(info.character) }
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
                .width(32.dp)
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
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** A folded result: its first meaning on one line. */
@Composable
private fun FoldedMeaning(group: LookupGroup) {
    val meaning = group.sections.firstNotNullOfOrNull { section ->
        section.senses.firstOrNull()?.glosses?.joinToString("; ")
    }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = meaning,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.lookup_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How common the word is ("#727"), from the first frequency dictionary. Tapping it explains
 * the number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrequencyBadge(frequency: Frequency) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.lookup_frequency_explained, frequency.dictionaryTitle, frequency.display))
            }
        },
        state = tooltip,
    ) {
        Surface(
            onClick = { scope.launch { tooltip.show() } },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = "#${frequency.display}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
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
            Text(
                text = if (senseCount > 1) "${i + 1}. $glosses" else glosses,
                style = MaterialTheme.typography.bodyMedium,
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
            onClose = {},
            onOpenDictionaries = {},
        )
    }
}
