package app.honmani.reader.ui.reader

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.AppContainer
import app.honmani.reader.R
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.review.ReviewScope
import app.honmani.reader.data.review.ReviewWord
import app.honmani.reader.diagnostics.ReaderDiagnostics
import app.honmani.reader.ui.HomeNavigationBar
import app.honmani.reader.ui.HomeNavigationRail
import app.honmani.reader.ui.HomeTab
import app.honmani.reader.ui.library.AddBookStart
import app.honmani.reader.ui.library.LibraryScreen
import app.honmani.reader.ui.library.LibraryViewModel
import app.honmani.reader.ui.progress.ProgressTab
import app.honmani.reader.ui.review.ReadNext
import app.honmani.reader.ui.review.ReviewScopeChoice
import app.honmani.reader.ui.settings.SettingsScreen
import app.honmani.reader.ui.theme.LocalMotionEnabled
import app.honmani.reader.ui.theme.motionTween
import app.honmani.reader.ui.words.WordsScreen

/**
 * The Library, Words, Progress and Settings tabs, shown over ttu's book list while no book is open.
 * [dueToday] are the words with a review due (the badge on Words), [dictionaryUpdate] marks Settings.
 */
@Composable
internal fun HomeTabs(
    tabState: MutableState<HomeTab>,
    focusSearchState: MutableState<Boolean>,
    addBookOnStartState: MutableState<AddBookStart?>,
    overlays: ReaderOverlays,
    library: LibraryViewModel,
    container: AppContainer,
    diagnostics: ReaderDiagnostics,
    dictionaryUpdate: Boolean,
    dueToday: List<ReviewWord>,
    reviewNew: Int,
    reviewScope: ReviewScope,
    reviewBooks: List<BookEntity>,
) {
    var tab by tabState
    var focusSearch by focusSearchState
    var addBookOnStart by addBookOnStartState
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.action_share_diagnostics)
    val reviewDue = dueToday.size
    val libraryState by library.uiState.collectAsStateWithLifecycle()
    // Opaque, and on top: the tabs take all touches while ttu's list stays loaded below.
    BackHandler(enabled = tab != HomeTab.LIBRARY) { tab = HomeTab.LIBRARY }
    // Tapping the open tab again scrolls the Library to its top and goes to the next page of Words and Progress;
    // Settings return to their main list below.
    var libraryTaps by remember { mutableIntStateOf(0) }
    var wordsTaps by remember { mutableIntStateOf(0) }
    var progressTaps by remember { mutableIntStateOf(0) }
    val onSelectTab = { selected: HomeTab ->
        if (selected == tab) {
            when (selected) {
                HomeTab.LIBRARY -> libraryTaps++
                HomeTab.WORDS -> wordsTaps++
                HomeTab.PROGRESS -> progressTaps++
                else -> Unit
            }
        }
        tab = selected
        // A tab opens at its top level.
        overlays.dictionariesShown = false
        overlays.ankiSettingsShown = false
    }
    val badges = if (dictionaryUpdate) setOf(HomeTab.SETTINGS) else emptySet()
    val counts = mapOf(HomeTab.WORDS to reviewDue)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // In landscape (wider than tall) the tabs go at the side and the screens get no bottom bar.
        val atTheSide = maxWidth > maxHeight
        val motionOn = LocalMotionEnabled.current
        val firstTab = remember { booleanArrayOf(true) }
        val bottomBar = @Composable {
            if (!atTheSide) HomeNavigationBar(selected = tab, onSelect = onSelectTab, badges = badges, counts = counts)
            // With the tabs at the side: what a screen pins above the bar stays clear of the system's.
            else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        Row(modifier = Modifier.fillMaxSize()) {
            if (atTheSide) HomeNavigationRail(selected = tab, onSelect = onSelectTab, badges = badges, counts = counts)
            Box(
                modifier = Modifier
                    .weight(1f)
                    // Opaque, so the book's page behind never shows while a tab fades in.
                    .background(MaterialTheme.colorScheme.background)
                    // The rail keeps clear of the system bars on its side.
                    .then(if (atTheSide) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier),
            ) {
                // The tab you switch to fades in over the background (150 ms, from 40 %); the first
                // tab shown, and E-ink mode, have no fade. A tab is tappable from its first frame.
                key(tab) {
                    val fade = remember { Animatable(if (motionOn && !firstTab[0]) TAB_FADE_START else 1f) }
                    val fadeSpec = motionTween<Float>(TAB_FADE_MS)
                    LaunchedEffect(Unit) {
                        firstTab[0] = false
                        fade.animateTo(1f, fadeSpec)
                    }
                    // The fade is a background-coloured veil over the tab's content, not an alpha on the
                    // tab: the bottom bar (inside the screen) stays solid. It stops above the bar.
                    var barHeight by remember { mutableIntStateOf(0) }
                    val tabBar = @Composable {
                        Box(Modifier.onSizeChanged { barHeight = it.height }) { bottomBar() }
                        DisposableEffect(Unit) { onDispose { barHeight = 0 } }
                    }
                    val veil = MaterialTheme.colorScheme.background
                    Box(Modifier.fillMaxSize()) {
                when (tab) {
                    HomeTab.LIBRARY -> LibraryScreen(
                        bottomBar = tabBar,
                        onOpenDictionaries = {
                            tab = HomeTab.SETTINGS
                            overlays.dictionariesShown = true
                        },
                        viewModel = library,
                        addBookOnStart = addBookOnStart,
                        onAddBookStarted = { addBookOnStart = null },
                        onShowProgress = { tab = HomeTab.PROGRESS },
                        scrollToTop = libraryTaps,
                    )
                    HomeTab.WORDS -> WordsScreen(
                        bottomBar = tabBar,
                        onOpenDictionaries = {
                            tab = HomeTab.SETTINGS
                            overlays.dictionariesShown = true
                        },
                        pageCycle = wordsTaps,
                        focusSearch = focusSearch,
                        onSearchFocused = { focusSearch = false },
                        reviewDue = reviewDue,
                        reviewNew = reviewNew,
                        dueIds = dueToday.mapTo(HashSet()) { it.id },
                        onStartReview = { overlays.reviewShown = true },
                        onShowStats = { tab = HomeTab.PROGRESS },
                        readNext = ReadNext.of(libraryState),
                        onReadNext = { next ->
                            when (next) {
                                is ReadNext.Continue -> library.open(next.bookId)
                                ReadNext.Library -> tab = HomeTab.LIBRARY
                                ReadNext.Sample -> {
                                    // Back from the book is the library, where it now is.
                                    tab = HomeTab.LIBRARY
                                    library.addSample()
                                }
                            }
                        },
                        reviewChoice = ReviewScopeChoice(reviewScope, reviewBooks.filter { !it.hidden }) {
                            container.reviewScope.value = it
                        },
                        libraryBookIds = remember(reviewBooks) { reviewBooks.mapTo(HashSet()) { it.id } },
                        // Back from the book returns to Words.
                        onOpenBook = { id, position ->
                            overlays.openAt = position?.let { id to it }
                            library.open(id)
                        },
                    )
                    HomeTab.PROGRESS -> ProgressTab(
                        bottomBar = tabBar,
                        onOpenBook = library::open,
                        pageCycle = progressTaps,
                        onOpenLibrary = { tab = HomeTab.LIBRARY },
                    )
                    HomeTab.SETTINGS -> SettingsScreen(
                        bottomBar = tabBar,
                        showingDictionaries = overlays.dictionariesShown,
                        onShowDictionaries = { overlays.dictionariesShown = it },
                        showingAnkiSettings = overlays.ankiSettingsShown,
                        onShowAnkiSettings = { overlays.ankiSettingsShown = it },
                        onOpenReaderSettings = {
                            overlays.openedFromMenu = false
                            overlays.readerSettings = true
                        },
                        onOpenSample = {
                            // Back from the book is the library, where it now is.
                            tab = HomeTab.LIBRARY
                            library.addSample(it)
                        },
                        onShareDiagnostics = {
                            val share = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Honmani diagnostics")
                                .putExtra(Intent.EXTRA_TEXT, diagnostics.report(context))
                            context.startActivity(Intent.createChooser(share, shareTitle))
                        },
                    )
                }
                        if (fade.value < 1f) {
                            Box(
                                Modifier.fillMaxSize().drawBehind {
                                    drawRect(
                                        veil,
                                        size = Size(size.width, (size.height - barHeight).coerceAtLeast(0f)),
                                        alpha = 1f - fade.value,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val TAB_FADE_MS = 150
private const val TAB_FADE_START = 0.4f
