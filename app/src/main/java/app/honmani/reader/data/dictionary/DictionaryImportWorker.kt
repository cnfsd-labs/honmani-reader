package app.honmani.reader.data.dictionary

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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DictionaryImportWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri()
        val url = inputData.getString(KEY_URL)
        if (uri == null && url == null) return failure(ImportError.FAILED, null)
        val resolver = applicationContext.contentResolver
        val container = (applicationContext as HonmaniApplication).container
        val copy = File(applicationContext.cacheDir, "dictionary-import-$id.zip")
        val throttle = ProgressThrottle()
        // True when the system stopped the worker and WorkManager will run it again: the retry
        // needs the picked document's access, so it is only released at a final outcome.
        var willRetry = false
        try {
            runInForeground(null)
            if (url != null) {
                val name = inputData.getString(KEY_NAME) ?: url.substringAfterLast('/')
                container.dictionaryDownloader.download(url, copy) { done, total ->
                    val download = DownloadProgress(name, done, total)
                    setProgress(download.toData())
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (throttle.shouldPost(DOWNLOAD_STAGE, percent)) runInForeground(null, download)
                }
            } else {
                // ZipFile needs random access, which a content URI does not offer.
                resolver.openInputStream(uri!!)?.use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                } ?: return failure(ImportError.FAILED, null)
            }

            val importer = container.dictionaryImporter
            val format = inputData.getString(KEY_FORMAT)
                ?.let { name -> DictionaryFileFormat.entries.firstOrNull { it.name == name } }
                ?: DictionaryFileFormat.YOMITAN
            val replaces = inputData.getLong(KEY_REPLACES, -1).takeIf { it >= 0 }
            importer.import(copy, format, replaces) { progress ->
                setProgress(progress.toData())
                if (throttle.shouldPost(progress.title, progress.percent)) runInForeground(progress)
            }
            return Result.success()
        } catch (e: CancellationException) {
            // Stopped by the system (constraints, timeout): WorkManager runs it again. Nothing in
            // the app cancels imports.
            willRetry = true
            throw e
        } catch (e: DownloadException) {
            return failure(ImportError.DOWNLOAD_FAILED, e.message)
        } catch (e: InvalidDictionaryException) {
            return failure(ImportError.INVALID, e.message)
        } catch (e: DictionaryAlreadyInstalledException) {
            return failure(ImportError.ALREADY_INSTALLED, e.title)
        } catch (e: NoTermsException) {
            return failure(ImportError.NO_TERMS, e.title)
        } catch (e: IOException) {
            return failure(ImportError.FAILED, e.message)
        } catch (e: SecurityException) {
            // Access to the picked document was lost, e.g. after a reboot.
            return failure(ImportError.FAILED, e.message)
        } catch (e: SQLException) {
            // Typically a full disk.
            return failure(ImportError.FAILED, e.message)
        } finally {
            copy.delete()
            if (uri != null && !willRetry) {
                runCatching {
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            .build()
        // One notification per import; imports waiting for another one show "Preparing".
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun failure(error: ImportError, detail: String?) = Result.failure(
        workDataOf(KEY_ERROR to error.name, KEY_DETAIL to detail),
    )

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_URL = "url"
        private const val KEY_NAME = "name"
        private const val KEY_FORMAT = "format"
        private const val KEY_REPLACES = "replaces"
        private const val KEY_BYTES_DONE = "bytesDone"
        private const val KEY_BYTES_TOTAL = "bytesTotal"
        private const val KEY_ERROR = "error"
        private const val KEY_DETAIL = "detail"
        private const val KEY_TITLE = "title"
        private const val KEY_PERCENT = "percent"

        private const val CHANNEL_ID = "imports"
        private const val DOWNLOAD_STAGE = "download"

        fun inputData(uri: String): Data = workDataOf(KEY_URI to uri)

        fun downloadInputData(dictionary: CatalogDictionary): Data =
            workDataOf(KEY_URL to dictionary.url, KEY_NAME to dictionary.name, KEY_FORMAT to dictionary.format.name)

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
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ImportStatus.Queued(id)
                    WorkInfo.State.RUNNING -> ImportStatus.Running(
                        id = id,
                        progress = DictionaryImportWorker.progressOf(info.progress),
                        download = DictionaryImportWorker.downloadOf(info.progress),
                    )
                    WorkInfo.State.FAILED -> {
                        val (error, detail) = DictionaryImportWorker.errorOf(info.outputData)
                        ImportStatus.Failed(id, error, detail)
                    }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> null
                }
            }
        }

    override fun download(dictionary: CatalogDictionary) {
        val request = OneTimeWorkRequestBuilder<DictionaryImportWorker>()
            .setInputData(DictionaryImportWorker.downloadInputData(dictionary))
            .addTag(TAG)
            .build()
        workManager.enqueue(request)
    }

    override fun update(update: DictionaryUpdate) {
        val request = OneTimeWorkRequestBuilder<DictionaryImportWorker>()
            .setInputData(DictionaryImportWorker.updateInputData(update))
            .addTag(TAG)
            .build()
        workManager.enqueue(request)
    }

    override fun clearFailures() {
        workManager.pruneWork()
    }

    private companion object {
        const val TAG = "dictionary-import"
    }
}
