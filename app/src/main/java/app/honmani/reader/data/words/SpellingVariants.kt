package app.honmani.reader.data.words

import app.honmani.reader.data.writeAtomically
import app.honmani.reader.data.difficulty.DifficultyDao
import app.honmani.reader.lookup.JapaneseText
import java.io.File

/**
 * Other spellings of the same word (当り前 for 当たり前, 判る for 分かる): the forms with kanji
 * one dictionary entry lists together (Yomitan's sequence number, JMdict's entry). Knowing one
 * spelling counts for all of them where words are matched by their written form ([WordKnowledge]).
 *
 * Single characters are left out: one kanji is often a spelling of several words (生 is うぶ in
 * the entry of 初 and 初心), and a word would then count as read because another one was.
 */
class SpellingVariants(private val variants: Map<String, Array<String>>) {
    /** The other spellings of [key]; empty for most words. */
    fun of(key: String): Array<String> = variants[key] ?: NONE

    val size: Int get() = variants.size

    /** Collects the spellings of each dictionary entry, in any order. */
    class Builder {
        private val entries = HashMap<Long, MutableList<String>>()

        /** [expression] is a spelling of entry [sequence] of [dictionary]; entries without a sequence (0 or less) are no group. */
        fun add(dictionary: Long, sequence: Long, expression: String): Builder {
            if (sequence <= 0 || !linkable(expression)) return this
            val forms = entries.getOrPut(dictionary shl SEQUENCE_BITS or sequence) { ArrayList(2) }
            if (expression !in forms) forms += expression
            return this
        }

        /** The entries with more than one spelling. */
        fun groups(): List<List<String>> =
            // A dictionary that gives unrelated words one sequence would link everything.
            entries.values.filter { it.size in 2..MAX_ENTRY }

        fun build(): SpellingVariants = of(groups())
    }

    companion object {
        val EMPTY = SpellingVariants(emptyMap())

        private val NONE = emptyArray<String>()

        /** JMdict's largest entry (倶利迦羅紋紋) has 16 spellings. */
        private const val MAX_ENTRY = 32

        private const val SEQUENCE_BITS = 40

        private const val PAGE = 20_000

        private fun linkable(form: String): Boolean =
            form.codePointCount(0, form.length) >= 2 && form.codePoints().anyMatch { JapaneseText.isKanji(it) }

        private fun of(groups: List<List<String>>): SpellingVariants {
            val all = HashMap<String, MutableSet<String>>()
            for (forms in groups) for (form in forms) all.getOrPut(form) { LinkedHashSet() }.addAll(forms)
            return SpellingVariants(all.mapValues { (form, forms) -> (forms - form).toTypedArray() })
        }

        /**
         * Reads the spellings of [dictionaries] page by page, as `LexiconLoader` does (about 3 s
         * for Jitendex on an older phone), or from [cache] if it was written for the same
         * [signature] (one entry's spellings per line).
         */
        suspend fun load(dao: DifficultyDao, dictionaries: List<Long>, cache: File, signature: String): SpellingVariants {
            if (dictionaries.isEmpty()) return EMPTY
            read(cache, signature)?.let { return it }
            val builder = Builder()
            var after = 0L
            while (true) {
                val page = dao.spellings(dictionaries, after, PAGE)
                if (page.isEmpty()) break
                for (row in page) builder.add(row.dictionaryId, row.sequence, row.expression)
                after = page.last().id
            }
            val groups = builder.groups()
            write(cache, signature, groups)
            return of(groups)
        }

        internal fun read(cache: File, signature: String): SpellingVariants? = runCatching {
            cache.bufferedReader().use { reader ->
                if (reader.readLine() != signature) return null
                of(reader.lineSequence().filter { it.isNotEmpty() }.map { it.split('\t') }.toList())
            }
        }.getOrNull()

        internal fun write(cache: File, signature: String, groups: List<List<String>>) {
            runCatching {
                writeAtomically(cache) { temporary ->
                    temporary.bufferedWriter().use { writer ->
                        writer.append(signature).append('\n')
                        for (forms in groups) forms.joinTo(writer, "\t").append('\n')
                    }
                }
            }
        }
    }
}
