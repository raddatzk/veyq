package me.raddatz.veyq.core.account.pairing

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Scheduled sweep over pairing channels (ADR 0059 §8) — `RecoveryPurge`'s two-step shape, plus the one thing
 * recovery has no equivalent of.
 *
 * The two steps, in this order: overdue channels are flipped to EXPIRED *first*, so a request in the window
 * between expiry and deletion reads as spent rather than unknown; only later are terminal rows deleted. Expiry
 * is enforced at read too (`PairingStore.isLive`), so this sweep is hygiene, not the security boundary.
 *
 * **The addition: an expiring channel takes its delegated family with it.** A ceremony that was confirmed and
 * then abandoned — the new device closed, the network dropped (uc.account.onboard-device E3) — has already
 * minted a family holding a live refresh token with `SessionService.REFRESH_TTL_DAYS` to run. Nobody collected
 * it, so nobody will ever rotate it, and it would sit in the account's session list as a device that does not
 * exist. Revoking is account-scoped through `revokeFamilyForUser`, so this sweep cannot reach a family that is
 * not the channel's own.
 */
@ApplicationScoped
class PairingPurge(
    private val store: PairingStore,
    private val sessions: SessionService,
    config: VeyqConfig,
) {
    private val retention = config.pairing().channel().retention()

    // @Transactional on run() so the whole sweep shares one session (the Quarkus @Scheduled + Panache pattern,
    // cr-0053).
    @Transactional
    @Scheduled(every = "{veyq.pairing.purge.every}")
    fun run() = sweep(Instant.now())

    /** [run] at the clock [now] — a test's way to sweep a channel as overdue without waiting out its TTL. */
    @Transactional
    fun sweep(now: Instant) {
        store.expireOverdue(now).forEach { sessions.revokeFamilyForUser(it.userId, it.familyId) }
        store.purgeTerminal(now.minus(retention))
    }
}
