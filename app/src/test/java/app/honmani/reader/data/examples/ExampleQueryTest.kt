package app.honmani.reader.data.examples

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleQueryTest {
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        // The layout of scripts/examples/build_examples.py.
        db.execSQL("create table sentence(id integer primary key, ja text not null, en text not null)")
        db.execSQL(
            "create table word(word text not null, reading text not null, sentence_id integer not null," +
                " rank integer not null, primary key(word, reading, sentence_id)) without rowid",
        )
    }

    @After
    fun tearDown() = db.close()

    private fun sentence(id: Int) = db.execSQL("insert into sentence values($id, 'ja$id', 'en$id')")

    private fun word(word: String, reading: String, id: Int, rank: Int = 0) =
        db.execSQL("insert into word values('$word', '$reading', $id, $rank)")

    private fun found(expression: String, reading: String, limit: Int = ExampleQuery.LIMIT) =
        ExampleQuery.find(db, expression, reading, limit).map { it.japanese }

    @Test
    fun aWordFindsItsSentencesInTheFilesOrder() {
        (1..3).forEach { sentence(it) }
        word("猫", "ねこ", 3, rank = 0)
        word("猫", "ねこ", 1, rank = 2)
        word("猫", "ねこ", 2, rank = 1)

        assertEquals(listOf("ja3", "ja2", "ja1"), found("猫", "ねこ"))
        assertEquals(Example("ja3", "en3"), ExampleQuery.find(db, "猫", "ねこ").first())
        assertTrue(found("犬", "いぬ").isEmpty())
    }

    @Test
    fun theReadingDecidesBetweenHomographs() {
        (1..4).forEach { sentence(it) }
        word("行", "ぎょう", 1)
        word("行く", "いく", 2, rank = 1)
        word("行く", "ゆく", 3, rank = 0)
        word("行く", "", 4, rank = 2)

        // Agreeing reading first, then sentences without a reading, then the other reading.
        assertEquals(listOf("ja2", "ja4", "ja3"), found("行く", "いく"))
        // Katakana readings count as hiragana.
        assertEquals(listOf("ja2", "ja4", "ja3"), found("行く", "イク"))
        assertEquals(listOf("ja3", "ja4", "ja2"), found("行く", "ゆく"))
    }

    @Test
    fun aSentenceWithTheWordTwiceCountsOnce() {
        sentence(1)
        word("猫", "ねこ", 1)
        word("猫", "", 1)

        assertEquals(listOf("ja1"), found("猫", "ねこ"))
    }

    @Test
    fun aKanaOnlyEntryFallsBackToTheReading() {
        sentence(1)
        sentence(2)
        word("為る", "する", 1)
        word("する", "", 2)

        // The kana headword is used when there is one ...
        assertEquals(listOf("ja2"), found("する", "する"))
        db.execSQL("delete from word where word = 'する'")
        // ... and the reading of kanji headwords when there is not.
        assertEquals(listOf("ja1"), found("する", "する"))
        assertEquals(listOf("ja1"), found("する", ""))
        assertEquals(listOf("ja1"), found("", "する"))
    }

    @Test
    fun anEntryWithKanjiDoesNotFallBackToItsReading() {
        sentence(1)
        word("港", "こう", 1)

        assertTrue(found("構", "こう").isEmpty())
    }

    @Test
    fun atMostTheLimitIsReturned() {
        (1..8).forEach {
            sentence(it)
            word("猫", "ねこ", it, rank = it)
        }

        assertEquals(5, found("猫", "ねこ").size)
        assertEquals(listOf("ja1", "ja2"), found("猫", "ねこ", limit = 2))
    }
}
