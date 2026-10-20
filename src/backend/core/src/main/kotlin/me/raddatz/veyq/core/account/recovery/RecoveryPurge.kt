package me.raddatz.veyq.core.account.recovery

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Scheduled sweep over recovery tokens (cr-account-recovery, ADR 0054) — the same soft-invalidate-then-delete
 * shape as `TenantInvitePurge`.
 *
 * Two steps, and the order matters: overdue tokens are flipped to EXPIRED *first*, so a replay in the window
 * between expiry and deletion reads as spent rather than unknown. Only later are terminal rows deleted.
 *
 * Expiry is enforced at redemption too (`RecoveryStore.isLive` compares the timestamp), so this sweep is
 * hygiene, not the security boundary — a token that outlives the sweep interval is still refused.
 */
@ApplicationScoped
class RecoveryPurge(private val store: RecoveryStore, config: VeyqConfig) {

    private val retention = config.recovery().token().retention()

    // @Transactional on run() so the whole sweep shares one session (the Quarkus @Scheduled + Panache pattern,
    // cr-0053).
    @Transactional
    @Scheduled(every = "{veyq.recovery.purge.every}")
    fun run() {
        val now = Instant.now()
        store.expireOverdue(now)
        store.purgeTerminal(now.minus(retention))
    }
}
