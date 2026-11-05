package app.honmani.reader.data.backup

import android.content.SharedPreferences
import androidx.room.withTransaction
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.FOLDER_NAME_MAX
import app.honmani.reader.data.library.FolderEntity
import app.honmani.reader.data.library.BookSource
import app.honmani.reader.data.library.RemovedBookEntity
import app.honmani.reader.data.opds.OpdsCatalogEntity
import app.honmani.reader.data.review.ReadDayEntity
import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewLogEntity
import app.honmani.reader.data.review.ReviewState
import app.honmani.reader.data.settings.AppearanceSettings
import app.honmani.reader.data.settings.DaySettings
import app.honmani.reader.data.settings.ReadingGoalSettings
import app.honmani.reader.data.settings.ReadingTimeSettings
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.library.BOOKMARK_NAME_MAX
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.HIGHLIGHT_COLORS
import app.honmani.reader.data.library.HIGHLIGHT_TEXT_MAX
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PRONUNCIATION_READING_MAX
import app.honmani.reader.data.library.PRONUNCIATION_TEXT_MAX
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.data.stats.FinishedBookEntity
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.words.KNOWN_DETAIL_MAX
import app.honmani.reader.data.words.KnownBatchEntity
import app.honmani.reader.data.words.KnownBatchWordEntity
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.data.words.LookupEventEntity
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordLookupEntity
import app.honmani.reader.data.words.WordProgressStore
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.data.words.WordStep
import app.honmani.reader.lookup.FuriganaOverride
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
    private val folderDao = database.folderDao()

    /** Everything but the books; [readerSettings] are ttu's, read by the caller (null if it couldn't). */
    suspend fun export(readerSettings: Map<String, String?>?): BackupFile {
        class Tables(
            val books: List<BookEntity>,
            val folders: Map<Long, String>,
            val days: List<ReadingDayEntity>,
            val events: List<LookupEventEntity>,
            val words: List<WordEntity>,
            val lookups: List<WordLookupEntity>,
            val removed: List<RemovedBookEntity>,
            val finished: List<FinishedBookEntity>,
            val bookmarks: List<BookmarkEntity>,
            val highlights: List<HighlightEntity>,
            val pronunciations: List<PronunciationEntity>,
            val catalogs: List<OpdsCatalogEntity>,
            val cards: Map<Long, ReviewCardEntity>,
            val logs: Map<Long, List<ReviewLogEntity>>,
            val reads: Map<Long, List<ReadDayEntity>>,
            val batches: List<KnownBatchEntity>,
            val batchWords: List<KnownBatchWordEntity>,
        )
        val tables = database.withTransaction {
            Tables(dao.books(), folderDao.all().associate { it.id to it.name }, dao.readingDays(), dao.lookupEvents(), dao.words(), dao.wordLookups(), dao.removedBooks(), dao.finishedBooks(), dao.bookmarks(), dao.highlights(), dao.pronunciations(), dao.catalogs(),
                dao.reviewCards().associateBy { it.wordId }, dao.reviewLogs().groupBy { it.wordId }, dao.readDays().groupBy { it.wordId },
                dao.knownBatches(), dao.knownBatchWords(),
            )
        }
        val grades = grades()
        val keys = HashMap<Long, String>()
        val books = ArrayList<BackupBook>()
        for (book in tables.books) {
            keys[book.id] = book.contentHash
            books += BackupBook(book.contentHash, details(book, grades[book.contentHash]?.difficulty?.score ?: 0, book.folderId?.let(tables.folders::get)))
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
        val readingDays = tables.days.map { BackupReadingDay(keyOf(it.bookId), it.day, it.seconds, it.characters, it.refoldSeconds) }
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
                ignored = word.ignored,
                lookupCount = word.lookupCount,
                firstLookedUpAt = word.firstLookedUpAt,
                lastLookedUpAt = word.lastLookedUpAt,
                rankKey = word.rankKey,
                lastSentence = word.lastSentence,
                lastSurfaceStart = word.lastSurfaceStart,
                lastSurfaceEnd = word.lastSurfaceEnd,
                lastBookTitle = word.lastBookTitle,
                note = word.note,
                furigana = word.furigana,
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
                        origin = lookup.origin.name,
                    )
                },
                card = tables.cards[word.id]?.let(::backupCard),
                reviews = tables.logs[word.id].orEmpty().map { BackupReview(it.at, it.grade, it.elapsedDays) },
                reads = tables.reads[word.id].orEmpty().map { BackupReadDay(it.day, it.count) },
            )
        }
        val wordsById = tables.words.associateBy { it.id }
        val batchWords = tables.batchWords.groupBy { it.batchId }
        val knownBatches = tables.batches.map { batch ->
            BackupKnownBatch(
                source = batch.source.name,
                detail = batch.detail,
                createdAt = batch.createdAt,
                count = batch.count,
                words = batchWords[batch.id].orEmpty().mapNotNull { entry ->
                    val word = wordsById[entry.wordId] ?: return@mapNotNull null
                    BackupKnownBatchWord(word.expression, word.reading, entry.created, entry.previousStatus?.name, entry.lookupCount)
                },
            )
        }
        return BackupFile(
            exportedAt = now(),
            settings = settings.all.mapNotNull { (key, value) -> typed(value)?.let { key to it } }.toMap(),
            readerSettings = readerSettings,
            milestones = milestones.seen.value.mapValues { (_, day) -> day.toString() },
            books = books,
            folders = tables.folders.values.toList(),
            stretches = stretches.all().mapValues { (_, steps) -> steps.toList() },
            removedBooks = tables.removed.map { BackupRemovedBook(it.source.name, it.sourceId, it.removedAt) },
            readingDays = readingDays,
            finishedBooks = tables.finished.map { BackupFinishedBook(it.contentHash, it.title, it.finishedAt) },
            bookmarks = tables.bookmarks.map { BackupBookmark(it.contentHash, it.name, it.characters, it.total, it.createdAt) },
            highlights = tables.highlights.map { BackupHighlight(it.contentHash, it.start, it.end, it.color, it.text, it.total, it.createdAt) },
            pronunciations = tables.pronunciations.map { BackupPronunciation(it.contentHash, it.text, it.reading, it.createdAt) },
            lookupEvents = lookupEvents,
            words = words,
            knownBatches = knownBatches,
            catalogs = tables.catalogs.map { BackupCatalog(it.title, it.url, it.username, it.autoImport) },
        )
    }

    /** Merges [backup] into what this phone has; ttu's reader settings are the caller's. */
    suspend fun restore(backup: BackupFile): RestoreSummary {
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
            for (name in backup.folders) folderId(name)
            val ownPrefix = "book:${installationId()}:"
            fun idOf(key: String): Long = ids.getOrPut(key) {
                library[key]?.id ?: waitingBefore[key]?.id
                    // Removed from this phone's library: its statistics are still under that id.
                    ?: key.removePrefix(ownPrefix).takeIf { key.startsWith(ownPrefix) }?.toLongOrNull()?.takeIf { it > 0 }
                    ?: (nextId--).also { newWaiting += WaitingBook(key, it) }
            }
            for (entry in backup.books) {
                val book = library[entry.key]
                val details = entry.book
                if (book != null) {
                    ids[entry.key] = book.id
                    if (details == null) continue
                    booksInLibrary++
                    val merged = withFolder(merge(book, details), details)
                    if (merged != book) dao.updateBook(merged)
                    if (merged.characters > book.characters) {
                        positions += merged
                        savedAt[book.id] = details.lastOpenedAt ?: backup.exportedAt
                    }
                } else if (entry.key !in waitingBefore && details != null) {
                    ids[entry.key] = nextId
                    newWaiting += WaitingBook(entry.key, nextId--, details)
                } else if (details != null && waitingBefore[entry.key]?.let { it.details == null || details.characters > it.details.characters } == true) {
                    // A newer backup knows the book further along (or knew only its statistics before).
                    val waiting = waitingBefore.getValue(entry.key)
                    ids[entry.key] = waiting.id
                    newWaiting += waiting.copy(details = details)
                } else {
                    idOf(entry.key)
                }
            }

            val days = dao.readingDays().associateBy { it.bookId to it.day }
            // A day that isn't a date would crash every screen that reads the days back.
            val changedDays = backup.readingDays.filter { runCatching { LocalDate.parse(it.day) }.isSuccess }.mapNotNull { day ->
                val id = idOf(day.book)
                val before = days[id to day.day]
                val after = ReadingDayEntity(
                    bookId = id,
                    day = day.day,
                    seconds = maxOf(before?.seconds ?: 0, day.seconds),
                    characters = maxOf(before?.characters ?: 0, day.characters),
                    refoldSeconds = maxOf(before?.refoldSeconds ?: 0, day.refoldSeconds),
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
            val cardsBefore = dao.reviewCards().associateBy { it.wordId }
            val logsBefore = dao.reviewLogs().groupBy { it.wordId }
            val readsBefore = dao.readDays().groupBy { it.wordId }
            // Removing a word keeps its review log (statistics); a restored word must not log those answers twice.
            val liveIds = words.values.mapTo(HashSet()) { it.id }
            val orphanLogged = logsBefore.filterKeys { it !in liveIds }.values.flatten().mapTo(HashSet()) { it.at to it.grade }
            val cards = ArrayList<ReviewCardEntity>()
            val newLogs = ArrayList<ReviewLogEntity>()
            val readDays = ArrayList<ReadDayEntity>()
            val seen = HashSet<String>()
            for (word in backup.words) {
                // The same word twice (an edited or merged file) would hit the unique index and fail the restore.
                if (!seen.add(WordRepository.key(word.expression, word.reading))) continue
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
                            origin = LookupOrigin.entries.firstOrNull { it.name == lookup.origin } ?: LookupOrigin.READER,
                        )
                    }
                // The backup's card wins: restoring is meant to bring the review memory back as it was.
                word.card?.let { card -> reviewCard(id, card)?.let(cards::add) }
                val loggedAt = logsBefore[id].orEmpty().mapTo(HashSet()) { it.at to it.grade }
                if (current == null) loggedAt.addAll(orphanLogged)
                word.reviews.filter { loggedAt.add(it.at to it.grade) }.mapTo(newLogs) {
                    ReviewLogEntity(wordId = id, at = it.at, grade = it.grade, elapsedDays = it.elapsedDays.takeIf { d -> d.isFinite() } ?: 0.0)
                }
                val readsHere = readsBefore[id].orEmpty().associate { it.day to it.count }
                word.reads.filter { it.count > 0 && it.count > (readsHere[it.day] ?: 0) }.mapTo(readDays) { ReadDayEntity(id, it.day, it.count) }
                if (added.isNotEmpty()) {
                    wordDao.insertLookups(added)
                    wordDao.pruneLookups(id, WordRepository.KEPT_LOOKUPS)
                }
            }

            // A batch this phone has (same source, detail and time) stays; its words are found again by written form and reading.
            val batchesHere = dao.knownBatches().mapTo(HashSet()) { Triple(it.source, it.detail, it.createdAt) }
            val wordIds = dao.words().associate { WordRepository.key(it.expression, it.reading) to it.id }
            for (batch in backup.knownBatches) {
                val source = KnownSource.entries.firstOrNull { it.name == batch.source } ?: continue
                if (!batchesHere.add(Triple(source, batch.detail, batch.createdAt))) continue
                val entries = batch.words.mapNotNull { entry ->
                    val id = wordIds[WordRepository.key(entry.expression, entry.reading)] ?: return@mapNotNull null
                    KnownBatchWordEntity(0, id, entry.created, entry.previousStatus?.let { status(it) }, entry.lookupCount.coerceAtLeast(0))
                }
                if (entries.isEmpty()) continue
                val id = dao.insertKnownBatch(KnownBatchEntity(source = source, detail = batch.detail.take(KNOWN_DETAIL_MAX), createdAt = batch.createdAt, count = entries.size))
                dao.insertKnownBatchWords(entries.map { it.copy(batchId = id) })
            }

            if (cards.isNotEmpty()) dao.putReviewCards(cards)
            if (newLogs.isNotEmpty()) dao.insertReviewLogs(newLogs)
            if (readDays.isNotEmpty()) dao.putReadDays(readDays)

            dao.insertRemovedBooks(
                backup.removedBooks.mapNotNull { removed ->
                    val source = BookSource.entries.firstOrNull { it.name == removed.source } ?: return@mapNotNull null
                    RemovedBookEntity(source, removed.sourceId, removed.removedAt)
                },
            )
            // Merged like the rest: a record this phone has stays, a date fills in where it had none.
            val finished = backup.finishedBooks.filter { it.hash.isNotEmpty() }
            dao.insertFinishedBooks(finished.map { FinishedBookEntity(it.hash, it.title, it.finishedAt) })
            for (book in finished) book.finishedAt?.let { dao.setFinishedDateIfUnknown(book.hash, it) }
            // A place this phone has at the same spot stays (its name may have been edited here).
            val placed = dao.bookmarks().mapTo(HashSet()) { it.contentHash to it.characters }
            dao.insertBookmarks(
                backup.bookmarks
                    .filter { it.hash.isNotEmpty() && it.characters >= 0 && it.total > 0 && placed.add(it.hash to it.characters) }
                    .map { BookmarkEntity(contentHash = it.hash, name = it.name.take(BOOKMARK_NAME_MAX), characters = it.characters, total = it.total, createdAt = it.createdAt) },
            )
            // A passage this phone has at the same range stays (its colour may have been changed here).
            val marked = dao.highlights().mapTo(HashSet()) { Triple(it.contentHash, it.start, it.end) }
            dao.insertHighlights(
                backup.highlights
                    .filter {
                        it.hash.isNotEmpty() && it.start >= 0 && it.end > it.start && it.total > 0 &&
                            it.color in 0 until HIGHLIGHT_COLORS && marked.add(Triple(it.hash, it.start, it.end))
                    }
                    .map {
                        HighlightEntity(
                            contentHash = it.hash, start = it.start, end = it.end, color = it.color,
                            text = it.text.take(HIGHLIGHT_TEXT_MAX), total = it.total, createdAt = it.createdAt,
                        )
                    },
            )
            // A text this phone already has a reading for stays as it is.
            val read = dao.pronunciations().mapTo(HashSet()) { it.contentHash to it.text }
            dao.insertPronunciations(
                backup.pronunciations
                    .filter {
                        it.hash.isNotEmpty() && it.text.isNotEmpty() && it.reading.isNotEmpty() &&
                            it.text.length <= PRONUNCIATION_TEXT_MAX && it.reading.length <= PRONUNCIATION_READING_MAX &&
                            read.add(it.hash to it.text)
                    }
                    .map { PronunciationEntity(contentHash = it.hash, text = it.text, reading = it.reading, createdAt = it.createdAt) },
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
            // Still inside the transaction: a kill before the commit loses both, a kill after it
            // can't leave reading days under ids no waiting book knows (a retry reuses these ids).
            restored.addWaiting(newWaiting)
        }
        // The database came first: a failed restore leaves the settings and files as they were.
        restoreSettings(backup.settings)
        milestones.restore(
            backup.milestones.mapNotNull { (id, day) -> runCatching { id to LocalDate.parse(day) }.getOrNull() }.toMap(),
        )
        stretches.merge(backup.stretches.mapValues { (_, steps) -> steps.toIntArray() })
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

    /** The id of the folder called [name], made if this phone has none; null for no name. */
    private suspend fun folderId(name: String?): Long? {
        val clean = name?.trim()?.take(FOLDER_NAME_MAX)?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return folderDao.findByName(clean)?.id ?: folderDao.insert(FolderEntity(name = clean, createdAt = now()))
    }

    /** [book] in the backup's folder, unless it is in one already: a restore never moves a book out of its folder. */
    private suspend fun withFolder(book: BookEntity, details: BackupBookDetails): BookEntity =
        if (book.folderId != null) book else book.copy(folderId = folderId(details.folder))

    /**
     * A book was added to the library: if a restored one was waiting for it, its progress and
     * statistics move to it. Returns the book as it is now.
     */
    suspend fun onBookAdded(book: BookEntity): BookEntity {
        val waiting = restored.find(book.contentHash) ?: return book
        val details = waiting.details ?: return book
        val merged = database.withTransaction {
            withFolder(merge(book, details), details).also { merged ->
                dao.moveReadingDays(waiting.id, book.id)
                dao.moveLookupEvents(waiting.id, book.id)
                dao.moveWordLookups(waiting.id, book.id)
                if (merged != book) dao.updateBook(merged)
            }
        }
        // Only now: if the move failed, the book keeps waiting and the next add tries again.
        restored.remove(waiting)
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
                BackupValue.INT -> value.intOrNull?.let { editor.putInt(key, it) }
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
            AppearanceSettings.KEY_SHOW_PREVIOUS_SENTENCE to BackupValue.BOOLEAN,
            AppearanceSettings.KEY_SHOW_WORD_FREQUENCY to BackupValue.BOOLEAN,
            AppearanceSettings.KEY_SHOW_EXAMPLES to BackupValue.BOOLEAN,
            ReviewSettings.KEY_NEW_PER_DAY to BackupValue.INT,
            ReviewSettings.KEY_REVIEWS_PER_DAY to BackupValue.INT,
            ReviewSettings.KEY_SENTENCE to BackupValue.BOOLEAN,
            ReviewSettings.KEY_KNOWN_AFTER to BackupValue.INT,
            DaySettings.KEY_START_HOUR to BackupValue.INT,
            ReadingGoalSettings.KEY_ENABLED to BackupValue.BOOLEAN,
            ReadingGoalSettings.KEY_MINUTES to BackupValue.INT,
            ReadingTimeSettings.KEY_COUNT_DURING_LOOKUP to BackupValue.BOOLEAN,
            AnkiSettings.KEY_AUDIO to BackupValue.STRING,
            AnkiSettings.KEY_SENTENCE_SPEED to BackupValue.STRING,
            AnkiSettings.KEY_GOOGLE_VOICE to BackupValue.STRING,
            AnkiSettings.KEY_DECK to BackupValue.STRING,
            AnkiSettings.KEY_CARD_STYLE to BackupValue.STRING,
            AnkiSettings.KEY_CUSTOM_NOTE_TYPE_NAME to BackupValue.STRING,
            AnkiSettings.KEY_FIELD_MAPPINGS to BackupValue.STRING,
            AnkiSettings.KEY_SENTENCE_ENDS to BackupValue.STRING,
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

        fun details(book: BookEntity, score: Int, folder: String? = null) = BackupBookDetails(
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
            folder = folder,
            score = score,
        )

        /** [book] with what the backup knew of it: the further reading position, the first dates. */
        fun merge(book: BookEntity, details: BackupBookDetails): BookEntity {
            val further = details.characters > book.characters
            return book.copy(
                progress = if (further) details.progress.takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: 0f else book.progress,
                characters = if (further) details.characters else book.characters,
                finishedAt = book.finishedAt ?: details.finishedAt,
                lastOpenedAt = listOfNotNull(book.lastOpenedAt, details.lastOpenedAt).maxOrNull(),
                hidden = book.hidden || details.hidden,
                addedAt = minOf(book.addedAt, details.addedAt),
            )
        }

        fun backupCard(card: ReviewCardEntity) = BackupCard(
            state = card.state.name,
            due = card.due,
            stability = card.stability,
            difficulty = card.difficulty,
            reps = card.reps,
            lapses = card.lapses,
            passedReviews = card.passedReviews,
            lastGrade = card.lastGrade,
            lastReviewAt = card.lastReviewAt,
            reviewStability = card.reviewStability,
            buriedUntil = card.buriedUntil,
            addedAt = card.addedAt,
        )

        /** The card of word [wordId] as the backup has it; null for a state this version doesn't know. */
        fun reviewCard(wordId: Long, card: BackupCard): ReviewCardEntity? {
            val state = ReviewState.entries.firstOrNull { it.name == card.state } ?: return null
            // A hand-edited NaN would poison every later scheduling of the card.
            fun finite(value: Double) = value.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            return ReviewCardEntity(
                wordId = wordId,
                state = state,
                due = card.due,
                stability = finite(card.stability),
                difficulty = finite(card.difficulty),
                reps = card.reps.coerceAtLeast(0),
                lapses = card.lapses.coerceAtLeast(0),
                passedReviews = card.passedReviews.coerceAtLeast(0),
                lastGrade = card.lastGrade,
                lastReviewAt = card.lastReviewAt,
                reviewStability = finite(card.reviewStability),
                buriedUntil = card.buriedUntil,
                addedAt = card.addedAt,
            )
        }

        fun status(name: String) = WordStatus.entries.firstOrNull { it.name == name } ?: WordStatus.LEARNING

        fun newWord(word: BackupWord) = WordEntity(
            expression = word.expression,
            reading = word.reading,
            gloss = word.gloss,
            status = status(word.status),
            starred = word.starred,
            ignored = word.ignored,
            lookupCount = word.lookupCount,
            firstLookedUpAt = word.firstLookedUpAt,
            lastLookedUpAt = word.lastLookedUpAt,
            rankKey = word.rankKey,
            lastSentence = word.lastSentence,
            lastSurfaceStart = word.lastSurfaceStart,
            lastSurfaceEnd = word.lastSurfaceEnd,
            lastBookTitle = word.lastBookTitle,
            note = word.note.take(WordRepository.MAX_NOTE_LENGTH),
            furigana = validFurigana(word),
        )

        /** The backup's correction of the furigana, empty unless it still fits the word. */
        private fun validFurigana(word: BackupWord): String =
            word.furigana.takeIf { FuriganaOverride.parse(it, word.expression) != null }.orEmpty()

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
                note = word.note.ifEmpty { backup.note.take(WordRepository.MAX_NOTE_LENGTH) },
                furigana = word.furigana.ifEmpty { validFurigana(backup) },
                status = status,
                starred = word.starred || backup.starred,
                ignored = word.ignored || backup.ignored,
                lookupCount = maxOf(word.lookupCount, backup.lookupCount),
                firstLookedUpAt = minOf(word.firstLookedUpAt, backup.firstLookedUpAt),
                lastLookedUpAt = maxOf(word.lastLookedUpAt, backup.lastLookedUpAt),
                rankKey = maxOf(word.rankKey, backup.rankKey),
                lastSentence = if (newer) backup.lastSentence else word.lastSentence,
                lastSurfaceStart = if (newer) backup.lastSurfaceStart else word.lastSurfaceStart,
                lastSurfaceEnd = if (newer) backup.lastSurfaceEnd else word.lastSurfaceEnd,
                lastBookTitle = if (newer) backup.lastBookTitle else word.lastBookTitle,
            )
        }
    }
}
