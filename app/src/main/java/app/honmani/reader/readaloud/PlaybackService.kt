package app.honmani.reader.readaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps read aloud and the audiobook going with the screen off ([BackgroundPlayback]): a media
 * playback foreground service with Android's own [MediaSession] (lock screen, headset buttons) and
 * a media notification with the book's title, Play or Pause, and Stop. It plays nothing itself:
 * the reader screen still owns the voices and tells it what plays through [PlaybackHub], and its
 * buttons go back there. It stops when [PlaybackHub.now] becomes null (stopped, ended, the book
 * closed, the setting turned off).
 *
 * The framework session, not media3's MediaSessionService: that one wants a media3 Player, and both
 * voices (a speech engine fed sentence by sentence, a MediaPlayer with page marks) would need one
 * written around them; this needs no new library.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var hub: PlaybackHub
    private lateinit var session: MediaSession
    private var shown: NowPlaying? = null

    /** [onStartCommand] ran: before that the service may not stop (it must reach the front first). */
    private var started = false

    /** [finish] ran and no new start came since: nothing may post the notification again (it would stay behind). */
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hub = (application as HonmaniApplication).container.playback
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        session = MediaSession(this, "Honmani").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        hub.controls?.play()
                    }

                    override fun onPause() {
                        hub.controls?.pause()
                    }

                    override fun onStop() {
                        hub.controls?.stop()
                    }
                },
            )
            isActive = true
        }
        scope.launch {
            hub.now.collect { now ->
                if (now == null) {
                    if (started) finish()
                } else {
                    show(now)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A started foreground service has to be in front within a few seconds, even when the
        // voice already ended again: then it goes at once.
        val now = hub.now.value
        stopping = false
        startInFront(now ?: shown ?: NowPlaying("", false))
        started = true
        when (intent?.action) {
            ACTION_PLAY -> hub.controls?.play()
            ACTION_PAUSE -> hub.controls?.pause()
            ACTION_STOP -> hub.controls?.stop()
        }
        if (now == null || hub.controls == null) finish()
        return START_NOT_STICKY
    }

    /** The app was swiped away from the recent apps: the voice goes with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        hub.controls?.stop()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun startInFront(now: NowPlaying) {
        val notification = notification(now)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // Not allowed from the background (Android 12+): the voice still plays while Android lets it.
            Log.w(TAG, "Could not show the playback notification", e)
        }
    }

    private fun show(now: NowPlaying) {
        if (stopping || now == shown) return
        shown = now
        session.isActive = true
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, now.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (now.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (now.playing) 1f else 0f,
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(now))
    }

    private fun finish() {
        stopping = true
        shown = null
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(now: NowPlaying): Notification {
        val toggle = if (now.playing) {
            action(R.drawable.ic_pause, R.string.read_aloud_pause, ACTION_PAUSE)
        } else {
            action(R.drawable.ic_play, R.string.read_aloud_play, ACTION_PLAY)
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(now.title)
            .setContentText(getString(if (now.playing) R.string.playback_playing else R.string.playback_paused))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(toggle)
            .addAction(action(R.drawable.ic_stop, R.string.playback_stop, ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun action(icon: Int, title: Int, action: String): Notification.Action {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pending).build()
    }

    companion object {
        private const val TAG = "Honmani.playback"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 7_301
        private const val ACTION_PLAY = "app.honmani.reader.playback.PLAY"
        private const val ACTION_PAUSE = "app.honmani.reader.playback.PAUSE"
        private const val ACTION_STOP = "app.honmani.reader.playback.STOP"

        /** Starts the service when something begins to play (the app is in front then). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            } catch (e: RuntimeException) {
                // Android 12+ refuses it from the background; the voice plays on without a notification.
                Log.w(TAG, "Could not start the playback service", e)
            }
        }
    }
}
