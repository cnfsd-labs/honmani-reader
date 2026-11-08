package app.honmani.reader.data.backup

import androidx.room.withTransaction
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.library.BOOKMARK_NAME_MAX
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookmarkEntity
import app.honmani.reader.data.library.FOLDER_NAME_MAX
import app.honmani.reader.data.library.FolderEntity
import app.honmani.reader.data.library.HIGHLIGHT_COLORS
import app.honmani.reader.data.library.HIGHLIGHT_TEXT_MAX
import app.honmani.reader.data.library.HighlightEntity
import app.honmani.reader.data.library.PRONUNCIATION_READING_MAX
import app.honmani.reader.data.library.PRONUNCIATION_TEXT_MAX
import app.honmani.reader.data.library.PronunciationEntity
import app.honmani.reader.data.merge.SyncKeys
import app.honmani.reader.data.review.ReadDayEntity
import app.honmani.reader.data.review.ReviewCardEntity
import app.honmani.reader.data.review.ReviewLogEntity
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
import app.honmani.reader.data.words.WordStep
import java.time.LocalDate

/**
 * Takes another phone's sync file ([SyncFolder]) into this phone's data. Unlike [Backup.restore], which
 * only ever adds, it passes on edits and deletions: of two edits the later one wins, a deletion marker
 * removes older rows and a newer row beats the marker (on equal times the deletion wins, on every
 * phone). Merging the same file again changes nothing, so phones settle instead of writing back and
 * forth. Everything is written through the DAOs' row methods, which keep the other phone's times.
 */
class SyncMerge(
    private val database: HonmaniDatabase,
    private val milestones: MilestoneStore,
    private val stretches: ReadStretchStore,
    private val wordProgress: WordProgressStore,
    private val restored: RestoredBooks,
    private val installationId: () -> String,
) {
    private val dao = database.backupDao()
    private val wordDao = database.wordDao()
    private val reviewDao = database.reviewDao()
    private val folderDao = database.folderDao()
    private val bookmarkDao = database.bookmarkDao()
    private val highlightDao = database.highlightDao()
    private val pronunciationDao = database.pronunciationDao()
    private val knownBatchDao = database.knownBatchDao()
    private val finishedBookDao = database.finishedBookDao()
    private val tombstoneDao = database.tombstoneDao()

    /** This phone's deletion markers plus the ones accepted from the file. */
    private inner class Markers {
        private val times = HashMap<Pair<String, String>, Long>()

        suspend fun load() {
            for (marker in tombstoneDao.all()) times[marker.kind to marker.key] = marker.deletedAt
        }

        fun has(kind: String, key: String) = (kind to key) in times

        fun time(kind: String, key: String) = times[kind to key]

        /** A row of that [rowTime] is older than this phone's marker: it stays deleted. */
        fun beats(kind: String, key: String, rowTime: Long) = times[kind to key]?.let { rowTime <= it } == true

        suspend fun put(kind: String, key: String, at: Long) {
            times[kind to key] = at
            tombstoneDao.putTombstone(kind, key, at)
        }

        /** A row came back that is newer than the marker. */
        suspend fun clear(kind: String, key: String) {
            if (times.remove(kind to key) != null) tombstoneDao.clearTombstone(kind, key)
        }
    }

    suspend fun merge(file: BackupFile) {
        val positions = ArrayList<Pair<BookEntity, Long>>()
        val progress = HashMap<Long, WordStep>()
        val progressBefore = wordProgress.load()
        database.withTransaction {
            val markers = Markers().also { it.load() }
            applyMarkers(file, markers)
            mergeFolders(file, markers)
            val waiting = ArrayList<WaitingBook>()
            val idOf = mergeBooks(file, positions, waiting)
            mergeReadingDays(file, idOf)
            mergeLookupEvents(file, idOf)
            mergeWords(file, markers, idOf, progressBefore, progress)
            mergeKnownBatches(file, markers)
            mergeFinished(file, markers)
            mergeBookmarks(file, markers)
            mergeHighlights(file, markers)
            mergePronunciations(file, markers)
            // Still inside the transaction, as in a restore: a kill can't leave days under unknown ids.
            restored.addWaiting(waiting)
        }
        milestones.restore(file.milestones.mapNotNull { (id, day) -> runCatching { id to LocalDate.parse(day) }.getOrNull() }.toMap())
        stretches.merge(file.stretches.mapValues { (_, steps) -> steps.toIntArray() })
        for ((book, savedAt) in positions) restored.setPosition(book.contentHash, Backup.position(book, savedAt))
        wordProgress.restore(progress)
    }

    /** Deletes what the file's markers delete, and keeps those markers; a newer row here rejects its marker. */
    private suspend fun applyMarkers(file: BackupFile, markers: Markers) {
        if (file.tombstones.isEmpty()) return
        val words = dao.words().associateByTo(HashMap()) { SyncKeys.word(it.expression, it.reading) }
        val wordsById = words.values.associateBy { it.id }
        val cards = dao.reviewCards().associateByTo(HashMap()) { it.wordId }
        val logs = dao.reviewLogs().filter { it.wordId in wordsById }.groupBy { log ->
            val word = wordsById.getValue(log.wordId)
            SyncKeys.reviewLog(word.expression, word.reading, log.at, log.grade)
        }
        val lookups = dao.wordLookups().filter { it.wordId in wordsById }.groupBy { lookup ->
            val word = wordsById.getValue(lookup.wordId)
            SyncKeys.wordLookup(word.expression, word.reading, lookup.lookedUpAt, lookup.surface)
        }
        val bookmarks = dao.bookmarks().groupBy { SyncKeys.bookmark(it.contentHash, it.characters) }
        val highlights = dao.highlights().groupBy { SyncKeys.highlight(it.contentHash, it.start, it.end) }
        val readings = dao.pronunciations().groupBy { SyncKeys.pronunciation(it.contentHash, it.text) }
        val folders = folderDao.all().groupBy { SyncKeys.folder(it.name) }
        val batches = dao.knownBatches().groupBy { SyncKeys.knownBatch(it.source.name, it.detail, it.createdAt) }
        val finished = dao.finishedBooks().groupBy { SyncKeys.finishedBook(it.contentHash) }

        for (marker in file.tombstones) {
            val known = markers.time(marker.kind, marker.key)
            if (known != null && known >= marker.deletedAt) continue

            /** Deletes [rows] unless one is newer than the marker; returns whether the marker holds. */
            suspend fun <T> settle(rows: List<T>?, time: (T) -> Long, delete: suspend (T) -> Unit): Boolean {
                if (rows.orEmpty().any { time(it) > marker.deletedAt }) return false
                rows.orEmpty().forEach { delete(it) }
                return true
            }
            val holds = when (marker.kind) {
                SyncKeys.WORD -> settle(listOfNotNull(words[marker.key]), { maxOf(it.updatedAt, it.lastLookedUpAt) }) {
                    wordDao.deleteRow(it.id)
                    words.remove(marker.key)
                    cards.remove(it.id)
                }
                SyncKeys.REVIEW_CARD -> {
                    val card = words[marker.key]?.let { cards[it.id] }
                    settle(listOfNotNull(card), ::cardTime) {
                        reviewDao.deleteCardRow(it.wordId)
                        cards.remove(it.wordId)
                    }
                }
                // Their keys hold a time, so doing it again makes a new key: the marker always wins.
                SyncKeys.REVIEW_LOG -> settle(logs[marker.key], { Long.MIN_VALUE }) { reviewDao.deleteLogRow(it.id) }
                SyncKeys.WORD_LOOKUP -> settle(lookups[marker.key], { Long.MIN_VALUE }) { wordDao.deleteLookupRow(it.id) }
                SyncKeys.KNOWN_BATCH -> settle(batches[marker.key], { Long.MIN_VALUE }) { knownBatchDao.deleteBatchRow(it.id) }
                SyncKeys.BOOKMARK -> settle(bookmarks[marker.key], { it.updatedAt }) { bookmarkDao.deleteRow(it.id) }
                SyncKeys.HIGHLIGHT -> settle(highlights[marker.key], { it.updatedAt }) { highlightDao.deleteRow(it.id) }
                SyncKeys.PRONUNCIATION -> settle(readings[marker.key], { it.updatedAt }) { pronunciationDao.deleteRow(it.id) }
                SyncKeys.FOLDER -> settle(folders[marker.key], { it.createdAt }) { folderDao.deleteRow(it.id) }
                SyncKeys.FINISHED_BOOK -> settle(finished[marker.key], { it.finishedAt ?: 0 }) { finishedBookDao.forgetRow(it.contentHash) }
                else -> false
            }
            if (holds) markers.put(marker.kind, marker.key, marker.deletedAt)
        }
    }

    /** A new card has no edit time yet, but it was made after a deletion it follows. */
    private fun cardTime(card: ReviewCardEntity) = maxOf(card.updatedAt, card.addedAt)

    private fun cleanFolderName(name: String) = name.trim().take(FOLDER_NAME_MAX).trim()

    private suspend fun mergeFolders(file: BackupFile, markers: Markers) {
        for (name in file.folders) {
            val clean = cleanFolderName(name)
            if (clean.isEmpty() || folderDao.findByName(clean) != null) continue
            val time = file.folderTimes[name] ?: 0
            val key = SyncKeys.folder(clean)
            if (markers.beats(SyncKeys.FOLDER, key, time)) continue
            markers.clear(SyncKeys.FOLDER, key)
            folderDao.insertRow(FolderEntity(name = clean, createdAt = time))
        }
    }

    /** Merges the books; the result finds this phone's id for any book key of the file. */
    private suspend fun mergeBooks(file: BackupFile, positions: MutableList<Pair<BookEntity, Long>>, newWaiting: MutableList<WaitingBook>): suspend (String) -> Long {
        val waitingBefore = restored.waiting().associateBy { it.key }
        val library = dao.books().associateBy { it.contentHash }
        var nextId = minOf(-1L, (waitingBefore.values.minOfOrNull { it.id } ?: 0L) - 1)
        val ids = HashMap<String, Long>()
        val ownPrefix = "book:${installationId()}:"
        fun idOf(key: String): Long = ids.getOrPut(key) {
            library[key]?.id ?: waitingBefore[key]?.id
                // Removed from this phone's library: its statistics are still under that id.
                ?: key.removePrefix(ownPrefix).takeIf { key.startsWith(ownPrefix) }?.toLongOrNull()?.takeIf { it > 0 }
                ?: (nextId--).also { newWaiting += WaitingBook(key, it) }
        }
        for (entry in file.books) {
            val book = library[entry.key]
            val details = entry.book
            if (book != null) {
                ids[entry.key] = book.id
                if (details == null) continue
                // The position and first dates are merged by themselves; the reader's choices go together, by time.
                var merged = Backup.merge(book, details).copy(finishedAt = book.finishedAt, hidden = book.hidden)
                if (details.updatedAt > book.updatedAt) {
                    merged = merged.copy(
                        finishedAt = details.finishedAt,
                        hidden = details.hidden,
                        folderId = details.folder?.let { folderDao.findByName(cleanFolderName(it))?.id },
                        updatedAt = details.updatedAt,
                    )
                }
                if (merged != book) dao.updateBookRow(merged)
                if (merged.characters > book.characters) positions += merged to (details.lastOpenedAt ?: file.exportedAt)
            } else if (entry.key !in waitingBefore && details != null) {
                ids[entry.key] = nextId
                newWaiting += WaitingBook(entry.key, nextId--, details)
            } else if (details != null && waitingBefore[entry.key]?.let { it.details == null || details.characters > it.details.characters } == true) {
                val waiting = waitingBefore.getValue(entry.key)
                ids[entry.key] = waiting.id
                newWaiting += waiting.copy(details = details)
            } else {
                idOf(entry.key)
            }
        }
        return { key -> idOf(key) }
    }

    /**
     * Per book, day and phone, so two phones' reading adds up, however often it is merged. This phone's
     * own rows only ever change here: coming back through another phone they would only undo a
     * "delete its statistics".
     */
    private suspend fun mergeReadingDays(file: BackupFile, idOf: suspend (String) -> Long) {
        val own = installationId()
        val days = dao.readingDays().associateBy { Triple(it.bookId, it.day, it.deviceId) }
        val changed = ArrayList<ReadingDayEntity>()
        for (day in file.readingDays) {
            val device = day.device.ifEmpty { file.deviceId.orEmpty() }
            if (device.isEmpty() || device == own) continue
            // A day that isn't a date would crash every screen that reads the days back.
            if (runCatching { LocalDate.parse(day.day) }.isFailure) continue
            val id = idOf(day.book)
            val before = days[Triple(id, day.day, device)]
            val after = ReadingDayEntity(
                bookId = id,
                day = day.day,
                deviceId = device,
                seconds = maxOf(before?.seconds ?: 0, day.seconds),
                characters = maxOf(before?.characters ?: 0, day.characters),
                listenSeconds = maxOf(before?.listenSeconds ?: 0, day.listenSeconds),
                refoldSeconds = maxOf(before?.refoldSeconds ?: 0, day.refoldSeconds),
                refoldListenSeconds = maxOf(before?.refoldListenSeconds ?: 0, day.refoldListenSeconds),
            )
            if (after != before) changed += after
        }
        if (changed.isNotEmpty()) dao.putReadingDays(changed)
    }

    /** As many per moment as either side has; by moment alone, as the book's key may differ between phones. */
    private suspend fun mergeLookupEvents(file: BackupFile, idOf: suspend (String) -> Long) {
        val here = dao.lookupEvents().groupingBy { it.lookedUpAt }.eachCount()
        val missing = ArrayList<LookupEventEntity>()
        for ((at, events) in file.lookupEvents.groupBy { it.lookedUpAt }) {
            for (event in events.drop(here[at] ?: 0)) {
                missing += LookupEventEntity(lookedUpAt = at, bookId = event.book?.let { idOf(it) })
            }
        }
        if (missing.isNotEmpty()) dao.insertLookupEvents(missing)
    }

    private suspend fun mergeWords(file: BackupFile, markers: Markers, idOf: suspend (String) -> Long, progressBefore: Map<Long, WordStep>, progress: MutableMap<Long, WordStep>) {
        val words = dao.words().associateBy { SyncKeys.word(it.expression, it.reading) }
        val lookups = dao.wordLookups().groupBy { it.wordId }
        val cardsBefore = dao.reviewCards().associateBy { it.wordId }
        val logsBefore = dao.reviewLogs().groupBy { it.wordId }
        val readsBefore = dao.readDays().groupBy { it.wordId }
        // Removing a word keeps its review log (statistics); the word coming back must not log those answers twice.
        val liveIds = words.values.mapTo(HashSet()) { it.id }
        val orphanLogged = logsBefore.filterKeys { it !in liveIds }.values.flatten().mapTo(HashSet()) { it.at to it.grade }
        val cards = ArrayList<ReviewCardEntity>()
        val newLogs = ArrayList<ReviewLogEntity>()
        val readDays = ArrayList<ReadDayEntity>()
        val seen = HashSet<String>()
        for (word in file.words) {
            val key = SyncKeys.word(word.expression, word.reading)
            if (!seen.add(key)) continue
            val current = words[key]
            val id = if (current == null) {
                if (markers.beats(SyncKeys.WORD, key, maxOf(word.updatedAt, word.lastLookedUpAt))) continue
                markers.clear(SyncKeys.WORD, key)
                wordDao.insertRow(Backup.newWord(word).copy(updatedAt = word.updatedAt))
            } else {
                val merged = merged(current, word)
                if (merged != current) wordDao.updateRow(merged)
                current.id
            }
            word.progress?.takeIf { current == null || id !in progressBefore }?.let {
                progress[id] = WordStep(it.steps, it.reads, it.lookups, it.learned, it.kana)
            }
            val known = lookups[id].orEmpty().mapTo(HashSet()) { Triple(it.lookedUpAt, it.sentence, it.surfaceStart) }
            val added = ArrayList<WordLookupEntity>()
            for (lookup in word.lookups) {
                if (Triple(lookup.lookedUpAt, lookup.sentence, lookup.surfaceStart) in known) continue
                if (markers.has(SyncKeys.WORD_LOOKUP, SyncKeys.wordLookup(word.expression, word.reading, lookup.lookedUpAt, lookup.surface))) continue
                added += WordLookupEntity(
                    wordId = id,
                    surface = lookup.surface,
                    sentence = lookup.sentence,
                    surfaceStart = lookup.surfaceStart,
                    surfaceEnd = lookup.surfaceEnd,
                    bookId = lookup.book?.let { idOf(it) },
                    bookTitle = lookup.bookTitle,
                    lookedUpAt = lookup.lookedUpAt,
                    origin = LookupOrigin.entries.firstOrNull { it.name == lookup.origin } ?: LookupOrigin.READER,
                )
            }
            word.card?.let { card ->
                val here = cardsBefore[id]
                val cardKey = SyncKeys.reviewCard(word.expression, word.reading)
                if (here == null && markers.beats(SyncKeys.REVIEW_CARD, cardKey, maxOf(card.updatedAt, card.addedAt))) return@let
                if (here == null || card.updatedAt > here.updatedAt) {
                    markers.clear(SyncKeys.REVIEW_CARD, cardKey)
                    Backup.reviewCard(id, card)?.let { cards += it.copy(updatedAt = card.updatedAt) }
                }
            }
            val loggedAt = logsBefore[id].orEmpty().mapTo(HashSet()) { it.at to it.grade }
            if (current == null) loggedAt.addAll(orphanLogged)
            word.reviews
                .filter { !markers.has(SyncKeys.REVIEW_LOG, SyncKeys.reviewLog(word.expression, word.reading, it.at, it.grade)) && loggedAt.add(it.at to it.grade) }
                .mapTo(newLogs) { ReviewLogEntity(wordId = id, at = it.at, grade = it.grade, elapsedDays = it.elapsedDays.takeIf { d -> d.isFinite() } ?: 0.0) }
            val readsHere = readsBefore[id].orEmpty().associate { it.day to it.count }
            word.reads.filter { it.count > 0 && it.count > (readsHere[it.day] ?: 0) }.mapTo(readDays) { ReadDayEntity(id, it.day, it.count) }
            if (added.isNotEmpty()) {
                wordDao.insertLookups(added)
                wordDao.pruneLookups(id, WordRepository.KEPT_LOOKUPS)
            }
        }
        if (cards.isNotEmpty()) dao.putReviewCardRows(cards)
        if (newLogs.isNotEmpty()) dao.insertReviewLogRows(newLogs)
        if (readDays.isNotEmpty()) dao.putReadDays(readDays)
    }

    /** The counters and dates as in a restore; what the reader sets goes by the later edit, as a group. */
    private fun merged(current: WordEntity, word: BackupWord): WordEntity {
        val counted = Backup.merge(current, word).copy(
            gloss = current.gloss,
            note = current.note,
            furigana = current.furigana,
            status = current.status,
            starred = current.starred,
            ignored = current.ignored,
        )
        if (word.updatedAt <= current.updatedAt) return counted
        return counted.copy(
            gloss = word.gloss.ifEmpty { current.gloss },
            note = word.note.take(WordRepository.MAX_NOTE_LENGTH),
            furigana = Backup.validFurigana(word),
            status = Backup.status(word.status),
            starred = word.starred,
            ignored = word.ignored,
            updatedAt = word.updatedAt,
        )
    }

    private suspend fun mergeKnownBatches(file: BackupFile, markers: Markers) {
        val here = dao.knownBatches().mapTo(HashSet()) { Triple(it.source, it.detail, it.createdAt) }
        val wordIds = dao.words().associate { WordRepository.key(it.expression, it.reading) to it.id }
        for (batch in file.knownBatches) {
            val source = KnownSource.entries.firstOrNull { it.name == batch.source } ?: continue
            val detail = batch.detail.take(KNOWN_DETAIL_MAX)
            if (markers.has(SyncKeys.KNOWN_BATCH, SyncKeys.knownBatch(batch.source, detail, batch.createdAt))) continue
            if (!here.add(Triple(source, detail, batch.createdAt))) continue
            val entries = batch.words.mapNotNull { entry ->
                val id = wordIds[WordRepository.key(entry.expression, entry.reading)] ?: return@mapNotNull null
                KnownBatchWordEntity(0, id, entry.created, entry.previousStatus?.let { Backup.status(it) }, entry.lookupCount.coerceAtLeast(0))
            }
            if (entries.isEmpty()) continue
            val id = dao.insertKnownBatchRow(KnownBatchEntity(source = source, detail = detail, createdAt = batch.createdAt, count = entries.size))
            dao.insertKnownBatchWords(entries.map { it.copy(batchId = id) })
        }
    }

    private suspend fun mergeFinished(file: BackupFile, markers: Markers) {
        for (book in file.finishedBooks) {
            if (book.hash.isEmpty()) continue
            val key = SyncKeys.finishedBook(book.hash)
            if (markers.beats(SyncKeys.FINISHED_BOOK, key, book.finishedAt ?: 0)) continue
            markers.clear(SyncKeys.FINISHED_BOOK, key)
            dao.insertFinishedBookRows(listOf(FinishedBookEntity(book.hash, book.title, book.finishedAt)))
            book.finishedAt?.let { dao.setFinishedDateIfUnknown(book.hash, it) }
        }
    }

    private suspend fun mergeBookmarks(file: BackupFile, markers: Markers) {
        val here = dao.bookmarks().associateByTo(HashMap()) { SyncKeys.bookmark(it.contentHash, it.characters) }
        for (mark in file.bookmarks) {
            if (mark.hash.isEmpty() || mark.characters < 0 || mark.total <= 0) continue
            val key = SyncKeys.bookmark(mark.hash, mark.characters)
            val current = here[key]
            val after = if (current == null) {
                if (markers.beats(SyncKeys.BOOKMARK, key, mark.updatedAt)) continue
                markers.clear(SyncKeys.BOOKMARK, key)
                BookmarkEntity(contentHash = mark.hash, name = mark.name.take(BOOKMARK_NAME_MAX), characters = mark.characters, total = mark.total, createdAt = mark.createdAt, updatedAt = mark.updatedAt)
            } else {
                if (mark.updatedAt <= current.updatedAt) continue
                current.copy(name = mark.name.take(BOOKMARK_NAME_MAX), total = mark.total, updatedAt = mark.updatedAt)
            }
            here[key] = after.copy(id = bookmarkDao.putRow(after))
        }
    }

    private suspend fun mergeHighlights(file: BackupFile, markers: Markers) {
        val here = dao.highlights().associateByTo(HashMap()) { SyncKeys.highlight(it.contentHash, it.start, it.end) }
        for (mark in file.highlights) {
            if (mark.hash.isEmpty() || mark.start < 0 || mark.end <= mark.start || mark.total <= 0 || mark.color !in 0 until HIGHLIGHT_COLORS) continue
            val key = SyncKeys.highlight(mark.hash, mark.start, mark.end)
            val current = here[key]
            val text = mark.text.take(HIGHLIGHT_TEXT_MAX)
            val after = if (current == null) {
                if (markers.beats(SyncKeys.HIGHLIGHT, key, mark.updatedAt)) continue
                markers.clear(SyncKeys.HIGHLIGHT, key)
                HighlightEntity(contentHash = mark.hash, start = mark.start, end = mark.end, color = mark.color, text = text, total = mark.total, createdAt = mark.createdAt, updatedAt = mark.updatedAt)
            } else {
                if (mark.updatedAt <= current.updatedAt) continue
                current.copy(color = mark.color, text = text, updatedAt = mark.updatedAt)
            }
            here[key] = after.copy(id = highlightDao.putRow(after))
        }
    }

    private suspend fun mergePronunciations(file: BackupFile, markers: Markers) {
        val here = dao.pronunciations().associateByTo(HashMap()) { SyncKeys.pronunciation(it.contentHash, it.text) }
        for (reading in file.pronunciations) {
            if (reading.hash.isEmpty() || reading.text.isEmpty() || reading.reading.isEmpty() ||
                reading.text.length > PRONUNCIATION_TEXT_MAX || reading.reading.length > PRONUNCIATION_READING_MAX
            ) continue
            val key = SyncKeys.pronunciation(reading.hash, reading.text)
            val current = here[key]
            val after = if (current == null) {
                if (markers.beats(SyncKeys.PRONUNCIATION, key, reading.updatedAt)) continue
                markers.clear(SyncKeys.PRONUNCIATION, key)
                PronunciationEntity(contentHash = reading.hash, text = reading.text, reading = reading.reading, createdAt = reading.createdAt, updatedAt = reading.updatedAt)
            } else {
                if (reading.updatedAt <= current.updatedAt) continue
                current.copy(reading = reading.reading, updatedAt = reading.updatedAt)
            }
            here[key] = after.copy(id = pronunciationDao.putRow(after))
        }
    }
}
