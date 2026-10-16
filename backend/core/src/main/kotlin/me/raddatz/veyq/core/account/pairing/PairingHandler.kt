package me.raddatz.veyq.core.account.pairing

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** What a collect returned. Both fields are absent until their step happened — presence is the whole state. */
data class CollectedChannel(val answer: String?, val envelope: String?)

/** What a delegation did. Every dead or wrong-state channel is one [ChannelInvalid] (see [PairingHandler]). */
sealed interface DelegateOutcome {
    /** The delegated family's refresh token, to be sealed to the answering device. */
    data class Delegated(val refreshToken: String) : DelegateOutcome
    data object ChannelInvalid : DelegateOutcome
    /** The delegating session was revoked by a reset, a password change or a rotation this request waited for. */
    data object SessionEnded : DelegateOutcome, HttpFailure by Failures.authenticationFailed
}

/**
 * The device-pairing ceremony (ADR 0059), server side.
 *
 * **What this handler is not allowed to know.** It never learns which of the two directions is running: `answer`
 * is an ephemeral transport key and a MAC under a channel key it never holds, one shape in both directions, the
 * MAC binding a transcribed code when *signing in with a QR code* (ADR 0059 §11); and §6 puts the check of that
 * code on the trusted device precisely so no server-side gate can
 * exist. A server that could tell the directions apart is one release-on-correct-code refactor away from being
 * the barrier ADR 0026 says it may never be — so there is nothing here that inspects a relayed value.
 *
 * **Every dead channel is one case, not four.** Unknown, expired, spent and wrong-state all return null from the
 * methods below, and the resource turns all of them into one `404 pairing_channel_invalid`
 * (uc.account.onboard-device E1).
 */
@ApplicationScoped
class PairingHandler(
    private val store: PairingStore,
    private val sessions: SessionService,
    private val accounts: AccountStore,
    private val capabilities: CapabilityRegistry,
    config: VeyqConfig,
) {
    private val ttl = config.pairing().channel().ttl()

    /**
     * Instance policy first (ADR 0032): every pairing endpoint asks this before it does anything, and answers
     * `422 capability_disabled` while [CapabilityIds.DEVICE_LINKING] is switched off. A pairing is a transient exchange,
     * not existing data, so "refuse only what is new" (ADR 0088 decision 5) is the whole exchange — which also stops a
     * pairing in flight. Independent of `core.remember-device` (decision 11). It reads no channel, so it is the same
     * answer for every handle and adds nothing to the dead-channel oracle E1 closes.
     */
    // Instance-only (ADR 0088 decision 12): the new device asks before sign-in, and the channel carries no tenant.
    fun refusal(): HttpFailure? = if (capabilities.isActive(CapabilityIds.DEVICE_LINKING, null)) null else Failures.capabilityDisabled

    /**
     * Open a channel under the client's handle.
     *
     * Nothing is returned and nothing is refused: a duplicate handle answers exactly as a fresh one does, because
     * a refusal would say that a channel exists. No account is bound here — in the QR-sign-in direction the
     * caller has no session to name one with (§3).
     */
    fun open(handle: String) {
        store.open(Tokens.hash(handle), Instant.now().plus(ttl))
    }

    /** Post the one opaque answer. Null-returning for every dead or wrong-state channel — see the class doc. */
    fun answer(handle: String, answer: String): Boolean {
        val row = liveChannel(handle) ?: return false
        if (row.status != DevicePairingEntity.PENDING) return false
        store.recordAnswer(row.id, answer)
        return true
    }

    /**
     * Mint the family the granted device will receive, and bind this channel to the caller's account.
     *
     * Four things happen in one transaction, and the order is deliberate: the channel is claimed for the account,
     * the family is minted, the channel records it, and *then* the account's other live channels are superseded —
     * each of which surrenders its own delegated family, because a ceremony the user walked away from must not
     * leave a live 30-day refresh token behind (§8, and the same reason the sweep revokes on expiry).
     *
     * A channel may be delegated **once**: `userId` already being set is a wrong-state channel like any other. A
     * second delegation would mint a second family and orphan the first, and an orphan is exactly the token no
     * sweep can find. The read below only turns a dead channel away early; the claim decides, because two
     * delegations can both read the channel unclaimed. **Claim before mint**: a delegation that lost the claim has
     * minted nothing, so no correctness rests on rolling back a family that already exists.
     *
     * The caller gets the refresh token and no access token: the granted device rotates it into its own pair,
     * and the trusted device has no business holding an access token for a family that is not its own.
     *
     * **Under the account lock, and only for a session still live under it** (inbox #297). A reset, a password
     * change and a rotation revoke every family they can see and a rotation supersedes every live channel, and
     * neither this claim nor this mint is one they can see before it commits: without the lock the delegated
     * family outlived the reset that revoked the delegating session, and against a rotation so did a channel whose
     * envelope would carry the old Master Key. The lock comes before the claim, the transaction's first write.
     */
    fun delegate(handle: String, userId: String, presentedAccessToken: String?): DelegateOutcome =
        QuarkusTransaction.requiringNew().call<DelegateOutcome> {
            delegateLocked(handle, userId, presentedAccessToken)
        }

    private fun delegateLocked(handle: String, userId: String, presentedAccessToken: String?): DelegateOutcome {
        val row = liveChannel(handle) ?: return DelegateOutcome.ChannelInvalid
        if (row.status != DevicePairingEntity.ANSWERED || row.userId != null) return DelegateOutcome.ChannelInvalid

        accounts.lock(userId)
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return DelegateOutcome.SessionEnded
        if (!store.claimDelegation(row.id, userId)) return DelegateOutcome.ChannelInvalid
        val delegated = sessions.issueDelegated(userId)
        store.bindDelegation(row.id, delegated.familyId)

        val superseded = store.otherLiveChannels(userId, row.id)
        superseded.mapNotNull { it.delegatedFamily() }
            .forEach { sessions.revokeFamilyForUser(it.userId, it.familyId) }
        store.supersede(superseded.map { it.id })

        return DelegateOutcome.Delegated(delegated.refreshToken)
    }

    /**
     * Post the sealed grant.
     *
     * Scoped to the delegating account: a channel this caller did not delegate is refused with the same answer a
     * dead one gets. The seal is gated by the binding step (§5) — a compared check number or a proof of the
     * transcribed code — and that gate runs **on the trusted device**, never here.
     */
    fun seal(handle: String, userId: String, envelope: String): Boolean {
        val row = liveChannel(handle) ?: return false
        if (row.status != DevicePairingEntity.ANSWERED || row.userId != userId) return false
        store.recordEnvelope(row.id, envelope)
        return true
    }

    /**
     * The single read, used by both sides.
     *
     * Reading the answer spends nothing; handing the grant over spends the channel, which is what makes the
     * ceremony single-use. Deliberately unauthenticated — the grant is worthless without the ephemeral private
     * key only the answering device holds, so a gate here would prove nothing and would force that device to
     * name an account before it has one (§3).
     *
     * The grant goes only to the collect that wins the spend: the row was read without a lock, so a concurrent
     * collect may have read `SEALED` too, and the loser answers as the spent channel it now is.
     */
    fun collect(handle: String): CollectedChannel? = QuarkusTransaction.requiringNew().call<CollectedChannel?> {
        val row = liveChannel(handle) ?: return@call null
        if (row.envelope != null && !store.markCollected(row.id)) return@call null
        CollectedChannel(row.answer, row.envelope)
    }

    private fun liveChannel(handle: String): DevicePairingEntity? {
        val row = store.findByHandleHash(Tokens.hash(handle)) ?: return null
        return row.takeIf { store.isLive(it, Instant.now()) }
    }
}
