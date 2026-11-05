package app.honmani.reader.data.words

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import app.honmani.reader.data.HonmaniDatabase
import kotlinx.coroutines.flow.Flow

/** Longest [KnownBatchEntity.detail] kept. */
const val KNOWN_DETAIL_MAX = 200

/** Where a batch of Known words came from; the reader can undo each batch. */
enum class KnownSource {
    ANKI_DECK,
    WORD_LIST,
    TOP_N,

    /** For the quick quiz, which is not built yet. */
    QUIZ,
}

/** One "Tell Honmani what you already know" action: [count] words it marked as Known. */
@Entity(tableName = "known_batch")
data class KnownBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: KnownSource,
    /** What the reader chose: the deck's name, the file's name, or the top-N size. */
    val detail: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val count: Int,
)

/**
 * A word a batch changed: [created] if the batch added it, otherwise it was [previousStatus] and
 * the batch made it Known. [lookupCount] is the word's lookups at that time, to see on undo that
 * the reader has not looked it up since. Words that were Known already are not in a batch.
 */
@Entity(
    tableName = "known_batch_word",
    primaryKeys = ["batch_id", "word_id"],
    foreignKeys = [
        ForeignKey(KnownBatchEntity::class, ["id"], ["batch_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(WordEntity::class, ["id"], ["word_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["word_id"])],
)
data class KnownBatchWordEntity(
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "word_id") val wordId: Long,
    val created: Boolean,
    @ColumnInfo(name = "previous_status") val previousStatus: WordStatus?,
    @ColumnInfo(name = "lookup_count") val lookupCount: Int,
)

@Dao
interface KnownBatchDao {
    @Query("SELECT * FROM known_batch ORDER BY created_at DESC, id DESC")
    fun observeBatches(): Flow<List<KnownBatchEntity>>

    @Query("SELECT * FROM known_batch WHERE id = :id")
    suspend fun batch(id: Long): KnownBatchEntity?

    @Query("SELECT * FROM known_batch_word WHERE batch_id = :batchId")
    suspend fun words(batchId: Long): List<KnownBatchWordEntity>

    @Insert
    suspend fun insertBatch(batch: KnownBatchEntity): Long

    @Insert
    suspend fun insertWords(words: List<KnownBatchWordEntity>)

    @Query("UPDATE known_batch SET count = :count WHERE id = :id")
    suspend fun setCount(id: Long, count: Int)

    @Query("DELETE FROM known_batch WHERE id = :id")
    suspend fun deleteBatch(id: Long)
}

/** A word to mark as Known: its dictionary form, reading and first meaning. */
data class KnownCandidate(val expression: String, val reading: String, val gloss: String = "")

/** What marking [KnownCandidate]s does (or did): new words, saved words made Known, and words left as they were. */
data class KnownChange(val created: Int = 0, val upgraded: Int = 0, val unchanged: Int = 0) {
    val changed: Int get() = created + upgraded
}

/** What an undo did: words removed again, words put back to learning, words left alone because the reader changed them since. */
data class KnownUndo(val removed: Int = 0, val restored: Int = 0, val leftAlone: Int = 0)

/**
 * Marks many words as Known at once, in batches the reader can undo. A word the reader has not
 * looked up is added as Known with no lookups and no times, so it appears in no session or lookup
 * count; a saved word being learned is made Known (as "Mark as known" does).
 */
class KnownBatchRepository(
    private val database: HonmaniDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.knownBatchDao()
    private val words = database.wordDao()

    /** How many words are Known now. */
    val knownCount: Flow<Int> = words.observeLearnedCount()

    val batches: Flow<List<KnownBatchEntity>> = dao.observeBatches()

    /** What [apply] would do, without doing it. */
    suspend fun preview(candidates: Collection<KnownCandidate>): KnownChange = database.withTransaction {
        var created = 0
        var upgraded = 0
        var unchanged = 0
        for (candidate in distinct(candidates)) {
            val existing = words.find(candidate.expression, candidate.reading)
            when {
                existing == null -> created++
                existing.status == WordStatus.LEARNING && !existing.ignored -> upgraded++
                else -> unchanged++
            }
        }
        KnownChange(created, upgraded, unchanged)
    }

    /** Marks [candidates] as Known in one batch; nothing is saved if none of them changes. */
    suspend fun apply(source: KnownSource, detail: String, candidates: Collection<KnownCandidate>): Pair<Long?, KnownChange> =
        database.withTransaction {
            val rows = ArrayList<KnownBatchWordEntity>()
            var created = 0
            var upgraded = 0
            var unchanged = 0
            val changes = ArrayList<(Long) -> KnownBatchWordEntity>()
            for (candidate in distinct(candidates)) {
                val existing = words.find(candidate.expression, candidate.reading)
                when {
                    existing == null -> {
                        val id = words.insert(
                            WordEntity(
                                expression = candidate.expression,
                                reading = candidate.reading,
                                gloss = candidate.gloss.take(MAX_GLOSS_LENGTH),
                                status = WordStatus.LEARNED,
                                lookupCount = 0,
                                firstLookedUpAt = 0,
                                lastLookedUpAt = 0,
                                rankKey = 0.0,
                                lastSentence = "",
                                lastSurfaceStart = 0,
                                lastSurfaceEnd = 0,
                            ),
                        )
                        changes += { batch -> KnownBatchWordEntity(batch, id, created = true, previousStatus = null, lookupCount = 0) }
                        created++
                    }
                    existing.status == WordStatus.LEARNING && !existing.ignored -> {
                        words.markLearned(existing.id)
                        changes += { batch -> KnownBatchWordEntity(batch, existing.id, created = false, previousStatus = existing.status, lookupCount = existing.lookupCount) }
                        upgraded++
                    }
                    else -> unchanged++
                }
            }
            val change = KnownChange(created, upgraded, unchanged)
            if (changes.isEmpty()) return@withTransaction null to change
            val batch = dao.insertBatch(KnownBatchEntity(source = source, detail = detail.take(KNOWN_DETAIL_MAX), createdAt = now(), count = changes.size))
            changes.mapTo(rows) { it(batch) }
            rows.chunked(CHUNK).forEach { dao.insertWords(it) }
            batch to change
        }

    /**
     * Takes a batch back: words it added are removed and words it made Known go back to learning,
     * but only those the reader has not touched since (looked up, unmarked, ignored, starred, noted,
     * sent to Anki). Those stay as they are and are counted in [KnownUndo.leftAlone]; words that
     * were Known before the batch are not in it. The batch is gone afterwards.
     */
    suspend fun undo(batchId: Long): KnownUndo = database.withTransaction {
        if (dao.batch(batchId) == null) return@withTransaction KnownUndo()
        var removed = 0
        var restored = 0
        var leftAlone = 0
        for (entry in dao.words(batchId)) {
            val word = words.find(entry.wordId)
            val untouched = word != null && word.status == WordStatus.LEARNED && !word.ignored && word.lookupCount == entry.lookupCount
            when {
                !untouched -> leftAlone++
                entry.created -> {
                    if (word!!.starred || word.ankiNoteId != null || word.note.isNotEmpty() || word.furigana.isNotEmpty()) {
                        leftAlone++
                    } else {
                        words.delete(word.id)
                        removed++
                    }
                }
                else -> {
                    words.markLearning(word!!.id)
                    restored++
                }
            }
        }
        dao.deleteBatch(batchId)
        KnownUndo(removed, restored, leftAlone)
    }

    private fun distinct(candidates: Collection<KnownCandidate>): List<KnownCandidate> {
        val seen = HashSet<String>()
        return candidates.filter { it.expression.isNotEmpty() && seen.add(WordRepository.key(it.expression, it.reading)) }
    }

    private companion object {
        const val MAX_GLOSS_LENGTH = 200
        const val CHUNK = 500
    }
}
