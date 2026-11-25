package app.honmani.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.honmani.reader.AppContainer
import app.honmani.reader.readaloud.AudiobookPhase
import app.honmani.reader.readaloud.BackgroundPlayback
import app.honmani.reader.readaloud.PlaybackHub
import app.honmani.reader.readaloud.PlaybackService
import app.honmani.reader.ui.rememberWithNotifications
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * "Keep playing with the screen off": tells [PlaybackService] what the reader's voice does and
 * takes its buttons. The controllers' own flows are followed, not the screen's state, which stops
 * updating while the app is in the background. The first playback asks for the notification
 * permission (Android 13+), once while the activity lives (the reader screen stays composed from
 * book to book) and only with the reader in front (a playback starting in the background asks
 * nothing); the voice plays whatever the answer.
 */
@Composable
internal fun KeepPlayingEffect(container: AppContainer, audio: ReaderAudio, title: String) {
    val context = LocalContext.current
    val withNotifications = rememberWithNotifications()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val asked = remember(audio) { booleanArrayOf(false) }
    val currentTitle by rememberUpdatedState(title)
    val hub = container.playback
    DisposableEffect(audio) {
        hub.controls = object : PlaybackHub.Controls {
            override fun play() {
                if (audio.audiobook.controller.state.value.phase != AudiobookPhase.OFF) {
                    audio.audiobook.controller.play()
                } else {
                    audio.readAloud.resume()
                }
            }

            override fun pause() {
                audio.readAloud.pause()
                audio.audiobook.controller.pause()
            }

            override fun stop() {
                audio.readAloud.close()
                audio.audiobook.close()
            }
        }
        onDispose {
            hub.controls = null
            hub.publish(null)
        }
    }
    LaunchedEffect(audio) {
        combine(
            container.readAloudSettings.keepPlaying,
            audio.readAloud.state,
            audio.audiobook.controller.state,
            snapshotFlow { currentTitle },
        ) { keep, readAloud, audiobook, name -> BackgroundPlayback.nowPlaying(keep, name, readAloud.phase, audiobook.phase) }
            .distinctUntilChanged()
            .collect { now ->
                if (hub.publish(now)) {
                    PlaybackService.start(context)
                    if (!asked[0] && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        asked[0] = true
                        withNotifications {}
                    }
                }
            }
    }
}
