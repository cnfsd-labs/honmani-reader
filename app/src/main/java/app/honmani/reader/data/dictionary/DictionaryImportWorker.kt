package app.honmani.reader.data.dictionary

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.dictionary.yomitan.InvalidDictionaryException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DictionaryImportWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    // stopReason is WorkManager's own record, set on every Android version; only the system's
    // reasons need API 31, and a cancel from the app is not one of them.
    @SuppressLint("NewApi")
    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri()
        val url = inputData.getString(KEY_URL)
        if (uri == null && url == null) return failure(ImportError.FAILED, null)
        val resolver = applicationContext.contentResolver
        val container = (applicationContext as HonmaniApplication).container
        val copy = File(applicationContext.cacheDir, "dictionary-import-$id.zip")
        val throttle = ProgressThrottle()
        // Interrupted downloads of this import, kept for a resume unless the user cancels.
        val partials = listOfNotNull(url, inputData.getString(KEY_FALLBACK_URL))
            .map { DictionaryDownloader.partialFile(applicationContext.cacheDir, it) }
        var ending = Ending.FINISHED
        try {
            runInForeground(null)
            val importer = container.dictionaryImporter
            val format = inputData.getString(KEY_FORMAT)
                ?.let { name -> DictionaryFileFormat.entries.firstOrNull { it.name == name } }
                ?: DictionaryFileFormat.YOMITAN
            val replaces = inputData.getLong(KEY_REPLACES, -1).takeIf { it >= 0 }
            val importCopy: suspend () -> Unit = {
                importer.import(copy, format, replaces) { progress ->
                    setProgress(progress.toData())
                    if (throttle.shouldPost(progress.title, progress.percent)) runInForeground(progress)
                }
            }
            if (url != null) {
                val name = inputData.getString(KEY_NAME) ?: url.substringAfterLast('/')
                // The publisher's latest release is sometimes incomplete: then the pinned one.
                withReleaseFallback(name, url, inputData.getString(KEY_FALLBACK_URL)) { from ->
                    val partial = DictionaryDownloader.partialFile(applicationContext.cacheDir, from)
                    container.dictionaryDownloader.download(from, copy, partial = partial) { done, total ->
                        val download = DownloadProgress(name, done, total)
                        setProgress(download.toData())
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (throttle.shouldPost(DOWNLOAD_STAGE, percent)) runInForeground(null, download)
                    }
                    if (format == DictionaryFileFormat.YOMITAN && !DownloadCheck.hasContentBank(copy)) {
                        throw IncompleteReleaseException(name)
                    }
                    importCopy()
                }
            } else {
                // ZipFile needs random access, which a content URI does not offer.
                resolver.openInputStream(uri!!)?.use { input ->
                    copy.outputStream().use { copyAtMost(input, it) }
                } ?: return failure(ImportError.FAILED, null)
                importCopy()
            }
            return Result.success()
        } catch (e: CancellationException) {
            // Cancelled by the user (the row's or the notification's Cancel): no retry. Otherwise
            // stopped by the system (constraints, timeout): WorkManager runs it again.
            ending = if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) Ending.CANCELLED else Ending.STOPPED
            throw e
        } catch (e: DownloadException) {
            return failure(ImportError.DOWNLOAD_FAILED, e.message, e.reason)
        } catch (e: InvalidDictionaryException) {
            return failure(ImportError.INVALID, e.message)
        } catch (e: DictionaryAlreadyInstalledException) {
            return failure(ImportError.ALREADY_INSTALLED, e.title)
        } catch (_: ReplacedDictionaryGoneException) {
            // The reader deleted the dictionary meanwhile: nothing to update, nothing to report.
            return Result.success()
        } catch (e: NoTermsException) {
            return failure(ImportError.NO_TERMS, e.title)
        } catch (e: IncompleteReleaseException) {
            return failure(ImportError.INCOMPLETE_RELEASE, e.name)
        } catch (e: IOException) {
            return failure(ImportError.FAILED, e.message, FailureReason.ofStorage(e))
        } catch (e: SecurityException) {
            // Access to the picked document was lost, e.g. after a reboot.
            return failure(ImportError.FAILED, e.message)
        } catch (e: SQLException) {
            // Typically a full disk.
            return failure(ImportError.FAILED, e.message, FailureReason.ofStorage(e))
        } finally {
            cleanUp(ending, copy, partials) {
                if (uri != null) {
                    runCatching {
                        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }

    /** Keeps the import running if the user leaves the app; large dictionaries take minutes. */
    private suspend fun runInForeground(progress: ImportProgress?, download: DownloadProgress? = null) {
        try {
            setForeground(foregroundInfo(progress, download))
        } catch (_: IllegalStateException) {
            // Android 12+ refuses to start a foreground service from the background; the import
            // continues as normal background work.
        }
    }

    private fun foregroundInfo(progress: ImportProgress?, download: DownloadProgress?): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_imports),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val (title, max, value) = when {
            download != null -> Triple(
                context.getString(R.string.download_running, download.name),
                if (download.bytesTotal > 0) 100 else 0,
                if (download.bytesTotal > 0) (download.bytesDone * 100 / download.bytesTotal).toInt() else 0,
            )
            progress != null -> Triple(
                context.getString(R.string.import_running, progress.title),
                100,
                progress.percent,
            )
            else -> Triple(context.getString(R.string.import_preparing), 0, 0)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setProgress(max, value, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()
        // One notification per import; imports waiting for another one show "Preparing".
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun failure(error: ImportError, detail: String?, reason: FailureReason = FailureReason.OTHER) =
        Result.failure(workDataOf(KEY_ERROR to error.name, KEY_DETAIL to detail, KEY_REASON to reason.name))

    /** How a run ended, for [cleanUp]. */
    internal enum class Ending {
        /** Done, successfully or not: nothing runs it again. */
        FINISHED,

        /** Stopped by the system: WorkManager runs it again. */
        STOPPED,

        /** Cancelled by the user. */
        CANCELLED,
    }

    companion object {
        /**
         * Tidies up after a run: the copy always goes; the picked document's access is released
         * ([releaseAccess]) unless WorkManager retries, which needs it; interrupted downloads
         * ([partials]) stay for a resume unless the user cancelled.
         */
        internal fun cleanUp(ending: Ending, copy: File, partials: List<File>, releaseAccess: () -> Unit) {
            copy.delete()
            if (ending != Ending.STOPPED) releaseAccess()
            if (ending == Ending.CANCELLED) partials.forEach(DictionaryDownloader::discardPartial)
        }

        private const val KEY_URI = "uri"
        private const val KEY_URL = "url"
        private const val KEY_FALLBACK_URL = "fallbackUrl"
        private const val KEY_NAME = "name"
        private const val KEY_FORMAT = "format"
        private const val KEY_REPLACES = "replaces"
        private const val KEY_BYTES_DONE = "bytesDone"
        private const val KEY_BYTES_TOTAL = "bytesTotal"
        private const val KEY_ERROR = "error"
        private const val KEY_DETAIL = "detail"
        private const val KEY_REASON = "reason"
        private const val KEY_TITLE = "title"
        private const val KEY_PERCENT = "percent"

        private const val CHANNEL_ID = "imports"
        private const val DOWNLOAD_STAGE = "download"

        /**
         * Copies a picked file, at most as much as a download may be
         * ([DictionaryDownloader.MAX_DOWNLOAD_BYTES]): a bigger file is no dictionary and would only fill the cache.
         */
        internal fun copyAtMost(input: InputStream, output: OutputStream, maxBytes: Long = DictionaryDownloader.MAX_DOWNLOAD_BYTES) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                total += read
                if (total > maxBytes) {
                    throw InvalidDictionaryException("The file is larger than ${maxBytes / (1024 * 1024)} MB, more than any dictionary.")
                }
                output.write(buffer, 0, read)
            }
        }

        fun inputData(uri: String): Data = workDataOf(KEY_URI to uri)

        fun downloadInputData(dictionary: CatalogDictionary): Data =
            workDataOf(
                KEY_URL to dictionary.url,
                KEY_FALLBACK_URL to dictionary.fallbackUrl,
                KEY_NAME to dictionary.name,
                KEY_FORMAT to dictionary.format.name,
            )

        fun updateInputData(update: DictionaryUpdate): Data = workDataOf(
            KEY_URL to update.downloadUrl,
            KEY_NAME to update.name,
            KEY_FORMAT to DictionaryFileFormat.YOMITAN.name,
            KEY_REPLACES to update.dictionaryId,
        )

        private fun DownloadProgress.toData() = workDataOf(
            KEY_NAME to name,
            KEY_BYTES_DONE to bytesDone,
            KEY_BYTES_TOTAL to bytesTotal,
        )

        fun downloadOf(data: Data): DownloadProgress? {
            val name = data.getString(KEY_NAME) ?: return null
            return DownloadProgress(name, data.getLong(KEY_BYTES_DONE, 0), data.getLong(KEY_BYTES_TOTAL, -1))
        }

        private fun ImportProgress.toData() = workDataOf(
            KEY_TITLE to title,
            KEY_PERCENT to percent,
        )

        fun progressOf(data: Data): ImportProgress? {
            val title = data.getString(KEY_TITLE) ?: return null
            return ImportProgress(title, data.getInt(KEY_PERCENT, 0))
        }

        fun reasonOf(data: Data): FailureReason = FailureReason.parse(data.getString(KEY_REASON))

        fun errorOf(data: Data): Pair<ImportError, String?> {
            val error = data.getString(KEY_ERROR)
                ?.let { name -> ImportError.entries.firstOrNull { it.name == name } }
                ?: ImportError.FAILED
            return error to data.getString(KEY_DETAIL)
        }
    }
}

/**
 * [DictionaryImportScheduler] backed by WorkManager, so imports survive process death.
 * [DictionaryImporter] runs them one at a time.
 */
class WorkManagerDictionaryImportScheduler(private val context: Context) : DictionaryImportScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun cancel(id: String) {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return
        workManager.cancelWorkById(uuid)
    }

    override fun enqueue(uri: String) {
        // The picker's grant ends with this process; keep access until the worker is done.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val request = OneTimeWorkRequestBuilder<DictionaryImportWorker>()
            .setInputData(DictionaryImportWorker.inputData(uri))
            .addTag(TAG)
            .build()
        workManager.enqueue(request)
    }

    override val imports: Flow<List<ImportStatus>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.mapNotNull { info ->
                val id = info.id.toString()
                val replaces = info.tags.firstOrNull { it.startsWith(UPDATE_TAG) }?.removePrefix(UPDATE_TAG)?.toLongOrNull()
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ImportStatus.Queued(id, replaces)
                    WorkInfo.State.RUNNING -> ImportStatus.Running(
                        id = id,
                        progress = DictionaryImportWorker.progressOf(info.progress),
                        download = DictionaryImportWorker.downloadOf(info.progress),
                        replaces = replaces,
                    )
                    WorkInfo.State.FAILED -> {
                        val (error, detail) = DictionaryImportWorker.errorOf(info.outputData)
                        val dictionaryId = info.tags.firstOrNull { it.startsWith(DICTIONARY_TAG) }
                            ?.removePrefix(DICTIONARY_TAG)
                        ImportStatus.Failed(id, error, detail, dictionaryId, DictionaryImportWorker.reasonOf(info.outputData), replaces)
                    }
                    // Kept (not shown) so a cancelled catalog download is offered again.
                    WorkInfo.State.CANCELLED -> ImportStatus.Failed(
                        id,
                        ImportError.CANCELLED,
                        null,
                        info.tags.firstOrNull { it.startsWith(DICTIONARY_TAG) }?.removePrefix(DICTIONARY_TAG),
                        replaces = replaces,
                    )
                    WorkInfo.State.SUCCEEDED -> null
                }
            }
        }

    override fun download(dictionary: CatalogDictionary) {
        val request = OneTimeWorkRequestBuilder<DictionaryImportWorker>()
            .setInputData(DictionaryImportWorker.downloadInputData(dictionary))
            .addTag(TAG)
            .addTag(DICTIONARY_TAG + dictionary.id)
            .build()
        // A second tap while it is on its way changes nothing.
        workManager.enqueueUniqueWork(DICTIONARY_TAG + dictionary.id, ExistingWorkPolicy.KEEP, request)
    }

    override fun update(update: DictionaryUpdate) {
        val request = OneTimeWorkRequestBuilder<DictionaryImportWorker>()
            .setInputData(DictionaryImportWorker.updateInputData(update))
            .addTag(TAG)
            .addTag(UPDATE_TAG + update.dictionaryId)
            .build()
        workManager.enqueueUniqueWork(UPDATE_TAG + update.dictionaryId, ExistingWorkPolicy.KEEP, request)
    }

    override fun clearFailures() {
        workManager.pruneWork()
    }

    internal companion object {
        const val TAG = "dictionary-import"

        /** Prefix of the tag that names the catalog dictionary a download is for. */
        const val DICTIONARY_TAG = "dictionary-import:"

        /** Prefix of the tag that names the installed dictionary an update replaces. */
        const val UPDATE_TAG = "dictionary-update:"
    }
}
