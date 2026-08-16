package com.veyq.app.features.account.rotation

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.account.pairing.PairingStore
import com.veyq.app.features.tasks.TasksStore
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.MembershipStore
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
}

/**
 * The account-side half of key rotation after a compromise (ADR 0060, uc.account.rotate-keys).
 *
 * **What is deliberately not here: the gate.** ADR 0060 Decision 2 refuses a rotation that was not started from
 * a cold credential — the `PASSWORD` floor or the released `RECOVERY` wrap, the two that take the Secret Key a
 * phished device-link grant never hands over. That check cannot live on this side: the server holds no evidence
 * of *which* wrap opened a caller's vault, the Secret Key never reaches it, and a blind server must not
 * adjudicate a crypto claim (ADR 0028 § Consequences). It is client-enforced, at ADR 0026's usual tier — which
 * is the same tier as every other veyq confidentiality property, not a weaker one.
 *
 * What this side *does* enforce is the shape: both cold wraps rewritten together or nothing written, every
 * other wrap gone, every other session cut, and the exclusion that keeps the rotator off their own re-grant.
 */
@ApplicationScoped
class RotationHandler(
    private val store: RotationStore,
    private val memberships: MembershipStore,
    private val tasks: TasksStore,
    private val pairings: PairingStore,
    private val sessions: SessionService,
    config: VeyqConfig,
) {
    private val maxAttempts: Int = config.tasks().maxAttempts()

    /**
     * Rotate. **One transaction, every write or none** — a partial apply is a lockout, not a degraded state.
     *
     * The order inside it is not arbitrary. The account rewrite runs **first** and can still refuse; everything
     * after it is a consequence that must not happen for an account that was never rewritten. Then the live
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
     */
    fun rotate(
        userId: String,
        presentedAccessToken: String?,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        passwordWrap: RewrittenWrap,
        recoveryWrap: RewrittenWrap,
        recoveryMasterKeyTag: String,
    ): RotationOutcome = QuarkusTransaction.requiringNew().call<RotationOutcome> {
        val applied = store.applyRotation(
            userId, identityPublicKey, wrappedIdentityPrivateKey, passwordWrap, recoveryWrap, recoveryMasterKeyTag,
        )
        if (!applied) return@call RotationOutcome.Unavailable

        pairings.supersede(pairings.liveChannels(userId).map { it.id })

        // The split (ADR 0060 Decision 5): a scope with exactly one member is self-served — nobody to be
        // re-admitted by, nobody to exclude but the rotator's own old identity. Everything else is queued for a
        // DIFFERENT key-holder, with the rotator excluded from claiming it (Decision 4).
        val counts = memberships.memberCountsByScopeFor(userId)
        val (sole, shared) = counts.keys.sorted().partition { (counts[it] ?: 0L) <= 1L }
        shared.forEach { tasks.originateScopeRotationExcluding(it, userId, maxAttempts) }

        presentedAccessToken?.let { sessions.revokeOtherFamilies(userId, it) }

        RotationOutcome.Applied(soleMemberScopeIds = sole, queuedScopeIds = shared)
    }
}
