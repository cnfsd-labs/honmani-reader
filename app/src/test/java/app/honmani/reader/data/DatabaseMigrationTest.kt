package app.honmani.reader.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Users' data must survive updates: a database made from each exported schema (`app/schemas`)
 * opens with the current code, through [HonmaniDatabase.MIGRATIONS], and keeps its rows.
 *
 * Until the first public release the schema is edited in place (version 1), so there is no
 * migration yet. Changing the schema afterwards: raise the version in [HonmaniDatabase], build once to export the new
 * schema, add the migration to [HonmaniDatabase.MIGRATIONS] and a test here that creates the old
 * version, fills it, runs `helper.runMigrationsAndValidate` and reads the rows back.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HonmaniDatabase::class.java)

    @Test
    fun aVersionOneDatabaseOpensWithTheCurrentCode_andKeepsItsData() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO word (expression, reading, gloss, status, starred, ignored, lookup_count, first_looked_up_at, " +
                    "last_looked_up_at, rank_key, last_sentence, last_surface_start, last_surface_end) " +
                    "VALUES ('猫', 'ねこ', 'cat', 'LEARNING', 1, 0, 3, 1000, 2000, 1.5, '猫がいる。', 0, 1)",
            )
            db.execSQL("INSERT INTO lookup_event (looked_up_at, book_id) VALUES (2000, NULL)")
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HonmaniDatabase::class.java,
            TEST_DB,
        ).addMigrations(*HonmaniDatabase.MIGRATIONS).build()
        try {
            val word = database.wordDao().find("猫", "ねこ")!!
            assertEquals(3, word.lookupCount)
            assertEquals(true, word.starred)
            assertEquals(app.honmani.reader.data.words.WordScheduler.HONMANI, word.scheduler)
            assertEquals(1, database.wordDao().countLookups().first())
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
