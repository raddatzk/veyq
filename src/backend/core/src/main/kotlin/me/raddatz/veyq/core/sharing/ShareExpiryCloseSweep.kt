package me.raddatz.veyq.core.sharing

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * The end-date sweep (ADR 0114 decision 6): no client is there when a link's end date passes, so this closes every
 * enabled, not deleted link whose end date has passed and whose close it has not carried out — through the one close
 * body ([SharingStore.close]), which marks a viewable link's subtree due and clears its keys — and records on the link
 * that it did (`expiry_closed`), so it marks each once. Any member's worker then re-keys. The server needs no key for it.
 *
 * One transaction per scope, under the scope row's lock and then the share rows' ([SharingStore.closeExpiredIn]); a scope
 * whose close fails is retried at the next run and keeps no other scope from its own. Runs every
 * `veyq.share.expiry-close.every`, never twice at once. Operator-blind: it reads end dates and flags it already holds and
 * writes flags; one line per run that closed anything, counts only — never an id.
 */
@ApplicationScoped
class ShareExpiryCloseSweep(private val store: SharingStore) {
    private val log = Logger.getLogger(ShareExpiryCloseSweep::class.java)

    @Scheduled(every = "{veyq.share.expiry-close.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun run() {
        sweep(Instant.now())
    }

    /** Close every expired, unclosed link as of [now]; returns how many it closed. */
    fun sweep(now: Instant): Int {
        var closed = 0
        var failed = 0
        for (scopeId in store.scopesWithExpiredUnclosedLinks(now)) {
            runCatching { store.closeExpiredIn(scopeId, now) }
                .onSuccess { closed += it }
                .onFailure { e ->
                    failed += 1
                    log.warnf("link end-date sweep: a scope's close failed (%s) — retried at the next run", e.javaClass.simpleName)
                }
        }
        if (closed > 0 || failed > 0) log.infof("link end-date sweep: closed %d link(s), %d scope(s) to retry", closed, failed)
        return closed
    }
}
