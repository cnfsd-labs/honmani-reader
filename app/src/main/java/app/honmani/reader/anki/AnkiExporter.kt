package app.honmani.reader.anki

import app.honmani.reader.diagnostics.logWarning
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity
import app.honmani.reader.data.words.WordRepository
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The outcome of adding a word to Anki. */
sealed interface AnkiResult {
    /** Added; [audioProblem] is set if the card went in without audio. */
    data class Added(val noteId: Long, val audioProblem: VoiceProblem? = null) : AnkiResult

    data object AlreadyInAnki : AnkiResult

    data object NotInstalled : AnkiResult

    data object NoPermission : AnkiResult

    /** AnkiDroid didn't take it, for example because it was never opened (no collection yet). */
    data object Refused : AnkiResult

    /**
     * The reader's own note type can't take the card ([problem]); nothing was added. [name] is
     * the note type's name, empty if none was ever chosen.
     */
    data class CustomUnusable(val name: String, val problem: CustomProblem) : AnkiResult

    /** Something unexpected went wrong; [reason] is for the message (and a bug report). */
    data class Failed(val reason: String) : AnkiResult
}

/**
 * Adds saved words to AnkiDroid as Honmani cards ([AnkiCard]): sets up the note type and the
 * deck chosen in [AnkiSettings], skips words already there, and attaches audio from the chosen
 * voice. Also writes words for Anki on a computer: as a package with audio ([ankiPackage]) or as
 * a text file ([textFile]).
 */
class AnkiExporter(
    private val anki: AnkiApi,
    private val settings: AnkiSettings,
    private val words: WordRepository,
    private val phoneVoice: () -> CardVoice,
    /** Where audio waits until AnkiDroid has copied it (shared through the file provider). */
    private val audioDir: File,
    /**
     * The sentence of a card as a recording from the book's audiobook ([app.honmani.reader.readaloud.SentenceClips]),
     * null if there is none. The file stays the cache's: it is copied, never deleted here.
     */
    private val clip: suspend (CardContent) -> File? = { null },
    /** What the installed dictionaries say about a word (senses from the best dictionary). */
    private val details: suspend (expression: String, reading: String) -> WordDetails,
) {
    private val mutex = Mutex()

    /**
     * Adds [word] with the sentence of [lookup], or of its latest lookup if null. With [deferred],
     * the word's "in Anki" mark (word id to note id) is put there instead of written, so a caller
     * adding many can write them in one go ([WordRepository.setAnkiNotes]).
     */
    suspend fun add(word: WordEntity, lookup: WordLookupEntity? = null, deferred: MutableMap<Long, Long>? = null): AnkiResult =
        add(content(word, lookup), deferred)

    /** [words] as a text file for Anki's import ([AnkiTextFile]), for the chosen deck. */
    suspend fun textFile(words: List<WordEntity>): String {
        val cards = words.map { content(it, null) }
        return AnkiTextFile.text(cards, settings.deck.value)
    }

    /**
     * [words] as an Anki package ([AnkiPackage]) for the chosen deck, written to [out], with
     * audio from the chosen voice. [onProgress] gets the number of words done. Returns why
     * cards went in without audio, if they did (the voice is not asked again after a failure).
     */
    suspend fun ankiPackage(words: List<WordEntity>, out: OutputStream, onProgress: (Int) -> Unit = {}): VoiceProblem? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = File(audioDir, "package").apply {
                    deleteRecursively()
                    mkdirs()
                }
                try {
                    val model = settings.cardStyle.value.model
                    val voice = settings.voice(phoneVoice)
                    var problem: VoiceProblem? = null
                    val notes = words.mapIndexed { i, word ->
                        val content = content(word, null)
                        val media = mutableListOf<File>()
                        var wordAudio = ""
                        var sentenceAudio = ""
                        findClip(content)?.let { file ->
                            val copy = File(dir, clipName(file) + "." + file.extension)
                            if (!copy.exists()) file.copyTo(copy)
                            media += copy
                            sentenceAudio = "[sound:${copy.name}]"
                        }
                        if (voice != null && problem == null) {
                            try {
                                wordAudio = packageSound(voice, content.reading.ifEmpty { content.expression }, dir, media)
                                // Too long for the voice: this card's sentence goes without audio, the rest go on.
                                if (sentenceAudio.isEmpty() && content.sentence.isNotBlank() &&
                                    content.sentence.length <= voice.maxInputLength
                                ) {
                                    val rate = settings.sentenceSpeed.value.rate
                                    sentenceAudio = packageSound(voice, content.sentence, dir, media, rate)
                                }
                            } catch (e: VoiceException) {
                                problem = e.problem
                            }
                        }
                        onProgress(i + 1)
                        AnkiPackage.Note(model.build(content, wordAudio, sentenceAudio), media)
                    }
                    AnkiPackage.write(notes, settings.deck.value, dir, out, model = model)
                    problem
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

    /** Speaks [text] into [dir] for a package and returns the field's `[sound:…]`. */
    private suspend fun packageSound(voice: CardVoice, text: String, dir: File, media: MutableList<File>, rate: Double = 1.0): String {
        val file = File(dir, soundName(voice, text, rate) + "." + voice.extension)
        if (!file.exists()) voice.speak(text, file, rate)
        media += file
        return "[sound:${file.name}]"
    }

    /** The card for [word] with the sentence of [lookup] (null: the latest). */
    private suspend fun content(word: WordEntity, lookup: WordLookupEntity?): CardContent {
        val sentence = lookup?.sentence ?: word.lastSentence
        val start = lookup?.surfaceStart ?: word.lastSurfaceStart
        val end = lookup?.surfaceEnd ?: word.lastSurfaceEnd
        val book = if (lookup != null) lookup.bookTitle else word.lastBookTitle
        // A saved word keeps only the title of its latest lookup's book; the clips find the book by it.
        val bookId = lookup?.bookId
        val found = try {
            details(word.expression, word.reading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Word details for a card failed", e)
            WordDetails()
        }
        return CardContent(
            expression = word.expression,
            reading = word.reading,
            senses = found.senses.ifEmpty { listOf(listOf(word.gloss)) },
            sentence = sentence,
            surfaceStart = start,
            surfaceEnd = end,
            bookTitle = book,
            bookId = bookId,
            pitches = found.pitches,
            frequency = found.frequency,
            furigana = word.furigana,
        )
    }

    /** Adds a card for [content] and marks the saved word, if there is one, as in Anki. */
    suspend fun add(content: CardContent, deferred: MutableMap<Long, Long>? = null): AnkiResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                addLocked(content, deferred)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AnkiDroid's provider throws for problems on its side; never let that close the app.
                AnkiResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun addLocked(content: CardContent, deferred: MutableMap<Long, Long>?): AnkiResult {
        if (anki.packageName() == null) return AnkiResult.NotInstalled
        if (!anki.hasPermission()) return AnkiResult.NoPermission
        val style = settings.cardStyle.value
        val model = style.model
        // The reader's own note type is only ever read: no setup, no new fields, no new card template.
        val custom = if (style == CardStyle.CUSTOM) {
            when (val found = resolveCustomNoteType(anki, settings)) {
                is CustomResolution.Ready -> found.type
                is CustomResolution.Unusable -> return AnkiResult.CustomUnusable(found.name, found.problem)
            }
        } else {
            null
        }
        val noteType = custom?.id ?: noteType(style) ?: return AnkiResult.Refused
        val fieldCount = if (custom == null) fieldCount(noteType, model) else custom.fieldNames.size
        val deck = anki.deck(settings.deck.value) ?: return AnkiResult.Refused
        val existing = if (custom != null) {
            val key = custom.key(content)
            anki.findNotes(noteType, custom.wordIndex, key.first).firstOrNull { custom.key(it.fields) == key }?.id
        } else {
            val plain = model.build(content, "", "")
            anki.findNote(noteType, plain[0], plain[1])
        }
        if (existing != null) {
            markInAnki(content, existing, deferred)
            return AnkiResult.AlreadyInAnki
        }

        var audioProblem: VoiceProblem? = null
        val made = mutableListOf<File>()
        try {
            var wordAudio = ""
            var sentenceAudio = ""
            // The audiobook's recording needs no voice. No clip (or one AnkiDroid won't take) is not a problem: the voice speaks.
            if (custom == null || custom.usesSentenceAudio) {
                sentenceAudio = clipSound(content, made)
            }
            // A note type of the reader's own may have no audio field: then nothing is spoken.
            val voice = settings.voice(phoneVoice)?.takeIf { custom == null || custom.usesWordAudio || custom.usesSentenceAudio }
            if (voice != null) {
                try {
                    // The reading, not the written form: a voice can misread kanji, never kana.
                    if (custom == null || custom.usesWordAudio) {
                        wordAudio = sound(voice, content.reading.ifEmpty { content.expression }, made)
                    }
                    // A sentence too long for the voice goes without audio; the card still gets its word.
                    if ((custom == null || custom.usesSentenceAudio) && sentenceAudio.isEmpty() &&
                        content.sentence.isNotBlank() && content.sentence.length <= voice.maxInputLength
                    ) {
                        sentenceAudio = sound(voice, content.sentence, made, settings.sentenceSpeed.value.rate)
                    }
                } catch (e: VoiceException) {
                    audioProblem = e.problem
                }
            }
            val fields = custom?.fields(content, wordAudio, sentenceAudio)
                ?: model.build(content, wordAudio, sentenceAudio).take(fieldCount)
            val noteId = anki.addNote(noteType, deck, fields, setOf(AnkiCard.TAG)) ?: return AnkiResult.Refused
            markInAnki(content, noteId, deferred)
            return AnkiResult.Added(noteId, audioProblem)
        } finally {
            made.forEach { it.delete() }
        }
    }

    /** Speaks [text], hands the file to AnkiDroid and returns the field's `[sound:…]`. */
    private suspend fun sound(voice: CardVoice, text: String, made: MutableList<File>, rate: Double = 1.0): String {
        val name = soundName(voice, text, rate)
        val file = File(audioDir, "$name.${voice.extension}")
        made += file
        voice.speak(text, file, rate)
        return anki.addSound(file, name) ?: throw VoiceException(VoiceProblem.FAILED)
    }

    /** The clip for [content]'s sentence if the switch is on and there is one; any failure means none. */
    private suspend fun findClip(content: CardContent): File? {
        if (!settings.audiobookSentences.value || content.sentence.isBlank()) return null
        return try {
            clip(content)?.takeIf { it.isFile }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Finding the sentence recording failed", e)
            null
        }
    }

    /** Hands the clip of [content] to AnkiDroid through a copy in [audioDir]; the `[sound:…]` or "" if none. */
    private suspend fun clipSound(content: CardContent, made: MutableList<File>): String {
        val file = findClip(content) ?: return ""
        val name = clipName(file)
        val copy = File(audioDir, "$name.${file.extension}")
        return try {
            audioDir.mkdirs()
            file.copyTo(copy, overwrite = true)
            made += copy
            anki.addSound(copy, name) ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Honmani.anki", "Handing the sentence recording to AnkiDroid failed", e)
            ""
        }
    }

    /** The clip's file name says book, audio and range, so the name is unique per clip (Anki keeps the first file of a name). */
    private fun clipName(file: File) = "honmani_" + shortHash(file.nameWithoutExtension)

    /** The rate is part of the name: Anki keeps the first file of a name. */
    private fun soundName(voice: CardVoice, text: String, rate: Double) =
        "honmani_" + shortHash(text + "\u0000" + voice.id + "\u0000" + rate)

    private fun noteType(style: CardStyle): Long? {
        val model = style.model
        val id = settings.noteTypeId(style)?.takeIf { anki.noteTypeExists(it) }
            ?: anki.findNoteType(model)
            ?: anki.addNoteType(
                name = model.name,
                fields = model.fields,
                cardName = model.cardName,
                front = model.front,
                back = model.back,
                css = model.css,
            )
            ?: return null
        settings.setNoteTypeId(style, id)
        checkCard(id, model)
        addMissingFields(id, model)
        return id
    }

    /** Note types whose missing fields were already tried once in this run. */
    private val fieldsTried = mutableSetOf<Long>()

    /**
     * A note type from an older Honmani lacks the [NoteModel.addedFields]: adds them, once per run.
     * Anki counts that as a change to the collection, so the next AnkiWeb sync goes one way. If
     * AnkiDroid refuses, the cards go in without the field ([fieldCount]).
     */
    private fun addMissingFields(noteType: Long, model: NoteModel) {
        if (model.addedFields.isEmpty() || !fieldsTried.add(noteType)) return
        try {
            val present = anki.fieldNames(noteType)
            if (present.isEmpty()) return
            for (field in model.addedFields) if (field !in present) anki.addField(noteType, field)
        } catch (e: Exception) {
            logWarning("Honmani.anki", "AnkiDroid did not add the field", e)
        }
    }

    /**
     * How many of [model]'s fields [noteType] takes. One made before the [NoteModel.addedFields] gets
     * its cards without them if AnkiDroid refused to add them, see [addMissingFields].
     */
    private fun fieldCount(noteType: Long, model: NoteModel): Int {
        val present = anki.fieldNames(noteType)
        if (present.isEmpty()) return model.fields.size
        return model.fields.size - model.addedFields.count { it !in present }
    }

    private fun existingNoteType(style: CardStyle): Long? =
        settings.noteTypeId(style)?.takeIf { anki.noteTypeExists(it) } ?: anki.findNoteType(style.model)

    /**
     * Once per note type: if its card doesn't show the sentence (AnkiDroid's placeholder card,
     * left behind when setting the card failed when the note type was made), sets Honmani's card.
     * Later edits in Anki are left alone.
     */
    private fun checkCard(noteType: Long, model: NoteModel) {
        if (settings.checkedNoteTypeId == noteType) return
        val front = anki.cardFront(noteType)
        val fine = front != null && "{{Sentence}}" in front
        if (fine || anki.setCard(noteType, model.cardName, model.front, model.back)) {
            settings.checkedNoteTypeId = noteType
        }
    }

    private suspend fun markInAnki(content: CardContent, noteId: Long, deferred: MutableMap<Long, Long>?) {
        val word = words.find(content.expression, content.reading) ?: return
        if (deferred != null) deferred[word.id] = noteId else words.setAnkiNote(word.id, noteId)
    }

    private fun shortHash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
}
