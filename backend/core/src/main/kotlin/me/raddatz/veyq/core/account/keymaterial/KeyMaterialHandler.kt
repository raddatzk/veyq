package me.raddatz.veyq.core.account.keymaterial

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.WrapMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Outcome of adding a wrap, so the resource can map each case to its status without leaking detail. */
sealed interface AddWrapOutcome {
    data class Created(val id: String) : AddWrapOutcome
    object LimitReached : AddWrapOutcome, HttpFailure by httpFailure("too_many_devices", CONFLICT)
    /** The `account.device-trust` capability is disabled instance-wide (ADR 0032) — a 422, full password only. */
    object CapabilityDisabled : AddWrapOutcome, HttpFailure by Failures.capabilityDisabled
    /** The caller's session was revoked by a reset, a password change or a rotation this request waited for. */
    object SessionEnded : AddWrapOutcome, HttpFailure by Failures.authenticationFailed
}

/**
 * The authenticated account key-material + wrap-management use-cases (uc.account.unlock / trust-device,
 * server side). Authentication is central (`@Authenticated` on the resources → `SecurityIdentity`, cr-0012), so
 * the handler is handed an already-resolved `userId` and only delegates persistence to [KeyMaterialStore] — the
 * resources stay thin HTTP adapters (ADR 0001): Resource → Handler → Store. Each method is @Transactional.
 */
@ApplicationScoped
class KeyMaterialHandler(
    private val store: KeyMaterialStore,
    private val capabilities: CapabilityRegistry,
    private val accounts: AccountStore,
    private val sessions: SessionService,
) {

    /** The caller's key material for the **normal unlock** path, or null only if the account has none — a
     *  server-side inconsistency for an authenticated user (every account is created with its wraps), surfaced
     *  as a 500 at the boundary.
     *
     *  Serves only the **interactive** unlock wraps. The RECOVERY wrap is a password-less, Secret-Key-only path
     *  (`KEK = Argon2id(secretKey)`) — it must be released **only** by the future email-gated recovery flow,
     *  never in the normal material; otherwise a live session + the Secret Key would open the vault **without
     *  the password**, silently demoting the password from a content factor (cr-0052). PASSWORD / DEVICE /
     *  PASSKEY stay (their KEKs are device- or password-bound); the client's normal unlock uses PASSWORD. */
    @Transactional
    fun forUser(userId: String): KeyMaterial? =
        store.find(userId)?.let { m -> m.copy(wraps = m.wraps.filterNot { it.method == WrapMethod.RECOVERY.name }) }

    /**
     * Add a wrap for the authenticated caller. Caps DEVICE enrolments per account so a buggy/malicious client can't
     * flood the row set (re-trust replaces, so normal use never approaches the cap).
     *
     * **Under the account lock, and only for a session still live under it** (inbox #297). A reset, a password
     * change and a rotation delete every device wrap they can see, and this insert is not one they can see before it
     * commits: without the lock a `DEVICE_KEY` wrap — password-less entry — outlived the reset that revoked the very
     * session that uploaded it. The lock orders the two; the session re-read refuses a wrap whose session the
     * cleanup ended while this waited. The lock also makes the cap a real one: two adds no longer both count 19.
     */
    @Transactional
    fun addWrap(
        userId: String,
        presentedAccessToken: String?,
        method: String,
        wrappedKey: String,
        kdfParams: String?,
    ): AddWrapOutcome {
        // Instance policy first (ADR 0032): an instance mandating a full password every time disables the
        // device-local convenience wraps (DEVICE / DEVICE_KEY) this endpoint serves → 422, server-enforced.
        if (!capabilities.isActive(CapabilityIds.ACCOUNT_DEVICE_TRUST)) return AddWrapOutcome.CapabilityDisabled
        accounts.lock(userId)
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return AddWrapOutcome.SessionEnded
        if (store.countWraps(userId, method) >= MAX_WRAPS_PER_METHOD) return AddWrapOutcome.LimitReached
        return AddWrapOutcome.Created(store.addWrap(userId, method, wrappedKey, kdfParams))
    }

    /** Remove a wrap for the authenticated caller (idempotent) — a no-op for an unknown or another account's
     *  wrap, and for any method outside the two device-local convenience ones (DEVICE / DEVICE_KEY); the
     *  whitelist and why it is a whitelist live on [KeyMaterialStore.deleteWrap]. */
    @Transactional
    fun deleteWrap(userId: String, id: String) {
        store.deleteWrap(userId, id)
    }

    companion object {
        const val MAX_WRAPS_PER_METHOD = 20L
    }
}
