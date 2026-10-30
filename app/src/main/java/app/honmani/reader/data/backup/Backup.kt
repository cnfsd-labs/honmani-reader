package app.honmani.reader.data.backup

import android.content.SharedPreferences
import androidx.room.withTransaction
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.opds.OpdsCatalogEntity
import app.honmani.reader.data.settings.AppearanceSettings
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.words.LookupEventEntity
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity
import app.honmani.reader.data.words.WordProgressStore
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.data.words.WordStep
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What a restore brought back. */
data class RestoreSummary(
    /** Books in the library whose progress and statistics were restored. */
    val booksInLibrary: Int,
    /** Books not in the library: their progress comes back when they are added again. */
    val booksWaiting: Int,
    /** Saved words new on this phone. */
    val wordsAdded: Int,
    /** Book servers added; their passwords have to be entered again. */
    val catalogsAdded: Int,
    /** Library books whose reading position moved forward, for ttu ([RestoredBooks.takePosition]). */
    val positions: List<BookEntity> = emptyList(),
)

/**
 * Makes and restores [BackupFile]s. A restore merges: whatever this phone already has stays, and
 * restoring the same backup twice changes nothing the second time. Per book and day the larger
 * reading time and characters count, words are matched by written form and reading, books by
 * content hash. Books not in the library wait in [restored] until the same file is added again
 * ([onBookAdded]).
 */
class Backup(
    private val database: HonmaniDatabase,
    /** The reader's choices (the `settings` preferences; never what is tied to this phone). */
    private val settings: SharedPreferences,
    private val milestones: MilestoneStore,
    private val stretches: ReadStretchStore,
    private val wordProgress: WordProgressStore,
    private val restored: RestoredBooks,
    /** Book grades by content hash. */
    private val grades: () -> Map<String, StoredDifficulty>,
    /** Names this installation in the keys of books known only by their statistics. */
    private val installationId: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.backupDao()
    private val wordDao = database.wordDao()

    /** Everything but the books; [readerSettings] are ttu's, read by the caller (null if it couldn't). */
    suspend fun export(readerSettings: Map<String, String?>?): BackupFile {
        class Tables(
            val books: List<BookEntity>,
            val days: List<ReadingDayEntity>,
            val events: List<LookupEventEntity>,
            val words: List<WordEntity>,
            val lookups: List<WordLookupEntity>,
            val removed: List<RemovedBookEntity>,
            val catalogs: List<OpdsCatalogEntity>,
        )
        val tables = database.withTransaction {
            Tables(dao.books(), dao.readingDays(), dao.lookupEvents(), dao.words(), dao.wordLookups(), dao.removedBooks(), dao.catalogs())
        }
        val grades = grades()
        val keys = HashMap<Long, String>()
        val books = ArrayList<BackupBook>()
        for (book in tables.books) {
            keys[book.id] = book.contentHash
            books += BackupBook(book.contentHash, details(book, grades[book.contentHash]?.difficulty?.score ?: 0))
        }
        for (waiting in restored.waiting()) {
            if (waiting.id in keys) continue
            keys[waiting.id] = waiting.key
            books += BackupBook(waiting.key, waiting.details)
        }
        // Statistics of books removed from the library: kept under a key of their own.
        fun keyOf(id: Long): String = keys.getOrPut(id) {
            "book:${installationId()}:$id".also { books += BackupBook(it) }
        }
        val readingDays = tables.days.map { BackupReadingDay(keyOf(it.bookId), it.day, it.seconds, it.characters) }
        val lookupEvents = tables.events.map { BackupLookupEvent(it.lookedUpAt, it.bookId?.let(::keyOf)) }
        val progress = wordProgress.load()
        val lookups = tables.lookups.groupBy { it.wordId }
        val words = tables.words.map { word ->
            BackupWord(
                expression = word.expression,
                reading = word.reading,
                gloss = word.gloss,
                status = word.status.name,
                starred = word.starred,
                lookupCount = word.lookupCount,
                firstLookedUpAt = word.firstLookedUpAt,
                lastLookedUpAt = word.lastLookedUpAt,
                rankKey = word.rankKey,
                lastSentence = word.lastSentence,
                lastSurfaceStart = word.lastSurfaceStart,
                lastSurfaceEnd = word.lastSurfaceEnd,
                lastBookTitle = word.lastBookTitle,
                ankiNoteId = word.ankiNoteId,
                progress = progress[word.id]?.let { BackupWordStep(it.steps, it.reads, it.lookups, it.learned, it.kana) },
                lookups = lookups[word.id].orEmpty().map { lookup ->
                    BackupWordLookup(
                        surface = lookup.surface,
                        sentence = lookup.sentence,
                        surfaceStart = lookup.surfaceStart,
                        surfaceEnd = lookup.surfaceEnd,
                        book = lookup.bookId?.let(::keyOf),
                        bookTitle = lookup.bookTitle,
                        lookedUpAt = lookup.lookedUpAt,
                    )
                },
            )
        }
        return BackupFile(
            exportedAt = now(),
            settings = settings.all.mapNotNull { (key, value) -> typed(value)?.let { key to it } }.toMap(),
            readerSettings = readerSettings,
            milestones = milestones.seen.value.mapValues { (_, day) -> day.toString() },
            books = books,
            stretches = stretches.all().mapValues { (_, steps) -> steps.toList() },
            removedBooks = tables.removed.map { BackupRemovedBook(it.source.name, it.sourceId, it.removedAt) },
            readingDays = readingDays,
            lookupEvents = lookupEvents,
            words = words,
            catalogs = tables.catalogs.map { BackupCatalog(it.title, it.url, it.username, it.autoImport) },
        )
    }

    /** Merges [backup] into what this phone has; ttu's reader settings are the caller's. */
    suspend fun restore(backup: BackupFile): RestoreSummary {
        restoreSettings(backup.settings)
        milestones.restore(
            backup.milestones.mapNotNull { (id, day) -> runCatching { id to LocalDate.parse(day) }.getOrNull() }.toMap(),
        )
        stretches.merge(backup.stretches.mapValues { (_, steps) -> steps.toIntArray() })

        val waitingBefore = restored.waiting().associateBy { it.key }
        val newWaiting = ArrayList<WaitingBook>()
        val positions = ArrayList<BookEntity>()
        val savedAt = HashMap<Long, Long>()
        val progressBefore = wordProgress.load()
        val progress = HashMap<Long, WordStep>()
        var booksInLibrary = 0
        var wordsAdded = 0
        var catalogsAdded = 0
        database.withTransaction {
            val library = dao.books().associateBy { it.contentHash }
            var nextId = minOf(-1L, (waitingBefore.values.minOfOrNull { it.id } ?: 0L) - 1)
            val ids = HashMap<String, Long>()
            fun idOf(key: String): Long = ids.getOrPut(key) {
                library[key]?.id ?: waitingBefore[key]?.id ?: (nextId--).also { newWaiting += WaitingBook(key, it) }
            }
            for (entry in backup.books) {
                val book = library[entry.key]
                val details = entry.book
                if (book != null) {
                    ids[entry.key] = book.id
                    if (details == null) continue
                    booksInLibrary++
                    val merged = merge(book, details)
                    if (merged != book) dao.updateBook(merged)
                    if (merged.characters > book.characters) {
                        positions += merged
                        savedAt[book.id] = details.lastOpenedAt ?: backup.exportedAt
                    }
                } else if (entry.key !in waitingBefore && details != null) {
                    ids[entry.key] = nextId
                    newWaiting += WaitingBook(entry.key, nextId--, details)
                } else {
                    idOf(entry.key)
                }
            }

            val days = dao.readingDays().associateBy { it.bookId to it.day }
            val changedDays = backup.readingDays.mapNotNull { day ->
                val id = idOf(day.book)
                val before = days[id to day.day]
                val after = ReadingDayEntity(
                    bookId = id,
                    day = day.day,
                    seconds = maxOf(before?.seconds ?: 0, day.seconds),
                    characters = maxOf(before?.characters ?: 0, day.characters),
                )
                after.takeIf { it != before }
            }
            if (changedDays.isNotEmpty()) dao.putReadingDays(changedDays)

            // Lookups have no identity of their own: as many per moment and book as either side has.
            val events = dao.lookupEvents().groupingBy { it.lookedUpAt to it.bookId }.eachCount()
            val missing = backup.lookupEvents
                .groupingBy { it.lookedUpAt to it.book?.let(::idOf) }.eachCount()
                .flatMap { (key, count) -> List((count - (events[key] ?: 0)).coerceAtLeast(0)) { LookupEventEntity(lookedUpAt = key.first, bookId = key.second) } }
            if (missing.isNotEmpty()) dao.insertLookupEvents(missing)

            val words = dao.words().associateBy { WordRepository.key(it.expression, it.reading) }
            val lookups = dao.wordLookups().groupBy { it.wordId }
            for (word in backup.words) {
                val current = words[WordRepository.key(word.expression, word.reading)]
                val id = if (current == null) {
                    wordsAdded++
                    wordDao.insert(newWord(word))
                } else {
                    val merged = merge(current, word)
                    if (merged != current) wordDao.update(merged)
                    current.id
                }
                word.progress?.takeIf { current == null || id !in progressBefore }?.let {
                    progress[id] = WordStep(it.steps, it.reads, it.lookups, it.learned, it.kana)
                }
                val known = lookups[id].orEmpty().mapTo(HashSet()) { Triple(it.lookedUpAt, it.sentence, it.surfaceStart) }
                val added = word.lookups
                    .filter { Triple(it.lookedUpAt, it.sentence, it.surfaceStart) !in known }
                    .map { lookup ->
                        WordLookupEntity(
                            wordId = id,
                            surface = lookup.surface,
                            sentence = lookup.sentence,
                            surfaceStart = lookup.surfaceStart,
                            surfaceEnd = lookup.surfaceEnd,
                            bookId = lookup.book?.let(::idOf),
                            bookTitle = lookup.bookTitle,
                            lookedUpAt = lookup.lookedUpAt,
                        )
                    }
                if (added.isNotEmpty()) {
                    wordDao.insertLookups(added)
                    wordDao.pruneLookups(id, WordRepository.KEPT_LOOKUPS)
                }
            }

            dao.insertRemovedBooks(
                backup.removedBooks.mapNotNull { removed ->
                    val source = BookSource.entries.firstOrNull { it.name == removed.source } ?: return@mapNotNull null
                    RemovedBookEntity(source, removed.sourceId, removed.removedAt)
                },
            )
            val urls = dao.catalogs().mapTo(HashSet()) { it.url }
            for (catalog in backup.catalogs) {
                if (!urls.add(catalog.url)) continue
                dao.insertCatalog(
                    OpdsCatalogEntity(
                        title = catalog.title,
                        url = catalog.url,
                        username = catalog.username,
                        addedAt = now(),
                        autoImport = catalog.autoImport,
                    ),
                )
                catalogsAdded++
            }
        }
        restored.addWaiting(newWaiting)
        for (book in positions) restored.setPosition(book.contentHash, position(book, savedAt[book.id] ?: backup.exportedAt))
        wordProgress.restore(progress)
        return RestoreSummary(
            booksInLibrary = booksInLibrary,
            booksWaiting = newWaiting.count { it.details != null },
            wordsAdded = wordsAdded,
            catalogsAdded = catalogsAdded,
            positions = positions,
        )
    }

    /**
     * A book was added to the library: if a restored one was waiting for it, its progress and
     * statistics move to it. Returns the book as it is now.
     */
    suspend fun onBookAdded(book: BookEntity): BookEntity {
        val waiting = restored.take(book.contentHash) ?: return book
        val details = waiting.details ?: return book
        val merged = merge(book, details)
        database.withTransaction {
            dao.moveReadingDays(waiting.id, book.id)
            dao.moveLookupEvents(waiting.id, book.id)
            dao.moveWordLookups(waiting.id, book.id)
            if (merged != book) dao.updateBook(merged)
        }
        if (merged.characters > book.characters) {
            restored.setPosition(book.contentHash, position(merged, details.lastOpenedAt ?: details.addedAt))
        }
        return merged
    }

    /**
     * Restores the settings Honmani knows ([SETTING_TYPES]) with the type it reads them with: a
     * value of another type would make reading the setting throw at every start.
     */
    private fun restoreSettings(values: Map<String, BackupValue>) {
        if (values.isEmpty()) return
        val editor = settings.edit()
        for ((key, typed) in values) {
            if (SETTING_TYPES[key] != typed.type) continue
            val value = typed.value as? JsonPrimitive ?: continue
            when (typed.type) {
                BackupValue.STRING -> value.contentOrNull?.let { editor.putString(key, it) }
                BackupValue.BOOLEAN -> value.booleanOrNull?.let { editor.putBoolean(key, it) }
                BackupValue.FLOAT -> value.doubleOrNull?.let { editor.putFloat(key, it.toFloat()) }
            }
        }
        editor.apply()
    }

    private companion object {
        /** The `settings` preferences a backup brings back, and their types. Add new settings here. */
        val SETTING_TYPES = mapOf(
            AppearanceSettings.KEY_ACCENT to BackupValue.STRING,
            AppearanceSettings.KEY_CUSTOM_HUE to BackupValue.FLOAT,
            AppearanceSettings.KEY_SHOW_DIFFICULTY to BackupValue.BOOLEAN,
            AppearanceSettings.KEY_LIBRARY_SORT to BackupValue.STRING,
            AnkiSettings.KEY_AUDIO to BackupValue.STRING,
            AnkiSettings.KEY_SENTENCE_SPEED to BackupValue.STRING,
            AnkiSettings.KEY_GOOGLE_VOICE to BackupValue.STRING,
            AnkiSettings.KEY_DECK to BackupValue.STRING,
        )

        fun typed(value: Any?): BackupValue? = when (value) {
            is String -> BackupValue(BackupValue.STRING, JsonPrimitive(value))
            is Boolean -> BackupValue(BackupValue.BOOLEAN, JsonPrimitive(value))
            is Int -> BackupValue(BackupValue.INT, JsonPrimitive(value))
            is Long -> BackupValue(BackupValue.LONG, JsonPrimitive(value))
            is Float -> BackupValue(BackupValue.FLOAT, JsonPrimitive(value))
            is Set<*> -> BackupValue(BackupValue.STRING_SET, JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive)))
            else -> null
        }

        /**
         * [book]'s reading position, read at about [savedAt]: the last time the book was opened
         * on the phone backed up. Its pages were read after that, and so were the positions it
         * sent to a sync server then: a sync server with a position from that reading or later
         * wins when the book opens; one only from before it doesn't.
         */
        fun position(book: BookEntity, savedAt: Long) = RestoredPosition(book.characters, book.progress, savedAt)

        fun details(book: BookEntity, score: Int) = BackupBookDetails(
            title = book.title,
            author = book.author,
            format = book.format.name,
            source = book.source.name,
            sourceId = book.sourceId,
            addedAt = book.addedAt,
            lastOpenedAt = book.lastOpenedAt,
            progress = book.progress,
            characters = book.characters,
            finishedAt = book.finishedAt,
            hidden = book.hidden,
            score = score,
        )

        /** [book] with what the backup knew of it: the further reading position, the first dates. */
        fun merge(book: BookEntity, details: BackupBookDetails): BookEntity {
            val further = details.characters > book.characters
            return book.copy(
                progress = if (further) details.progress.coerceIn(0f, 1f) else book.progress,
                characters = if (further) details.characters else book.characters,
                finishedAt = book.finishedAt ?: details.finishedAt,
                lastOpenedAt = listOfNotNull(book.lastOpenedAt, details.lastOpenedAt).maxOrNull(),
                hidden = book.hidden || details.hidden,
                addedAt = minOf(book.addedAt, details.addedAt),
            )
        }

        fun status(name: String) = WordStatus.entries.firstOrNull { it.name == name } ?: WordStatus.LEARNING

        fun newWord(word: BackupWord) = WordEntity(
            expression = word.expression,
            reading = word.reading,
            gloss = word.gloss,
            status = status(word.status),
            starred = word.starred,
            lookupCount = word.lookupCount,
            firstLookedUpAt = word.firstLookedUpAt,
            lastLookedUpAt = word.lastLookedUpAt,
            rankKey = word.rankKey,
            lastSentence = word.lastSentence,
            lastSurfaceStart = word.lastSurfaceStart,
            lastSurfaceEnd = word.lastSurfaceEnd,
            lastBookTitle = word.lastBookTitle,
            ankiNoteId = word.ankiNoteId,
        )

        /**
         * A word saved on both sides: the latest lookup's sentence and status (a lookup takes back
         * "learned"), the larger counts, the first and last dates of both.
         */
        fun merge(word: WordEntity, backup: BackupWord): WordEntity {
            val newer = backup.lastLookedUpAt > word.lastLookedUpAt
            val status = when {
                newer -> status(backup.status)
                backup.lastLookedUpAt < word.lastLookedUpAt -> word.status
                status(backup.status) == WordStatus.LEARNED -> WordStatus.LEARNED
                else -> word.status
            }
            return word.copy(
                gloss = word.gloss.ifEmpty { backup.gloss },
                status = status,
                starred = word.starred || backup.starred,
                lookupCount = maxOf(word.lookupCount, backup.lookupCount),
                firstLookedUpAt = minOf(word.firstLookedUpAt, backup.firstLookedUpAt),
                lastLookedUpAt = maxOf(word.lastLookedUpAt, backup.lastLookedUpAt),
                rankKey = maxOf(word.rankKey, backup.rankKey),
                lastSentence = if (newer) backup.lastSentence else word.lastSentence,
                lastSurfaceStart = if (newer) backup.lastSurfaceStart else word.lastSurfaceStart,
                lastSurfaceEnd = if (newer) backup.lastSurfaceEnd else word.lastSurfaceEnd,
                lastBookTitle = if (newer) backup.lastBookTitle else word.lastBookTitle,
                ankiNoteId = word.ankiNoteId ?: backup.ankiNoteId,
            )
        }
    }
}
