package app.honmani.reader.readaloud.generate

import android.util.Log
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.readaloud.AlignedCue
import app.honmani.reader.readaloud.AudiobookAlignment
import app.honmani.reader.readaloud.AudiobookStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How far making an audiobook is: [done] of [total] sentences, [spentUsd] billed so far (every request sent counts). */
data class MakerProgress(val done: Int, val total: Int, val spentUsd: Double)

/** How a run of [AudiobookMaker] ended. Everything done so far is kept, whatever the result. */
sealed interface MakerResult {
    /** The audio is in [audio], paired with the book. */
    data class Finished(val audio: File, val sentences: Int, val spentUsd: Double) : MakerResult

    /** The next request would have crossed the cap. */
    data class CapReached(val spentUsd: Double, val capUsd: Double) : MakerResult

    /** Google is busy or not reachable; try again later ([retryAfterMs]). */
    data class Paused(val problem: VoiceProblem, val retryAfterMs: Long = AudiobookMaker.PAUSE_MS) : MakerResult

    /** Something needs the reader's attention (key, billing, the audio itself); trying again as it is won't help. */
    data class Stopped(val problem: VoiceProblem?, val reason: String) : MakerResult

    /** A readable sentence for the notification and the dialog. */
    val message: String
        get() = when (this) {
            is Finished -> "The audiobook is ready."
            is CapReached -> "Spending cap reached: ${usd(spentUsd)} of ${usd(capUsd)}. Raise it to continue."
            is Paused -> "Google is not answering right now. Honmani will try again later."
            is Stopped -> reason
        }
}

/** What is on disk for a book. */
sealed interface MakerStatus {
    data object None : MakerStatus

    /** Started but not finished; the progress as the journal has it. */
    data class Partial(val done: Int, val total: Int, val spentUsd: Double, val capUsd: Double) : MakerStatus

    data class Finished(val sizeBytes: Long) : MakerStatus
}

private sealed interface Outcome {
    class Done(val chunks: List<Mp3Chunk>) : Outcome

    class Halted(val result: MakerResult) : Outcome
}

/** Stops the whole run with [result]. */
private class Halt(val result: MakerResult) : RuntimeException(null, null, false, false)

private fun usd(value: Double) = "$" + String.format(Locale.ROOT, "%.2f", value)

/**
 * Makes an audiobook from a [FrozenPlan] with a [SentenceVoice]: one request per sentence, up to
 * [PARALLEL] at once but appended in order, each sentence's frames into `audio.mp3.part` and a
 * line in the journal. A run that stops (cancelled, killed, out of money, Google refusing) can be
 * run again and goes on after the last whole sentence; the finished file is the same as an
 * uninterrupted run's. When all is done the part is renamed to `audio.mp3`, the subtitles (the
 * book's own text, times from the frame counts) are written, and the book is paired with them
 * together with their alignment, so the audiobook loader never has to match them to the book.
 *
 * [root] holds one folder per book (`files/audiobook/generated`). [pause] and [dispatcher] can be
 * replaced by tests.
 */
class AudiobookMaker(
    private val root: File,
    private val store: AudiobookStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    fun files(bookHash: String): GeneratedFiles = GeneratedFiles(File(root, store.fileName(bookHash)))

    /** Fixes the plan on disk; false if the book has one already (that one stays: delete first to start over). */
    fun freeze(plan: FrozenPlan): Boolean {
        val files = files(plan.bookHash)
        if (files.plan.isFile) return false
        files.writePlan(plan)
        return true
    }

    /** What [status] needs of a plan; parsing a novel's plan.json takes long, and status is asked about every second. */
    private class PlanSummary(val length: Long, val modified: Long, val sentences: Int, val capUsd: Double)

    private val summaries = ConcurrentHashMap<String, PlanSummary>()

    /** The sentence count and cap of the plan, parsed again only when the plan file changed (null: no plan). */
    private fun summary(files: GeneratedFiles): PlanSummary? {
        val key = files.plan.path
        if (!files.plan.isFile) {
            summaries.remove(key)
            return null
        }
        val length = files.plan.length()
        val modified = files.plan.lastModified()
        summaries[key]?.takeIf { it.length == length && it.modified == modified }?.let { return it }
        val plan = files.readPlan() ?: return null
        return PlanSummary(length, modified, plan.sentences.size, plan.capUsd).also { summaries[key] = it }
    }

    fun status(bookHash: String): MakerStatus {
        val files = files(bookHash)
        val plan = summary(files)
        val pairing = store.pairing(bookHash)
        if (files.audio.isFile && pairing != null && pairing.audio == uri(files.audio)) return MakerStatus.Finished(files.audio.length())
        plan ?: return MakerStatus.None
        val done = if (files.audio.isFile) plan.sentences else files.wholeLines().coerceAtMost(plan.sentences)
        return MakerStatus.Partial(done, plan.sentences, files.billedMicros() / MICROS, plan.capUsd)
    }

    /** Deletes the partial or finished audio of the book, and its pairing if that points to it. */
    fun delete(bookHash: String) {
        val files = files(bookHash)
        val pairing = store.pairing(bookHash)
        if (pairing != null && pairing.audio == uri(files.audio)) store.forget(bookHash)
        files.directory.deleteRecursively()
    }

    /**
     * Runs the frozen plan of [bookHash] to its end or to a stop; [capUsd] raises the cap (a lower
     * value is ignored). Cancelling keeps all files. [progress] is called after each sentence.
     */
    suspend fun run(
        bookHash: String,
        voice: SentenceVoice,
        capUsd: Double? = null,
        progress: (MakerProgress) -> Unit = {},
    ): MakerResult = withContext(dispatcher) {
        val files = files(bookHash)
        var plan = files.readPlan() ?: return@withContext MakerResult.Stopped(null, "There is nothing to make: the plan is missing.")
        if (capUsd != null && capUsd > plan.capUsd) {
            plan = plan.copy(capUsd = capUsd)
            files.writePlan(plan)
        }
        if (plan.pricePerMillionUsd <= 0 || plan.sentences.isEmpty()) {
            return@withContext MakerResult.Stopped(null, "There is nothing to make: no price or no text.")
        }
        Run(files, plan, voice, progress).run()
    }

    private inner class Run(
        val files: GeneratedFiles,
        val plan: FrozenPlan,
        val voice: SentenceVoice,
        val progress: (MakerProgress) -> Unit,
    ) {
        private val lock = Any()
        private val capMicros = Math.round(plan.capUsd * MICROS)
        private var spent = files.billedMicros()
        private var capHit = false
        private var format: Mp3Format? = null
        private lateinit var billing: FileOutputStream

        suspend fun run(): MakerResult {
            val total = plan.sentences.size
            val recovered = files.recover(total)
            // The audio is renamed already if all sentences are done; only subtitles and pairing are missing then.
            if (files.audio.isFile && recovered.size != total) return MakerResult.Stopped(null, "The audio is there but its journal is damaged. Delete it and start over.")
            val entries = recovered.toMutableList()
            if (entries.isNotEmpty() && files.part.isFile) {
                format = firstBytes(files.part).let { Mp3Frames.parseHeader(it, 0)?.format }
            }
            progress(snapshot(entries.size))
            if (entries.size == total) return finish(entries)
            files.directory.mkdirs()
            val result = FileOutputStream(files.billing, true).use { billingOut ->
                billing = billingOut
                FileOutputStream(files.part, true).use { audioOut ->
                    FileOutputStream(files.journal, true).use { journalOut -> loop(entries, audioOut, journalOut) }
                }
            }
            return result ?: finish(entries)
        }

        /** Null when every sentence is done. */
        private suspend fun loop(entries: MutableList<JournalEntry>, audioOut: FileOutputStream, journalOut: FileOutputStream): MakerResult? = coroutineScope {
            val total = plan.sentences.size
            val pending = ArrayDeque<Pair<Int, Deferred<Outcome>>>()
            var next = entries.size
            var stop: MakerResult? = null
            while (stop == null && entries.size < total) {
                // UNDISPATCHED: a request is paid for (and the cap checked) in sentence order, before the next one starts.
                while (pending.size < PARALLEL && next < total) {
                    val index = next++
                    pending.addLast(index to async(start = CoroutineStart.UNDISPATCHED) { sentence(plan.sentences[index].spoken) })
                }
                val (index, deferred) = pending.removeFirst()
                when (val outcome = deferred.await()) {
                    is Outcome.Halted -> stop = outcome.result
                    is Outcome.Done -> {
                        var bytes = 0L
                        var frames = 0L
                        for (chunk in outcome.chunks) {
                            chunk.writeTo(audioOut)
                            bytes += chunk.sizeBytes
                            frames += chunk.frames
                        }
                        audioOut.flush()
                        val entry = JournalEntry(index, bytes, frames, plan.sentences[index].billedChars)
                        journalOut.write(GeneratedFiles.line(entry).toByteArray())
                        journalOut.flush()
                        entries += entry
                        progress(snapshot(entries.size))
                    }
                }
            }
            // What is still in flight was paid for already (it is in the billing file) but is not used.
            pending.forEach { it.second.cancel() }
            stop
        }

        private fun snapshot(done: Int) = MakerProgress(done, plan.sentences.size, synchronized(lock) { spent } / MICROS)

        private suspend fun sentence(text: String): Outcome = try {
            Outcome.Done(piece(text))
        } catch (h: Halt) {
            Outcome.Halted(h.result)
        }

        /** The audio of [text], sent as one request or, if the service finds it too long, in halves. Empty if it can't be said. */
        private suspend fun piece(text: String): List<Mp3Chunk> {
            var attempt = 0
            while (true) {
                attempt++
                charge(text)
                var problem: VoiceProblem
                var retryAfter: Long? = null
                try {
                    val bytes = voice.synthesize(text, plan.sampleRate)
                    return listOf(accept(bytes))
                } catch (e: VoiceException) {
                    problem = e.problem
                    retryAfter = e.retryAfterSeconds
                    when {
                        problem == VoiceProblem.QUOTA || problem == VoiceProblem.NETWORK -> Unit
                        problem == VoiceProblem.FAILED && e.status == 400 -> return tooLong(text)
                        problem == VoiceProblem.FAILED && (e.status == 0 || e.status >= 500) -> Unit
                        else -> throw Halt(MakerResult.Stopped(problem, stopReason(problem)))
                    }
                } catch (e: Mp3Exception) {
                    log("Unusable audio for a sentence: ${e.message}")
                    // The voice itself keeps failing: waiting and paying again won't help, so stop instead of pausing.
                    if (attempt >= MAX_TRIES) throw Halt(MakerResult.Stopped(VoiceProblem.FAILED, stopReason(VoiceProblem.FAILED)))
                    problem = VoiceProblem.FAILED
                }
                if (attempt >= MAX_TRIES) throw Halt(MakerResult.Paused(problem))
                val seconds = (retryAfter ?: (2L shl (attempt - 1))).coerceIn(MIN_BACKOFF_S, MAX_BACKOFF_S)
                pause(seconds * 1000)
            }
        }

        private suspend fun tooLong(text: String): List<Mp3Chunk> {
            val middle = text.length / 2
            val cut = text.indices.filter { text[it] == '、' && it < text.length - 1 }.minByOrNull { Math.abs(it - middle) }
            if (cut == null) {
                log("Skipped a sentence the voice finds too long (${text.length} characters)")
                return emptyList()
            }
            return piece(text.substring(0, cut + 1)) + piece(text.substring(cut + 1))
        }

        /** Pays for one request: it is on the billing file before it goes out; or stops the run at the cap. */
        private fun charge(text: String) {
            synchronized(lock) {
                val cost = Math.round(text.codePointCount(0, text.length) * plan.pricePerMillionUsd)
                if (capHit || spent + cost > capMicros) {
                    capHit = true
                    throw Halt(MakerResult.CapReached(spent / MICROS, plan.capUsd))
                }
                spent += cost
                billing.write("$cost\n".toByteArray())
                billing.flush()
            }
        }

        private fun accept(bytes: ByteArray): Mp3Chunk {
            val chunk = Mp3Frames.read(bytes)
            if (chunk.frames == 0 || chunk.truncatedBytes > 0) throw Mp3Exception("The audio is empty or cut off")
            if (chunk.format.sampleRate != plan.sampleRate) {
                throw Halt(MakerResult.Stopped(null, "The voice sent audio at ${chunk.format.sampleRate} Hz instead of ${plan.sampleRate} Hz."))
            }
            synchronized(lock) {
                val known = format
                if (known == null) format = chunk.format
                else if (known != chunk.format) throw Halt(MakerResult.Stopped(null, "The voice changed the audio format in the middle of the book."))
            }
            return chunk
        }

        /** The audio is complete: name it, write subtitles and alignment, pair the book. */
        private fun finish(entries: List<JournalEntry>): MakerResult {
            val frames = entries.sumOf { it.frames }
            if (frames == 0L) return MakerResult.Stopped(null, "No sentence could be turned into audio.")
            if (!files.audio.isFile) Files.move(files.part.toPath(), files.audio.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val head = firstBytes(files.audio)
            val audioFormat = Mp3Frames.parseHeader(head, 0)?.format ?: return MakerResult.Stopped(null, "The audio file can't be read.")
            val srt = StringBuilder()
            val cues = ArrayList<AlignedCue>()
            var before = 0L
            for (entry in entries) {
                val after = before + entry.frames
                if (entry.frames > 0) {
                    val sentence = plan.sentences[entry.index]
                    val start = Mp3Frames.durationMs(before, audioFormat)
                    val end = Mp3Frames.durationMs(after, audioFormat)
                    cues += AlignedCue(start, end, sentence.span)
                    srt.append(cues.size).append('\n').append(time(start)).append(" --> ").append(time(end)).append('\n')
                        .append(sentence.original.replace(LINE_BREAKS, "")).append("\n\n")
                }
                before = after
            }
            val pairing = store.pair(plan.bookHash, uri(files.audio), srt.toString().toByteArray(Charsets.UTF_8))
            store.saveAlignment(plan.bookHash, pairing.srtHash, AudiobookAlignment(cues))
            return MakerResult.Finished(files.audio, cues.size, files.billedMicros() / MICROS)
        }
    }

    companion object {
        private const val TAG = "Honmani.audiobookmaker"

        /** Requests at once. */
        const val PARALLEL = 2

        /** Tries for one request that Google answers with too many requests (or not at all). */
        const val MAX_TRIES = 6
        private const val MIN_BACKOFF_S = 2L
        private const val MAX_BACKOFF_S = 60L

        /** How long a paused job waits before it tries again. */
        const val PAUSE_MS = 15 * 60 * 1000L

        private const val MICROS = 1_000_000.0
        private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")

        /** The file as the audiobook pairing keeps it. */
        fun uri(file: File): String = file.toURI().toString().replaceFirst("file:/", "file:///").replaceFirst("file://///", "file:///")

        /** The first four bytes of [file] (fewer if it is shorter). */
        private fun firstBytes(file: File): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(4)
            var n = 0
            while (n < 4) {
                val read = input.read(buffer, n, 4 - n)
                if (read < 0) break
                n += read
            }
            buffer.copyOf(n)
        }

        private fun time(ms: Long): String =
            String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

        private fun stopReason(problem: VoiceProblem): String = when (problem) {
            VoiceProblem.KEY_REJECTED, VoiceProblem.NO_KEY -> "Google did not accept the API key. Check it in Settings and that the Cloud Text-to-Speech API is enabled for it."
            VoiceProblem.BILLING_DISABLED -> "Billing is not switched on for the Google Cloud project of your key. Link a billing account to it in the Cloud console."
            VoiceProblem.SERVICE_DISABLED -> "The Cloud Text-to-Speech API is not switched on for the project of your key. Enable it in the Cloud console."
            else -> "The voice could not make the audio."
        }
    }
}
