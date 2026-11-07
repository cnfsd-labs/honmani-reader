package app.honmani.reader.readaloud.generate

import app.honmani.reader.readaloud.CueSpan
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What is fixed when an audiobook is started (`plan.json`): what is said, in which voice, at which
 * price, and the reader's choices. Changing the text later means deleting and starting over.
 * [pricePerMillionUsd] is USD per million characters; [capUsd] is the only part that can be raised.
 */
data class FrozenPlan(
    val bookHash: String,
    val voice: String,
    val pricePerMillionUsd: Double,
    val capUsd: Double,
    /** The reader's choice to also work on mobile data; kept for the worker. */
    val mobileData: Boolean,
    val sampleRate: Int,
    val sentences: List<PlannedSentence>,
) {
    fun toJson(): String = buildJsonObject {
        put("version", 1)
        put("bookHash", bookHash)
        put("voice", voice)
        put("pricePerMillionUsd", pricePerMillionUsd)
        put("capUsd", capUsd)
        put("mobileData", mobileData)
        put("sampleRate", sampleRate)
        put("sentences", buildJsonArray {
            for (s in sentences) {
                add(buildJsonObject {
                    put("spoken", s.spoken)
                    put("original", s.original)
                    put("start", s.span.start)
                    put("end", s.span.end)
                })
            }
        })
    }.toString()

    companion object {
        /** The plan in [text]; null if it is not one of ours or damaged. */
        fun parse(text: String): FrozenPlan? = try {
            val o = Json.parseToJsonElement(text).jsonObject
            if (o["version"]?.jsonPrimitive?.int != 1) null else FrozenPlan(
                bookHash = o.string("bookHash"),
                voice = o.string("voice"),
                pricePerMillionUsd = o.double("pricePerMillionUsd"),
                capUsd = o.double("capUsd"),
                mobileData = o["mobileData"]!!.jsonPrimitive.content.toBooleanStrict(),
                sampleRate = o["sampleRate"]!!.jsonPrimitive.int,
                sentences = o["sentences"]!!.jsonArray.map {
                    val s = it.jsonObject
                    PlannedSentence(s.string("spoken"), s.string("original"), CueSpan(s["start"]!!.jsonPrimitive.int, s["end"]!!.jsonPrimitive.int))
                },
            )
        } catch (_: Exception) {
            // Missing field, wrong type or not JSON: not a plan.
            null
        }

        private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content
        private fun JsonObject.double(key: String): Double = (getValue(key) as JsonPrimitive).content.toDouble()
    }
}

/** One finished sentence in the journal: its audio is [bytes] long, [frames] MP3 frames, and cost [chars] characters. */
data class JournalEntry(val index: Int, val bytes: Long, val frames: Long, val chars: Int)

/**
 * The files of one generated audiobook in [directory]: `plan.json`, `audio.mp3.part` and
 * `journal.tsv` while it is made (the journal has a line per sentence, flushed after its audio,
 * so a stop at any moment leaves a clean prefix), `billing.tsv` (a line for every request sent,
 * written before it goes out), `outcome.txt` (why the last run stopped, if it needs the reader), and
 * `audio.mp3` when it is done.
 */
class GeneratedFiles(val directory: File) {
    val plan = File(directory, "plan.json")
    val part = File(directory, "audio.mp3.part")
    val audio = File(directory, "audio.mp3")
    val journal = File(directory, "journal.tsv")
    val billing = File(directory, "billing.tsv")
    val outcome = File(directory, "outcome.txt")

    fun readPlan(): FrozenPlan? = plan.takeIf { it.isFile }?.let { FrozenPlan.parse(it.readText()) }

    fun writePlan(value: FrozenPlan) {
        directory.mkdirs()
        val temporary = File(directory, "plan.json.tmp")
        temporary.writeText(value.toJson())
        Files.move(temporary.toPath(), plan.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Why the last run did not finish, kept for the dialog; null if there is nothing to tell. */
    fun readOutcome(): MakerOutcome? = outcome.takeIf { it.isFile }?.let { MakerOutcome.parse(it.readText()) }

    fun writeOutcome(value: MakerOutcome) {
        directory.mkdirs()
        outcome.writeText(value.toText())
    }

    fun clearOutcome() {
        outcome.delete()
    }

    /** The micro-dollars billed so far: the sum of the lines of `billing.tsv` (a half-written last line counts as nothing). */
    fun billedMicros(): Long {
        if (!billing.isFile) return 0
        val text = billing.readText()
        return text.substring(0, text.lastIndexOf('\n') + 1).lineSequence().sumOf { it.toLongOrNull() ?: 0L }
    }

    /** The lines in the journal, without changing anything (for showing progress while a run may be going). */
    fun wholeLines(): Int {
        if (!journal.isFile) return 0
        val text = journal.readText()
        return text.substring(0, text.lastIndexOf('\n') + 1).count { it == '\n' }
    }

    /**
     * The sentences that are done, after a stop at any moment: lines of the journal that are
     * whole, in order from 0, and covered by the audio file. The audio file is cut back to their
     * size and the journal rewritten without anything beyond them.
     */
    fun recover(total: Int): List<JournalEntry> {
        val entries = ArrayList<JournalEntry>()
        if (journal.isFile) {
            val text = journal.readText()
            // What follows the last line feed is an unfinished line.
            for (line in text.substring(0, text.lastIndexOf('\n') + 1).lineSequence()) {
                if (line.isEmpty()) continue
                val f = line.split('\t')
                val entry = if (f.size == 4) {
                    val index = f[0].toIntOrNull()
                    val bytes = f[1].toLongOrNull()
                    val frames = f[2].toLongOrNull()
                    val chars = f[3].toIntOrNull()
                    if (index != null && bytes != null && frames != null && chars != null && bytes >= 0 && frames >= 0) JournalEntry(index, bytes, frames, chars) else null
                } else {
                    null
                }
                if (entry == null || entry.index != entries.size || entries.size >= total) break
                entries += entry
            }
        }
        val available = (if (part.isFile) part else audio).takeIf { it.isFile }?.length() ?: 0L
        var sum = entries.sumOf { it.bytes }
        while (sum > available && entries.isNotEmpty()) sum -= entries.removeAt(entries.lastIndex).bytes
        if (part.isFile && part.length() != sum) RandomAccessFile(part, "rw").use { it.setLength(sum) }
        val clean = entries.joinToString("") { line(it) }
        if (!journal.isFile && clean.isEmpty()) return entries
        if (!journal.isFile || journal.readText() != clean) journal.writeText(clean)
        return entries
    }

    companion object {
        fun line(entry: JournalEntry): String = "${entry.index}\t${entry.bytes}\t${entry.frames}\t${entry.chars}\n"
    }
}
