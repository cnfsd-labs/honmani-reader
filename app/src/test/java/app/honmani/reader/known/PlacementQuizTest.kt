package app.honmani.reader.known

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementQuizTest {
    @Test
    fun sampledRanksAreSortedUniqueAndInRange_andSpreadOverTheLogScale() {
        val ranks = PlacementQuiz.sampleRanks(15000, Random(1))

        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct(), ranks)
        assertTrue(ranks.size in 40..PlacementQuiz.QUESTIONS)
        assertTrue(ranks.all { it in PlacementQuiz.FIRST_RANK..15000 })
        // Log-spread: about as many in 30..300 as in 1500..15000, none of it all in the top.
        assertTrue(ranks.count { it < 300 } in 10..20)
        assertTrue(ranks.count { it >= 1500 } in 10..20)
    }

    @Test
    fun aTooShortListAsksNothing() {
        assertTrue(PlacementQuiz.sampleRanks(10, Random(1)).isEmpty())
    }

    @Test
    fun estimateFindsTheFlip() {
        val answers = listOf(50, 100, 400, 1000, 3000).map { QuizAnswer(it, true) } +
            listOf(5000, 9000, 14000).map { QuizAnswer(it, false) }

        // Between the last known (3000) and the first unknown (5000): the geometric middle.
        assertEquals(3873, PlacementQuiz.estimate(answers, 15000))
    }

    @Test
    fun estimateIgnoresOneStrayAnswer() {
        // A lucky known word at 12000 and a missed one at 200 do not move the cut far.
        val answers = listOf(
            QuizAnswer(50, true), QuizAnswer(200, false), QuizAnswer(400, true), QuizAnswer(1000, true),
            QuizAnswer(3000, true), QuizAnswer(5000, false), QuizAnswer(9000, false), QuizAnswer(12000, true), QuizAnswer(14000, false),
        )

        val estimate = PlacementQuiz.estimate(answers, 15000)!!

        assertTrue("was $estimate", estimate in 3000..5000)
    }

    @Test
    fun allKnownReachesTheEnd_noneKnownStaysNearTheStart() {
        val ranks = listOf(30, 200, 2000, 14000)

        assertTrue(PlacementQuiz.estimate(ranks.map { QuizAnswer(it, true) }, 15000)!! in 14000..15000)
        assertTrue(PlacementQuiz.estimate(ranks.map { QuizAnswer(it, false) }, 15000)!! < 30)
    }

    @Test
    fun noAnswersNoEstimate() {
        assertNull(PlacementQuiz.estimate(emptyList(), 15000))
    }

    @Test
    fun tidyRoundsToAFriendlyStep() {
        assertEquals(10, PlacementQuiz.tidy(3))
        assertEquals(1100, PlacementQuiz.tidy(1149))
        assertEquals(3900, PlacementQuiz.tidy(3873))
        assertEquals(450, PlacementQuiz.tidy(441))
    }

    @Test
    fun steppingKeepsTheFullListRanks_soTheEstimateIsNotSkewed() = kotlinx.coroutines.test.runTest {
        // Every k-th word of the list is a function word that is never asked; the reader knows exactly the top K.
        val lines = StringBuilder()
        // Every 2nd, every 3rd, and mostly function words among the 500 most common (only every 5th is askable there).
        val patterns = listOf<Pair<String, (Int) -> Boolean>>(
            "every 2nd" to { rank -> rank % 2 != 0 },
            "every 3rd" to { rank -> rank % 3 != 0 },
            "top 500 mostly" to { rank -> rank > 500 || rank % 5 == 0 },
        )
        for ((name, isAskable) in patterns) {
            for (known in listOf(150, 400, 1200, 3000, 7000, 12000)) {
                val estimates = (1..20).map { seed ->
                    val drawn = PlacementQuiz.sampleRanks(15000, Random(seed))
                    val asked = PlacementQuiz.stepToAskable(drawn, 15000) { batch -> batch.map(isAskable) }
                    assertTrue(asked.all(isAskable))
                    PlacementQuiz.estimate(asked.map { QuizAnswer(it, it <= known) }, 15000)!!
                }
                val worst = estimates.maxOf { kotlin.math.abs(it - known) / known.toDouble() }
                val mean = estimates.average()
                lines.append("$name, K=$known: mean estimate ${mean.toInt()}, worst error ${(worst * 100).toInt()}%\n")
                assertTrue("$name K=$known: worst error $worst", worst <= 0.25)
            }
        }
        println(lines)
    }

    @Test
    fun steppingMovesToTheNearestAskableRankAndUsesARankOnce() = kotlinx.coroutines.test.runTest {
        // 100 and 101 are not askable; 102 is taken by the first draw, so the second goes on to 103.
        val asked = PlacementQuiz.stepToAskable(listOf(100, 101), 1000) { batch -> batch.map { it >= 102 } }
        assertEquals(listOf(102, 103), asked)
        // Nothing askable within reach: dropped. Past the end of the list: dropped.
        assertEquals(emptyList<Int>(), PlacementQuiz.stepToAskable(listOf(50), 1000, maxStep = 5) { batch -> batch.map { false } })
        assertEquals(emptyList<Int>(), PlacementQuiz.stepToAskable(listOf(1000), 1000) { batch -> batch.map { false } })
    }
}
