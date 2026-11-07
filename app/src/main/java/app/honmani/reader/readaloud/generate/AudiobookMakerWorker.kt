package app.honmani.reader.readaloud.generate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What [AudiobookMakerWorker] needs from the app; a test replaces it with [AudiobookMakerWorker.environment]. */
class AudiobookEnvironment(
    val maker: AudiobookMaker,
    /** The voice for the named Google voice with the reader's key; null if there is no key. */
    val voice: (String) -> SentenceVoice?,
    /** The title of the book for the notification. */
    val title: suspend (bookHash: String) -> String?,
)

/**
 * Makes the audiobook of one book ([AudiobookMaker]) as foreground work with a notification that
 * has a Cancel button. WorkManager runs it once per book, on the network the reader allowed.
 *
 * - Finished: success, and a quiet notification that the book is ready.
 * - Cap reached or stopped (key, billing, bad audio): failure, so nothing retries; the message is
 *   kept in `outcome.txt` for the dialog and shown in a notification.
 * - Paused (Google busy, or Android would not start the foreground service from the background):
 *   retry after 15 minutes; the outcome says why.
 * - Stopped by the system (network lost, Android 15's six hours for data sync) or cancelled:
 *   the coroutine is cancelled, WorkManager reschedules a system stop, and the journal makes the
 *   next run go on after the last whole sentence.
 */
class AudiobookMakerWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val bookHash = inputData.getString(KEY_BOOK) ?: return Result.failure()
        val env = (environment ?: ::fromApplication)(applicationContext)
        val maker = env.maker
        val files = maker.files(bookHash)
        files.clearOutcome()
        val title = env.title(bookHash).orEmpty()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val notificationId = notificationId(bookHash)
        val progress = MakerProgress(files.wholeLines(), files.readPlan()?.sentences?.size ?: 0, files.billedMicros() / MICROS)
        try {
            setForeground(foregroundInfo(title, progress.takeIf { it.total > 0 }))
        } catch (_: IllegalStateException) {
            // Android 12+ refuses to start a foreground service while the app is in the background,
            // and a job this long must not run unseen: wait, and go on when the reader opens Honmani.
            return paused(files, applicationContext.getString(R.string.audiobook_open_honmani))
        }
        val plan = files.readPlan()
        val voice = env.voice(plan?.voice ?: inputData.getString(KEY_VOICE).orEmpty())
            ?: return stopped(files, MakerOutcome(MakerOutcome.Kind.STOPPED, applicationContext.getString(R.string.audiobook_no_key)), title)
        val cap = inputData.getDouble(KEY_CAP, -1.0).takeIf { it > 0 }
        var lastPosted = 0L
        val result = maker.run(bookHash, voice, cap) { p ->
            val now = clock()
            if (now - lastPosted >= POST_EVERY_MS) {
                lastPosted = now
                setProgressAsync(workDataOf(KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_SPENT to p.spentUsd))
                manager.notify(notificationId, notification(title, p))
            }
        }
        manager.cancel(notificationId)
        return when (result) {
            is MakerResult.Finished -> {
                postResult(title, applicationContext.getString(R.string.audiobook_ready), bookHash)
                Result.success()
            }
            is MakerResult.CapReached -> stopped(files, MakerOutcome(MakerOutcome.Kind.CAP, result.message), title)
            is MakerResult.Stopped -> stopped(files, MakerOutcome(MakerOutcome.Kind.STOPPED, result.message), title)
            is MakerResult.Paused -> paused(files, result.message)
        }
    }

    private fun paused(files: GeneratedFiles, message: String): Result {
        files.writeOutcome(MakerOutcome(MakerOutcome.Kind.PAUSED, message))
        return Result.retry()
    }

    private fun stopped(files: GeneratedFiles, outcome: MakerOutcome, title: String): Result {
        files.writeOutcome(outcome)
        postResult(title, outcome.message, inputData.getString(KEY_BOOK).orEmpty())
        return Result.failure(workDataOf(KEY_MESSAGE to outcome.message))
    }

    private fun channel(): String {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_audiobook), NotificationManager.IMPORTANCE_LOW),
        )
        return CHANNEL_ID
    }

    private fun notification(title: String, progress: MakerProgress?): android.app.Notification {
        val context = applicationContext
        val text = if (progress == null) {
            context.getString(R.string.audiobook_preparing)
        } else {
            context.getString(R.string.audiobook_progress, progress.done, progress.total, usd(progress.spentUsd))
        }
        val builder = NotificationCompat.Builder(context, channel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.audiobook_making, title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(R.string.action_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
        if (progress != null && progress.total > 0) builder.setProgress(progress.total, progress.done, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun foregroundInfo(title: String, progress: MakerProgress?): ForegroundInfo {
        val notification = notification(title, progress)
        val notificationId = notificationId(inputData.getString(KEY_BOOK).orEmpty())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /** A notification that stays after the work ended: the book is ready, or why it stopped. */
    private fun postResult(title: String, message: String, bookHash: String) {
        val context = applicationContext
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, channel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { context.getString(R.string.audiobook_notification_title) })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(bookHash) + 1, notification)
    }

    companion object {
        private const val KEY_BOOK = "book"
        private const val KEY_VOICE = "voice"
        private const val KEY_CAP = "cap"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val KEY_SPENT = "spent"
        private const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "audiobook"
        private const val POST_EVERY_MS = 1000L
        private const val MICROS = 1_000_000.0

        /** What the worker uses; the app's by default, a test's own when set. */
        @Volatile
        internal var environment: ((Context) -> AudiobookEnvironment)? = null

        @Volatile
        internal var clock: () -> Long = System::currentTimeMillis

        private fun fromApplication(context: Context): AudiobookEnvironment =
            (context.applicationContext as HonmaniApplication).container.audiobookEnvironment

        private fun notificationId(bookHash: String) = ("audiobook:$bookHash").hashCode() and 0x7FFFFFFE

        private fun usd(value: Double) = "$" + String.format(Locale.ROOT, "%.2f", value)

        fun inputData(bookHash: String, voice: String, capUsd: Double): Data =
            workDataOf(KEY_BOOK to bookHash, KEY_VOICE to voice, KEY_CAP to capUsd)

        fun progressOf(data: Data): MakerProgress? {
            val total = data.getInt(KEY_TOTAL, -1)
            if (total < 0) return null
            return MakerProgress(data.getInt(KEY_DONE, 0), total, data.getDouble(KEY_SPENT, 0.0))
        }
    }
}

/**
 * [AudiobookScheduler] on WorkManager: one unique job per book, that survives the process. The
 * state combines the work's own state with `outcome.txt`, which the worker writes before it ends.
 */
class WorkManagerAudiobookScheduler(
    context: Context,
    private val maker: AudiobookMaker,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : AudiobookScheduler {

    override fun start(bookHash: String, voice: String, capUsd: Double, allowMobileData: Boolean) {
        maker.files(bookHash).clearOutcome()
        val request = OneTimeWorkRequestBuilder<AudiobookMakerWorker>()
            .setInputData(AudiobookMakerWorker.inputData(bookHash, voice, capUsd))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, AudiobookMaker.PAUSE_MS, TimeUnit.MILLISECONDS)
            .build()
        // Replace: a new cap or network choice applies; the journal makes the new run go on where the old one was.
        workManager.enqueueUniqueWork(workName(bookHash), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(bookHash: String) {
        workManager.cancelUniqueWork(workName(bookHash))
        maker.files(bookHash).clearOutcome()
    }

    override fun state(bookHash: String): Flow<AudiobookJobState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(bookHash)).map { infos ->
            jobState(infos.firstOrNull(), maker.files(bookHash).readOutcome())
        }

    companion object {
        fun workName(bookHash: String) = "audiobook-$bookHash"

        /** The state for the work [info] (null: none) and the last [outcome] on disk. */
        fun jobState(info: WorkInfo?, outcome: MakerOutcome?): AudiobookJobState = when (info?.state) {
            WorkInfo.State.RUNNING -> AudiobookJobState.Running(AudiobookMakerWorker.progressOf(info.progress))
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                if (outcome?.kind == MakerOutcome.Kind.PAUSED) AudiobookJobState.Paused(outcome.message) else AudiobookJobState.Waiting
            WorkInfo.State.SUCCEEDED -> AudiobookJobState.Finished
            WorkInfo.State.CANCELLED -> AudiobookJobState.Idle
            WorkInfo.State.FAILED, null -> when (outcome?.kind) {
                MakerOutcome.Kind.CAP -> AudiobookJobState.Stopped(outcome.message, capReached = true)
                MakerOutcome.Kind.STOPPED -> AudiobookJobState.Stopped(outcome.message, capReached = false)
                else -> AudiobookJobState.Idle
            }
        }
    }
}
