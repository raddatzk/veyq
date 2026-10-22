package me.raddatz.veyq.core.blobstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Every way the adoption's own check ends (ADR 0089 decision 3; [AdoptionCheckLoop] says which steps it retries), on a
 * clock the sleep advances, so the ten-minute budget runs in no time and each ending is read off how many checks ran
 * and how long the loop waited. Each negative is read against a positive control on the same loop: a check that
 * passes on its third attempt, which proves the retry runs at all.
 */
class AdoptionCheckLoopTest {

    private var now = Instant.parse("2026-10-22T12:00:00Z")
    private val slept = mutableListOf<Duration>()
    private val loop = AdoptionCheckLoop(clock = { now }, sleep = { slept += it; now = now.plus(it) })

    @Test
    fun aCheckThatStartsPassingStopsTheLoopOnThePass() {
        // The positive control: the proxy, the certificate or DNS caught up after two failed probes.
        val checks = Checks(fail(StoreCheckStep.PROBE_PUT), fail(StoreCheckStep.CORS), pass())
        assertEquals(AdoptionCheckLoop.Outcome.Passed, loop.run({ true }, checks::next))
        assertEquals(3, checks.ran)
        assertEquals(listOf(Duration.ofSeconds(10), Duration.ofSeconds(20)), slept, "backing off from 10 s")
    }

    @Test
    fun aStepWaitingCannotFixIsNotTriedAgain() {
        for (step in listOf(StoreCheckStep.CREDENTIALS, StoreCheckStep.PROVISION, StoreCheckStep.BUCKET)) {
            val checks = Checks(fail(step), pass())
            assertEquals(AdoptionCheckLoop.Outcome.NotRetryable(step), loop.run({ true }, checks::next), "$step")
            assertEquals(1, checks.ran, "$step: one check, no retry")
        }
        assertEquals(emptyList<Duration>(), slept)
    }

    @Test
    fun aChangedPendingSettingStopsTheLoopBeforeTheNextCheck() {
        val checks = Checks(fail(StoreCheckStep.PROBE_PUT), pass())
        var adopted = true
        val outcome = loop.run({ adopted }, { checks.next().also { adopted = false } })
        assertEquals(AdoptionCheckLoop.Outcome.Superseded, outcome)
        assertEquals(1, checks.ran, "the Server Owner saved after the first check: no second one")
    }

    @Test
    fun aCheckThatFoundSomethingElsePendingStopsTheLoop() {
        assertEquals(AdoptionCheckLoop.Outcome.Superseded, loop.run({ true }, { null }))
        assertEquals(emptyList<Duration>(), slept)
    }

    @Test
    fun aCheckStillFailingWhenTheBudgetIsSpentEndsTheLoop() {
        val checks = Checks(*Array(100) { fail(StoreCheckStep.PROBE_GET) })
        assertEquals(AdoptionCheckLoop.Outcome.OutOfBudget(StoreCheckStep.PROBE_GET), loop.run({ true }, checks::next))
        // 10 + 20 + 40 + 60 × 8 = 550 s, and the next 60 s wait would end past the 600 s budget.
        assertEquals(listOf(10L, 20L, 40L) + List(8) { 60L }, slept.map { it.seconds })
        assertEquals(12, checks.ran)
    }

    @Test
    fun aShutdownWhileWaitingEndsTheLoop() {
        val interrupting = AdoptionCheckLoop(clock = { now }, sleep = { throw InterruptedException() })
        assertEquals(AdoptionCheckLoop.Outcome.Interrupted, interrupting.run({ true }, Checks(fail(StoreCheckStep.PROBE_PUT), pass())::next))
        Thread.interrupted() // clear the flag the loop re-set, so it does not leak into the next test
    }

    private class Checks(vararg val results: StoreCheckResult) {
        var ran = 0
        fun next(): StoreCheckResult = results[ran++]
    }

    private fun pass() = StoreCheckResult(true, now, StoreCheckStep.entries.map { StoreCheckStepResult(it, StoreCheckOutcome.PASSED, null) })

    private fun fail(at: StoreCheckStep) = StoreCheckResult(
        false, now,
        StoreCheckStep.entries.map {
            val outcome = when {
                it < at -> StoreCheckOutcome.PASSED
                it == at -> StoreCheckOutcome.FAILED
                else -> StoreCheckOutcome.SKIPPED
            }
            StoreCheckStepResult(it, outcome, if (it == at) "no answer" else null)
        },
    )
}
