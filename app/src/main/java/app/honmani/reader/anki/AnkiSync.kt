package app.honmani.reader.anki

import app.honmani.reader.data.words.WordRepository
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

/**
 * Compares the saved words with what AnkiDroid holds, on request only (nothing runs in the
 * background): [check] fixes the "in Anki" marks.
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
