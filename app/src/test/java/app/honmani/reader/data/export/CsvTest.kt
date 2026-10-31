package app.honmani.reader.data.export

import app.honmani.reader.data.stats.DayTotal
import app.honmani.reader.data.words.WordEntity
import app.honmani.reader.data.words.WordStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CsvTest {
    @Test
    fun plainFieldsAreNotQuoted() {
        assertEquals("a,b,c\r\n", Csv.write(listOf(listOf("a", "b", "c"))))
    }

    @Test
    fun commasQuotesAndLineBreaksAreQuoted() {
        assertEquals("\"a,b\"", Csv.quote("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.quote("say \"hi\""))
        assertEquals("\"line\nbreak\"", Csv.quote("line\nbreak"))
        assertEquals("\"line\r\nbreak\"", Csv.quote("line\r\nbreak"))
    }

    @Test
    fun spacesAtTheEdgesAreKept() {
        assertEquals("\" a\"", Csv.quote(" a"))
        assertEquals("\"a \"", Csv.quote("a "))
        assertEquals("a b", Csv.quote("a b"))
    }

    @Test
    fun emptyFieldsStayEmpty() {
        assertEquals(",,\r\n", Csv.write(listOf(listOf("", "", ""))))
    }

    @Test
    fun japaneseTextIsWrittenAsIs_andItsCommaIsNotASeparator() {
        assertEquals("食べる,たべる,\"to eat, to live on\"\r\n", Csv.write(listOf(listOf("食べる", "たべる", "to eat, to live on"))))
        assertEquals("「はい」、と言った。", Csv.quote("「はい」、と言った。"))
        assertFalse(Csv.write(listOf(listOf("あ"))).startsWith("﻿"))
    }

    @Test
    fun wordsAreListedLatestLookupFirst() {
        val zone = ZoneId.of("UTC")
        fun word(expression: String, last: Long, status: WordStatus = WordStatus.LEARNING) = WordEntity(
            expression = expression,
            reading = "よみ",
            gloss = "a, b",
            status = status,
            starred = expression == "犬",
            lookupCount = 2,
            firstLookedUpAt = 0L,
            lastLookedUpAt = last,
            rankKey = 0.0,
            lastSentence = "「$expression」\nだ。",
            lastSurfaceStart = 0,
            lastSurfaceEnd = 1,
            lastBookTitle = "猫, 犬",
            ankiNoteId = if (expression == "猫") 5L else null,
        )

        val lines = CsvExports.words(
            listOf(word("犬", 60_000L), word("猫", 120_000L, WordStatus.LEARNED)),
            zone,
        )

        assertEquals(
            "expression,reading,meaning,status,starred,lookups,first_looked_up,last_looked_up,book,sentence,in_anki\r\n" +
                "猫,よみ,\"a, b\",learned,no,2,1970-01-01 00:00,1970-01-01 00:02,\"猫, 犬\",\"「猫」\nだ。\",yes\r\n" +
                "犬,よみ,\"a, b\",learning,yes,2,1970-01-01 00:00,1970-01-01 00:01,\"猫, 犬\",\"「犬」\nだ。\",no\r\n",
            lines,
        )
    }

    @Test
    fun readingIsListedOldestFirstWithSpeed() {
        val csv = CsvExports.reading(
            listOf(
                DayTotal(LocalDate.of(2026, 3, 2), 0, 0),
                DayTotal(LocalDate.of(2026, 3, 1), 1800, 5000),
            ),
        )

        assertEquals(
            "date,seconds,characters,characters_per_hour\r\n2026-03-01,1800,5000,10000\r\n2026-03-02,0,0,\r\n",
            csv,
        )
    }
}
