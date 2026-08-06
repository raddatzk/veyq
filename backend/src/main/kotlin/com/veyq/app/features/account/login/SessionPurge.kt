package com.veyq.app.features.account.login

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
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

    // @Transactional so all four deletes share ONE session/transaction on the scheduler thread — without it the
    // first nested @Transactional commits + closes the session and the next call fails with "EntityManager is
    // closed" (the Quarkus @Scheduled + Panache pattern, cr-0053).
    @Transactional
    @Scheduled(every = "{veyq.session.purge.every}")
    fun run() {
        val now = Instant.now()
        sessions.purgeExpiredAccessTokens(now)
        sessions.purgeExpiredRefreshTokens(now)
        sessions.purgeExpiredChallenges(now)
        sessions.purgeEmptyFamilies()
    }
}
