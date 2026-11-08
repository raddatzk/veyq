package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The web's `livePoller.test.ts` cadence rules, on virtual time: interval after settling, backoff, skip, catch-up, jitter. */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePollTest {
    private fun TestScope.poll(random: Double = 0.5) = LivePoll(now = { currentTime }, random = { random })

    /** Runs [p] with [outcomes] in order (the last repeated), each tick taking [tickMs]; returns the tick start times. */
    private fun TestScope.ticks(p: LivePoll, until: Long, tickMs: Long = 0, outcomes: List<LivePoll.Tick> = listOf(LivePoll.Tick.DONE)): List<Long> {
        val starts = mutableListOf<Long>()
        val job = launch {
            p.run {
                starts += currentTime
                if (tickMs > 0) delay(tickMs)
                val o = outcomes[minOf(starts.size - 1, outcomes.size - 1)]
                if (o == LivePoll.Tick.FAILED && starts.size % 2 == 0) error("a tick that throws counts as failed")
                o
            }
        }
        advanceTimeBy(until)
        runCurrent()
        job.cancel()
        return starts
    }

    @Test
    fun a_screen_just_opened_waits_the_interval_and_the_next_tick_counts_from_the_settle() = runTest {
        assertEquals(listOf(20_000L, 45_000L, 70_000L), ticks(poll(), 75_000, tickMs = 5_000))
    }

    @Test
    fun failures_double_up_to_two_minutes_and_a_success_is_back_to_the_interval() = runTest {
        val f = LivePoll.Tick.FAILED
        val starts = ticks(poll(), 600_000, outcomes = listOf(f, f, f, f, LivePoll.Tick.DONE))
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(40_000L, 80_000L, 120_000L, 120_000L, 20_000L, 20_000L), gaps.take(6))
    }

    @Test
    fun a_skip_asks_again_after_the_interval_and_keeps_the_failures() = runTest {
        val p = poll()
        assertEquals(40_000L, p.after(LivePoll.Tick.FAILED))
        assertEquals(20_000L, p.after(LivePoll.Tick.SKIP))
        assertEquals(80_000L, p.after(LivePoll.Tick.FAILED))
        assertEquals(20_000L, p.after(LivePoll.Tick.DONE))
        assertEquals(40_000L, p.after(LivePoll.Tick.FAILED))
    }

    @Test
    fun a_screen_shown_again_catches_up_at_once_unless_a_tick_just_started() = runTest {
        val p = poll()
        assertEquals(listOf(20_000L), ticks(p, 21_000))
        // Hidden at 21 s, shown again at 30 s: more than the gap since the last start, so it ticks now.
        advanceTimeBy(9_000)
        assertEquals(listOf(30_000L, 50_000L), ticks(p, 21_000))
        // Shown again within the gap of the last start: it waits the interval.
        assertEquals(listOf(71_000L), ticks(p, 20_000).take(1))
    }

    @Test
    fun the_delay_is_spread_by_the_jitter() = runTest {
        assertEquals(16_000L, poll(random = 0.0).spread(20_000))
        assertEquals(24_000L, poll(random = 1.0).spread(20_000))
        assertEquals(20_000L, poll(random = 0.5).spread(20_000))
    }
}
