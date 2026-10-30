package app.honmani.reader.data.words

import app.honmani.reader.data.readStored
import app.honmani.reader.data.writeTextAtomically
import app.honmani.reader.data.difficulty.BookWords
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The parts of a book really read: pages on screen long enough (`ReadingTracker`), in
 * [BookWords.POSITIONS] steps, as ascending pairs `from, to` (both included) that neither overlap
 * nor touch. Pages flicked past, jumps through the table of contents and positions from progress
 * sync add nothing.
 */
object ReadStretches {
    val NONE = IntArray(0)

    /** [stretches] with [from]..[to] (0 to 1) added. */
    fun add(stretches: IntArray, from: Float, to: Float): IntArray = addSteps(stretches, step(minOf(from, to)), step(maxOf(from, to)))

    /** [stretches] with the steps [start]..[end] added. */
    fun addSteps(stretches: IntArray, start: Int, end: Int): IntArray {
        val merged = ArrayList<Int>(stretches.size + 2)
        var newStart = minOf(start, end).coerceIn(0, BookWords.POSITIONS)
        var newEnd = maxOf(start, end).coerceIn(0, BookWords.POSITIONS)
        var placed = false
        for (i in stretches.indices step 2) {
            val a = stretches[i]
            val b = stretches[i + 1]
            when {
                b < newStart - 1 -> merged += listOf(a, b)
                a > newEnd + 1 -> {
                    if (!placed) {
                        merged += listOf(newStart, newEnd)
                        placed = true
                    }
                    merged += listOf(a, b)
                }
                else -> {
                    newStart = minOf(newStart, a)
                    newEnd = maxOf(newEnd, b)
                }
            }
        }
        if (!placed) merged += listOf(newStart, newEnd)
        return merged.toIntArray()
    }

    /** The share of the book read, 0 to 1. */
    fun share(stretches: IntArray): Float {
        var steps = 0
        for (i in stretches.indices step 2) steps += stretches[i + 1] - stretches[i]
        return (steps.toFloat() / BookWords.POSITIONS).coerceIn(0f, 1f)
    }

    private fun step(share: Float): Int = (share.coerceIn(0f, 1f) * BookWords.POSITIONS).roundToInt()
}

/** Keeps the [ReadStretches] of each book by content hash, in app storage. */
class ReadStretchStore(private val file: File) {
    private val lock = Any()
    private var loaded: Map<String, IntArray>? = null
    private val changes = MutableStateFlow(0)

    /** Every book's stretches, again after each change. */
    val stretches: Flow<Map<String, IntArray>> = changes.map { synchronized(lock) { current() } }

    fun add(contentHash: String, from: Float, to: Float) {
        synchronized(lock) {
            val updated = current().toMutableMap()
            updated[contentHash] = ReadStretches.add(updated[contentHash] ?: ReadStretches.NONE, from, to)
            loaded = updated
            save(updated)
        }
        changes.update { it + 1 }
    }

    /** Every book's stretches now, by content hash. */
    fun all(): Map<String, IntArray> = synchronized(lock) { current() }

    /** Adds [other] books' stretches (from a backup) to what was read here. */
    fun merge(other: Map<String, IntArray>) {
        if (other.isEmpty()) return
        synchronized(lock) {
            val updated = current().toMutableMap()
            for ((hash, steps) in other) {
                var merged = updated[hash] ?: ReadStretches.NONE
                for (i in 0 until steps.size - 1 step 2) merged = ReadStretches.addSteps(merged, steps[i], steps[i + 1])
                updated[hash] = merged
            }
            loaded = updated
            save(updated)
        }
        changes.update { it + 1 }
    }

    /** Under [lock]. */
    private fun current(): Map<String, IntArray> = loaded ?: load().also { loaded = it }

    /** One line per book: its content hash, then its stretches' steps, separated by spaces. */
    private fun load(): Map<String, IntArray> = readStored(file, emptyMap()) {
        it.readLines().mapNotNull { line ->
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            val steps = parts.drop(1).map { it.toIntOrNull() ?: return@mapNotNull null }
            if (parts.isEmpty() || steps.size % 2 != 0) null else parts[0] to steps.toIntArray()
        }.toMap()
    }

    private fun save(stretches: Map<String, IntArray>) {
        val text = stretches.entries.joinToString("") { (hash, steps) -> hash + steps.joinToString("") { " $it" } + "\n" }
        writeTextAtomically(file, text)
    }
}
