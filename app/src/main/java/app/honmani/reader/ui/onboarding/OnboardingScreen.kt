package app.honmani.reader.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.R
import app.honmani.reader.data.dictionary.CatalogDictionary
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.dictionary.FailureReason
import app.honmani.reader.data.dictionary.ImportStatus
import app.honmani.reader.ui.ProgressBar
import app.honmani.reader.ui.dictionaries.CatalogItem
import app.honmani.reader.ui.dictionaries.DictionariesUiState
import app.honmani.reader.ui.dictionaries.DictionariesViewModel
import app.honmani.reader.ui.theme.HonmaniTheme
import app.honmani.reader.ui.rememberWithNotifications
import app.honmani.reader.ui.theme.LocalMotionEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Where the one-tap dictionary download stands. */
sealed interface StarterPackState {
    data object NotStarted : StarterPackState

    /**
     * [fraction] is how far the whole pack is (0 to 1), or null while the running step reports
     * nothing yet (the bar is then indeterminate).
     */
    data class Downloading(val installed: Int, val total: Int, val fraction: Float? = null) : StarterPackState

    data object Ready : StarterPackState

    data class Failed(val reason: FailureReason = FailureReason.OTHER) : StarterPackState

    companion object {
        /** The pack's state from the Dictionaries screen's state; [started] once requested. */
        fun of(pack: List<CatalogDictionary>, state: DictionariesUiState, started: Boolean): StarterPackState {
            val loaded = state as? DictionariesUiState.Loaded ?: return NotStarted
            val items = loaded.catalog.filter { it.dictionary in pack }
            val installed = items.count { it.state == CatalogItem.State.INSTALLED }
            val downloading = Downloading(installed, pack.size, fractionOf(installed, pack.size, loaded.imports))
            return when {
                installed == pack.size -> Ready
                !started -> NotStarted
                // Failed only once nothing is left running; a failure beside running downloads waits.
                items.any { it.state == CatalogItem.State.IN_PROGRESS } -> downloading
                loaded.imports.any { it is ImportStatus.Failed } ->
                    Failed(loaded.imports.filterIsInstance<ImportStatus.Failed>().first().reason)
                else -> downloading
            }
        }

        /**
         * The pack's progress from the running import: its download is the first half of a step,
         * writing it to the database the second. Null when nothing reports a size yet.
         */
        fun fractionOf(installed: Int, total: Int, imports: List<ImportStatus>): Float? {
            val running = imports.filterIsInstance<ImportStatus.Running>().firstOrNull() ?: return null
            val step = when {
                running.download != null && running.download.bytesTotal > 0 ->
                    0.5f * (running.download.bytesDone.toFloat() / running.download.bytesTotal).coerceIn(0f, 1f)
                running.download != null -> return null
                running.progress != null -> 0.5f + 0.5f * (running.progress.percent / 100f).coerceIn(0f, 1f)
                else -> return null
            }
            return ((installed + step) / total).coerceIn(0f, 1f)
        }

        /**
         * The first failed download of a starter pack (either language) whose dictionary is still
         * missing, for a notice after the first-start screen is gone; null if there is none.
         */
        fun failedDownload(state: DictionariesUiState): ImportStatus.Failed? {
            val loaded = state as? DictionariesUiState.Loaded ?: return null
            val ids = (DictionaryCatalog.starterPack(false) + DictionaryCatalog.starterPack(true)).map { it.id }.toSet()
            val installed = loaded.catalog.filter { it.state == CatalogItem.State.INSTALLED }.map { it.dictionary.id }.toSet()
            return loaded.imports.filterIsInstance<ImportStatus.Failed>()
                .firstOrNull { it.dictionaryId != null && it.dictionaryId in ids && it.dictionaryId !in installed }
        }
    }
}

/** The sentence for why a download failed, as a string resource. */
fun FailureReason.messageRes(): Int = when (this) {
    FailureReason.OFFLINE -> R.string.download_failed_offline
    FailureReason.SERVER -> R.string.download_failed_server
    FailureReason.NO_SPACE -> R.string.download_failed_no_space
    FailureReason.OTHER -> R.string.download_failed_other
}

/** The two steps of the first start, one screen each. */
enum class OnboardingPage {
    DICTIONARIES,
    BOOK,
    ;

    /** After "Download", "Skip" or "Next" on the dictionaries step: on to the book. */
    fun next(): OnboardingPage = BOOK

    /** Back from this step, or null when back should leave the first start as before. */
    fun back(): OnboardingPage? = if (this == BOOK) DICTIONARIES else null
}

/**
 * The first-start screen in two steps: dictionaries with one tap, then a first book (the sample,
 * a file on the phone, or a book server). The download goes on during the second step, so the
 * sample can be opened meanwhile. Shown once; skipping it leaves everything in the tabs.
 */
@Composable
fun OnboardingScreen(
    onTrySample: () -> Unit,
    onAddBook: () -> Unit,
    onAddServer: () -> Unit,
    onSkip: () -> Unit,
    dictionaries: DictionariesViewModel = viewModel(factory = DictionariesViewModel.Factory),
) {
    val dictionariesState by dictionaries.uiState.collectAsStateWithLifecycle()
    var japanese by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(OnboardingPage.DICTIONARIES) }
    val pack = DictionaryCatalog.starterPack(japanese)
    val withNotifications = rememberWithNotifications()
    val download = {
        dictionaries.dismissFailures()
        // Marked started only when the downloads are queued: the permission question can outlive
        // the activity, and "started" without a queue would show a download that never runs.
        withNotifications {
            started = true
            pack.forEach(dictionaries::download)
        }
    }
    BackHandler(enabled = page.back() != null) { page.back()?.let { page = it } }
    OnboardingContent(
        page = page,
        japanese = japanese,
        onJapaneseChange = { japanese = it },
        sizeMb = pack.sumOf { it.sizeMb },
        pack = StarterPackState.of(pack, dictionariesState, started),
        onDownload = {
            download()
            page = page.next()
        },
        onRetry = { dictionaries.retryFailed(pack) },
        onSkipDictionaries = { page = page.next() },
        onBackToDictionaries = { page.back()?.let { page = it } },
        onTrySample = onTrySample,
        onAddBook = onAddBook,
        onAddServer = onAddServer,
        onSkip = onSkip,
    )
}

@Composable
private fun OnboardingContent(
    page: OnboardingPage,
    japanese: Boolean,
    onJapaneseChange: (Boolean) -> Unit,
    sizeMb: Int,
    pack: StarterPackState,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onSkipDictionaries: () -> Unit,
    onBackToDictionaries: () -> Unit,
    onTrySample: () -> Unit,
    onAddBook: () -> Unit,
    onAddServer: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().welcomeGlow(MaterialTheme.colorScheme.primary)) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                // The launcher icon's artwork, in a launcher-like rounded square. The art is an
                // adaptive-icon layer (108 dp) of which launchers show the middle 72 dp, so it is
                // scaled by 108 / 72 to look the same size as on the home screen.
                Image(
                    painter = painterResource(R.drawable.ic_launcher_art),
                    contentDescription = null,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .graphicsLayer {
                            scaleX = ADAPTIVE_ICON_ZOOM
                            scaleY = ADAPTIVE_ICON_ZOOM
                        },
                )
                Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.onboarding_message),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    label = "onboarding page",
                ) { shown ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (shown) {
                            OnboardingPage.DICTIONARIES -> DictionariesStep(
                                japanese = japanese,
                                onJapaneseChange = onJapaneseChange,
                                sizeMb = sizeMb,
                                pack = pack,
                                onDownload = onDownload,
                                onRetry = onRetry,
                                onNext = onSkipDictionaries,
                            )
                            OnboardingPage.BOOK -> BookStep(
                                pack = pack,
                                onRetry = onRetry,
                                onGetDictionaries = onBackToDictionaries,
                                onTrySample = onTrySample,
                                onAddBook = onAddBook,
                                onAddServer = onAddServer,
                                onSkip = onSkip,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionariesStep(
    japanese: Boolean,
    onJapaneseChange: (Boolean) -> Unit,
    sizeMb: Int,
    pack: StarterPackState,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    Step(number = 1, title = stringResource(R.string.onboarding_dictionaries_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val enabled = pack == StarterPackState.NotStarted
            FilterChip(
                selected = !japanese,
                onClick = { onJapaneseChange(false) },
                label = { Text(stringResource(R.string.onboarding_english)) },
                enabled = enabled,
            )
            FilterChip(
                selected = japanese,
                onClick = { onJapaneseChange(true) },
                label = { Text(stringResource(R.string.onboarding_japanese)) },
                enabled = enabled,
            )
        }
        Text(
            stringResource(if (japanese) R.string.onboarding_pack_japanese else R.string.onboarding_pack_english, sizeMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pack == StarterPackState.NotStarted) {
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_download))
            }
        } else {
            // Back here after the download started: how it stands, and on to the book.
            PackStatus(pack, onRetry)
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_next))
            }
        }
    }
    if (pack == StarterPackState.NotStarted) {
        TextButton(onClick = onNext) { Text(stringResource(R.string.onboarding_skip_dictionaries)) }
    }
}

@Composable
private fun BookStep(
    pack: StarterPackState,
    onRetry: () -> Unit,
    onGetDictionaries: () -> Unit,
    onTrySample: () -> Unit,
    onAddBook: () -> Unit,
    onAddServer: () -> Unit,
    onSkip: () -> Unit,
) {
    Step(number = 2, title = stringResource(R.string.onboarding_book_title)) {
        Text(stringResource(R.string.onboarding_book_message), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onTrySample, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_sample))
        }
        OutlinedButton(onClick = onAddBook, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_add_book))
        }
        OutlinedButton(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_add_server))
        }
    }
    // The dictionaries below the book: the download as it goes on, or a way back to step 1 when
    // they were skipped.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pack == StarterPackState.NotStarted) {
                Text(
                    stringResource(R.string.onboarding_no_dictionaries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onGetDictionaries, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_get_dictionaries))
                }
            } else {
                PackStatus(pack, onRetry)
            }
        }
    }
    TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
}

/** The starter pack's download once started: progress, done, or failed with a retry. */
@Composable
private fun PackStatus(pack: StarterPackState, onRetry: () -> Unit) {
    when (pack) {
        StarterPackState.NotStarted -> Unit
        is StarterPackState.Downloading -> {
            val fraction = pack.fraction
            if (fraction != null) {
                ProgressBar(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else if (LocalMotionEnabled.current) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                // No moving bar without animations: the finished dictionaries so far.
                ProgressBar(progress = { pack.installed.toFloat() / pack.total }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                stringResource(R.string.onboarding_downloading, pack.installed + 1, pack.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StarterPackState.Ready -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onboarding_ready))
        }
        is StarterPackState.Failed -> {
            Text(
                stringResource(pack.reason.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.onboarding_failed_skip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_retry))
            }
        }
    }
}

/**
 * A soft glow of [color] behind the icon that drifts and breathes slowly, like the glow at the
 * top of the icon. Still when the phone's animations are off (Accessibility, "Remove
 * animations").
 */
@Composable
private fun Modifier.welcomeGlow(color: Color): Modifier {
    val animate = LocalMotionEnabled.current
    // Read only while drawing, so the glow redraws without recomposing the screen.
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "welcome glow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(GLOW_PERIOD_MS, easing = LinearEasing)),
            label = "welcome glow phase",
        )
    } else {
        remember { mutableFloatStateOf(0.25f) }
    }
    return drawBehind {
        val angle = phase.value * 2 * PI.toFloat()
        val center = Offset(
            x = size.width * (0.5f + 0.08f * sin(angle)),
            y = 140.dp.toPx() + 24.dp.toPx() * cos(angle),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.30f + 0.10f * sin(2 * angle)), color.copy(alpha = 0f)),
                center = center,
                radius = size.width * 0.85f,
            ),
        )
    }
}

@Composable
private fun Step(number: Int, title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.onboarding_step, number, title),
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Preview
@Composable
private fun OnboardingPreview() {
    HonmaniTheme {
        OnboardingContent(
            page = OnboardingPage.DICTIONARIES,
            japanese = false,
            onJapaneseChange = {},
            sizeMb = 51,
            pack = StarterPackState.NotStarted,
            onDownload = {},
            onRetry = {},
            onSkipDictionaries = {},
            onBackToDictionaries = {},
            onTrySample = {},
            onAddBook = {},
            onAddServer = {},
            onSkip = {},
        )
    }
}

/** An adaptive-icon layer is 108 dp; launchers show its middle 72 dp. */
private const val ADAPTIVE_ICON_ZOOM = 108f / 72f

/** One slow drift of the welcome glow. */
private const val GLOW_PERIOD_MS = 12_000
