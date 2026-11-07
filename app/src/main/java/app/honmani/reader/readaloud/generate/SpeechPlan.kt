package app.honmani.reader.readaloud.generate

import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.data.sync.SpeechPiece
import app.honmani.reader.data.words.Sentences
import app.honmani.reader.readaloud.CueSpan
import app.honmani.reader.readaloud.PronunciationRules
import app.honmani.reader.readaloud.splitForSpeech
import java.io.File

/**
 * One request to the voice, one subtitle: [spoken] is what is sent (readings for ruby, the
 * book's pronunciation rules applied, gaiji left out), [original] is the book's own text, and
 * [span] is where it is in the book as ttu counts characters (the same positions
 * `EpubPositions.countedText` has, gaiji picture included), as `CueAligner` would find it.
 */
class PlannedSentence(val spoken: String, val original: String, val span: CueSpan) {
    /** Characters billed for this request: the spoken text, as code points. */
    val billedChars: Int get() = spoken.codePointCount(0, spoken.length)
}

/** The sentences of a book, in reading order, for making an audiobook with a cloud voice. */
class SpeechPlan(val sentences: List<PlannedSentence>, val countedTotal: Int = 0) {
    /** [countedTotal]: the characters ttu counts in the whole book, as `countedText` has them. */
    /** Characters billed for the whole book. */
    val billedChars: Long by lazy { sentences.sumOf { it.billedChars.toLong() } }

    companion object {
        /** The most characters of one request (`GoogleCloudVoice.maxInputLength`). */
        const val MAX_REQUEST_CHARS = 1_600

        /** The plan for the EPUB [file], or null if it can't be read. */
        fun read(file: File, rules: PronunciationRules = PronunciationRules.EMPTY): SpeechPlan? =
            EpubPositions.speechPieces(file)?.let { build(it, rules) }

        /**
         * The plan for documents given as [SpeechPiece]s. Sentences end as the reader's tap
         * lookup cuts them (`Sentences`); one longer than [MAX_REQUEST_CHARS] spoken characters
         * is cut with `splitForSpeech`. A sentence with no character ttu counts, or nothing
         * left to say (gaiji only), is left out.
         */
        fun build(documents: List<List<SpeechPiece>>, rules: PronunciationRules = PronunciationRules.EMPTY): SpeechPlan {
            val sentences = ArrayList<PlannedSentence>()
            var offset = 0
            for (pieces in documents) {
                offset += document(pieces, rules, offset, sentences)
            }
            return SpeechPlan(sentences, offset)
        }

        /** Adds the sentences of one document, whose first counted character is [offset]; returns the characters it counts. */
        private fun document(pieces: List<SpeechPiece>, rules: PronunciationRules, offset: Int, out: MutableList<PlannedSentence>): Int {
            val original = StringBuilder()
            val starts = IntArray(pieces.size + 1)
            for ((i, piece) in pieces.withIndex()) {
                starts[i] = original.length
                original.append(piece.text)
            }
            starts[pieces.size] = original.length
            val text = original.toString()
            val counted = countedBefore(text)
            var next = 0
            for (range in Sentences.of(text)) {
                val from = range.first
                val to = range.last + 1
                while (next < pieces.size && starts[next + 1] <= from) next++
                val spoken = Spoken()
                // A rule may not reach over a ruby or a gaiji: plain text is spoken in runs between them.
                val run = StringBuilder()
                var runStart = 0
                fun flush() {
                    if (run.isEmpty()) return
                    val said = rules.apply(run.toString())
                    for (k in said.text.indices) spoken.add(said.text[k], runStart + said.originalOffset(k))
                    run.setLength(0)
                }
                var i = next
                while (i < pieces.size && starts[i] < to) {
                    val piece = pieces[i]
                    val reading = piece.reading
                    if (reading != null) {
                        flush()
                        // A ruby that begins in this sentence is spoken with it.
                        if (starts[i] >= from) {
                            if (isSymbols(reading)) {
                                for ((k, c) in piece.text.withIndex()) if (c != SpeechPiece.GAIJI_CHAR) spoken.add(c, starts[i] + k)
                            } else {
                                for (c in reading) spoken.add(c, starts[i])
                            }
                        }
                    } else {
                        for (k in maxOf(from, starts[i]) until minOf(to, starts[i + 1])) {
                            val c = text[k]
                            if (c == SpeechPiece.GAIJI_CHAR) {
                                flush()
                            } else {
                                if (run.isEmpty()) runStart = k
                                run.append(c)
                            }
                        }
                    }
                    i++
                }
                flush()
                chunk(spoken, text, counted, offset, from, to, out)
            }
            return counted[text.length]
        }

        /** Cuts a sentence's [spoken] text for the request limit and adds its parts. */
        private fun chunk(spoken: Spoken, text: String, counted: IntArray, offset: Int, from: Int, to: Int, out: MutableList<PlannedSentence>) {
            val said = spoken.text()
            var a = 0
            var b = said.length
            while (a < b && said[a].isWhitespace()) a++
            while (b > a && said[b - 1].isWhitespace()) b--
            if (a == b) return
            var at = a
            for (part in splitForSpeech(said.substring(a, b), MAX_REQUEST_CHARS)) {
                val end = at + part.length
                val originalFrom = if (at == a) from else spoken.origin(at)
                val originalTo = if (end >= b) to else spoken.origin(end)
                at = end
                var start = originalFrom
                var stop = maxOf(originalFrom, originalTo)
                // Gaiji at an edge are not part of the sentence.
                while (start < stop && text[start] == SpeechPiece.GAIJI_CHAR) start++
                while (stop > start && text[stop - 1] == SpeechPiece.GAIJI_CHAR) stop--
                val first = offset + counted[start]
                val last = offset + counted[stop]
                if (last <= first) continue
                val shown = text.substring(start, stop).replace(SpeechPiece.GAIJI_CHAR.toString(), "").trim()
                val sayThis = part.trim()
                if (sayThis.isEmpty()) continue
                out += PlannedSentence(sayThis, shown, CueSpan(first, last))
            }
        }

        /** The spoken characters of a sentence and the index in the document's text each came from. */
        private class Spoken {
            private val chars = StringBuilder()
            private val origins = ArrayList<Int>()

            fun add(c: Char, origin: Int) {
                chars.append(c)
                origins += origin
            }

            fun text(): String = chars.toString()

            fun origin(index: Int): Int = origins[index.coerceIn(0, origins.lastIndex)]
        }

        /** A reading that is only emphasis dots or other symbols (ruby used for 傍点): the base is spoken instead. */
        private fun isSymbols(reading: String): Boolean {
            var i = 0
            while (i < reading.length) {
                val cp = reading.codePointAt(i)
                i += Character.charCount(cp)
                if (EpubPositions.isCounted(cp) && cp != 0x25CB && cp != 0x25EF) return false
            }
            return true
        }

        /** For each index of [text] (and its end): the characters ttu counts before it, a gaiji as one. */
        private fun countedBefore(text: String): IntArray {
            val counted = IntArray(text.length + 1)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val width = Character.charCount(cp)
                val here = if (cp == SpeechPiece.GAIJI_CHAR.code || EpubPositions.isCounted(cp)) 1 else 0
                for (k in 1..width) counted[i + k] = counted[i] + (if (k == width) here else 0)
                i += width
            }
            return counted
        }
    }
}
