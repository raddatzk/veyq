package me.raddatz.veyq.core.admin.instance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The release check's spacing after failures ([ReleaseCheckBackoff]), pure and in ticks: lane L1's rhythm of 5 s backs
 * off by doubling; a success or a changed setting asks at the next tick; the default rhythm of a day is untouched.
 */
class ReleaseCheckBackoffTest {

    private val github = true to null
    private val mirror = true to "https://releases.mirror.example/veyq"

    /** Which of [ticks] ticks ask the source when every attempt fails, as 1/0. */
    private fun asked(backoff: ReleaseCheckBackoff, ticks: Int, setting: Any = github): String =
        (1..ticks).joinToString("") { if (backoff.due(setting)) "1".also { backoff.failed(setting) } else "0" }

    @Test
    fun failuresInARowDoubleTheTicksBetweenAttempts() {
        val backoff = ReleaseCheckBackoff(Duration.ofSeconds(5))
        // Attempts at ticks 1, 2, 4, 8, 16: the wait after each failure is 1, 2, 4, 8 … ticks.
        assertEquals("1101000100000001", asked(backoff, 16))
    }

    @Test
    fun theWaitItLogsIsTheRhythmTimesTheTicks() {
        val backoff = ReleaseCheckBackoff(Duration.ofSeconds(5))
        assertEquals(listOf(5L, 10L, 20L, 40L), (1..4).map { backoff.failed(github).seconds })
    }

    @Test
    fun theWaitStopsGrowingAtTheCap() {
        val backoff = ReleaseCheckBackoff(Duration.ofSeconds(5), cap = Duration.ofSeconds(20))
        assertEquals(listOf(5L, 10L, 20L, 20L, 20L), (1..5).map { backoff.failed(github).seconds })
    }

    @Test
    fun atTheDailyDefaultEveryTickStillAsks() {
        val backoff = ReleaseCheckBackoff(Duration.ofHours(24))
        assertEquals("11111", asked(backoff, 5))
    }

    @Test
    fun aSuccessAsksAgainAtTheNextTick() {
        val backoff = ReleaseCheckBackoff(Duration.ofSeconds(5))
        asked(backoff, 8)
        backoff.succeeded(github)
        assertEquals(true, backoff.due(github))
        assertEquals(Duration.ofSeconds(5), backoff.failed(github), "the count starts over")
    }

    @Test
    fun aSourceJustPointedElsewhereIsReadAtTheNextTick() {
        val backoff = ReleaseCheckBackoff(Duration.ofSeconds(5))
        asked(backoff, 30) // deep in back-off on the unreachable default source
        assertEquals(false, backoff.due(github))
        assertEquals(true, backoff.due(mirror), "the Server Owner's new source is asked at once")
    }
}
