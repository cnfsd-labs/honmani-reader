package app.honmani.reader.ui.reader

import app.honmani.reader.data.stats.ReadingStats
import app.honmani.reader.data.stats.StatsPeriod
import app.honmani.reader.data.words.ReadingSession
import app.honmani.reader.diagnostics.logWarning
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import app.honmani.reader.readaloud.AudiobookPhase
import androidx.compose.runtime.SideEffect
import app.honmani.reader.readaloud.ReadAloudPhase
import app.honmani.reader.readaloud.SpokenSentence
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.honmani.reader.data.library.BookText
import app.honmani.reader.readaloud.PronunciationRules
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.readaloud.BackgroundPlayback
import app.honmani.reader.data.settings.ReaderSettings
import app.honmani.reader.ui.review.ReadNext
import app.honmani.reader.ui.review.ReviewScreen
import app.honmani.reader.ui.HomeTab
import app.honmani.reader.ui.anki.rememberAnkiAccess
import app.honmani.reader.ui.anki.rememberAnkiEnabled
import app.honmani.reader.ui.anki.showAnkiResult
import app.honmani.reader.ui.anki.showVoiceProblem
import app.honmani.reader.ui.library.AddBookStart
import app.honmani.reader.ui.library.LibraryViewModel
import app.honmani.reader.ui.onboarding.OnboardingScreen
import app.honmani.reader.ui.settings.AutoBackupOffer
import app.honmani.reader.ui.settings.ReaderSettingsScreen
import app.honmani.reader.ui.settings.syncFailureReason
import app.honmani.reader.ui.words.openInDictionary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's home: the library, words, dictionaries and settings tabs, and ッツ Ebook Reader with
 * the lookup panel once a book is open. The reader WebView stays loaded underneath the tabs (on
 * ttu's own book list, which they cover), so books open quickly and the library can hand new
 * books to ttu.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory),
) {
    val supported = remember { isReaderWebViewSupported() }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (!supported) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.reader_webview_unsupported))
            }
        } else {
            ReaderWithTabs(viewModel)
        }
    }
}

@Composable
private fun ReaderWithTabs(viewModel: ReaderViewModel) {
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    var atLibrary by remember { mutableStateOf(true) }
    // Keeps the tabs' saved state (Words page, scroll positions) while a book is open, so Back returns to it.
    val homeState = rememberSaveableStateHolder()
    // A picture of the book open in the zoom viewer (a tap on it).
    var picture by remember { mutableStateOf<ByteArray?>(null) }
    val tabState = rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    var tab by tabState
    val context = LocalContext.current
    val resources = LocalResources.current
    val anki = rememberAnkiAccess()
    val ankiEnabled = rememberAnkiEnabled()
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val dictionaryUpdates by remember { container.dictionaryUpdateChecker.updates }.collectAsStateWithLifecycle(emptyList())
    // Reading time counts while a book is on screen and the app is in front.
    val tracker = remember { container.readingTracker }
    val showPreviousSentence by container.appearanceSettings.showPreviousSentence.collectAsStateWithLifecycle()
    val sentenceEnds by container.ankiSettings.sentenceEnds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val audio = rememberReaderAudio(container, tracker, scope)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // With "Keep playing with the screen off" the voice goes on (PlaybackService).
                if (BackgroundPlayback.pauseWhenStopped(container.readAloudSettings.keepPlaying.value)) {
                    audio.readAloud.appStopped()
                    audio.audiobook.controller.appStopped()
                }
                tracker.setAudioPlaying(false)
                // The state collection is stopped too, so the flag is cleared here, not by the effect.
                context.findActivity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // Back with the voice still playing: the screen state did not follow it in the background.
            if (event == Lifecycle.Event.ON_START && audio.playingNow) {
                tracker.setAudioPlaying(true)
                context.findActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            tracker.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            // Characters read count from ttu's positions; ask before the app may be closed.
            if (event == Lifecycle.Event.ON_STOP) {
                container.ttuLibrary.requestProgress()
                container.progressSync.flush()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.setForeground(false)
        }
    }
    // Listen or Read sentence was tapped in the open panel: the volume buttons change the volume
    // until the panel closes (they would close the panel otherwise).
    var panelSpeaking by remember { mutableStateOf(false) }
    // With "Count time while a lookup is open" off, the timer pauses while the panel is open
    // (its touches are native: the book sees no activity).
    val countDuringLookup by container.readingTimeSettings.countDuringLookup.collectAsStateWithLifecycle()
    LaunchedEffect(countDuringLookup) { tracker.setCountLookupTime(countDuringLookup) }
    LaunchedEffect(panel != null) {
        panelSpeaking = false
        tracker.setLookupOpen(panel != null)
        // The panel's word audio never talks over the reader's voice: it ends before that goes on.
        if (panel == null) container.wordAudio.stop()
        audio.readAloud.panelChanged(panel != null)
        audio.audiobook.controller.panelChanged(panel != null)
    }
    LaunchedEffect(atLibrary) {
        if (atLibrary) {
            audio.readAloud.close()
            audio.audiobook.close()
        }
        tracker.setVisible(!atLibrary)
        // Back at the library: the position reached goes to the sync server now.
        if (atLibrary) container.progressSync.flush()
    }
    // The first-start screen, once: not for a library that has books or dictionaries already.
    val onboardingDone by container.onboardingSettings.done.collectAsStateWithLifecycle()
    var onboardingChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!container.onboardingSettings.done.value) {
            val books = container.bookDao.observeAll().first()
            val dictionaries = container.dictionaryRepository.dictionaries.first()
            if (books.isNotEmpty() || dictionaries.isNotEmpty()) container.onboardingSettings.finish()
        }
        onboardingChecked = true
    }
    val addBookOnStartState = remember { mutableStateOf<AddBookStart?>(null) }
    var addBookOnStart by addBookOnStartState
    val overlays = rememberReaderOverlays()
    val current = rememberCurrentBook(container)
    // ttu's last saved position (book id to characters): the menu's place follows page turns made
    // while it is open (read aloud, the audiobook), with the screen on or off.
    var savedPosition by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    KeepPlayingEffect(container, audio, current.book?.title.orEmpty())
    // Bookmarks and jumps in the open book (reader menu): the list, a bookmark being named, "Back to where you were".
    val jumpSnackbar = remember { SnackbarHostState() }
    val jumper = remember(viewModel) { PlaceJumper(viewModel.ttuLibrary, container.progressSync, scope, jumpSnackbar) }
    // "Open in book" on a saved word: once its book is on screen, a jump to the lookup's place (with "Back to where you were").
    val jumpToLookup = { (bookId, position): Pair<Long, Long> ->
        val ttuId = current.ttuId
        if (ttuId != null) {
            scope.launch {
                if (container.bookDao.find(bookId)?.ttuId != ttuId) return@launch
                val text = container.searchableBook(ttuId) ?: return@launch
                val characters = position.coerceIn(0L, text.total.toLong())
                val spot = text.around(characters.toInt())
                jumper.jump(
                    ttuId, characters, text.total.toLong(), spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                    resources.getString(R.string.jump_back_message, BookPlace.percent(characters, text.total.toLong())),
                    resources.getString(R.string.jump_back_action),
                )
            }
        }
    }
    SideEffect { audio.pronunciationRules = PronunciationRules(current.pronunciations.map { it.text to it.reading }) }
    LaunchedEffect(atLibrary, current.ttuId) { jumpSnackbar.currentSnackbarData?.dismiss() }
    // After the effect above: the page can report the book before the reader leaves the library,
    // and leaving it would dismiss the jump's "Back to where you were".
    var bookShown by remember { mutableStateOf(false) }
    LaunchedEffect(atLibrary, current.ttuId, bookShown) {
        if (atLibrary || !bookShown) return@LaunchedEffect
        // Progress sync moved the book when it opened: said here, with the way back to this phone's place.
        current.ttuId?.let { ttuId ->
            container.progressSync.takePlaceBefore(ttuId)?.let { before ->
                val text = container.searchableBook(ttuId)
                jumper.offerBack(
                    ttuId, before.characters, before.total, text?.aroundPageStart(before.characters.toInt()),
                    resources.getString(R.string.sync_continued, before.pulled.percent, before.pulled.device),
                    resources.getString(R.string.sync_back_to, before.percent),
                )
            }
        }
        overlays.openAt?.let { place ->
            overlays.openAt = null
            jumpToLookup(place)
        }
    }
    // Back from a book after a session of lookups: its words, once per session (ReadingSession.offersReview).
    val sessionSnackbar = remember { SnackbarHostState() }
    var bookOpenedAt by remember { mutableStateOf<Long?>(null) }
    var offeredSession by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(atLibrary) {
        if (!atLibrary) {
            bookOpenedAt = System.currentTimeMillis()
            return@LaunchedEffect
        }
        val openedAt = bookOpenedAt ?: return@LaunchedEffect
        bookOpenedAt = null
        val words = container.wordRepository
        val session = words.session.first()
        val start = session.start
        val inSession = if (start == null) 0 else words.lookupCount(start).first()
        val thisVisit = words.lookupCount(openedAt).first()
        if (!ReadingSession.offersReview(start, inSession, thisVisit, offeredSession)) return@LaunchedEffect
        offeredSession = start
        val message = resources.getQuantityString(R.plurals.session_left_book, session.words.size, session.words.size)
        val result = sessionSnackbar.showSnackbar(
            message, resources.getString(R.string.session_left_book_action), withDismissAction = true, duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            overlays.openedFromMenu = false
            overlays.sessionShown = true
        }
    }
    LaunchedEffect(atLibrary) { if (!atLibrary) sessionSnackbar.currentSnackbarData?.dismiss() }
    // A passage the audiobook can't reach by turning pages: the book opens there, like a jump.
    SideEffect {
        audio.audiobook.navigate = { span ->
            val id = current.ttuId
            if (id != null) {
                scope.launch {
                    val text = container.searchableBook(id)
                    val total = text?.total ?: container.bookDao.findByTtuId(id)?.let { container.sectionStarts(it)?.total } ?: return@launch
                    val spot = text?.aroundPageStart(span.start)
                    jumper.jump(
                        id, span.start.toLong(), total.toLong(), spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                        resources.getString(R.string.jump_back_message, BookPlace.percent(span.start.toLong(), total.toLong())),
                        resources.getString(R.string.jump_back_action),
                    )
                }
            }
        }
    }
    val setup = rememberAudiobookSetup(container, current, audio, overlays, scope, jumpSnackbar)
    // Once per opening of a book: it counts as reading; with an audiobook the reader is told, or
    // (after "Continue listening" in the library) it plays. Never plays on its own otherwise.
    var announcedBook by rememberSaveable { mutableStateOf<String?>(null) }
    val audiobookAvailable = stringResource(R.string.audiobook_available)
    val audiobookAvailablePlay = stringResource(R.string.audiobook_available_play)
    val menuHint = stringResource(R.string.reader_menu_hint)
    LaunchedEffect(current.hash, atLibrary) {
        val hash = current.hash
        if (hash == null) {
            announcedBook = null
            return@LaunchedEffect
        }
        val id = current.ttuId
        if (atLibrary || announcedBook == hash || id == null) return@LaunchedEffect
        announcedBook = hash
        container.sessionModes.opened(hash)
        if (container.audiobookStore.pairing(hash) != null) {
            if (container.sessionModes.takeAutoplay(hash, SystemClock.elapsedRealtime())) {
                audio.play(id)
            } else {
                jumpSnackbar.currentSnackbarData?.dismiss()
                val result = jumpSnackbar.showSnackbar(audiobookAvailable, audiobookAvailablePlay, withDismissAction = true, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) audio.play(id)
            }
        }
        // The one-time menu hint queues behind the audiobook snackbar (showSnackbar waits its turn).
        if (container.readerMenuHint.take()) {
            jumpSnackbar.showSnackbar(menuHint, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    // The first playback of a session makes the book "listened to" for the library's Continue bubble.
    LaunchedEffect(audio.audiobookState.phase) {
        if (audio.audiobookState.phase == AudiobookPhase.PLAYING) audio.audiobook.hash?.let(container.sessionModes::played)
    }
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    // Books opened with Honmani from other apps: added in the library, a single one opened.
    LaunchedEffect(library) {
        container.openedBooks.received.collect { uris ->
            tab = HomeTab.LIBRARY
            library.addOpened(uris)
        }
    }
    // Text shared from other apps becomes a book and opens.
    LaunchedEffect(library) {
        container.openedBooks.receivedText.collect { shared ->
            tab = HomeTab.LIBRARY
            library.addText(shared.text, shared.title)
        }
    }
    // The launcher's "Search" shortcut: Words with its search field focused, out of an open book.
    val focusSearchState = rememberSaveable { mutableStateOf(false) }
    var focusSearch by focusSearchState
    LaunchedEffect(container) {
        container.searchRequests.received.collect {
            if (!atLibrary) viewModel.closeBook()
            tab = HomeTab.WORDS
            focusSearch = true
        }
    }
    // The review screen, from Words or the launcher's "Review" shortcut.
    LaunchedEffect(container) {
        container.searchRequests.reviewReceived.collect {
            if (!atLibrary) viewModel.closeBook()
            tab = HomeTab.WORDS
            overlays.reviewShown = true
        }
    }
    // "Open card settings" from the dialog about the reader's own Anki note type.
    LaunchedEffect(container) {
        container.searchRequests.ankiSettingsReceived.collect {
            if (!atLibrary) viewModel.closeBook()
            overlays.reviewShown = false
            tab = HomeTab.SETTINGS
            overlays.ankiSettingsShown = true
        }
    }
    // Words due, for the badge on the Words tab, and the new words a session would start.
    val reviewScope by container.reviewScope.collectAsStateWithLifecycle()
    val reviewBooks by remember(container) { container.bookDao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val dueToday by remember(container) {
        flow { emitAll(container.reviewRepository.observeDueToday(container.reviewSettings.reviewsPerDay)) }
    }.collectAsStateWithLifecycle(emptyList())
    val reviewNew by remember(container) {
        flow { emitAll(container.reviewRepository.observeNewAvailable(container.reviewSettings.newPerDay)) }
    }.collectAsStateWithLifecycle(0)
    LaunchedEffect(viewModel) {
        viewModel.ankiResults.collect { (expression, result) -> showAnkiResult(context, expression, result) }
    }
    LaunchedEffect(viewModel) {
        viewModel.voiceProblems.collect { showVoiceProblem(context, it) }
    }
    PanelUndo(viewModel, jumpSnackbar)

    // The words with a review card due today, for their tint in the book (word-marks.js).
    val dueWords = remember(dueToday) { dueToday.mapTo(HashSet()) { it.expression } as Set<String> }

    // While a book is open: the words being learned, how they were written, and the known and
    // ignored words, so marks follow lookups and "Mark as learned" (word-marks.js).
    val learning by produceState<List<Set<String>>?>(null, atLibrary) {
        value = null
        if (!atLibrary) {
            container.wordKnowledge
                .map { listOf(it.learning, it.learningForms, it.known, it.ignored) }
                .distinctUntilChanged()
                .collect { value = it }
        }
    }

    // Reader settings the app itself acts on (volume buttons, long-press lookup), kept in ttu's
    // storage, read again when a book opens and after the reader settings close.
    var settingsVersion by remember { mutableStateOf(0) }
    val appSettings by produceState(ReaderSettings(), atLibrary, overlays.readerSettings, settingsVersion) {
        if (atLibrary || overlays.readerSettings) return@produceState
        value = try {
            ReaderSettings.fromStorage(viewModel.ttuLibrary.readSettings()).also {
                // The rest of the app drops its animations in E-ink mode too.
                container.appearanceSettings.setEInk(it.eInk)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.reader", "Reading the reader settings failed", e)
            value
        }
    }

    // The book's background, also behind the status and navigation bars while a book is shown.
    var bookBackground by remember { mutableStateOf<Color?>(null) }
    val frame = bookBackground.takeIf { !atLibrary && !overlays.readerSettings }
    BarIcons(background = frame)
    ImmersiveBars(hidden = appSettings.hidesBars(atLibrary, overlays.readerSettings))

    // The room the read-aloud bar takes: the book lays itself out above it, since a bar over the
    // page would cut the end off every column of vertical text.
    val readAloudBarShown = audio.readAloudState.phase != ReadAloudPhase.OFF && !atLibrary
    val audiobookBarShown = audio.audiobookState.phase != AudiobookPhase.OFF && !atLibrary
    var readAloudBarHeight by remember { mutableStateOf(0.dp) }
    val bookBottom = if (readAloudBarShown || audiobookBarShown) readAloudBarHeight else 0.dp

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().then(frame?.let { Modifier.background(it) } ?: Modifier)) {
            ReaderWebView(
                onLookup = viewModel::onTap,
                onDismiss = viewModel::dismiss,
                highlight = viewModel.highlight,
                diagnostics = viewModel.diagnostics,
                ttuLibrary = viewModel.ttuLibrary,
                booksDir = viewModel.booksDir,
                onBookChanged = {
                    current.ttuId = it
                    viewModel.onBookChanged(it)
                    tracker.setBook(it)
                },
                onActivity = tracker::onActivity,
                onPage = { ttuId, characters, progress ->
                    savedPosition = ttuId to characters
                    tracker.onPage(ttuId, characters, progress)
                    container.progressSync.onPosition(ttuId, characters)
                },
                onOpenSettings = {
                    viewModel.dismiss()
                    overlays.openedFromMenu = false
                    overlays.readerSettings = true
                },
                onOpenMenu = viewModel::openMenu,
                onContentsClosedByBack = {
                    if (overlays.contentsFromMenu) viewModel.openMenu()
                    overlays.contentsFromMenu = false
                },
                onImage = {
                    viewModel.dismiss()
                    picture = it
                },
                closeRequests = viewModel.closeRequests,
                onAtLibraryChanged = {
                    // Back from a book (or ttu's settings): what was read goes into the backup.
                    if (it && !atLibrary) {
                        // The book's time is counted first (the effect below repeats it harmlessly).
                        tracker.setVisible(false)
                        container.saveAutoBackup()
                    }
                    atLibrary = it
                    if (it) {
                        bookShown = false
                        viewModel.dismiss()
                        library.bookClosed()
                    }
                },
                onBackground = { bookBackground = Color(it) },
                marks = { texts, mode, knownWords, sentences, dueMarks, isStale ->
                    container.wordMarker.mark(texts, mode, knownWords, sentences, dueMarks, isStale)
                },
                marksKey = learning?.let { Triple(it, dueWords, sentenceEnds) },
                sentenceEnds = sentenceEnds,
                highlightsMessage = { bookId ->
                    container.bookDao.findByTtuId(bookId)?.let { book ->
                        container.sectionStarts(book)?.let { sections ->
                            highlightsMessage(bookId, sections, container.highlightDao.observe(book.contentHash).first())
                        }
                    }
                },
                highlightsKey = current.highlights,
                readAloudMessages = audio.pageMessages,
                onReadAloud = { message ->
                    when (message) {
                        is BridgeMessage.ReadAloudSentences ->
                            audio.readAloud.onSentences(message.gen, message.items.map { SpokenSentence(it.i, it.text) }, message.end)
                        is BridgeMessage.ReadAloudLost -> audio.readAloud.onLost(message.gen)
                        is BridgeMessage.AudiobookLost -> audio.audiobook.controller.onLost(message.gen)
                        is BridgeMessage.FollowState -> {
                            audio.readAloud.viewDetached(message.detached)
                            audio.audiobook.controller.viewDetached(message.detached)
                        }
                        else -> Unit
                    }
                },
                onBookShown = {
                    audio.readAloud.pageReloaded()
                    audio.audiobook.controller.pageReloaded()
                    bookShown = true
                },
                volumeKeysTurnPages = VolumeKeys.turnPages(appSettings.volumeKeys, readAloudBarShown || audiobookBarShown, panelSpeaking),
                hideSelectionToolbar = appSettings.selectionLookup,
                panelOpen = panel != null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bookBottom)
                    .consumeWindowInsets(PaddingValues(bottom = bookBottom))
                    // In immersive mode a book fills the screen, also under bars a swipe shows for
                    // a moment: its size doesn't follow them, so they never reflow the page.
                    .windowInsetsPadding(if (appSettings.immersive && !atLibrary) bookInsets else WindowInsets.safeDrawing),
            )
            ReaderAudioBars(audio, overlays, readAloudBarShown, audiobookBarShown, readAloudBarHeight) { readAloudBarHeight = it }
            val state = panel
            val highlightControls = rememberHighlightControls(current, container, scope, jumpSnackbar, appSettings.selectionLookup, viewModel::dismiss)
            val countedTap by viewModel.countedTap.collectAsStateWithLifecycle()
            if (state != null && !atLibrary) {
                // The frame after this state was first composed: it has been drawn (for the log).
                LaunchedEffect(state) {
                    withFrameNanos {}
                    viewModel.onPanelDrawn(state)
                }
                // Composed after the WebView, so back closes the panel first.
                BackHandler { if (!viewModel.back()) viewModel.dismiss() }
                LookupPanel(
                    state = state,
                    onClose = viewModel::dismiss,
                    onAddToAnki = { group -> anki.whenReady { viewModel.addToAnki(group) } },
                    showAnki = ankiEnabled,
                    onSetStarred = viewModel::setStarred,
                    onSetIgnored = viewModel::setIgnored,
                    onMarkLearned = viewModel::markLearned,
                    onSetFurigana = viewModel::setFurigana,
                    onEditLookup = { openInDictionary(context, it) },
                    onListen = { group ->
                        panelSpeaking = true
                        viewModel.listen(group)
                    },
                    onLookupWord = viewModel::lookupWord,
                    onShowKanji = viewModel::showKanji,
                    onBack = { viewModel.back() },
                    previousSentence = if (showPreviousSentence) state.tap.previousSentence else "",
                    onReadSentence = {
                        panelSpeaking = true
                        viewModel.readSentence()
                    },
                    onDontCount = if (countedTap == state.tap) viewModel::dontCount else null,
                    onShorterMatch = { viewModel.changeMatch(longer = false) },
                    onLongerMatch = { viewModel.changeMatch(longer = true) },
                    verticalText = appSettings.vertical,
                    onOpenDictionaries = {
                        // Leave the book for the Dictionaries screen.
                        tab = HomeTab.SETTINGS
                        overlays.dictionariesShown = true
                        viewModel.ttuLibrary.show(LIBRARY_PATH)
                    },
                    highlight = highlightControls(state.tap),
                    availableHeight = maxHeight,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            val viewing = picture
            if (viewing != null && !atLibrary) {
                PictureViewer(viewing, onClose = { picture = null })
            }
        }
        // A picture belongs to the book it was in.
        LaunchedEffect(atLibrary) { if (atLibrary) picture = null }
        if (atLibrary && !onboardingDone) {
            if (onboardingChecked) {
                OnboardingScreen(
                    onTrySample = {
                        tab = HomeTab.LIBRARY
                        container.onboardingSettings.finish()
                        library.addSample()
                    },
                    onAddBook = {
                        tab = HomeTab.LIBRARY
                        addBookOnStart = AddBookStart.FILES
                        container.onboardingSettings.finish()
                    },
                    onAddServer = {
                        tab = HomeTab.LIBRARY
                        addBookOnStart = AddBookStart.SERVER
                        container.onboardingSettings.finish()
                    },
                    onSkip = container.onboardingSettings::finish,
                )
            } else {
                // A moment while the app checks for books; covers ttu's list like the tabs.
                Surface(modifier = Modifier.fillMaxSize()) {}
            }
        } else if (atLibrary) {
            homeState.SaveableStateProvider("home") {
                HomeTabs(
                    tabState, focusSearchState, addBookOnStartState, overlays, library, container, viewModel.diagnostics,
                    dictionaryUpdates.isNotEmpty(), dueToday, reviewNew, reviewScope, reviewBooks,
                )
            }
        }
        // A backup Android brought back from the previous phone, offered once on a new install.
        AutoBackupOffer()
        // Progress sync asks before going back to another device's earlier place in a book.
        val syncQuestion by library.syncQuestion.collectAsStateWithLifecycle()
        syncQuestion?.let { question ->
            AlertDialog(
                onDismissRequest = { library.answerSync(false) },
                title = { Text(stringResource(R.string.sync_question_title)) },
                text = { Text(stringResource(R.string.sync_question_message, question.device, question.theirs, question.ours)) },
                confirmButton = {
                    TextButton(onClick = { library.answerSync(false) }) {
                        Text(stringResource(R.string.sync_question_stay, question.ours))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { library.answerSync(true) }) {
                        Text(stringResource(R.string.sync_question_go, question.theirs))
                    }
                },
            )
        }
        val menuOpen by viewModel.menuOpen.collectAsStateWithLifecycle()
        // The book's text, only while the menu, the bookmark list or a new bookmark needs it (the slider, names, the page's spot).
        val textWanted = !atLibrary && (menuOpen || overlays.bookmarksShown || overlays.bookmarkDraft != null || overlays.highlightsShown)
        val bookText by produceState<BookText?>(null, current.ttuId, textWanted) {
            val id = current.ttuId
            value = if (textWanted && id != null) container.searchableBook(id) else null
        }
        // Back from a sub-screen that came from the menu: close it and show the menu again; null otherwise.
        val backToMenu = { close: () -> Unit -> if (overlays.openedFromMenu) ({ close(); viewModel.openMenu() }) else null }
        val jumpMessage = { percent: Int -> resources.getString(R.string.jump_back_message, percent) }
        val backLabel = stringResource(R.string.jump_back_action)
        val defaultName = { percent: Int -> resources.getString(R.string.bookmark_default_name, percent) }
        if (menuOpen && !atLibrary) {
            val syncState by container.koSyncStatus.state.collectAsStateWithLifecycle()
            val syncFailure = syncState.failureFor(current.ttuId)
            val opened by produceState<MenuPlace?>(null, bookText) {
                val id = current.ttuId
                val text = bookText
                value = if (id == null || text == null) null else {
                    val characters = try {
                        viewModel.ttuLibrary.bookmark(id).first
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    // The speed the book's sheet uses for its time left: recent days, else all reading.
                    val speed = try {
                        val days = container.readingDao.observeAll().first()
                        ReadingStats.period(days, container.dayClock.today(), StatsPeriod.ALL).charactersPerHour
                            ?: ReadingStats.book(days).charactersPerHour
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    MenuPlace(characters ?: 0L, text.total, speed)
                }
            }
            // Only saves made while the menu is open: an older one may predate a position set otherwise (sync).
            val savedAtOpen = remember { savedPosition }
            val place = opened?.let { base ->
                savedPosition?.takeIf { it !== savedAtOpen && it.first == current.ttuId }
                    ?.let { base.copy(characters = it.second) } ?: base
            }
            ReaderMenuSheet(
                showProgress = appSettings.showProgress,
                syncFailure = syncFailure?.let { syncFailureReason(it) },
                onSyncNow = {
                    viewModel.closeMenu()
                    val ttuId = current.ttuId
                    scope.launch {
                        val sync = container.progressSync
                        if (sync.syncNow() || ttuId == null) return@launch
                        // Nothing waited to be sent: the failure came from opening the book, so that pull runs again.
                        val book = container.bookDao.findByTtuId(ttuId) ?: return@launch
                        val ttu = viewModel.ttuLibrary
                        var moved: Pair<Long, Double>? = null
                        sync.takePlaceBefore(ttuId)
                        val pulled = try {
                            sync.pull(book, ttuId, bookmark = { ttu.bookmark(ttuId) }, setBookmark = { c, p -> moved = c to p }, ask = library::askSync)
                        } catch (_: TtuLibraryException) {
                            null
                        }
                        val (characters, progress) = moved ?: return@launch
                        if (pulled == null) return@launch
                        val text = container.searchableBook(ttuId)
                        val spot = text?.around(characters.toInt())
                        try {
                            ttu.openHit(ttuId, characters, progress, spot?.hint.orEmpty(), spot?.match.orEmpty())
                        } catch (_: TtuLibraryException) {
                            return@launch
                        }
                        val message = resources.getString(R.string.sync_continued, pulled.percent, pulled.device)
                        sync.takePlaceBefore(ttuId)?.let { before ->
                            jumper.offerBack(
                                ttuId, before.characters, before.total, text?.aroundPageStart(before.characters.toInt()),
                                message, resources.getString(R.string.sync_back_to, before.percent),
                            )
                        } ?: jumpSnackbar.showSnackbar(message)
                    }
                },
                place = place,
                onJump = { characters ->
                    val id = current.ttuId
                    val text = bookText
                    if (id != null && text != null) {
                        viewModel.closeMenu()
                        val spot = text.around(characters.toInt())
                        jumper.jump(
                            id, characters, text.total.toLong(), spot?.hint.orEmpty(), spot?.match.orEmpty(), text,
                            jumpMessage(BookPlace.percent(characters, text.total.toLong())), backLabel,
                        )
                    }
                },
                onAddBookmark = {
                    val text = bookText
                    val here = place
                    if (text != null && here != null) {
                        viewModel.closeMenu()
                        val name = BookPlace.defaultName(text.around(here.characters.toInt())?.after.orEmpty())
                            ?: defaultName(BookPlace.percent(here.characters, here.total.toLong()))
                        overlays.bookmarkDraft = BookmarkDraft(here.characters, here.total.toLong(), name)
                    }
                },
                onBookmarks = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.bookmarksShown = true
                },
                onHighlights = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.highlightsShown = true
                },
                onContents = {
                    viewModel.closeMenu()
                    overlays.contentsFromMenu = true
                    viewModel.ttuLibrary.openContents()
                },
                onSearch = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.searchShown = true
                },
                onReadAloud = {
                    viewModel.closeMenu()
                    // One voice at a time: the recording stops when the phone starts reading.
                    audio.audiobook.close()
                    audio.readAloud.start()
                },
                pronunciationCount = current.pronunciations.size,
                onPronunciations = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.pronunciationsShown = true
                },
                audiobookPaired = setup.paired || setup.generated != null,
                onAudiobook = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.audiobookStep = if (setup.paired || setup.generated != null) AudiobookStep.MANAGE else AudiobookStep.INTRO
                },
                onSettings = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.readerSettings = true
                },
                onSessionWords = {
                    viewModel.closeMenu()
                    overlays.openedFromMenu = true
                    overlays.sessionShown = true
                },
                onCloseBook = viewModel::closeBook,
                onSetShowProgress = {
                    // ttu reads the setting when the page loads: the page reloads, in place.
                    viewModel.ttuLibrary.writeSettings(ReaderSettings.progressStorage(it))
                    settingsVersion++
                    viewModel.closeMenu()
                    viewModel.ttuLibrary.reload()
                },
                onDismiss = viewModel::closeMenu,
            )
        }
        ReaderAudiobookDialogs(setup, audio, current, overlays, container, scope, jumpSnackbar, backToMenu)
        ReaderBookSheets(overlays, current, container, scope, jumpSnackbar, jumper, bookText, atLibrary, backToMenu, viewModel::openMenu)
        if (overlays.reviewShown) {
            val libraryState by library.uiState.collectAsStateWithLifecycle()
            ReviewScreen(
                continueBook = ReadNext.of(libraryState) as? ReadNext.Continue,
                onClose = { overlays.reviewShown = false },
                onOpenBook = { id, position ->
                    // Back from the book returns to Words and its review card.
                    overlays.reviewShown = false
                    overlays.openAt = position?.let { id to it }
                    library.open(id)
                },
            )
        }
        if (overlays.readerSettings) {
            // On top of everything; closing it reloads the reader if anything changed.
            ReaderSettingsScreen(onClose = { overlays.readerSettings = false; if (overlays.openedFromMenu) viewModel.openMenu() })
        }
        SnackbarHost(jumpSnackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        // Above the home screen's navigation bar.
        if (atLibrary) {
            SnackbarHost(sessionSnackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
        }
    }
}

/**
 * Status and navigation bar icons dark on a light [background] and light on a dark one, while
 * there is one (a book on screen); otherwise as the app's theme set them.
 */
@Composable
private fun BarIcons(background: Color?) {
    val view = LocalView.current
    DisposableEffect(view, background) {
        val window = view.context.findActivity()?.window
        if (background == null || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        val light = background.luminance() > 0.5f
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

/**
 * Hides the status and navigation bars while [hidden] (immersive mode, over a book); a swipe
 * from the screen edge shows them for a moment without moving the page. They come back when
 * [hidden] ends, and are hidden again after the app was in the background.
 */
@Composable
private fun ImmersiveBars(hidden: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, hidden) {
        val window = view.context.findActivity()?.window
        if (!hidden || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The room an immersive book keeps clear: the display cutout and the keyboard, never the bars. */
private val bookInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.ime)

/** Height of ttu's page footer, in dp (CSS px), above the read-aloud bar. */
internal val FOOTER_HEIGHT = 32.dp

/** ttu's book list, which the tabs cover. */
private const val LIBRARY_PATH = "/manage"


/** The panel leaves most of the page visible. */
