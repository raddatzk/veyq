package me.raddatz.veyq.mobile.audio

import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The web's `core/audio/order.test.ts`, case for case (ADR 0135 decision 6, ADR 0142 decision 1): shuffled, endless, never
 * the same track twice across a round's edge.
 */
class OrderTest {
    private val random = randomIntOf(testPrimitives::randomBytes)
    private fun ids(n: Int) = List(n) { "t$it" }

    @Test
    fun aRoundIsAPermutationOfTheQueue() {
        for (n in 1..7) assertEquals(ids(n).sorted(), nextRound(ids(n), null, random).sorted())
    }

    @Test
    fun acrossAThousandRoundsOfTwoToFiveTracksARoundsFirstIsNeverTheLastOnePlayed() {
        for (n in 2..5) {
            val order = PlayOrder(ids(n), random)
            var last = order.current()
            for (played in 1 until n * 1000) {
                val next = order.next()
                if (played % n == 0) assertNotEquals(last, next, "round ${played / n} of $n")
                last = next
            }
        }
    }

    @Test
    fun eachRoundPlaysEveryTrackOnceThenAFreshRoundFollows() {
        val order = PlayOrder(ids(4), random)
        val first = listOf(order.current(), order.next(), order.next(), order.next())
        val second = listOf(order.next(), order.next(), order.next(), order.next())
        assertEquals(ids(4), first.map { it!! }.sorted())
        assertEquals(ids(4), second.map { it!! }.sorted())
    }

    @Test
    fun aSingleTrackPlaysAgainAndAgain() {
        val order = PlayOrder(listOf("only"), random)
        assertEquals(listOf("only", "only", "only"), listOf(order.current(), order.next(), order.next()))
    }

    @Test
    fun aStartTrackPlaysFirstTheRestOfItsRoundShuffled() {
        repeat(50) {
            val order = PlayOrder(ids(5), random, "t3")
            val round = listOf(order.current(), order.next(), order.next(), order.next(), order.next())
            assertEquals("t3", round[0])
            assertEquals(ids(5), round.map { it!! }.sorted())
        }
    }

    @Test
    fun shufflesByTheRandomnessHandedInFisherYates() {
        assertEquals(listOf("b", "c", "a"), shuffled(listOf("a", "b", "c")) { 0 })
        assertEquals(listOf("a", "b", "c"), shuffled(listOf("a", "b", "c")) { n -> n - 1 })
    }

    @Test
    fun theSuitesRandomnessStaysInRange() {
        repeat(1000) {
            val v = random(3)
            assertTrue(v in 0..2)
        }
        // and reaches every value: a draw that is not uniform would rarely miss one, a broken one always
        assertEquals(setOf(0, 1, 2, 3, 4), List(500) { random(5) }.toSet())
        assertEquals(0, random(1))
    }

    @Test
    fun theSuitesRandomnessRejectsTheBiasedTailOfAWord() {
        // 0xFFFFFFFF lies past the largest multiple of 3 below 2^32: drawn again, so the next word decides
        val words = ArrayDeque(listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0, 0, 0, 5)))
        assertEquals(2, randomIntOf { words.removeFirst() }(3))
        assertTrue(words.isEmpty())
    }

    @Test
    fun previousAtTwoSecondsGoesBackToTheTrackPlayedBeforeAtFourItRestartsTheCurrentOne() {
        val order = PlayOrder(ids(4), random)
        val a = order.current()
        val b = order.next()
        assertEquals(PreviousStep.Restart, order.previous(4000))
        assertEquals(b, order.current())
        assertEquals(PreviousStep.Back(a!!), order.previous(2000))
        assertEquals(a, order.current())
        // nothing before the first: previous restarts it
        assertEquals(PreviousStep.Restart, order.previous(2000))
        // and next goes forward through what was played again
        assertEquals(b, order.next())
    }

    @Test
    fun peekFixesTheNextTrackWithoutGoingThereNextThenGoesToIt() {
        val order = PlayOrder(ids(5), random)
        val peeked = order.peek()
        assertEquals(peeked, order.peek())
        assertEquals(peeked, order.next())
    }

    @Test
    fun aDroppedTrackLeavesEveryRoundAndTheHistoryTheQueueEmptiesWhenNoneIsLeft() {
        val order = PlayOrder(listOf("a", "b", "c"), { 0 })
        val first = order.current()!!
        order.drop(first)
        assertEquals(2, order.size)
        val rest = listOf(order.next(), order.next(), order.next(), order.next())
        assertFalse(first in rest)
        for (id in listOf("a", "b", "c")) order.drop(id)
        assertEquals(0 to null, order.size to order.next())
    }
}
