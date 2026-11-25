package app.honmani.reader.data.difficulty

import app.honmani.reader.data.writeAtomically
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Every written form and reading of the installed dictionaries, with the word classes they can
 * have and how common they are, packed into a few arrays so that a book can be split into words
 * without a database query per word. Holding Jitendex this way takes about 10 MB.
 */
class Lexicon private constructor(
    private val chars: CharArray,
    /** Where each key starts in [chars]; one more entry marks the end of the last key. */
    private val starts: IntArray,
    private val classes: IntArray,
    private val ranks: IntArray,
) {
    val size: Int get() = classes.size

    /** The key's index, or -1 if no dictionary has it. */
    fun find(key: String): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(middle, key)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    /** [WordClass] flags of the entries with this key (0 for nouns, particles and the like). */
    fun classes(index: Int): Int = classes[index]

    /** The best frequency rank of a word with this key; [UNRANKED] if no frequency list has it. */
    fun rank(index: Int): Int = ranks[index]

    private fun compare(index: Int, key: String): Int {
        val start = starts[index]
        val length = starts[index + 1] - start
        for (i in 0 until minOf(length, key.length)) {
            val difference = chars[start + i] - key[i]
            if (difference != 0) return difference
        }
        return length - key.length
    }

    /**
     * Collects keys in any order; a key added again takes the union of classes and the best rank.
     * Keys added again are merged whenever the arrays are full, not only at the end: dictionaries
     * share most of their words, and all of several at once did not fit a 192 MB heap.
     */
    class Builder {
        private var chars = CharArray(4096)
        private var starts = IntArray(1025)
        private var classes = IntArray(1024)
        private var ranks = IntArray(1024)
        private var count = 0

        /** How many keys the arrays have room for. */
        internal val capacity: Int get() = classes.size

        fun add(key: String, classes: Int, rank: Int): Builder {
            if (key.isEmpty()) return this
            if (count == capacity) makeRoom()
            val start = starts[count]
            val end = start + key.length
            if (end > chars.size) chars = chars.copyOf(maxOf(end, chars.size + chars.size / 2))
            key.toCharArray(chars, start)
            this.classes[count] = classes
            ranks[count] = if (rank > 0) rank else UNRANKED
            count++
            starts[count] = end
            return this
        }

        fun build(): Lexicon {
            merge()
            return Lexicon(chars.copyOf(starts[count]), starts.copyOf(count + 1), classes.copyOf(count), ranks.copyOf(count))
        }

        /** Merges the keys; the arrays grow only if that frees less than a third of them. */
        private fun makeRoom() {
            merge()
            val room = capacity
            if (count <= room - room / 3) return
            starts = starts.copyOf(room + room / 2 + 1)
            classes = classes.copyOf(room + room / 2)
            ranks = ranks.copyOf(room + room / 2)
        }

        /** Sorts the keys and makes one of those that are equal. */
        private fun merge() {
            val order = sortIndices(count, ::compare)
            var size = 0
            // Classes and ranks go into the first of equal keys; only that one stays in [order].
            for (i in order) {
                val last = if (size > 0) order[size - 1] else -1
                if (last >= 0 && compare(last, i) == 0) {
                    classes[last] = classes[last] or classes[i]
                    ranks[last] = minOf(ranks[last], ranks[i])
                } else {
                    order[size++] = i
                }
            }
            classes = IntArray(capacity).also { for (i in 0 until size) it[i] = classes[order[i]] }
            ranks = IntArray(ranks.size).also { for (i in 0 until size) it[i] = ranks[order[i]] }
            val sortedChars = CharArray(chars.size)
            val sortedStarts = IntArray(starts.size)
            for (i in 0 until size) {
                val from = order[i]
                chars.copyInto(sortedChars, sortedStarts[i], starts[from], starts[from + 1])
                sortedStarts[i + 1] = sortedStarts[i] + starts[from + 1] - starts[from]
            }
            chars = sortedChars
            starts = sortedStarts
            count = size
        }

        private fun compare(a: Int, b: Int): Int {
            val lengthA = starts[a + 1] - starts[a]
            val lengthB = starts[b + 1] - starts[b]
            for (i in 0 until minOf(lengthA, lengthB)) {
                val difference = chars[starts[a] + i] - chars[starts[b] + i]
                if (difference != 0) return difference
            }
            return lengthA - lengthB
        }

        /** 0 until [count] ordered by [compare], stable; a merge sort on ints, no boxing. */
        private fun sortIndices(count: Int, compare: (Int, Int) -> Int): IntArray {
            var from = IntArray(count) { it }
            var to = IntArray(count)
            var width = 1
            while (width < count) {
                var start = 0
                while (start < count) {
                    val middle = minOf(start + width, count)
                    val end = minOf(start + 2 * width, count)
                    var left = start
                    var right = middle
                    var out = start
                    while (left < middle && right < end) {
                        to[out++] = if (compare(from[left], from[right]) <= 0) from[left++] else from[right++]
                    }
                    while (left < middle) to[out++] = from[left++]
                    while (right < end) to[out++] = from[right++]
                    start = end
                }
                from = to.also { to = from }
                width *= 2
            }
            return from
        }
    }

    /** Saves the lexicon for [read] with the same [signature]. */
    fun write(file: File, signature: String) {
        writeAtomically(file) { temporary ->
            DataOutputStream(BufferedOutputStream(temporary.outputStream(), 1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                val name = signature.toByteArray(Charsets.UTF_8)
                out.writeInt(name.size)
                out.write(name)
                out.writeInt(chars.size)
                out.writeInt(classes.size)
                for (c in chars) out.writeChar(c.code)
                for (start in starts) out.writeInt(start)
                for (value in classes) out.writeInt(value)
                for (rank in ranks) out.writeInt(rank)
            }
        }
    }

    companion object {
        /** In a dictionary, but in no frequency list: rarer than anything ranked. */
        const val UNRANKED = Int.MAX_VALUE

        private const val MAGIC = 0x4C455831

        /**
         * A lexicon saved by [write] with [signature], or null (none, another signature, or
         * damaged). Reading it takes a fraction of building it from the dictionaries.
         */
        fun read(file: File, signature: String): Lexicon? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                if (buffer.int != MAGIC) return null
                val name = ByteArray(buffer.int).also { buffer.get(it) }
                if (String(name, Charsets.UTF_8) != signature) return null
                val charCount = buffer.int
                val count = buffer.int
                val chars = CharArray(charCount).also { buffer.asCharBuffer().get(it) }
                buffer.position(buffer.position() + charCount * 2)
                val ints = buffer.asIntBuffer()
                val starts = IntArray(count + 1).also { ints.get(it) }
                val classes = IntArray(count).also { ints.get(it) }
                val ranks = IntArray(count).also { ints.get(it) }
                Lexicon(chars, starts, classes, ranks)
            }
        }.getOrNull()
    }
}
