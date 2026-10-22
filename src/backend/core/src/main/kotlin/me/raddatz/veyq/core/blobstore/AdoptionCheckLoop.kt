package me.raddatz.veyq.core.blobstore

import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant

/**
 * The check the server runs itself on the bundled store it adopted (ADR 0089 decision 3), **retried while the outside
 * world can still fix it**. A first boot's check races what the deployment brings up beside the app: the proxy picking
 * up the new route, the domain's first certificate, DNS. So a failure at [RETRYABLE] — the steps that travel the
 * public endpoint — is tried again on a backing-off schedule within [budget]; one at any other step (credentials,
 * provisioning, the bucket) is the deployment's own configuration, which waiting does not fix, and ends the loop.
 *
 * It also ends as soon as the pending setting is no longer the bundled one it adopted ([stillAdopted] false, or a
 * check that found something else pending): the Server Owner saved, discarded, or checked it into use, and their
 * act is the one that counts. Each attempt is the ordinary check ([run]'s `check`), so the console shows the latest
 * attempt exactly as an owner's check would; when the budget runs out, *Check store* is how to try again.
 *
 * One log line per finished check (decision 6): the pass, or the step that failed and whether it is tried again.
 *
 * A class of its own whose clock and sleep are injected, so `AdoptionCheckLoopTest` runs every ending without a
 * store, a boot or the real schedule — the boot worker that calls it does not run under `%test`.
 */
class AdoptionCheckLoop(
    private val clock: () -> Instant = Instant::now,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val budget: Duration = BUDGET,
    private val firstDelay: Duration = FIRST_DELAY,
    private val maxDelay: Duration = MAX_DELAY,
) {
    sealed interface Outcome {
        data object Passed : Outcome

        /** Failed at a step waiting cannot fix; not tried again. */
        data class NotRetryable(val step: StoreCheckStep?) : Outcome

        /** Still failing at [step] when the budget ran out. */
        data class OutOfBudget(val step: StoreCheckStep) : Outcome

        /** The pending setting is no longer the adopted bundled store. */
        data object Superseded : Outcome

        /** Shutdown, while waiting for the next attempt. */
        data object Interrupted : Outcome
    }

    /**
     * Run [check] until it passes or the loop ends. [check] answers null when it found the adopted setting no longer
     * pending (and so checked nothing).
     */
    fun run(stillAdopted: () -> Boolean, check: () -> StoreCheckResult?): Outcome {
        val deadline = clock().plus(budget)
        var delay = firstDelay
        while (true) {
            if (!stillAdopted()) return Outcome.Superseded
            val result = check() ?: return Outcome.Superseded
            if (result.passed) {
                log.info("The bundled store passed its check and is in use.")
                return Outcome.Passed
            }
            val failed = result.steps.firstOrNull { it.outcome == StoreCheckOutcome.FAILED }
            if (failed == null || failed.step !in RETRYABLE) {
                log.errorf(
                    "The bundled store failed its check at %s (%s) — not tried again: the deployment's own configuration " +
                        "is wrong. It stays pending; check it again from Server → Object store once that is fixed.",
                    failed?.step, failed?.detail,
                )
                return Outcome.NotRetryable(failed?.step)
            }
            if (clock().plus(delay).isAfter(deadline)) {
                log.errorf(
                    "The bundled store failed its check at %s (%s) and is not tried again: the retry budget of %s is spent. " +
                        "It stays pending; check it again from Server → Object store, or restart the app.",
                    failed.step, failed.detail, budget,
                )
                return Outcome.OutOfBudget(failed.step)
            }
            log.warnf("The bundled store failed its check at %s (%s); trying again in %s.", failed.step, failed.detail, delay)
            try {
                sleep(delay)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome.Interrupted
            }
            delay = minOf(delay.multipliedBy(2), maxDelay)
        }
    }

    companion object {
        /** The steps that cross the public endpoint — the proxy, the certificate, DNS — and so can start passing. */
        val RETRYABLE = setOf(StoreCheckStep.PROBE_PUT, StoreCheckStep.PROBE_GET, StoreCheckStep.CORS)

        /**
         * About ten minutes (cr-bundled-store-adopts-itself Q2): ACME's first certificate and Traefik's file watch
         * normally take seconds to a minute, DNS still propagating can take longer. The provisioning's own two minutes
         * are too short for a certificate.
         */
        val BUDGET: Duration = Duration.ofMinutes(10)
        val FIRST_DELAY: Duration = Duration.ofSeconds(10)
        val MAX_DELAY: Duration = Duration.ofSeconds(60)

        private val log = Logger.getLogger(AdoptionCheckLoop::class.java)
    }
}
