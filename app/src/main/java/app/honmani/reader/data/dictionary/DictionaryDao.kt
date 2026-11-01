package app.honmani.reader.data.dictionary

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary WHERE status = 'READY' ORDER BY priority, title")
    fun observeReady(): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionary WHERE status = 'READY' AND enabled = 1")
    suspend fun enabledReady(): List<DictionaryEntity>

    @Query("SELECT * FROM dictionary WHERE status = 'READY'")
    suspend fun allReady(): List<DictionaryEntity>

    @Query("SELECT * FROM dictionary WHERE title = :title LIMIT 1")
    suspend fun findByTitle(title: String): DictionaryEntity?

    @Query("SELECT * FROM dictionary WHERE id = :id")
    suspend fun findById(id: Long): DictionaryEntity?

    /** Returns the new row id. */
    @Insert
    suspend fun insert(dictionary: DictionaryEntity): Long

    @Query(
        "UPDATE dictionary SET status = 'READY', term_count = :termCount, meta_count = :metaCount, " +
            "kanji_count = :kanjiCount WHERE id = :id",
    )
    suspend fun markReady(id: Long, termCount: Int, metaCount: Int = 0, kanjiCount: Int = 0)

    @Query("UPDATE dictionary SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Gives an update the place of the dictionary it replaces. */
    @Query("UPDATE dictionary SET enabled = :enabled, priority = :priority WHERE id = :id")
    suspend fun setEnabledAndPriority(id: Long, enabled: Boolean, priority: Int)

    /** Its place on the Dictionaries screen and in lookups: lower first. */
    @Query("UPDATE dictionary SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: Long, priority: Int)

    /** The place after all installed dictionaries, for a new one. */
    @Query("SELECT COALESCE(MAX(priority) + 1, 0) FROM dictionary")
    suspend fun nextPriority(): Int

    /** Deletes the dictionary with its terms and tags. */
    @Query("DELETE FROM dictionary WHERE id = :id")
    suspend fun delete(id: Long)

    /** Removes what an interrupted import left behind. */
    @Query("DELETE FROM dictionary WHERE status = 'IMPORTING'")
    suspend fun deleteIncomplete()
}

/** Keeps [TermDao.findByExpressionOrReading] under SQLite's 999 bound parameters. */
const val MAX_LOOKUP_TEXTS = 400

/** Query used by a test to check that lookups use the expression and reading indexes. */
const val LOOKUP_SQL_PLAN_CHECK =
    "EXPLAIN QUERY PLAN SELECT * FROM term WHERE expression IN ('a', 'b') " +
        "UNION ALL SELECT * FROM term WHERE reading IN ('a', 'b')"

/** Sorts after every text that starts with the same prefix (U+10FFFF; SQLite compares UTF-8 bytes). */
const val PREFIX_END = "\uDBFF\uDFFF"

/** A term without its glossary, for ranking search results before loading the few shown. */
data class TermKey(
    val id: Long,
    @ColumnInfo(name = "dictionary_id") val dictionaryId: Long,
    val expression: String,
    val reading: String,
    val score: Double,
)

@Dao
interface TermDao {
    @Insert
    suspend fun insertAll(terms: List<TermEntity>)

    /**
     * Terms whose expression or reading is one of [texts] (at most [MAX_LOOKUP_TEXTS]), from all
     * dictionaries. A term matching both ways appears twice. Written as two branches so that
     * each uses its index; see [LOOKUP_SQL_PLAN_CHECK].
     */
    @Query(
        "SELECT * FROM term WHERE expression IN (:texts) " +
            "UNION ALL SELECT * FROM term WHERE reading IN (:texts)",
    )
    suspend fun findByExpressionOrReading(texts: List<String>): List<TermEntity>

    /**
     * Light rows of terms whose written form is at least [from] and below [to]: with `to` the
     * prefix plus [PREFIX_END], the terms starting with a prefix, on the expression index.
     */
    @Query(
        "SELECT id, dictionary_id, expression, reading, score FROM term " +
            "WHERE expression >= :from AND expression < :to LIMIT :limit",
    )
    suspend fun keysByExpressionRange(from: String, to: String, limit: Int): List<TermKey>

    /** As [keysByExpressionRange], on the reading. */
    @Query(
        "SELECT id, dictionary_id, expression, reading, score FROM term " +
            "WHERE reading >= :from AND reading < :to LIMIT :limit",
    )
    suspend fun keysByReadingRange(from: String, to: String, limit: Int): List<TermKey>

    /** Light rows of terms whose written form matches the GLOB [pattern]. */
    @Query("SELECT id, dictionary_id, expression, reading, score FROM term WHERE expression GLOB :pattern LIMIT :limit")
    suspend fun keysByExpressionPattern(pattern: String, limit: Int): List<TermKey>

    /** Light rows of terms whose reading matches the GLOB [pattern]. */
    @Query("SELECT id, dictionary_id, expression, reading, score FROM term WHERE reading GLOB :pattern LIMIT :limit")
    suspend fun keysByReadingPattern(pattern: String, limit: Int): List<TermKey>

    /** The terms with [ids] (at most [MAX_LOOKUP_TEXTS]). */
    @Query("SELECT * FROM term WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<TermEntity>

    @Insert
    suspend fun insertPreset(preset: GlossaryPresetEntity)

    /** The preset the dictionary's glossaries are packed with; null if it has none. */
    @Query("SELECT preset FROM glossary_preset WHERE dictionary_id = :dictionaryId")
    suspend fun glossaryPreset(dictionaryId: Long): ByteArray?

    @Query("SELECT COUNT(*) FROM term WHERE dictionary_id = :dictionaryId")
    suspend fun countForDictionary(dictionaryId: Long): Int

    @Query("SELECT * FROM term WHERE dictionary_id = :dictionaryId ORDER BY id")
    suspend fun allForDictionary(dictionaryId: Long): List<TermEntity>
}

@Dao
interface TagDao {
    /** A later definition of the same tag name wins, as in Yomitan. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Query("SELECT * FROM tag WHERE dictionary_id = :dictionaryId ORDER BY name")
    suspend fun allForDictionary(dictionaryId: Long): List<TagEntity>
}

@Dao
interface KanjiDao {
    @Insert
    suspend fun insertAll(rows: List<KanjiEntity>)

    /** Entries for [characters] (at most [MAX_LOOKUP_TEXTS]), from all dictionaries. */
    @Query("SELECT * FROM kanji WHERE character IN (:characters)")
    suspend fun find(characters: List<String>): List<KanjiEntity>

    @Query("SELECT * FROM kanji WHERE dictionary_id = :dictionaryId ORDER BY id")
    suspend fun allForDictionary(dictionaryId: Long): List<KanjiEntity>
}

@Dao
interface TermMetaDao {
    @Insert
    suspend fun insertAll(rows: List<TermMetaEntity>)

    /** Frequency and pitch rows for [expressions] (at most [MAX_LOOKUP_TEXTS]), all dictionaries. */
    @Query("SELECT * FROM term_meta WHERE expression IN (:expressions)")
    suspend fun find(expressions: List<String>): List<TermMetaEntity>
}
