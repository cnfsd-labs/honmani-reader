package app.honmani.reader.readaloud.generate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.opds.HeaderServer
import app.honmani.reader.data.opds.respond
import app.honmani.reader.readaloud.AudiobookLoad
import app.honmani.reader.readaloud.AudiobookLoader
import app.honmani.reader.readaloud.AudiobookStore
import app.honmani.reader.readaloud.CueSpan
import java.io.File
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookMakerTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** What the fake service does with a request for [text], the [call]th of the test (from 0). */
    private var behave: (call: Int, text: String) -> String? = { _, _ -> null }
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val pauses = Collections.synchronizedList(mutableListOf<Long>())

    private val server: HeaderServer = HeaderServer { path, out ->
        val request = server.requests.last()
        val text = Json.parseToJsonElement(request.body).jsonObject["input"]!!.jsonObject["text"]!!.jsonPrimitive.content
        val call = calls.size
        calls += text
        val answer = behave(call, text)
        when {
            answer == null -> out.respond("200 OK", """{"audioContent":"${Base64.getEncoder().encodeToString(mp3(text))}"}""", "Content-Type: application/json")
            else -> {
                val (status, body, retryAfter) = answer.split("|").let { Triple(it[0], it[1], it.getOrNull(2)) }
                out.respond(status, body, "Content-Type: application/json", *listOfNotNull(retryAfter?.let { "Retry-After: $it" }).toTypedArray())
            }
        }
    }

    @After
    fun stopServer() = server.stop()

    private fun frame(info: Boolean = false): ByteArray {
        val bytes = ByteArray(96) { 0x55 }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xF3.toByte()
        bytes[2] = 0x44
        bytes[3] = 0xC0.toByte()
        if (info) "Info".forEachIndexed { i, c -> bytes[13 + i] = c.code.toByte() }
        return bytes
    }

    /** What the fake service sends for [text]: an Info frame and a frame per character, a distinct fill per text. */
    private fun mp3(text: String): ByteArray {
        var out = frame(info = true)
        repeat(text.length) { i -> out += frame().also { f -> for (k in 4 until f.size) f[k] = (text.hashCode() + i).toByte() } }
        return out
    }

    private fun sentences(count: Int = 12) = List(count) { i ->
        val text = "文" + "あ".repeat(i % 3 + 5) + "い" + (i % 10)
        PlannedSentence(text, "原文$i", CueSpan(i * 10, i * 10 + 9))
    }

    private fun plan(hash: String = "book", sentences: List<PlannedSentence> = sentences(), cap: Double = 1.0, price: Double = 30.0) =
        FrozenPlan(hash, "ja-JP-Chirp3-HD-Aoede", price, cap, false, 24_000, sentences)

    private val voice get() = GoogleSentenceVoice("key", "ja-JP-Chirp3-HD-Aoede", "http://127.0.0.1:${server.port}")

    private fun newStore(name: String) = AudiobookStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("maker-test-$name", Context.MODE_PRIVATE),
        File(folder.root, "audiobook-$name"),
    )

    private fun maker(name: String, store: AudiobookStore = newStore(name)) =
        AudiobookMaker(File(folder.root, "generated-$name"), store, Dispatchers.IO, { pauses += it }, {})

    private fun finished(result: MakerResult): MakerResult.Finished = result as MakerResult.Finished

    @Test
    fun aRunThatWasStoppedAndResumedMakesTheSameFileAsOneThatWasNot() {
        val whole = maker("whole")
        whole.freeze(plan())
        val first = finished(runBlocking { whole.run("book", voice) })
        val wholeBytes = first.audio.readBytes()
        assertEquals(12, first.sentences)

        val store = newStore("killed")
        val killed = maker("killed", store)
        killed.freeze(plan())
        runBlocking {
            lateinit var job: Job
            job = launch { killed.run("book", voice) { if (it.done == 5) job.cancel() } }
            job.join()
        }
        val files = killed.files("book")
        assertFalse(files.audio.isFile)
        assertTrue(files.part.isFile)
        val state = killed.status("book") as MakerStatus.Partial
        assertTrue("stopped after the fifth sentence, at most two more in flight", state.done in 5..7)
        // A hard kill also leaves audio beyond the journal and a half line.
        files.part.appendBytes(ByteArray(50) { 7 })
        files.journal.appendText("${state.done}\t96")

        val calls = calls.size
        val result = finished(runBlocking { killed.run("book", voice) })
        assertArrayEquals(wholeBytes, result.audio.readBytes())
        assertTrue("sentences already done are not sent again", this.calls.size - calls <= 12 - state.done)
        assertEquals(whole.status("book").let { (it as MakerStatus.Finished).sizeBytes }, (killed.status("book") as MakerStatus.Finished).sizeBytes)
        assertEquals(newStore("whole").subtitles("book"), store.subtitles("book"))
        assertFalse(files.part.exists())
    }

    @Test
    fun theFinishedBookIsPairedAndLoadsWithoutMatching() {
        val store = newStore("loader")
        val maker = maker("loader", store)
        maker.freeze(plan(sentences = sentences(3)))
        val result = finished(runBlocking { maker.run("book", voice) })
        val ready = runBlocking { AudiobookLoader(store, Dispatchers.Unconfined).load("book", true) { error("the matching must not run") } } as AudiobookLoad.Ready
        assertEquals(AudiobookMaker.uri(result.audio), ready.audio)
        assertTrue(ready.audio.startsWith("file:///"))
        assertEquals(3, ready.alignment.total)
        assertEquals(3, ready.alignment.matched)
        val frames = sentences(3).map { it.spoken.length.toLong() }
        // 24 ms a frame, exact from the frame counts.
        assertEquals(listOf(0L, frames[0] * 24, (frames[0] + frames[1]) * 24), ready.alignment.cues.map { it.startMs })
        assertEquals((frames.sum()) * 24, ready.alignment.cues.last().endMs)
        assertEquals(CueSpan(10, 19), ready.alignment.cues[1].span)
        assertTrue(store.subtitles("book")!!.contains("原文2"))
        assertEquals(frames.sum() * 96, result.audio.length())
    }

    @Test
    fun theCapStopsBeforeTheRequestThatWouldCrossIt() {
        val maker = maker("cap")
        val all = sentences()
        // 30 USD per million characters: 30 micro-dollars a character.
        val chars = all.map { it.billedChars }
        val cap = (chars[0] + chars[1] + chars[2] + 1) * 30 / 1_000_000.0
        maker.freeze(plan(cap = cap))
        val result = runBlocking { maker.run("book", voice) }
        assertTrue(result is MakerResult.CapReached)
        result as MakerResult.CapReached
        assertEquals((chars[0] + chars[1] + chars[2]) * 30 / 1_000_000.0, result.spentUsd, 1e-9)
        assertEquals(cap, result.capUsd, 1e-9)
        assertEquals(3, calls.size)
        assertEquals(3, (maker.status("book") as MakerStatus.Partial).done)
        assertTrue(result.message, result.message.startsWith("Spending cap reached: $") && result.message.endsWith("Raise it to continue."))

        // Raising the cap goes on where it stopped.
        val after = runBlocking { maker.run("book", voice, capUsd = 1.0) }
        assertTrue(after is MakerResult.Finished)
        assertEquals(12, calls.size)
    }

    @Test
    fun everyRequestSentCountsAsBilledAlsoAfterARestart() {
        val maker = maker("billed")
        val all = sentences(4)
        val first = all[0].spoken
        var failed = false
        behave = { _, text ->
            if (text == first && !failed) {
                failed = true
                "500 Internal Server Error|{}"
            } else {
                null
            }
        }
        maker.freeze(plan(sentences = all))
        val result = finished(runBlocking { maker.run("book", voice) })
        val chars = all.sumOf { it.billedChars } + all[0].billedChars
        assertEquals(chars * 30 / 1_000_000.0, result.spentUsd, 1e-9)
        assertEquals(5, calls.size)
        assertEquals(2, calls.count { it == first })
    }

    @Test
    fun tooManyRequestsWaitAndAreThenAnswered() {
        val maker = maker("quota")
        val all = sentences(3)
        val firstText = all[0].spoken
        var failures = 0
        behave = { _, text ->
            if (text == firstText && failures < 2) {
                failures++
                if (failures == 1) "429 Too Many Requests|{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}|7" else "429 Too Many Requests|{}"
            } else {
                null
            }
        }
        maker.freeze(plan(sentences = all))
        val result = runBlocking { maker.run("book", voice) }
        assertTrue(result is MakerResult.Finished)
        // Retry-After is honoured, then the backoff goes on (2 s, 4 s, ...).
        assertEquals(listOf(7_000L, 4_000L), pauses.toList())
    }

    @Test
    fun aServiceThatKeepsSayingTooManyRequestsPausesTheJobWithProgressKept() {
        val maker = maker("pause")
        val all = sentences(3)
        val secondText = all[1].spoken
        behave = { _, text -> if (text == secondText) "429 Too Many Requests|{}" else null }
        maker.freeze(plan(sentences = all))
        val result = runBlocking { maker.run("book", voice) }
        assertEquals(MakerResult.Paused(VoiceProblem.QUOTA), result)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L), pauses.toList())
        assertEquals(1, (maker.status("book") as MakerStatus.Partial).done)
        assertEquals(15 * 60 * 1000L, (result as MakerResult.Paused).retryAfterMs)
    }

    @Test
    fun aVoiceThatKeepsSendingUnusableAudioStopsTheJobInsteadOfPausingIt() {
        val maker = maker("unusable")
        val all = sentences(3)
        val secondText = all[1].spoken
        // A 200 answer with audio that is not MP3 at all.
        behave = { _, text -> if (text == secondText) "200 OK|{\"audioContent\":\"${Base64.getEncoder().encodeToString(ByteArray(10))}\"}" else null }
        maker.freeze(plan(sentences = all))
        val result = runBlocking { maker.run("book", voice) }
        assertTrue(result.toString(), result is MakerResult.Stopped)
        result as MakerResult.Stopped
        assertEquals(VoiceProblem.FAILED, result.problem)
        assertEquals("The voice could not make the audio.", result.message)
        assertEquals(calls.count { it == secondText }, AudiobookMaker.MAX_TRIES)
        assertEquals(1, (maker.status("book") as MakerStatus.Partial).done)
    }

    @Test
    fun billingNotSwitchedOnStopsTheJobButKeepsWhatIsDone() {
        val maker = maker("billing")
        val all = sentences()
        val third = all[2].spoken
        behave = { _, text ->
            if (text == third) "403 Forbidden|{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\",\"details\":[{\"reason\":\"BILLING_DISABLED\"}]}}" else null
        }
        maker.freeze(plan(sentences = all))
        val result = runBlocking { maker.run("book", voice) }
        assertTrue(result is MakerResult.Stopped)
        assertEquals(VoiceProblem.BILLING_DISABLED, (result as MakerResult.Stopped).problem)
        assertTrue(result.message.contains("billing account"))
        assertEquals(2, (maker.status("book") as MakerStatus.Partial).done)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun aRejectedKeyStopsTheJob() {
        val maker = maker("key")
        behave = { _, _ -> "400 Bad Request|{\"error\":{\"message\":\"API key not valid\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}" }
        maker.freeze(plan())
        val result = runBlocking { maker.run("book", voice) }
        assertEquals(VoiceProblem.KEY_REJECTED, (result as MakerResult.Stopped).problem)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun aSentenceThatIsTooLongIsSplitAtACommaOrSkipped() {
        val maker = maker("long")
        val long = "あいうえお、かきくけこ、さしすせそ"
        val noComma = "あいうえおかきくけこさしすせそ"
        val all = listOf(
            PlannedSentence("はい", "はい", CueSpan(0, 2)),
            PlannedSentence(long, "長い", CueSpan(2, 20)),
            PlannedSentence(noComma, "読点なし", CueSpan(20, 40)),
            PlannedSentence("いいえ", "いいえ", CueSpan(40, 43)),
        )
        behave = { _, text -> if (text.length > 8) "400 Bad Request|{\"error\":{\"message\":\"Sentences that are too long\"}}" else null }
        maker.freeze(plan(sentences = all))
        val result = finished(runBlocking { maker.run("book", voice) })
        // The cue of the skipped sentence is left out; the others follow each other without a gap.
        assertEquals(3, result.sentences)
        // A frame a character: 2 + (6 + 6 + 5) + 3.
        assertEquals(22L, result.audio.length() / 96)
        assertTrue(calls.containsAll(listOf("あいうえお、", "かきくけこ、", "さしすせそ")))
    }

    @Test
    fun theRequestAsksForMp3At24KilohertzWithTheKeyInAHeader() {
        val maker = maker("request")
        maker.freeze(plan(sentences = sentences(1)))
        runBlocking { maker.run("book", voice) }
        val request = server.requests.single()
        assertEquals("/v1/text:synthesize", request.path)
        assertEquals("key", request.headers["x-goog-api-key"])
        val config = Json.parseToJsonElement(request.body).jsonObject["audioConfig"]!!.jsonObject
        assertEquals("MP3", config["audioEncoding"]!!.jsonPrimitive.content)
        assertEquals("24000", config["sampleRateHertz"]!!.jsonPrimitive.content)
    }

    @Test
    fun audioAtAnotherRateFailsTheJob() {
        val maker = maker("rate")
        maker.freeze(plan(sentences = sentences(2)).copy(sampleRate = 22_050))
        behave = { _, _ -> null }
        val result = runBlocking { maker.run("book", voice) }
        assertTrue(result is MakerResult.Stopped)
        assertTrue((result as MakerResult.Stopped).reason.contains("24000 Hz"))
    }

    @Test
    fun deletingRemovesTheAudioAndThePairing() {
        val store = newStore("delete")
        val maker = maker("delete", store)
        maker.freeze(plan(sentences = sentences(2)))
        runBlocking { maker.run("book", voice) }
        assertTrue(maker.status("book") is MakerStatus.Finished)
        maker.delete("book")
        assertEquals(MakerStatus.None, maker.status("book"))
        assertEquals(null, store.pairing("book"))
        assertFalse(maker.files("book").directory.exists())
    }

    @Test
    fun aRunCancelledKeepsItsFilesAndTheStoredPlanIsNotReplaced() {
        val maker = maker("keep")
        assertTrue(maker.freeze(plan()))
        assertFalse(maker.freeze(plan(cap = 5.0)))
        assertEquals(1.0, maker.files("book").readPlan()!!.capUsd, 0.0)
        val thrown = try {
            runBlocking {
                lateinit var job: Job
                job = launch { maker.run("book", voice) { if (it.done == 2) throw CancellationException("stop") } }
                job.join()
                job.isCancelled
            }
        } catch (e: CancellationException) {
            true
        }
        assertTrue(thrown)
        assertTrue((maker.status("book") as MakerStatus.Partial).done >= 2)
    }
}
