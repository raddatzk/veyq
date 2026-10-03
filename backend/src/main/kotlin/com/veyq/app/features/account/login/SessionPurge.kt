package com.veyq.app.features.account.login

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Scheduled cleanup of expired/abandoned auth rows (cr-0011) — the second `@Scheduled` purge, extending the
 * seam cr-0009 opened (`TenantInvitePurge`). Deletes expired access + refresh tokens and stale login
 * challenges, then the now token-less session families. Tokens are purged BEFORE families (FK order).
 * Strict `expiresAt < now` matching keeps the reuse-detection tripwire intact (see [SessionService]). No
 * operator-blind impact — every row is an opaque token hash / nonce, never content.
 */
@ApplicationScoped
class SessionPurge(private val sessions: SessionService) {

    // Every step commits in its OWN short transaction, and the families go in batches. One transaction for the
    // whole sweep stalled sign-in: under MariaDB's REPEATABLE READ (the server default; every connection now runs
    // READ COMMITTED, ADR 0078) a delete next-key-locked every index record it scanned until commit, so the token
    // and family scans held every login and refresh for the purge's whole
    // duration. Measured on MariaDB 11.4 at ~4 M refresh rows (cr-session-purge-cost-on-mariadb): the sweep took
    // 19–31 s, a refresh waited it out, and a login deadlocked against it. Split, and with V59's index, the worst
    // probe wait was ~22 ms. The family ids come from a plain read (a snapshot, no lock); each batch delete checks
    // "no token" again by primary key, so a family that gained a token in between survives. `QuarkusTransaction`
    // per step, as `ScopePurge.run()` does: each step opens and closes its own session, which on the scheduler
    // thread is what the old sweep-wide `@Transactional` was there for ("EntityManager is closed", cr-0053).
    @Scheduled(every = "{veyq.session.purge.every}")
    fun run() {
        val now = Instant.now()
        QuarkusTransaction.requiringNew().run { sessions.purgeExpiredAccessTokens(now) }
        QuarkusTransaction.requiringNew().run { sessions.purgeExpiredRefreshTokens(now) }
        QuarkusTransaction.requiringNew().run { sessions.purgeExpiredChallenges(now) }
        while (true) {
            val ids = QuarkusTransaction.requiringNew().call { sessions.emptyFamilyIds(FAMILY_BATCH) }
            if (ids.isEmpty()) break
            val deleted = QuarkusTransaction.requiringNew().call { sessions.purgeEmptyFamilies(ids) }
            // A short batch was the last one; a batch the re-check refused entirely would be picked again forever.
            if (ids.size < FAMILY_BATCH || deleted == 0L) break
        }
    }

    internal companion object {
        /** Families per batch: each batch is one short transaction that locks at most this many family rows. */
        const val FAMILY_BATCH = 1000
    }
}
