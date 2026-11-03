package app.honmani.reader.data.dictionary

import android.util.Log
import androidx.room.withTransaction
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.dictionary.yomitan.InvalidDictionaryException
import app.honmani.reader.data.dictionary.yomitan.YomitanDictionaryReader
import app.honmani.reader.data.dictionary.yomitan.TermMetaKind
import app.honmani.reader.data.dictionary.yomitan.YomitanKanji
import app.honmani.reader.data.dictionary.yomitan.YomitanTerm
import app.honmani.reader.data.dictionary.yomitan.YomitanTermMeta
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A dictionary with the same title is already installed. */
class DictionaryAlreadyInstalledException(val title: String) :
    Exception("A dictionary titled \"$title\" is already installed")

/** The dictionary is valid but has nothing Honmani can use (e.g. only media files). */
class NoTermsException(val title: String) : Exception("\"$title\" contains no terms")

/** What kind of file a dictionary comes as. */
enum class DictionaryFileFormat {
    /** A Yomitan zip. */
    YOMITAN,

    /** Kanjium's pitch accent list ([KanjiumAccents]). */
    KANJIUM_ACCENTS,
}

data class ImportProgress(
    val title: String,
    /** Share of the dictionary's data written so far, 0 to 100. */
    val percent: Int,
)

/**
 * Turns bytes read into [ImportProgress], reported when the percentage changes. Measured in
 * bytes rather than files: frequency dictionaries such as Jiten keep everything in one file.
 */
internal class ProgressMeter(
    private val title: String,
    private val totalBytes: Long,
    private val onProgress: suspend (ImportProgress) -> Unit,
) {
    private var doneBytes = 0L
    private var reported = -1

    /** Reports the start (0%). */
    suspend fun start() = report(0)

    /** A reader that will report its own bytes read so far, added to what came before it. */
    fun part(): suspend (Long) -> Unit {
        val base = doneBytes
        return { bytes ->
            doneBytes = base + bytes
            report((doneBytes * 100 / totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 99))
        }
    }

    suspend fun finish() = report(100)

    private suspend fun report(percent: Int) {
        if (percent == reported) return
        reported = percent
        onProgress(ImportProgress(title, percent))
    }
}

/** Imports Yomitan dictionary zips into the database, one at a time. */
class DictionaryImporter(
    private val database: HonmaniDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dictionaryDao = database.dictionaryDao()
    private val termDao = database.termDao()
    private val tagDao = database.tagDao()
    private val termMetaDao = database.termMetaDao()
    private val kanjiDao = database.kanjiDao()
    private val mutex = Mutex()

    /** Where the time of the running import goes, logged when it is done (imports are serialized). */
    private val packNanos = AtomicLong()
    private var insertNanos = 0L
    private var waitNanos = 0L

    /**
     * Imports the dictionary in [file], waiting for any import already running. Nothing is left
     * in the database if this fails or is cancelled.
     *
     * With [replaces], the file is an update of that installed dictionary: it may carry the same
     * title, takes over its place (enabled, order) and the old version is deleted once the new
     * one is ready. Until then lookups keep using the old one.
     *
     * @throws InvalidDictionaryException the file is not a valid Yomitan dictionary
     * @throws DictionaryAlreadyInstalledException
     * @throws NoTermsException
     */
    suspend fun import(
        file: File,
        format: DictionaryFileFormat = DictionaryFileFormat.YOMITAN,
        replaces: Long? = null,
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): DictionaryEntity = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Imports are serialized, so any IMPORTING row is from an interrupted one.
            dictionaryDao.deleteIncomplete()
            packNanos.set(0L)
            insertNanos = 0L
            waitNanos = 0L
            val started = System.nanoTime()
            val replaced = replaces?.let { dictionaryDao.findById(it) }
            val imported = when (format) {
                DictionaryFileFormat.YOMITAN -> importYomitan(file, replaced, onProgress)
                DictionaryFileFormat.KANJIUM_ACCENTS -> importKanjium(file, replaced, onProgress)
            }
            Log.i(
                TAG,
                "Imported \"${imported.title}\" in ${(System.nanoTime() - started) / 1_000_000} ms: " +
                    "inserting ${insertNanos / 1_000_000} ms, waiting for the readers ${waitNanos / 1_000_000} ms " +
                    "(writer); packing ${packNanos.get() / 1_000_000} ms (summed over threads)",
            )
            imported
        }
    }

    /** Throws if a dictionary titled [title] is installed, other than the one being replaced. */
    private suspend fun checkNotInstalled(title: String, replaced: DictionaryEntity?) {
        val installed = dictionaryDao.findByTitle(title) ?: return
        if (installed.id != replaced?.id) throw DictionaryAlreadyInstalledException(title)
    }

    private suspend fun importKanjium(
        file: File,
        replaced: DictionaryEntity?,
        onProgress: suspend (ImportProgress) -> Unit,
    ): DictionaryEntity {
        val title = KanjiumAccents.TITLE
        checkNotInstalled(title, replaced)
        val pending = DictionaryEntity(
            title = title,
            revision = "kanjium",
            formatVersion = 3,
            author = "Uros O. (Kanjium)",
            attribution = KanjiumAccents.ATTRIBUTION,
            importedAt = clock(),
            status = DictionaryEntity.Status.IMPORTING,
        )
        val meter = ProgressMeter(title, file.length(), onProgress)
        return writeDictionary(pending, replaced, meter) { id, count ->
            KanjiumAccents.forEachChunk(file, CHUNK_SIZE, meter.part()) { chunk -> count(insertMeta(id, chunk)) }
        }
    }

    /**
     * Inserts [pending] as IMPORTING, runs [terms], [kanji] and [write] (which report rows
     * written through their second argument) and marks it ready, in place of [replaced] if set;
     * on failure or cancellation nothing is left behind.
     */
    private suspend fun writeDictionary(
        pending: DictionaryEntity,
        replaced: DictionaryEntity?,
        meter: ProgressMeter,
        terms: suspend (id: Long, count: (Int) -> Unit) -> Unit = { _, _ -> },
        kanji: suspend (id: Long, count: (Int) -> Unit) -> Unit = { _, _ -> },
        write: suspend (id: Long, count: (Int) -> Unit) -> Unit,
    ): DictionaryEntity {
        // A new dictionary goes last; an update takes the place of the version it replaces.
        val placed = if (replaced != null) {
            pending.copy(enabled = replaced.enabled, priority = replaced.priority)
        } else {
            pending.copy(priority = dictionaryDao.nextPriority())
        }
        val id = dictionaryDao.insert(placed)
        try {
            var termCount = 0
            var metaCount = 0
            var kanjiCount = 0
            meter.start()
            terms(id) { termCount += it }
            kanji(id) { kanjiCount += it }
            write(id) { metaCount += it }
            if (termCount == 0 && metaCount == 0 && kanjiCount == 0) throw NoTermsException(pending.title)
            database.withTransaction {
                dictionaryDao.markReady(id, termCount, metaCount, kanjiCount)
                if (replaced != null) dictionaryDao.delete(replaced.id)
            }
            meter.finish()
            return placed.copy(
                id = id,
                status = DictionaryEntity.Status.READY,
                termCount = termCount,
                metaCount = metaCount,
                kanjiCount = kanjiCount,
            )
        } catch (e: Throwable) {
            withContext(NonCancellable) { dictionaryDao.delete(id) }
            throw e
        }
    }

    private suspend fun insertMeta(dictionaryId: Long, rows: List<YomitanTermMeta>): Int {
        val entities = rows.map { it.toEntity(dictionaryId) }
        timedInsert { database.withTransaction { termMetaDao.insertAll(entities) } }
        return rows.size
    }

    private suspend fun importYomitan(
        file: File,
        replaced: DictionaryEntity?,
        onProgress: suspend (ImportProgress) -> Unit,
    ): DictionaryEntity {
        return YomitanDictionaryReader.open(file).use { reader ->
            val index = reader.index
            checkNotInstalled(index.title, replaced)
            if (reader.termBanks.isEmpty() && reader.termMetaBanks.isEmpty() && reader.kanjiBanks.isEmpty()) {
                throw NoTermsException(index.title)
            }

            val tags = reader.readTags()
            val pending = DictionaryEntity(
                title = index.title,
                revision = index.revision,
                formatVersion = index.format,
                author = index.author,
                attribution = index.attribution,
                isUpdatable = index.isUpdatable,
                indexUrl = index.indexUrl,
                downloadUrl = index.downloadUrl,
                frequencyMode = index.frequencyMode,
                importedAt = clock(),
                status = DictionaryEntity.Status.IMPORTING,
            )
            val banks = reader.termBanks + reader.kanjiBanks + reader.termMetaBanks
            val meter = ProgressMeter(index.title, banks.sumOf { reader.bankSize(it) }, onProgress)
            writeDictionary(
                pending,
                replaced,
                meter,
                terms = { id, count ->
                    // Glossaries are packed with a preset sampled from the first chunk.
                    val preset = samplePreset(reader)
                    if (preset != null) {
                        termDao.insertPreset(GlossaryPresetEntity(id, preset))
                        importTerms(reader, id, preset, meter, count)
                    }
                    tagDao.insertAll(
                        tags.map { TagEntity(id, it.name, it.category, it.order, it.notes, it.score) },
                    )
                },
                kanji = { id, count ->
                    reader.kanjiBanks.forEach { bank ->
                        reader.forEachKanjiChunk(bank, CHUNK_SIZE, meter.part()) { chunk ->
                            timedInsert { database.withTransaction { kanjiDao.insertAll(chunk.map { it.toEntity(id) }) } }
                            count(chunk.size)
                        }
                    }
                },
            ) { id, count ->
                reader.termMetaBanks.forEach { bank ->
                    reader.forEachTermMetaChunk(bank, CHUNK_SIZE, meter.part()) { chunk ->
                        count(insertMeta(id, chunk))
                    }
                }
            }
        }
    }

    /** The preset for [reader]'s glossaries, sampled from its first chunk of terms; null without terms. */
    private suspend fun samplePreset(reader: YomitanDictionaryReader): ByteArray? {
        for (bank in reader.termBanks) {
            var preset: ByteArray? = null
            try {
                reader.forEachTermChunk(bank, CHUNK_SIZE) { chunk ->
                    preset = GlossaryPacker.preset(chunk.map { it.glossary })
                    throw PresetSampled()
                }
            } catch (_: PresetSampled) {
                // The sample is all that was wanted.
            }
            if (preset != null) return preset
        }
        return null
    }

    /**
     * Writes all term banks of [reader], in file order. Banks are read, parsed and packed on a few
     * background threads at once (each hands its chunks over in a small channel of its own, so
     * memory stays bounded) while this one writes them in order: rows get the ids they would
     * have had in a sequential import. A bank that fails ends the import when its turn comes, so
     * the error is the first one in file order.
     */
    private suspend fun importTerms(
        reader: YomitanDictionaryReader,
        dictionaryId: Long,
        preset: ByteArray,
        meter: ProgressMeter,
        count: (Int) -> Unit,
    ) = coroutineScope {
        val runtime = Runtime.getRuntime()
        val parallel = minOf(runtime.availableProcessors() / 2L, runtime.maxMemory() / HEAP_PER_READER)
            .toInt().coerceIn(1, MAX_READERS)
        val lanes = Channel<Channel<TermPiece>>(parallel + 1)
        val workers = Dispatchers.Default.limitedParallelism(parallel)
        launch {
            for (bank in reader.termBanks) {
                val lane = Channel<TermPiece>(LANE_CAPACITY)
                lanes.send(lane)
                launch(workers) { readBank(reader, bank, dictionaryId, preset, lane) }
            }
            lanes.close()
        }
        for (lane in lanes) {
            val onRead = meter.part()
            val pieces = lane.iterator()
            while (timedWait { pieces.hasNext() }) {
                val piece = pieces.next()
                if (piece.entities.isNotEmpty()) {
                    timedInsert { database.withTransaction { termDao.insertAll(piece.entities) } }
                    count(piece.entities.size)
                }
                onRead(piece.bytesRead)
            }
        }
    }

    /** Reads [bank] into [lane] as chunks of entities; closes it, with the error if there is one. */
    private suspend fun readBank(
        reader: YomitanDictionaryReader,
        bank: String,
        dictionaryId: Long,
        preset: ByteArray,
        lane: Channel<TermPiece>,
    ) {
        try {
            GlossaryPacker(preset).use { packer ->
                var packed = emptyList<TermEntity>()
                reader.forEachTermChunk(
                    bank,
                    READER_CHUNK_SIZE,
                    onRead = { bytes ->
                        currentCoroutineContext().ensureActive()
                        lane.send(TermPiece(packed, bytes))
                        packed = emptyList()
                    },
                ) { chunk ->
                    val start = System.nanoTime()
                    packed = chunk.map { it.toEntity(dictionaryId, packer) }
                    packNanos.addAndGet(System.nanoTime() - start)
                }
            }
            lane.close()
        } catch (e: CancellationException) {
            lane.cancel()
            throw e
        } catch (e: Throwable) {
            lane.close(e)
        }
    }

    private suspend inline fun timedWait(block: () -> Boolean): Boolean {
        val start = System.nanoTime()
        return block().also { waitNanos += System.nanoTime() - start }
    }

    private inline fun timedInsert(block: () -> Unit) {
        val start = System.nanoTime()
        block()
        insertNanos += System.nanoTime() - start
    }

    private fun YomitanTerm.toEntity(dictionaryId: Long, packer: GlossaryPacker) =
        TermEntity(
            dictionaryId = dictionaryId,
            expression = expression,
            reading = reading,
            definitionTags = definitionTags,
            rules = rules,
            score = score,
            glossary = packer.pack(glossary),
            sequence = sequence,
            termTags = termTags,
        )

    private fun YomitanKanji.toEntity(dictionaryId: Long) =
        KanjiEntity(
            dictionaryId = dictionaryId,
            character = character,
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = tags,
            meanings = JsonArray(meanings.map(::JsonPrimitive)).toString(),
            stats = JsonObject(stats.mapValues { JsonPrimitive(it.value) }).toString(),
        )

    private fun YomitanTermMeta.toEntity(dictionaryId: Long) =
        TermMetaEntity(
            dictionaryId = dictionaryId,
            expression = expression,
            reading = reading,
            kind = when (kind) {
                TermMetaKind.FREQUENCY -> TermMetaEntity.Kind.FREQUENCY
                TermMetaKind.PITCH -> TermMetaEntity.Kind.PITCH
            },
            value = value,
            display = display,
            pitches = pitches.takeIf { it.isNotEmpty() }?.joinToString(","),
        )

    /** A chunk of [entities] read and packed, and how many bytes of its bank were read up to its end. */
    private class TermPiece(val entities: List<TermEntity>, val bytesRead: Long)

    /** Ends [samplePreset]'s read once it has its chunk. */
    private class PresetSampled : RuntimeException(null, null, false, false)

    private companion object {
        const val TAG = "DictionaryImporter"

        /** Banks read at the same time at most; the writer is the bottleneck, so more would only cost memory. */
        const val MAX_READERS = 4

        /** Heap a reader is allowed for: phones with a small heap read fewer banks at once. */
        const val HEAP_PER_READER = 64L * 1024 * 1024

        /** Chunks a bank may have read ahead of the writer. */
        const val LANE_CAPACITY = 1

        /** Rows per transaction: large enough to be fast, small enough to keep the WAL small. */
        const val CHUNK_SIZE = 5_000

        /** Terms a reader holds unpacked: glossaries are large, and [MAX_READERS] hold this many at once. */
        const val READER_CHUNK_SIZE = 1_000
    }
}
