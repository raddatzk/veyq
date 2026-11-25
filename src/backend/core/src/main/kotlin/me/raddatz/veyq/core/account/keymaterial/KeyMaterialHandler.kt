package me.raddatz.veyq.core.account.keymaterial

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.WrapMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.util.Base64

/** Outcome of adding a wrap, so the resource can map each case to its status without leaking detail. */
sealed interface AddWrapOutcome {
    data class Created(val id: String) : AddWrapOutcome
    object LimitReached : AddWrapOutcome, HttpFailure by httpFailure("too_many_devices", CONFLICT)
    /** The capability the wrap's method needs is switched off instance-wide (ADR 0032, [capabilityFor]) — a 422. */
    object CapabilityDisabled : AddWrapOutcome, HttpFailure by Failures.capabilityDisabled
    /** The caller's session was revoked by a reset, a password change or a rotation this request waited for. */
    object SessionEnded : AddWrapOutcome, HttpFailure by Failures.authenticationFailed
    /** DEVICE_PIN without its share and verifier, or either of them on another method (ADR 0160 decision 3). */
    object InvalidRequest : AddWrapOutcome, HttpFailure by Failures.invalidRequest
}

/** Outcome of a PIN unlock (ADR 0160 decision 3). */
sealed interface PinUnlockOutcome {
    /** The verifier matched: the wrap's server share, the count reset. */
    data class Released(val share: String) : PinUnlockOutcome
    /** A wrong verifier, counted; [attemptsLeft] failures remain (at least 1 — the last one deletes the wrap). */
    data class Incorrect(val attemptsLeft: Int) : PinUnlockOutcome, HttpFailure by httpFailure("pin_incorrect", FORBIDDEN)
    /** No DEVICE_PIN wrap of the caller's with this id: never one, removed, or deleted by the tenth failure. */
    object Gone : PinUnlockOutcome, HttpFailure by httpFailure("not_found", NOT_FOUND)
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
     *  (`KEK = Argon2id(secretKey)`) — it is released **only** by the two-factor recovery flow (ADR 0054,
     *  `RecoveryStore.recoveryMaterial`), never in the normal material; otherwise a live session + the Secret Key
     *  would open the vault **without the password**, silently demoting the password from a content factor.
     *  PASSWORD / DEVICE / PASSKEY stay (their KEKs are device- or password-bound); the client's normal unlock
     *  uses PASSWORD.
     *
     *  **A device switch acts at once** (ADR 0088 decision 5): every DEVICE wrap is withheld while
     *  [CapabilityIds.REMEMBER_DEVICE] is effectively off, and every DEVICE_KEY wrap while
     *  [CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS] is (its parent's switch included, the AND along the path). A
     *  remembered device then asks for what its wrap replaced. Nothing is deleted, so switching back on restores every
     *  such device, and PASSWORD is never withheld, so no switch locks a member out. */
    @Transactional
    fun forUser(userId: String): KeyMaterial? {
        val withheld = buildSet {
            add(WrapMethod.RECOVERY.name)
            // Instance-only entries (ADR 0088 decision 12): the key material is the account's, not a tenant's, and the
            // sign-in screen that offers remembering reads the pre-auth manifest. So no tenant is asked.
            if (!capabilities.isActive(CapabilityIds.REMEMBER_DEVICE, null)) add(WrapMethod.DEVICE.name)
            if (!capabilities.isActive(CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS, null)) {
                add(WrapMethod.DEVICE_KEY.name)
                add(WrapMethod.DEVICE_PIN.name)
            }
        }
        return store.find(userId)?.let { m -> m.copy(wraps = m.wraps.filterNot { it.method in withheld }) }
    }

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
        pinShare: String? = null,
        pinVerifier: String? = null,
    ): AddWrapOutcome {
        // A DEVICE_PIN wrap is worthless without its share and verifier, and no other method may carry them (the
        // contract's 400): the shape is checked before anything else, so a malformed add learns nothing more.
        val pin = if (method == WrapMethod.DEVICE_PIN.name) {
            if (pinShare == null || pinVerifier == null || !is32Bytes(pinShare) || !is32Bytes(pinVerifier)) {
                return AddWrapOutcome.InvalidRequest
            }
            PinParts(pinShare, pinVerifier)
        } else {
            if (pinShare != null || pinVerifier != null) return AddWrapOutcome.InvalidRequest
            null
        }
        // Instance policy first (ADR 0032): the capability this method needs must be active, else 422, server-enforced.
        if (!capabilities.isActive(capabilityFor(method), null)) return AddWrapOutcome.CapabilityDisabled // instance-only, as above
        accounts.lock(userId)
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return AddWrapOutcome.SessionEnded
        if (store.countWraps(userId, method) >= MAX_WRAPS_PER_METHOD) return AddWrapOutcome.LimitReached
        return AddWrapOutcome.Created(store.addWrap(userId, method, wrappedKey, kdfParams, pin))
    }

    /**
     * A PIN unlock (ADR 0160 decision 3): compare [verifier] in constant time with the one stored at enrolment; a match
     * releases the server share and resets the count, a mismatch counts one failure, and the [PIN_ATTEMPTS]th failure in
     * a row deletes the wrap with its share and verifier. The row is locked for the read-modify-write, so two racing
     * unlocks count twice. The count is per wrap on the server: a reinstall or a second device does not reset it.
     */
    @Transactional
    fun unlockWithPin(userId: String, id: String, verifier: String): PinUnlockOutcome {
        val wrap = store.lockPinWrap(userId, id) ?: return PinUnlockOutcome.Gone
        val stored = wrap.pinVerifier?.let(::decodeOrNull)
        val presented = decodeOrNull(verifier)
        if (stored != null && presented != null && MessageDigest.isEqual(stored, presented)) {
            wrap.pinFailures = 0
            return PinUnlockOutcome.Released(checkNotNull(wrap.pinShare) { "a DEVICE_PIN wrap without its share" })
        }
        val failures = wrap.pinFailures + 1
        if (failures >= PIN_ATTEMPTS) {
            wrap.delete()
            return PinUnlockOutcome.Gone
        }
        wrap.pinFailures = failures
        return PinUnlockOutcome.Incorrect(PIN_ATTEMPTS - failures)
    }

    /** Remove a wrap for the authenticated caller (idempotent) — a no-op for an unknown or another account's
     *  wrap, and for any method outside the two device-local convenience ones (DEVICE / DEVICE_KEY); the
     *  whitelist and why it is a whitelist live on [KeyMaterialStore.deleteWrap]. */
    @Transactional
    fun deleteWrap(userId: String, id: String) {
        store.deleteWrap(userId, id)
    }

    /** The instance capability enrolling a wrap of [method] needs (ADR 0088): DEVICE_KEY, the passwordless wrap,
     *  needs [CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS] (and through the AND along the path its parent); DEVICE, the
     *  one other method the contract lets through, needs [CapabilityIds.REMEMBER_DEVICE]. Enrolling is refused here;
     *  a wrap that exists is withheld from the material while its switch is off ([forUser]), never deleted, and
     *  [deleteWrap] stays allowed. */
    private fun capabilityFor(method: String): String =
        // DEVICE_PIN is password-less too: a TV never sees the password (ADR 0160), so it needs the same switch.
        if (method == WrapMethod.DEVICE_KEY.name || method == WrapMethod.DEVICE_PIN.name) {
            CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS
        } else {
            CapabilityIds.REMEMBER_DEVICE
        }

    private fun decodeOrNull(b64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(b64) }.getOrNull()

    private fun is32Bytes(b64: String): Boolean = decodeOrNull(b64)?.size == PIN_PART_BYTES

    companion object {
        const val MAX_WRAPS_PER_METHOD = 20L

        /** Failed PIN unlocks in a row that delete a DEVICE_PIN wrap (ADR 0160 decision 3; a constant, owner 2026-11-25). */
        const val PIN_ATTEMPTS = 10

        private const val PIN_PART_BYTES = 32
    }
}
