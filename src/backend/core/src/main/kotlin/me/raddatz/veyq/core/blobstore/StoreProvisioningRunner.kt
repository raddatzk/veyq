package me.raddatz.veyq.core.blobstore

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fired on the provisioning worker's thread once the boot brought up the bundled hint's Garage for an instance with no
 * store in use ([BootProvisioning.Source.BUNDLED_HINT]) — what `BundledStoreAdoption` observes. An event rather than a
 * call, so this package does not reach into the console's handler.
 */
data object BundledHintProvisioned

/** What the operator can do about the store's bring-up. The wire vocabulary of `GET /api/instance/store-provisioning`. */
enum class StoreProvisioningState {
    /** No bring-up is attempted: an S3-compatible store veyq must not poke, or no store set and none bundled. */
    NOT_ATTEMPTED,

    /** A bring-up is running or waiting to retry. Uploads may fail until it finishes. */
    IN_PROGRESS,

    /** The store is usable: layout, bucket, key, grant and CORS policy are all in place. */
    PROVISIONED,

    /** The bounded retries are exhausted. Every upload and download stays broken until the store is repaired and the app restarted. */
    FAILED,
}

/**
 * Runs the store bring-up **in the background, with a bounded retry, and never gates the boot** (ADR 0067
 * Decision 3), then holds its outcome for `GET /api/instance/store-provisioning` to read.
 *
 * **This is the first non-blocking startup seam in this codebase, and it exists because of a specific
 * incident.** All three existing `StartupEvent` observers are synchronous and *can* fail the boot; that is
 * right for [BlobStoreStartupCheck], whose check is a local, instant, config-only fact. Provisioning is the
 * opposite kind of act — it reaches another container over the network — and a startup observer that can fail
 * on an unreachable dependency is exactly how ADR 0063's ingress projector restart-looped a **first-ever**
 * `docker compose up` of the production stack into serving nothing at all. So [onStart] hands the work to a
 * thread and returns, and its entire body is inside a `catch`: the *cause* is removed by not doing network I/O
 * on the startup thread, and the *coupling* is removed by there being no path from here back to the boot. An
 * instance whose store is not yet usable still answers `/api/setup`.
 *
 * **A named seam rather than an anonymous thread**, for the reason this docstring is long: there was nothing in
 * the tree to copy — `grep -rn '@Startup' src/backend/src/main/kotlin` returns nothing and all three
 * `@Observes StartupEvent` observers are synchronous — so the next person wanting background startup work
 * should find a shape here rather than improvise a second one.
 *
 * **The failure is readable, not only logged.** ADR 0063's projector logs loudly and that is enough because its
 * failure stays invisible until someone adds a tenant; this one's failure reaches the operator as a file that
 * will not upload, which is the experience the read exists to remove.
 *
 * **It does not run in `%test`.** The hermetic slice suite has no network and uses the in-memory stub, so there
 * is nothing to provision — the `LaunchMode.TEST` escape [BlobStoreStartupCheck.hasStore] uses. The bounded
 * retry itself is still covered there, because [runBounded] is a pure function this class merely calls.
 */
@ApplicationScoped
class StoreProvisioningRunner(
    private val provisioners: StoreProvisionerFactory,
    private val bundledHintProvisioned: Event<BundledHintProvisioned>,
) {
    private val log = Logger.getLogger(StoreProvisioningRunner::class.java)
    private val current = AtomicReference(StoreProvisioningState.NOT_ATTEMPTED)

    /**
     * Its own single daemon thread, named. Not `@Scheduled` — which would delay the first attempt by a whole
     * interval, and the operator and the e2e orchestrator both want it at boot — and not a bare `Thread`, so
     * shutdown is [stop]'s job rather than nobody's.
     */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "veyq-store-provisioning").apply { isDaemon = true }
    }

    /** What the Server Owner reads. Never `null`, never blocking: a plain read of the last state reached. */
    fun state(): StoreProvisioningState = current.get()

    // After the default observer priority, so `InstanceStore.seedOnStartup` has committed the instance singleton the
    // adoption locks by the time the worker reaches it.
    fun onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) ev: StartupEvent) {
        // Everything, including the mode check and the factory call, is inside the catch. ADR 0063's lesson is
        // that a catch which starts one line too late covers the only failure that never happens: there, the
        // two calls above `writeAtomically`'s try were the only ones an unwritable directory ever threw on.
        try {
            if (LaunchMode.current() == LaunchMode.TEST) return
            // null ⇒ an S3-compatible store veyq must not poke, or no store to provision at all (no setting and
            // no bundled hint). Both are states the operator reads, not errors.
            val target = provisioners.forBoot() ?: return
            current.set(StoreProvisioningState.IN_PROGRESS)
            worker.execute {
                val state = runBounded(target.provisioner)
                current.set(state)
                // The hint's Garage is up and no store is in use: the server adopts and checks the bundled store
                // (ADR 0089 decision 3) — on this thread, so the boot still waits for none of it.
                if (state == StoreProvisioningState.PROVISIONED && target.source == BootProvisioning.Source.BUNDLED_HINT) {
                    try {
                        bundledHintProvisioned.fire(BundledHintProvisioned)
                    } catch (e: Exception) {
                        log.error("Adopting the bundled store failed; set it in the admin console (Server → Object store)", e)
                    }
                }
            }
        } catch (e: Exception) {
            // Reaching here means the *scheduling* failed, not the provisioning. It is still not the boot's
            // problem — but it is the operator's, so it is reported as the give-up it is rather than left as a
            // permanent IN_PROGRESS that no retry will ever move.
            current.set(StoreProvisioningState.FAILED)
            log.error("Store provisioning could not be started; the store will not be brought up", e)
        }
    }

    /**
     * Record a provisioning the check ran itself, synchronously ([StoreCheck]'s PROVISION step), so the read reports
     * the store as it now is rather than what the boot reached before the setting existed.
     */
    fun recordCheckProvisioning(succeeded: Boolean) {
        current.set(if (succeeded) StoreProvisioningState.PROVISIONED else StoreProvisioningState.FAILED)
    }

    @PreDestroy
    fun stop() {
        worker.shutdownNow()
    }

    companion object {
        /**
         * Deliberately set rather than empirically calibrated. The bound has to outlast a `docker compose up`
         * in which the store container starts beside the app — the worst realistic case it exists for — and it
         * must still end: roughly two minutes of trying, then a state the operator can read.
         */
        const val MAX_ATTEMPTS = 8
        val FIRST_BACKOFF: Duration = Duration.ofSeconds(2)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)

        private val log = Logger.getLogger(StoreProvisioningRunner::class.java)

        /**
         * The bounded retry, and the terminal state it reaches. A pure function of [target] and the policy, so
         * the hermetic suite can exercise the give-up without a container and without waiting out the real
         * backoff — the class around it cannot run under `@QuarkusTest` at all (it skips `%test` on purpose).
         *
         * **The bound is the point** (ADR 0062 Decision 5's shape, one subsystem over): an unbounded retry
         * against a permanently unreachable store is a loop nobody can read the end of, so the give-up is named
         * and becomes the value the read reports.
         */
        fun runBounded(
            target: StoreProvisioner,
            maxAttempts: Int = MAX_ATTEMPTS,
            firstBackoff: Duration = FIRST_BACKOFF,
            maxBackoff: Duration = MAX_BACKOFF,
        ): StoreProvisioningState {
            var delay = firstBackoff
            for (attempt in 1..maxAttempts) {
                try {
                    target.provision()
                    log.infof("Object store provisioned (attempt %d)", attempt)
                    return StoreProvisioningState.PROVISIONED
                } catch (e: Exception) {
                    if (attempt == maxAttempts) {
                        log.errorf(
                            e,
                            "Object store could not be provisioned after %d attempts — media will not work until " +
                                "the store is repaired and then checked (admin console: Server → Object store, or " +
                                "`veyq-admin store check`, which provisions it again) or the app restarted",
                            maxAttempts,
                        )
                        return StoreProvisioningState.FAILED
                    }
                    // `toString()`, not `message`: the commonest failure here is a ConnectException, whose
                    // message is NULL — measured on a live stack, where every retry line read "failed (null)"
                    // and named nothing an operator could act on.
                    log.warnf("Store provisioning attempt %d failed (%s); retrying in %s", attempt, e.toString(), delay)
                    try {
                        TimeUnit.MILLISECONDS.sleep(delay.toMillis())
                    } catch (interrupted: InterruptedException) {
                        // Shutdown. IN_PROGRESS is the truth about an instance that stopped before it finished;
                        // claiming FAILED would report a give-up that never happened.
                        Thread.currentThread().interrupt()
                        return StoreProvisioningState.IN_PROGRESS
                    }
                    delay = minOf(delay.multipliedBy(2), maxBackoff)
                }
            }
            return StoreProvisioningState.FAILED
        }
    }
}
