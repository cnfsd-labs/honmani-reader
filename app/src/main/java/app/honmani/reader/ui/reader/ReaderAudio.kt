package app.honmani.reader.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.AppContainer
import app.honmani.reader.R
import app.honmani.reader.data.settings.ReadAloudSettings
import app.honmani.reader.data.stats.ReadingTracker
import app.honmani.reader.readaloud.AudiobookPhase
import app.honmani.reader.readaloud.AudiobookProblem
import app.honmani.reader.readaloud.AudiobookState
import app.honmani.reader.readaloud.LiveVoice
import app.honmani.reader.readaloud.PronunciationRules
import app.honmani.reader.readaloud.ReadAloudController
import app.honmani.reader.readaloud.ReadAloudPhase
import app.honmani.reader.readaloud.ReadAloudState
import app.honmani.reader.ui.anki.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The reader's two voices, one at a time: read aloud (the phone's voice) and the audiobook (the same
 * bar and the same marks on the page, but the book follows a recording).
 */
@Stable
internal class ReaderAudio(
    val readAloud: ReadAloudController,
    val audiobook: AudiobookSession,
    /** What both tell the page (read-aloud.js). */
    val pageMessages: Flow<String>,
    private val rules: Array<PronunciationRules>,
    readAloudState: State<ReadAloudState>,
    audiobookState: State<AudiobookState>,
) {
    val readAloudState by readAloudState
    val audiobookState by audiobookState

    /** The open book's "read this as that" list. */
    var pronunciationRules: PronunciationRules
        get() = rules[0]
        set(value) {
            rules[0] = value
        }

    /** Sound is playing: the voice speaking or the recording running. */
    val playing: Boolean
        get() = readAloudState.phase == ReadAloudPhase.SPEAKING || audiobookState.phase == AudiobookPhase.PLAYING

    /** Sound is playing now, from the voices themselves (the screen's state stands still in the background). */
    val playingNow: Boolean
        get() = readAloud.state.value.phase == ReadAloudPhase.SPEAKING ||
            audiobook.controller.state.value.phase == AudiobookPhase.PLAYING

    /** Plays the open book's audiobook from where it was left (the dialog's Play, the snackbar's, "Continue listening"). */
    fun play(id: Long) {
        readAloud.close()
        val phase = audiobook.controller.state.value.phase
        if (phase == AudiobookPhase.PAUSED || phase == AudiobookPhase.ENDED) {
            audiobook.controller.play()
        } else if (phase != AudiobookPhase.PLAYING) {
            audiobook.start(id)
        }
    }
}

/**
 * The voices of the reader screen and what goes with them while they run: the screen stays on,
 * listening time is counted and interface sounds keep quiet.
 */
@Composable
internal fun rememberReaderAudio(container: AppContainer, tracker: ReadingTracker, scope: CoroutineScope): ReaderAudio {
    val context = LocalContext.current
    val readAloudMessages = remember { Channel<String>(Channel.UNLIMITED) }
    val audiobookMessages = remember { Channel<String>(Channel.UNLIMITED) }
    val pageMessages = remember { merge(readAloudMessages.receiveAsFlow(), audiobookMessages.receiveAsFlow()) }
    // The open book's "read this as that" list, set below once the book is known.
    val pronunciationRules = remember { arrayOf(PronunciationRules.EMPTY) }
    val readAloud = remember {
        // The spoken text is only logged by a debug build.
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        ReadAloudController(
            voice = LiveVoice(context),
            toPage = { readAloudMessages.trySend(it) },
            initialRate = container.readAloudSettings.rate.value,
            saveRate = container.readAloudSettings::setRate,
            onSpoken = tracker::onActivity,
            log = { if (debuggable) Log.i("ReadAloud", it) },
            pronunciations = { pronunciationRules[0] },
        )
    }
    val readAloudStateHolder = readAloud.state.collectAsStateWithLifecycle()
    val readAloudState by readAloudStateHolder
    // The audiobook: the same bar and the same marks on the page, but the book follows a recording.
    val audiobook = remember {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        AudiobookSession(
            context = context,
            container = container,
            scope = scope,
            toPage = { audiobookMessages.trySend(it) },
            onPlaying = tracker::onActivity,
            log = { if (debuggable) Log.i("Audiobook", it) },
        )
    }
    val audiobookStateHolder = audiobook.controller.state.collectAsStateWithLifecycle()
    val audiobookState by audiobookStateHolder
    // The engine, the focus and the queue go with the reader.
    DisposableEffect(readAloud) { onDispose { readAloud.close() } }
    DisposableEffect(audiobook) { onDispose { audiobook.close() } }
    // About five times a second while the audio plays, the page is told which subtitle is heard.
    LaunchedEffect(audiobookState.running) {
        while (audiobookState.running) {
            audiobook.controller.tick()
            delay(AUDIOBOOK_TICK_MS)
        }
    }
    // The screen stays on while the book is read aloud, and not a moment longer.
    val rootView = LocalView.current
    // The window flag, not View.keepScreenOn: that one is only passed on at the next frame, which
    // a stopped or still screen never draws.
    val keepScreenOn = readAloudState.running || audiobookState.running
    // Time with sound playing (the voice speaking or the recording running) is also counted as listening;
    // a pause, the end, a problem or leaving the book (both close) all clear it.
    val audioPlaying = readAloudState.phase == ReadAloudPhase.SPEAKING || audiobookState.phase == AudiobookPhase.PLAYING
    DisposableEffect(audioPlaying) {
        tracker.setAudioPlaying(audioPlaying)
        onDispose { tracker.setAudioPlaying(false) }
    }
    // Interface sounds keep quiet over speech.
    DisposableEffect(keepScreenOn) {
        container.uiSounds.readerSpeaking = keepScreenOn
        onDispose { container.uiSounds.readerSpeaking = false }
    }
    DisposableEffect(rootView, keepScreenOn) {
        val window = rootView.context.findActivity()?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    return remember { ReaderAudio(readAloud, audiobook, pageMessages, pronunciationRules, readAloudStateHolder, audiobookStateHolder) }
}

/**
 * The bar of whichever voice is on, at the bottom of the book, and the button that returns the page
 * to what is being read. [onBarHeight] reports the room the bar takes.
 */
@Composable
internal fun BoxScope.ReaderAudioBars(
    audio: ReaderAudio,
    overlays: ReaderOverlays,
    readAloudBarShown: Boolean,
    audiobookBarShown: Boolean,
    readAloudBarHeight: Dp,
    onBarHeight: (Dp) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Back ends read aloud first (composed after the WebView, so it comes before its back);
    // the lookup panel's own handler is composed later still and closes the panel before this.
    BackHandler(enabled = readAloudBarShown) { audio.readAloud.close() }
    if (readAloudBarShown) {
        val problem = audio.readAloudState.problem
        val ended = audio.readAloudState.phase == ReadAloudPhase.ENDED
        ReadAloudBar(
            playing = audio.readAloudState.running,
            rate = audio.readAloudState.rate,
            onPlayPause = audio.readAloud::togglePlay,
            onPrevious = audio.readAloud::previous,
            onNext = audio.readAloud::next,
            onSpeed = { audio.readAloud.setRate(ReadAloudSettings.nextRate(audio.readAloudState.rate)) },
            onClose = audio.readAloud::close,
            message = when {
                audio.readAloudState.phase == ReadAloudPhase.PROBLEM && problem != null ->
                    stringResource(R.string.read_aloud_problem, stringResource(problem.label()))
                ended -> stringResource(R.string.read_aloud_ended)
                else -> null
            },
            messageAction = if (audio.readAloudState.phase == ReadAloudPhase.PROBLEM) stringResource(R.string.read_aloud_voice_settings) else null,
            onMessageAction = { openVoiceSettings(context) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { onBarHeight(with(density) { it.height.toDp() }) },
        )
    }
    // The audiobook's bar goes the same way: Back closes it, the book stays.
    BackHandler(enabled = audiobookBarShown) { audio.audiobook.close() }
    if (audiobookBarShown) {
        val problem = audio.audiobookState.problem
        val audiobookPhase = audio.audiobookState.phase
        val needsFiles = audiobookPhase == AudiobookPhase.PROBLEM && problem != AudiobookProblem.BOOK_UNSUPPORTED
        ReadAloudBar(
            playing = audio.audiobookState.running,
            rate = audio.audiobookState.rate,
            onPlayPause = audio.audiobook.controller::togglePlay,
            onPrevious = audio.audiobook.controller::previous,
            onNext = audio.audiobook.controller::next,
            onSpeed = { audio.audiobook.controller.setRate(ReadAloudSettings.nextRate(audio.audiobookState.rate)) },
            onClose = audio.audiobook::close,
            message = when {
                audiobookPhase == AudiobookPhase.PROBLEM && problem != null ->
                    stringResource(R.string.audiobook_problem, stringResource(problem.label()))
                audiobookPhase == AudiobookPhase.LOADING -> stringResource(R.string.audiobook_loading)
                audiobookPhase == AudiobookPhase.ENDED -> stringResource(R.string.audiobook_ended)
                audio.audiobookState.doubtful -> stringResource(R.string.audiobook_doubtful, audio.audiobookState.matched, audio.audiobookState.total)
                else -> null
            },
            messageAction = if (needsFiles) stringResource(R.string.audiobook_change) else null,
            onMessageAction = {
                overlays.openedFromMenu = false
                overlays.audiobookStep = AudiobookStep.INTRO
            },
            previousDescription = stringResource(R.string.audiobook_previous),
            nextDescription = stringResource(R.string.audiobook_next),
            closeDescription = stringResource(R.string.audiobook_close),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { onBarHeight(with(density) { it.height.toDp() }) },
        )
    }
    // Over the bar, not in it: the book keeps its size when the button comes and goes. Above
    // ttu's 32 px footer (page counter) too, which sits just over the bar.
    val following = readAloudBarShown && audio.readAloudState.detached || audiobookBarShown && audio.audiobookState.detached
    if (following) {
        FollowButton(
            onClick = {
                audio.readAloud.follow()
                audio.audiobook.controller.follow()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = readAloudBarHeight + FOOTER_HEIGHT + 8.dp),
        )
    }
}

/** Android's text-to-speech settings, where a Japanese voice is installed; the main settings if there are none. */
private fun openVoiceSettings(context: Context) {
    val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tts)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No settings app: nothing more to offer.
        }
    }
}

/** How often the audiobook checks where the audio is, in milliseconds. */
private const val AUDIOBOOK_TICK_MS = 200L
