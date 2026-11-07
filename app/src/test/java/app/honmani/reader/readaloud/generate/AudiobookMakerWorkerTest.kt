package app.honmani.reader.readaloud.generate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.readaloud.AudiobookStore
import app.honmani.reader.readaloud.CueSpan
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookMakerWorkerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = CopyOnWriteArrayList<String>()

    /** What the fake voice does for the [call]th request of the test (from 0): its audio, or a failure. */
    private var behave: suspend (call: Int, text: String) -> ByteArray = { _, text -> mp3(text) }

    private val voice = object : SentenceVoice {
        override suspend fun synthesize(text: String, sampleRateHertz: Int): ByteArray {
            val call = calls.size
            calls += text
            return behave(call, text)
        }
    }

    private fun frame(info: Boolean = false): ByteArray {
        val bytes = ByteArray(96) { 0x55 }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xF3.toByte()
        bytes[2] = 0x44
        bytes[3] = 0xC0.toByte()
        if (info) "Info".forEachIndexed { i, c -> bytes[13 + i] = c.code.toByte() }
        return bytes
    }

    private fun mp3(text: String): ByteArray {
        var out = frame(info = true)
        repeat(text.length) { i -> out += frame().also { f -> for (k in 4 until f.size) f[k] = (text.hashCode() + i).toByte() } }
        return out
    }

    private fun sentences(count: Int = 12) = List(count) { i ->
        PlannedSentence("文" + "あ".repeat(i % 3 + 5) + "い" + (i % 10), "原文$i", CueSpan(i * 10, i * 10 + 9))
    }

    private fun plan(sentences: List<PlannedSentence> = sentences(), cap: Double = 1.0) =
        FrozenPlan("book", "ja-JP-Chirp3-HD-Aoede", 30.0, cap, false, 24_000, sentences)

    private fun maker(name: String) = AudiobookMaker(
        File(folder.root, "generated-$name"),
        AudiobookStore(context.getSharedPreferences("worker-test-$name", Context.MODE_PRIVATE), File(folder.root, "audiobook-$name")),
        Dispatchers.IO,
        { },
        { },
    )

    private fun useMaker(maker: AudiobookMaker, voice: SentenceVoice? = this.voice) {
        AudiobookMakerWorker.environment = { AudiobookEnvironment(maker, { voice }, { "Test book" }) }
    }

    private fun worker(cap: Double = -1.0, foregroundRefused: Boolean = false) =
        TestListenableWorkerBuilder<AudiobookMakerWorker>(context)
            .setInputData(AudiobookMakerWorker.inputData("book", "ja-JP-Chirp3-HD-Aoede", cap))
            .also { builder ->
                if (foregroundRefused) builder.setForegroundUpdater { _, _, _ -> throw IllegalStateException("not allowed") }
            }
            .build()

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun resetEnvironment() {
        AudiobookMakerWorker.environment = null
    }

    @Test
    fun theWorkerMakesTheAudiobookAndLeavesNoStopMessage() {
        val maker = maker("run")
        maker.freeze(plan())
        useMaker(maker)
        assertEquals(Result.success(), runBlocking { worker().doWork() })
        assertTrue(maker.status("book") is MakerStatus.Finished)
        assertNull(maker.files("book").readOutcome())
        assertEquals(12, calls.size)
    }

    @Test
    fun theCapEndsTheJobWithItsMessageKeptForTheDialog() {
        val maker = maker("cap")
        maker.freeze(plan(cap = 0.0005))
        useMaker(maker)
        val result = runBlocking { worker().doWork() }
        assertTrue(result is Result.Failure)
        val outcome = maker.files("book").readOutcome()!!
        assertEquals(MakerOutcome.Kind.CAP, outcome.kind)
        assertTrue(outcome.message, outcome.message.startsWith("Spending cap reached: $"))
        assertEquals(AudiobookJobState.Stopped(outcome.message, capReached = true), WorkManagerAudiobookScheduler.jobState(null, outcome))

        // Raising the cap goes on; the old message is gone.
        useMaker(maker)
        assertEquals(Result.success(), runBlocking { worker(cap = 1.0).doWork() })
        assertNull(maker.files("book").readOutcome())
        assertTrue(maker.status("book") is MakerStatus.Finished)
    }

    @Test
    fun aBusyServicePausesTheJobAndWorkManagerRetries() {
        val maker = maker("busy")
        maker.freeze(plan(sentences(3)))
        useMaker(maker)
        behave = { _, _ -> throw VoiceException(VoiceProblem.QUOTA, status = 429) }
        assertEquals(Result.retry(), runBlocking { worker().doWork() })
        val outcome = maker.files("book").readOutcome()!!
        assertEquals(MakerOutcome.Kind.PAUSED, outcome.kind)

        behave = { _, text -> mp3(text) }
        assertEquals(Result.success(), runBlocking { worker().doWork() })
        assertNull(maker.files("book").readOutcome())
    }

    @Test
    fun aRejectedKeyStopsTheJobForGood() {
        val maker = maker("key")
        maker.freeze(plan(sentences(3)))
        useMaker(maker)
        behave = { _, _ -> throw VoiceException(VoiceProblem.KEY_REJECTED, status = 403) }
        assertTrue(runBlocking { worker().doWork() } is Result.Failure)
        assertEquals(MakerOutcome.Kind.STOPPED, maker.files("book").readOutcome()!!.kind)
    }

    @Test
    fun noKeyStopsTheJobWithoutSendingAnything() {
        val maker = maker("nokey")
        maker.freeze(plan(sentences(3)))
        useMaker(maker, voice = null)
        assertTrue(runBlocking { worker().doWork() } is Result.Failure)
        assertEquals(MakerOutcome.Kind.STOPPED, maker.files("book").readOutcome()!!.kind)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aForegroundServiceThatCannotStartPausesTheJobBeforeAnythingIsSent() {
        val maker = maker("foreground")
        maker.freeze(plan(sentences(3)))
        useMaker(maker)
        val result = runBlocking { worker(foregroundRefused = true).doWork() }
        assertEquals(Result.retry(), result)
        val outcome = maker.files("book").readOutcome()!!
        assertEquals(MakerOutcome.Kind.PAUSED, outcome.kind)
        assertEquals("Paused. Open Honmani to continue.", outcome.message)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aCancelledJobKeepsItsFilesAndContinuesWithTheSameResultAsOneThatWasNot() {
        val whole = maker("whole")
        whole.freeze(plan())
        useMaker(whole)
        assertEquals(Result.success(), runBlocking { worker().doWork() })
        val wholeBytes = whole.files("book").audio.readBytes()
        calls.clear()

        val maker = maker("cancelled")
        maker.freeze(plan())
        useMaker(maker)
        val reached = CompletableDeferred<Unit>()
        behave = { call, text ->
            if (call == 4) {
                reached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            mp3(text)
        }
        runBlocking {
            val job = async(Dispatchers.IO) { worker().doWork() }
            withTimeout(30_000) { reached.await() }
            job.cancel()
            job.join()
        }
        val files = maker.files("book")
        assertFalse(files.audio.isFile)
        assertTrue(files.part.isFile)
        assertNull("a cancelled job leaves no stop message", files.readOutcome())
        val done = (maker.status("book") as MakerStatus.Partial).done
        assertTrue("sentences before the stuck one are kept: $done", done in 3..5)

        behave = { _, text -> mp3(text) }
        calls.clear()
        assertEquals(Result.success(), runBlocking { worker().doWork() })
        assertArrayEquals(wholeBytes, files.audio.readBytes())
        assertTrue("finished sentences are not sent again", calls.size <= 12 - done)
    }

    @Test
    fun theSchedulerWaitsForWifiUnlessMobileDataIsAllowed() {
        val maker = maker("scheduler")
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerAudiobookScheduler(context, maker, workManager)
        val name = WorkManagerAudiobookScheduler.workName("book")
        fun live() = workManager.getWorkInfosForUniqueWork(name).get().single { it.state != WorkInfo.State.CANCELLED }

        scheduler.start("book", "ja-JP-Chirp3-HD-Aoede", 2.0, allowMobileData = false)
        assertEquals(NetworkType.UNMETERED, live().constraints.requiredNetworkType)
        assertEquals(AudiobookJobState.Waiting, runBlocking { scheduler.state("book").first() })

        scheduler.start("book", "ja-JP-Chirp3-HD-Aoede", 2.0, allowMobileData = true)
        assertEquals(NetworkType.CONNECTED, live().constraints.requiredNetworkType)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(name).get().count { it.state == WorkInfo.State.ENQUEUED })

        scheduler.cancel("book")
        assertEquals(AudiobookJobState.Idle, runBlocking { scheduler.state("book").first() })
    }

    @Test
    fun theSchedulerShowsWhatTheLastRunLeftForTheDialog() {
        val maker = maker("states")
        val scheduler = WorkManagerAudiobookScheduler(context, maker, WorkManager.getInstance(context))
        assertEquals(AudiobookJobState.Idle, runBlocking { scheduler.state("book").first() })
        maker.files("book").writeOutcome(MakerOutcome(MakerOutcome.Kind.STOPPED, "Billing is off."))
        assertEquals(AudiobookJobState.Stopped("Billing is off.", capReached = false), runBlocking { scheduler.state("book").first() })
        // Starting again clears the old message and queues the work.
        scheduler.start("book", "ja-JP-Chirp3-HD-Aoede", 1.0, allowMobileData = false)
        assertNull(maker.files("book").readOutcome())
        assertEquals(AudiobookJobState.Waiting, runBlocking { scheduler.state("book").first() })
        maker.files("book").writeOutcome(MakerOutcome(MakerOutcome.Kind.PAUSED, "Google is busy."))
        assertEquals(AudiobookJobState.Paused("Google is busy."), runBlocking { scheduler.state("book").first() })
    }
}
