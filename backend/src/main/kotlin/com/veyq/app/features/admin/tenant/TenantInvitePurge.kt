package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/**
 * The instance's first scheduled cleanup job: flips overdue ACTIVE invites to EXPIRED, then physically
 * deletes terminal-state invites (SUPERSEDED/CONSUMED/EXPIRED) whose TTL passed longer than the retention
 * window ago. Soft-invalidate now, hard-delete later — never inline (the design the user chose, mirroring
 * the session-token model). The same seam later covers expired session tokens (inbox #42 → cr-0011).
 */
@ApplicationScoped
class TenantInvitePurge(
    private val store: TenantStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.tenant().invite().retention()

    // @Transactional so the expire + delete share ONE session/transaction on the scheduler thread — without it
    // the first nested @Transactional closes the session and the next call fails ("EntityManager is closed", the
    // Quarkus @Scheduled + Panache pattern, cr-0053).
    @Transactional
    @Scheduled(every = "{veyq.tenant.invite.purge.every}")
    fun run() {
        val now = Instant.now()
        store.expireOverdueInvites(now)
        store.purgeTerminalInvites(now.minus(retention))
    }
}
