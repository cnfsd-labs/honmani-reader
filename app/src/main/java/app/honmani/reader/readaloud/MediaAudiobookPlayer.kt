package app.honmani.reader.readaloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException

/**
 * The audiobook's audio, played by Android's [MediaPlayer] as media (speech content) and with the
 * audio focus: another app taking the audio pauses it ([AudiobookPlayer.Listener.onFocusLost]).
 * The source is a content URI the reader chose; a file that is gone, or that the app may not read
 * any more, is [AudiobookProblem.AUDIO_GONE]. All calls and callbacks are on the main thread.
 */
class MediaAudiobookPlayer(context: Context) : AudiobookPlayer {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var media: MediaPlayer? = null
    private var prepared = false
    private var focus: AudioFocusRequest? = null
    private var rate = 1f

    /** Where a seek is going, until it lands: [MediaPlayer.getCurrentPosition] may still say the old place. */
    private var seekTarget: Long? = null

    override var listener: AudiobookPlayer.Listener? = null

    override fun open(source: String, onResult: (AudiobookProblem?) -> Unit) {
        dispose()
        val player = MediaPlayer()
        media = player
        var answered = false
        fun answer(problem: AudiobookProblem?) {
            if (answered || media !== player) return
            answered = true
            if (problem != null) dispose()
            onResult(problem)
        }
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            if (media !== player) return@setOnPreparedListener
            prepared = true
            answer(null)
        }
        player.setOnCompletionListener {
            if (media !== player || !prepared) return@setOnCompletionListener
            abandonFocus()
            listener?.onCompleted()
        }
        player.setOnSeekCompleteListener { if (media === player) seekTarget = null }
        player.setOnErrorListener { _, _, extra ->
            if (media === player) {
                if (!prepared) {
                    answer(if (extra == MediaPlayer.MEDIA_ERROR_IO) AudiobookProblem.AUDIO_GONE else AudiobookProblem.UNSUPPORTED)
                } else {
                    abandonFocus()
                    listener?.onError()
                }
            }
            true
        }
        try {
            player.setDataSource(context, Uri.parse(source))
            player.prepareAsync()
        } catch (_: IOException) {
            answer(AudiobookProblem.AUDIO_GONE)
        } catch (_: SecurityException) {
            answer(AudiobookProblem.AUDIO_GONE)
        } catch (_: IllegalArgumentException) {
            answer(AudiobookProblem.AUDIO_GONE)
        } catch (_: IllegalStateException) {
            answer(AudiobookProblem.UNSUPPORTED)
        }
    }

    override val positionMs: Long
        get() {
            seekTarget?.let { return it }
            val player = media?.takeIf { prepared } ?: return 0L
            return try {
                player.currentPosition.toLong()
            } catch (_: IllegalStateException) {
                0L
            }
        }

    override fun seekTo(ms: Long) {
        val player = media?.takeIf { prepared } ?: return
        val target = ms.coerceAtLeast(0)
        seekTarget = target
        try {
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } catch (_: IllegalStateException) {
            seekTarget = null
        }
    }

    override fun play(): Boolean {
        val player = media?.takeIf { prepared } ?: return false
        if (!requestFocus()) return false
        return try {
            player.start()
            // The speed only after start(): setting it on a stopped player can start the playback.
            applyRate(player)
            true
        } catch (_: IllegalStateException) {
            abandonFocus()
            false
        }
    }

    override fun pause() {
        val player = media?.takeIf { prepared }
        try {
            if (player != null && player.isPlaying) player.pause()
        } catch (_: IllegalStateException) {
            // The player is already gone.
        }
        abandonFocus()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        val player = media?.takeIf { prepared } ?: return
        try {
            if (player.isPlaying) applyRate(player)
        } catch (_: IllegalStateException) {
            // The player is already gone.
        }
    }

    override fun release() {
        dispose()
    }

    private fun applyRate(player: MediaPlayer) {
        try {
            player.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
        } catch (_: IllegalArgumentException) {
            // A speed this phone can't do: the audio plays at normal speed.
        } catch (_: IllegalStateException) {
            // The player is already gone.
        }
    }

    private fun dispose() {
        val player = media
        media = null
        prepared = false
        seekTarget = null
        abandonFocus()
        if (player != null) {
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            player.setOnSeekCompleteListener(null)
            player.release()
        }
    }

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        listener?.onFocusLost()
                    }
                },
                main,
            )
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        return true
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
