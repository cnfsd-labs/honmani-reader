package app.honmani.reader.anki

import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.diagnostics.logWarning
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The outcome of [AnkiSync.check]. */
sealed interface AnkiCheck {
    /** [cleared] words no longer have a card in AnkiDroid; [marked] words got a mark for a card found there. */
    data class Done(val cleared: Int, val marked: Int) : AnkiCheck

    data object NotInstalled : AnkiCheck

    data object NoPermission : AnkiCheck

    data class Failed(val reason: String) : AnkiCheck
}

/** The outcome of [AnkiSync.deckWords]. */
sealed interface DeckWords {
    /** The deck has [notes] notes; [matching] are saved words not known yet whose form is among them. */
    data class Found(val deck: String, val notes: Int, val matching: List<WordEntity>) : DeckWords

    data object NotInstalled : DeckWords

    data object NoPermission : DeckWords

    data class Failed(val reason: String) : DeckWords
}

/**
 * Compares the saved words with what AnkiDroid holds, on request only (nothing runs in the
 * background): [check] fixes the "in Anki" marks, [deckWords] and [markKnown] take words the
 * reader already learns in an Anki deck as known.
 */
class AnkiSync(
    private val anki: AnkiApi,
    private val settings: AnkiSettings,
    private val words: WordRepository,
) {
    /**
     * Clears the mark of words whose card was deleted in AnkiDroid and marks words that have a
     * Honmani card there but no mark (for example after a restore). A card counts when it is a
     * note of Honmani's note type with the word and reading as its first two fields (as when
     * adding finds an existing card); a marked word's card is found by its id in any note type.
     */
    suspend fun check(): AnkiCheck = guarded({ AnkiCheck.Failed(it) }) {
        if (anki.packageName() == null) return@guarded AnkiCheck.NotInstalled
        if (!anki.hasPermission()) return@guarded AnkiCheck.NoPermission
        // A working AnkiDroid always lists a deck; without one its answers mean nothing.
        if (anki.deckNames().isEmpty()) return@guarded AnkiCheck.Failed("AnkiDroid has no collection")
        val all = words.all.first()
        val markedIds = all.mapNotNull { it.ankiNoteId }
        val existing = anki.existingNotes(markedIds) ?: return@guarded AnkiCheck.Failed("AnkiDroid gave no answer")
        val noteType = settings.noteTypeId?.takeIf { anki.noteTypeExists(it) }
            ?: anki.findNoteType(AnkiCard.NOTE_TYPE, AnkiCard.FIELDS)
        val byWord = noteType?.let { type ->
            (anki.notes(type) ?: return@guarded AnkiCheck.Failed("AnkiDroid gave no answer"))
                .associate { (it.fields.getOrNull(0).orEmpty() to it.fields.getOrNull(1).orEmpty()) to it.id }
        }.orEmpty()
        var cleared = 0
        var marked = 0
        for (word in all) {
            val current = word.ankiNoteId
            if (current != null && current in existing) continue
            val found = byWord[AnkiCard.escape(word.expression) to AnkiCard.escape(word.reading.ifEmpty { word.expression })]
            when {
                found != null -> {
                    words.setAnkiNote(word.id, found)
                    if (current == null) marked++
                }
                current != null -> {
                    words.setAnkiNote(word.id, null)
                    cleared++
                }
            }
        }
        AnkiCheck.Done(cleared, marked)
    }

    /**
     * Reads the notes of [deck] and finds which saved words (not known yet) they contain. The
     * word of a note is its field named like Expression, Word or Front, else its first field,
     * cleaned of markup and readings ([AnkiWords]).
     */
    suspend fun deckWords(deck: String): DeckWords = guarded({ DeckWords.Failed(it) }) {
        if (anki.packageName() == null) return@guarded DeckWords.NotInstalled
        if (!anki.hasPermission()) return@guarded DeckWords.NoPermission
        val notes = anki.deckNotes(deck) ?: return@guarded DeckWords.Failed("AnkiDroid gave no answer")
        val fieldNames = notes.map { it.noteType }.distinct().associateWith { anki.fieldNames(it) }
        val forms = notes.mapNotNull { note ->
            val index = AnkiWords.fieldIndex(fieldNames[note.noteType].orEmpty())
            AnkiWords.extract(note.fields.getOrNull(index).orEmpty())
        }.toSet()
        val matching = words.all.first().filter { it.status == WordStatus.LEARNING && it.expression in forms }
        DeckWords.Found(deck, notes.size, matching)
    }

    /** Marks [found] as known (learned), and records that the import was done. */
    suspend fun markKnown(found: List<WordEntity>) {
        found.forEach { words.markLearned(it.id) }
        settings.setKnownFromAnkiOffered(true)
    }

    private suspend fun <T> guarded(failed: (String) -> T, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AnkiDroid's provider throws for problems on its side; never let that close the app.
                logWarning("Honmani.anki", "Reading AnkiDroid failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }
}

/** Finds the Japanese word on an Anki note. */
object AnkiWords {
    private val NAMES = listOf(
        "expression", "word", "front", "vocab", "vocabulary", "kanji", "japanese", "term",
        "単語", "表現", "語", "言葉", "漢字", "表",
    )

    /** The field of a note type to take the word from: one named like [NAMES], else the first. */
    fun fieldIndex(fieldNames: List<String>): Int {
        for (name in NAMES) {
            val i = fieldNames.indexOfFirst { it.trim().equals(name, ignoreCase = true) }
            if (i >= 0) return i
        }
        return 0
    }

    private val READING_RUBY = Regex("<rt>.*?</rt>|<rp>.*?</rp>", RegexOption.IGNORE_CASE)
    private val LINE_BREAK = Regex("<br\\s*/?>|</div>|</p>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val SOUND = Regex("\\[sound:[^\\]]*]")
    private val BRACKETS = Regex("\\[[^\\]]*]")

    /**
     * The word in a field's [html]: markup, `[sound:…]`, ruby readings and Anki's furigana
     * brackets (`漢字[かんじ]`) removed, the first line only. Null if no Japanese is left.
     */
    fun extract(html: String): String? {
        val text = html
            .replace(READING_RUBY, "")
            .replace(LINE_BREAK, "\n")
            .replace(TAG, "")
            .replace(SOUND, "")
            .replace(BRACKETS, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
        val line = text.lineSequence().map { it.replace(" ", "").replace(" ", "").replace("　", "").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        return line.takeIf { l -> l.any(::isJapanese) }
    }

    private fun isJapanese(c: Char) = c in 'ぁ'..'ヿ' || c in '一'..'鿿' || c in '々'..'〇'
}
