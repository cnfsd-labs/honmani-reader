package app.honmani.reader.readaloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Headphones unplugged (or a Bluetooth headset gone) while Honmani speaks: Android would carry on
 * over the speaker. Between [start] and [stop] that calls [onNoisy] on the main thread; the
 * players treat it like the audio focus lost for good (pause and stay paused).
 */
internal class BecomingNoisy(context: Context, private val onNoisy: () -> Unit) {
    private val context = context.applicationContext
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (registered && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }
}
