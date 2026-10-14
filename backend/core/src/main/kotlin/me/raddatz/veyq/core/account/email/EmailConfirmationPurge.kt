package me.raddatz.veyq.core.account.email

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Scheduled sweep over address-confirmation tokens (ADR 0074 Decision 4) — the `RecoveryPurge` shape: overdue tokens
 * are flipped to EXPIRED first, so a replay between expiry and deletion reads as spent rather than unknown; only
 * later are terminal rows deleted.
 *
 * Expiry is enforced at confirmation too (and at an address change's, `EmailChangeStore.spend`) (`EmailConfirmationStore.isLive`), so this sweep is hygiene, not the
 * boundary.
 */
@ApplicationScoped
class EmailConfirmationPurge(
    private val store: EmailConfirmationStore,
    private val changes: EmailChangeStore,
    config: VeyqConfig,
) {

    private val retention = config.emailConfirmation().token().retention()

    // @Transactional on run() so the whole sweep shares one session (the Quarkus @Scheduled + Panache pattern).
    @Transactional
    @Scheduled(every = "{veyq.email-confirmation.purge.every}")
    fun run() {
        val now = Instant.now()
        store.expireOverdue(now)
        store.purgeTerminal(now.minus(retention))
        // Pending address changes past their expiry (uc.account.change-email) ride the same schedule. A spent one is
        // already gone, so there is no replay window to keep: an expired one is deleted at once.
        changes.purgeExpired(now)
    }
}
