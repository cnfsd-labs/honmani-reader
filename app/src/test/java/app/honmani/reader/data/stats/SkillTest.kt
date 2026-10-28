package app.honmani.reader.data.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillTest {
    private val today = LocalDate.of(2026, 10, 21)

    private fun day(daysAgo: Long, book: Long, seconds: Long, characters: Long) =
        ReadingDayEntity(book, today.minusDays(daysAgo).toString(), seconds, characters)

    private val grades = mapOf(1L to 50, 2L to 60)

    @Test
    fun readingAtTheReferenceSpeedCountsAsTheBooksDifficulty_doubleSpeedTenMore() {
        val estimate = Skill.estimate(
            listOf(day(1, 1, 1_000, 2_000), day(2, 1, 800, 2_000)),
            grades::get,
            today,
        )
        assertEquals(50, estimate.current)

        val faster = Skill.estimate(listOf(day(1, 2, 3_600, 16_000)), grades::get, today)
        assertEquals(70, faster.current)
    }

    @Test
    fun recentWeeksCountMore_andTheTrendShowsEachWeek() {
        val estimate = Skill.estimate(
            listOf(
                // This week: level 50 for half an hour.
                day(0, 1, 1_800, 4_000),
                // Two weeks ago: level 70 for an hour, weighed down by its age.
                day(15, 2, 3_600, 16_000),
            ),
            grades::get,
            today,
        )

        assertEquals(62, estimate.current)
        assertEquals(12, estimate.trend.size)
        assertEquals(today, estimate.trend.last().weekEnd)
        assertEquals(listOf(70, 70, 62), estimate.trend.takeLast(3).map { it.skill })
        assertNull(estimate.trend[8].skill)
    }

    @Test
    fun aRereadDoesntRaiseTheFluency_butStillCountsAsReading() {
        val rows = listOf(
            // First read of a 20,000-character level-50 book, three weeks ago: 8,000 an hour.
            day(21, 1, 9_000, 20_000),
            // Started over this week and read twice as fast: the story is remembered.
            day(1, 1, 3_600, 16_000),
        )

        val estimate = Skill.estimate(rows, grades::get, today, length = mapOf(1L to 20_000L)::get)

        assertEquals(50, estimate.current)
        assertEquals(12_600, estimate.gradedSeconds)
        assertEquals(36_000, estimate.gradedCharacters)
        // Without the book's length every read counts, the reread at level 60.
        assertEquals(54, Skill.estimate(rows, grades::get, today).current)
    }

    @Test
    fun theDayAFirstReadEnds_onlyItsShareUpToTheBooksLengthCounts() {
        val rows = listOf(
            day(3, 1, 3_600, 8_000),
            // 2,000 + 10% slack (2,000) of these 8,000 characters finish the first read.
            day(2, 1, 3_600, 8_000),
            // Still re-reading: time without characters counts no more.
            day(1, 1, 600, 0),
        )

        val estimate = Skill.estimate(rows, grades::get, today, length = mapOf(1L to 10_000L)::get)

        // 12,000 characters in 1 h 30 min: 8,000 an hour, level 50.
        assertEquals(50, estimate.current)
        assertEquals(7_800, estimate.gradedSeconds)
    }

    @Test
    fun ungradedBooksAndShortReadingDontCount() {
        val estimate = Skill.estimate(
            listOf(day(0, 3, 7_200, 40_000), day(0, 1, 300, 2_000), day(10, 1, 400, 1_000)),
            grades::get,
            today,
        )

        assertEquals(700, estimate.gradedSeconds)
        // Neither week of book 1 reaches ten minutes, and the total is under half an hour.
        assertNull(estimate.current)
        assertEquals(listOf<Int?>(null), estimate.trend.map { it.skill }.distinct())
    }

    @Test
    fun booksThatFitAreAStretch_notTooFar() {
        val books = listOf(10L to 40, 11L to 47, 12L to 52, 13L to 58, 14L to 70)
        assertEquals(listOf(12L, 11L, 13L), Skill.fitting(books, skill = 50))
    }

    @Test
    fun vocabularyJoinsWithMoreReading() {
        val vocabulary = VocabularySkill(70, VocabularyEstimate(words = 30_000, met = 5_000, learning = 300))
        fun estimate(characters: Long) = SkillEstimate(50, emptyList(), 7_200, gradedCharacters = characters)

        // Too little read: fluency alone.
        assertEquals(null, Skill.combine(estimate(10_000), vocabulary).vocabulary)
        assertEquals(50, Skill.combine(estimate(10_000), vocabulary).current)
        // Half the full weight at 50,000 characters: 0.3 · 70 + 0.7 · 50.
        assertEquals(56, Skill.combine(estimate(50_000), vocabulary).current)
        // Full weight: 0.6 · 70 + 0.4 · 50.
        assertEquals(62, Skill.combine(estimate(500_000), vocabulary).current)
        assertEquals(50, Skill.combine(estimate(500_000), vocabulary).fluency)
    }
}
