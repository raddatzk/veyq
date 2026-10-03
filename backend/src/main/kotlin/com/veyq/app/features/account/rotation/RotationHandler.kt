package com.veyq.app.features.account.rotation

import com.veyq.app.features.account.deviceregister.DeviceRegisterStore
import com.veyq.app.features.account.identitypins.IdentityPinsStore
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.account.deviceregister.PutResult as RegisterPut
import com.veyq.app.features.account.identitypins.PutResult as PinsPut
import com.veyq.app.features.account.pairing.PairingStore
import com.veyq.app.features.tasks.CompromiseRotationRow
import com.veyq.app.features.tasks.CompromiseRotationState
import com.veyq.app.features.tasks.RequeueResult
import com.veyq.app.features.tasks.TasksStore
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.crypto.SecretKeyProof
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.identity.AccountStore
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.MembershipStore
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/** Outcome of a rotation — the per-scope split, or the one refusal the server itself can make. */
sealed interface RotationOutcome {
    /**
     * Applied. The two lists partition the caller's scopes on member count: what they re-key alone, and what
     * now waits on somebody else (uc.account.rotate-keys §6/§7). Neither is a success tick — `queued` is
     * exactly the set the caller is **outside** until a human there accepts their new key (ADR 0060 Decision 6).
     */
    data class Applied(val soleMemberScopeIds: List<String>, val queuedScopeIds: List<String>) : RotationOutcome

    /** The account has no `PASSWORD` floor or no `RECOVERY` wrap to rewrite — nothing was written. */
    data object Unavailable : RotationOutcome, HttpFailure by httpFailure("rotation_unavailable", CONFLICT)

    /** The request did not prove the Secret Key (ADR 0060 Decision 2) — nothing was written. */
    data object SecretKeyProofFailed : RotationOutcome, HttpFailure by httpFailure("secret_key_proof_failed", FORBIDDEN)

    /**
     * The pin store or the device register moved since the client read it — **the whole rotation rolled back**,
     * so nothing was published and there is nothing to repair. The client re-reads, re-seals and resubmits with
     * the same new keys and the same signature (the documents are not part of the proof's statement).
     */
    data object TrustStateStale : RotationOutcome, HttpFailure by httpFailure("stale_version", CONFLICT)

    /** The caller's session was revoked by a reset, a password change or another rotation this request waited
     *  for — nothing was written. */
    data object SessionEnded : RotationOutcome, HttpFailure by Failures.authenticationFailed
}

/** Outcome of the rotator queueing one of their dead-lettered rotations again (uc.account.rotate-keys E3). */
sealed interface RequeueRotationOutcome {
    data class Requeued(val scopeId: String, val state: CompromiseRotationState) : RequeueRotationOutcome
    data object Suspended : RequeueRotationOutcome, HttpFailure by Failures.tenantSuspended
    data object Deleting : RequeueRotationOutcome, HttpFailure by Failures.scopeDeleting
    data object ScopeNotFound : RequeueRotationOutcome, HttpFailure by Failures.scopeNotFound
    /** A member of the scope, but no rotation of their account was ever queued on it. */
    data object RotationNotFound : RequeueRotationOutcome, HttpFailure by httpFailure("rotation_not_found", NOT_FOUND)
    /** Waiting, stalled or secured: nothing to queue again. Nothing written. */
    data object NotFailed : RequeueRotationOutcome, HttpFailure by httpFailure("rotation_not_failed", CONFLICT)
}

/** One Master-Key-sealed account document as the rotation re-sealed it: the blob under `MK'`, guarded by the
 *  version the client read. */
data class ResealedDocument(val blob: String, val expectedVersion: Long)

/**
 * The account-side half of key rotation after a compromise (ADR 0060, uc.account.rotate-keys).
 *
 * **The gate's server half is here** (ADR 0060 Decision 2). A session is not enough to rotate: a device-link
 * holder has one without ever seeing the password, and whoever phished the password has one too — and either
 * could otherwise install an identity of their own, to which the next scope rotation would wrap every shared key.
 * So the request carries a signature under the account's Secret-Key proof key over what it installs and the
 * identity it replaces, and [SecretKeyProof] verifies it before anything is written. The password half stays on
 * the client, which re-opens the `PASSWORD` wrap.
 *
 * What this side also enforces is the shape: both cold wraps rewritten together or nothing written, every
 * other wrap gone, every other session cut, and the exclusion that keeps the rotator off their own re-grant.
 */
@ApplicationScoped
class RotationHandler(
    private val store: RotationStore,
    private val memberships: MembershipStore,
    private val tasks: TasksStore,
    private val pairings: PairingStore,
    private val sessions: SessionService,
    private val identityPins: IdentityPinsStore,
    private val deviceRegister: DeviceRegisterStore,
    private val access: ScopeAccess,
    private val accounts: AccountStore,
    config: VeyqConfig,
) {
    private val maxAttempts: Int = config.tasks().maxAttempts()

    /**
     * Rotate. **One transaction, every write or none** — a partial apply is a lockout, not a degraded state.
     *
     * The order inside it is not arbitrary. The Secret-Key proof is checked before anything, inside the same
     * transaction that reads the identity it binds — so the identity it was verified against is the one replaced.
     * The account rewrite runs next and can still refuse; everything
     * after it is a consequence that must not happen for an account that was never rewritten. The pin store and
     * the device register, re-sealed under the new Master Key, are written next through their own
     * compare-and-set stores (ADR 0060 § Consequences): a stale version on either is **not** a partial success —
     * the transaction is rolled back, so the account rewrite above is undone with it and the answer is
     * `409 stale_version`. Written after the publish instead, a failed re-seal left a document under a key that
     * no longer exists, which every device then refuses (ADR 0028 Decision 7). Then the live
     * pairing channels are superseded (their envelopes carry the *old* Master Key and a live delegated session,
     * so a later collect would hand a device a dead key and a working login), then the scope rotations are
     * queued, and the sessions are cut **last** — the session revoke is the one step that changes what the
     * *caller* may still do, so it comes after every write that needs the caller's own session to exist.
     *
     * `presentedAccessToken` spares the family this request arrived on. A rotation that also signed out the
     * device performing it would strand the ceremony halfway through, in the exact state uc.account.rotate-keys
     * §E7 has to be resumable from — and it is `SessionService.revokeOtherFamilies` that resolves the spared
     * family from the token, never the request.
     *
     * The superseded channels' delegated families need no separate revoke: they are ACTIVE families of this
     * account other than the caller's own, so the account-wide cut below already reaches them.
     *
     * **The account row is locked first** (`AccountStore.lock`, inbox #297): the proof is checked against the
     * identity as it is after anything this waited for, the caller's session is re-read under the lock, and a wrap
     * or a delegation racing this either commits before it — and is superseded, deleted or cut here — or decides
     * after it and is refused.
     */
    fun rotate(
        userId: String,
        presentedAccessToken: String?,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        passwordWrap: RewrittenWrap,
        recoveryWrap: RewrittenWrap,
        secretKeyProofSignature: String,
        pins: ResealedDocument,
        register: ResealedDocument,
    ): RotationOutcome = try {
        applyAll(
            userId, presentedAccessToken, identityPublicKey, wrappedIdentityPrivateKey, passwordWrap, recoveryWrap,
            secretKeyProofSignature, pins, register,
        )
    } catch (_: StaleDocumentRollback) {
        RotationOutcome.TrustStateStale
    }

    private fun applyAll(
        userId: String,
        presentedAccessToken: String?,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        passwordWrap: RewrittenWrap,
        recoveryWrap: RewrittenWrap,
        secretKeyProofSignature: String,
        pins: ResealedDocument,
        register: ResealedDocument,
    ): RotationOutcome = QuarkusTransaction.requiringNew().call<RotationOutcome> {
        val user = accounts.lock(userId) ?: return@call RotationOutcome.Unavailable
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return@call RotationOutcome.SessionEnded
        val statement = SecretKeyProof.rotationStatement(
            currentIdentityPublicKey = user.identityPublicKey,
            identityPublicKey = identityPublicKey,
            wrappedIdentityPrivateKey = wrappedIdentityPrivateKey,
            passwordWrap = SecretKeyProof.Wrap(passwordWrap.wrappedKey, passwordWrap.kdfParams),
            recoveryWrap = SecretKeyProof.Wrap(recoveryWrap.wrappedKey, recoveryWrap.kdfParams),
        )
        if (!SecretKeyProof.verify(user.secretKeyProofPublicKey, statement, secretKeyProofSignature)) {
            return@call RotationOutcome.SecretKeyProofFailed
        }

        val applied = store.applyRotation(
            userId, identityPublicKey, wrappedIdentityPrivateKey, passwordWrap, recoveryWrap,
        )
        if (!applied) return@call RotationOutcome.Unavailable

        // Both documents or no rotation. Each store's `put` joins this transaction, so a Stale here means the
        // writes above are still pending — the throw is what rolls them back, not a compensating write.
        // (`setRollbackOnly` + returning an outcome does not work here: `call` then tries to commit and fails
        // with a RollbackException, which reached the client as a 500.)
        val pinsStale = identityPins.put(userId, pins.blob, pins.expectedVersion) is PinsPut.Stale
        if (pinsStale || deviceRegister.put(userId, register.blob, register.expectedVersion) is RegisterPut.Stale) {
            throw StaleDocumentRollback()
        }

        pairings.supersede(pairings.liveChannels(userId).map { it.id })

        // The split (ADR 0060 Decision 5): a scope with exactly one member is self-served — nobody to be
        // re-admitted by, nobody to exclude but the rotator's own old identity. Everything else is queued for
        // another OWNER of the scope, with the rotator excluded from every live rotation job there (Decision 4).
        // Ascending scope order: each origination takes its scope's row lock, so two requests locking several
        // scopes take them in one order and cannot deadlock.
        val counts = memberships.memberCountsByScopeFor(userId)
        val (sole, shared) = counts.keys.sorted().partition { (counts[it] ?: 0L) <= 1L }
        shared.forEach { tasks.originateScopeRotationExcluding(it, userId, maxAttempts) }

        presentedAccessToken?.let { sessions.revokeOtherFamilies(userId, it) }

        RotationOutcome.Applied(soleMemberScopeIds = sole, queuedScopeIds = shared)
    }

    /**
     * Where each rotation the caller's own account rotation queued stands, read from the server's rows every time
     * (uc.account.rotate-keys §9, ADR 0060 Decision 6) — never a status the client synthesised once. Only the
     * caller's own rows; empty for an account that never rotated.
     */
    fun scopeStates(userId: String): List<CompromiseRotationRow> = tasks.compromiseRotations(rotatorId = userId)

    /**
     * Queue one of the caller's dead-lettered rotations again, the caller still excluded (uc.account.rotate-keys E3).
     * Safe to hand to the rotator: it creates work only for the scope's other owners and excludes the caller — the
     * opposite of re-admitting themselves (ADR 0060 Decision 4) — and it re-arms `rotateScope`'s refusal, closing
     * the window the dead-letter opened. Membership first (ADR 0042); only from `FAILED`, else nothing is written.
     */
    fun requeue(userId: String, scopeId: String): RequeueRotationOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RequeueRotationOutcome.ScopeNotFound, RequeueRotationOutcome.Suspended, RequeueRotationOutcome.Deleting)
            ?.let { return it }
        return when (val r = tasks.requeueFailedRotations(scopeId, userId, maxAttempts)) {
            is RequeueResult.Requeued -> RequeueRotationOutcome.Requeued(scopeId, r.rows.single().state)
            RequeueResult.NoRotation -> RequeueRotationOutcome.RotationNotFound
            RequeueResult.NotFailed -> RequeueRotationOutcome.NotFailed
        }
    }

    /** Thrown inside the rotation's transaction to roll every write in it back; [rotate] maps it to
     *  [RotationOutcome.TrustStateStale]. Never escapes this class. */
    private class StaleDocumentRollback : RuntimeException(null, null, false, false)
}
