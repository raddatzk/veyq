package me.raddatz.veyq.core.backup

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import java.time.Duration
import java.time.Instant
import org.jboss.logging.Logger

/**
 * The erasure ledger at every start, **before the instance serves** (ADR 0056 decision 8; decision 5 of
 * cr-backup-erasure-and-retention): the outbox's unsent rows into the file, then every line read and re-applied
 * ([ErasureReapplication]), then the lines older than B dropped ([ErasureLedgerFile.rewrite]). No restore needs to be
 * detected: on an instance nothing was restored onto, every listed row is already gone and every line changes nothing.
 *
 * **Before the first request answers.** A synchronous `StartupEvent` observer: Quarkus opens the HTTP socket only after
 * every one has returned (`AdminListener` names the order), so `/api/setup` — the Compose healthcheck and the chart's
 * readiness probe — answers nothing until this is done, and a failure here fails the start rather than serve an instance
 * with an erasure undone. Ahead of the default-priority observers, so the admin listener (`AdminListener.open`) opens
 * after it and the routing projection (`TenantIngressProjector.onStartup`) reads the tenants as it left them.
 */
@ApplicationScoped
class ErasureLedgerStart(
    private val config: VeyqConfig,
    private val file: ErasureLedgerFile,
    private val reapplication: ErasureReapplication,
    private val retention: BackupRetention,
) {
    private val log = Logger.getLogger(ErasureLedgerStart::class.java)

    fun onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) event: StartupEvent) {
        if (config.erasureLedger().applyAtStart()) run()
    }

    /** The start's three steps; the tests call it as a restart would. Answers what the re-application did. */
    fun run(now: Instant = Instant.now()): Reapplied {
        file.sweep(now)
        val read = file.read()
        val done = reapplication.apply(read.lines, now)
        val days = QuarkusTransaction.requiringNew().call { retention.retention() }.days
        val dropped = file.rewrite(now.minus(Duration.ofDays(days.toLong())))
        // Counts only, by kind — never an id (ADR 0092 § 1: a log names no subject).
        log.infof(
            "erasure ledger: %d line(s) read (%d unreadable), erased again %s, %d block(s) taken again, %d lift(s) " +
                "applied again, %d case stub(s) restored, %d case(s) moved on to their last state, %d line(s) of an " +
                "unknown kind; %d erased storage key(s), %d restored row(s) still naming one (served as a missing " +
                "object); %d line(s) older than %d days dropped",
            done.lines, read.unreadable, done.erased, done.reblocked, done.relifted, done.caseStubs, done.casesMoved,
            done.unknown, done.erasedKeys, done.rowsNamingErasedKeys, dropped, days,
        )
        if (done.unknown > 0) {
            log.errorf("erasure ledger: %d line(s) name a kind no application of this build re-applies", done.unknown)
        }
        return done
    }
}
