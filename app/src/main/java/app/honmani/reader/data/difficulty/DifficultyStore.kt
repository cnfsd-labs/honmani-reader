package app.honmani.reader.data.difficulty

import app.honmani.reader.data.writeAtomically
import app.honmani.reader.data.writeTextAtomically
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A book's grade, with the [LexiconSources.signature] it was computed with. */
data class StoredDifficulty(val difficulty: BookDifficulty, val signature: String)

/**
 * Book grades as small JSON files named by the book's content hash, like the books themselves,
 * and each book's words ([BookWords]) in a text file beside it, read only when needed. A cache:
 * deleting the directory only means the books are graded again.
 */
class DifficultyStore(private val directory: File) {
    private val _grades = MutableStateFlow<Map<String, StoredDifficulty>>(emptyMap())

    /** By content hash; empty until [load]. */
    val grades: StateFlow<Map<String, StoredDifficulty>> = _grades.asStateFlow()

    /** Reads the stored grades once; call off the main thread. */
    fun load() {
        val files = directory.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
        _grades.value = files.mapNotNull { file ->
            runCatching { file.name.removeSuffix(SUFFIX) to decode(JSONObject(file.readText())) }.getOrNull()
        }.toMap()
    }

    fun save(contentHash: String, grade: StoredDifficulty) {
        directory.mkdirs()
        writeTextAtomically(File(directory, contentHash + SUFFIX), encode(grade).toString())
        _grades.value += contentHash to grade
    }

    /** Keeps a book's words; save them before the grade, so a graded book has its words. */
    fun saveWords(contentHash: String, words: BookWords) {
        directory.mkdirs()
        writeAtomically(File(directory, contentHash + WORDS_SUFFIX)) { temporary ->
            DataOutputStream(temporary.outputStream().buffered()).use { out ->
                out.writeInt(WORDS_FORMAT)
                out.writeInt(words.size)
                for (i in 0 until words.size) {
                    out.writeUTF(words.keys[i])
                    out.writeInt(words.ranks[i])
                    val positions = words.positions[i]
                    out.writeInt(positions.size)
                    for (position in positions) out.writeShort(position.toInt())
                }
            }
        }
    }

    /** A book's words; null if it has none (not graded, or graded before words were kept). Off the main thread. */
    fun words(contentHash: String): BookWords? {
        val file = File(directory, contentHash + WORDS_SUFFIX)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != WORDS_FORMAT) return null
                val count = input.readInt()
                val keys = ArrayList<String>(count)
                val ranks = IntArray(count)
                val positions = ArrayList<ShortArray>(count)
                for (i in 0 until count) {
                    keys += input.readUTF()
                    ranks[i] = input.readInt()
                    positions += ShortArray(input.readInt()) { input.readShort() }
                }
                BookWords(keys, ranks, positions)
            }
        }.getOrNull()
    }

    /** Drops one book's grade and words (the book was removed); they are recomputed if it comes back. */
    fun forget(contentHash: String) {
        File(directory, contentHash + SUFFIX).delete()
        File(directory, contentHash + WORDS_SUFFIX).delete()
        _grades.value -= contentHash
    }

    /** Drops grades and words of books no longer in the library. */
    fun keepOnly(contentHashes: Set<String>) {
        val gone = _grades.value.keys - contentHashes
        if (gone.isEmpty()) return
        for (hash in gone) {
            File(directory, hash + SUFFIX).delete()
            File(directory, hash + WORDS_SUFFIX).delete()
        }
        _grades.value -= gone
    }

    internal companion object {
        private const val SUFFIX = ".json"
        private const val WORDS_SUFFIX = ".words"

        /** Words files of another layout are read as none: their books are graded again anyway. */
        private const val WORDS_FORMAT = 2

        fun encode(grade: StoredDifficulty): JSONObject = with(grade.difficulty) {
            JSONObject()
                .put("signature", grade.signature)
                .put("version", version)
                .put("score", score)
                .put("r95", r95)
                .put("kanjiShare", kanjiShare.toDouble())
                .put("sentenceLength", sentenceLength.toDouble())
                .put("characters", characters)
                .put("words", words)
                .put("distinctWords", distinctWords)
                .put("unknownShare", unknownShare.toDouble())
                .put("rareShare", rareShare.toDouble())
                .put("variety", variety.toDouble())
        }

        fun decode(json: JSONObject): StoredDifficulty = StoredDifficulty(
            difficulty = BookDifficulty(
                version = json.getInt("version"),
                score = json.getInt("score"),
                r95 = json.getInt("r95"),
                kanjiShare = json.getDouble("kanjiShare").toFloat(),
                sentenceLength = json.getDouble("sentenceLength").toFloat(),
                characters = json.optLong("characters"),
                words = json.getInt("words"),
                distinctWords = json.getInt("distinctWords"),
                unknownShare = json.getDouble("unknownShare").toFloat(),
                rareShare = json.optDouble("rareShare", 0.0).toFloat(),
                variety = json.optDouble("variety", 0.0).toFloat(),
            ),
            signature = json.getString("signature"),
        )
    }
}
