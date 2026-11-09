package app.honmani.reader.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.anki.CardVoice
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.ui.anki.label
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Plays a sample sentence with a [CardVoice] (Voice settings and the audiobook creator share it).
 * [playing] is true from the request until the sound ends; a failure leaves a readable [error].
 * Main thread only.
 */
class VoiceSamplePlayer(
    private val cacheDir: File,
    private val readMessage: (Int, Int) -> String,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var playing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private var job: Job? = null

    /** Speaks [text] at [rate]; ignored while a sample is already on its way. */
    fun play(voice: CardVoice, text: String, rate: Double) {
        if (playing) return
        playing = true
        error = null
        job = scope.launch {
            try {
                val file = File(cacheDir, "voice-sample.${voice.extension}")
                voice.speak(text, file, rate)
                release()
                player = MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener { playing = false }
                    setOnErrorListener { _, _, _ -> fail(VoiceProblem.FAILED); true }
                    prepare()
                    start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceException) {
                fail(e.problem)
            } catch (e: java.io.IOException) {
                fail(VoiceProblem.FAILED)
            } catch (e: IllegalStateException) {
                // The phone's media player refused the file.
                fail(VoiceProblem.FAILED)
            }
        }
    }

    private fun fail(problem: VoiceProblem) {
        error = readMessage(R.string.voice_sample_failed, problem.label())
        playing = false
    }

    private fun release() {
        player?.release()
        player = null
    }

    /** Stops the sound and any request still running. */
    fun stop() {
        job?.cancel()
        release()
        playing = false
    }
}

/** A [VoiceSamplePlayer] that stops when the screen or dialog leaves the composition. */
@Composable
fun rememberVoiceSamplePlayer(): VoiceSamplePlayer {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val player = remember {
        VoiceSamplePlayer(
            cacheDir = context.cacheDir,
            readMessage = { template, problem -> resources.getString(template, resources.getString(problem)) },
            scope = scope,
        )
    }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

/** The sample button: filled and with a speaker, so it is seen; its progress and any failure sit under it. */
@Composable
fun VoiceSampleButton(sample: VoiceSamplePlayer, enabled: Boolean, onClick: () -> Unit, horizontalPadding: Dp = 16.dp) {
    Column(modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                enabled = enabled,
                onClick = onClick,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(painter = painterResource(R.drawable.ic_listen), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_listen))
            }
            if (sample.playing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        sample.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
