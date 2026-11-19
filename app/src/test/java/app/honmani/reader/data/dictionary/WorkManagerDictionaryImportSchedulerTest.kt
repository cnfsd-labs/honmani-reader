package app.honmani.reader.data.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerDictionaryImportSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Runs until stopped, like a long download. */
    private class Endless(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result = awaitCancellation()
    }

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                Endless(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
    }

    private val dictionary = DictionaryCatalog.dictionaries.first()

    private fun works() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WorkManagerDictionaryImportScheduler.DICTIONARY_TAG + dictionary.id).get()

    @Test
    fun aSecondTapKeepsTheDownloadOnItsWay() {
        val scheduler = WorkManagerDictionaryImportScheduler(context)
        scheduler.download(dictionary)
        val first = works().single()
        scheduler.download(dictionary)

        assertEquals(first.id, works().single().id)
    }

    @Test
    fun aCancelledDownloadIsOfferedAgainAndCanBeRequestedAgain() = runBlocking {
        val scheduler = WorkManagerDictionaryImportScheduler(context)
        scheduler.download(dictionary)
        val id = works().single().id

        scheduler.cancel(id.toString())

        assertEquals(WorkInfo.State.CANCELLED, works().single().state)
        val status = scheduler.imports.first().single() as ImportStatus.Failed
        assertTrue(status.cancelled)
        assertEquals(dictionary.id, status.dictionaryId)

        scheduler.download(dictionary)
        val again = works().single()
        assertTrue(again.id != id && !again.state.isFinished)
    }
}
