package me.raddatz.veyq.core.config

import io.quarkus.scheduler.Scheduler
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the `%test` scheduler **halted** (`application-test.properties`): no `@Scheduled` job fires on its own in
 * the suite, and every job body runs only when a test calls its `run()`.
 *
 * Why it matters: Quarkus' simple scheduler fires every `every = …` job at its first trigger check, about a second
 * after **each** application start, and the suite starts the app once per `@TestProfile` (~27 times). Each job
 * then ran ~27 times on worker threads against the JVM-shared H2 while tests ran — the session purge was killed
 * mid-delete at nearly every profile switch (`SQLState 57014`), and a startup-fired `StoreReclaimSweep` was the
 * only thing that could delete the reclaim row `MediaBudgetBranchSliceTest` asserts on (inbox #182).
 *
 * The three assertions run in order, and each says something the others cannot:
 * 1. `isStarted()` tells *halted* apart from *disabled* (`quarkus.scheduler.enabled=false` is not started at all).
 * 2. A non-empty `getScheduledJobs()` proves the triggers were built, so a malformed `every` interval still fails
 *    startup — the validation `enabled=false` would drop.
 * 3. `isRunning()` false is the halt itself.
 */
@QuarkusTest
class ScheduledJobsHaltedInTestTest {

    @Inject
    lateinit var scheduler: Scheduler

    @Test
    fun theTestSchedulerIsStartedWithItsTriggersBuiltButFiresNothing() {
        assertTrue(scheduler.isStarted, "the scheduler is started (halted), not disabled")
        assertTrue(scheduler.scheduledJobs.isNotEmpty(), "the @Scheduled triggers were built, so their intervals were validated")
        assertFalse(scheduler.isRunning, "no @Scheduled job fires on its own in %test")
    }
}
