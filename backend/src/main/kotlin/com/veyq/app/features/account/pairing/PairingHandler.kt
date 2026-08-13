package com.veyq.app.features.account.pairing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.crypto.Tokens
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** What a collect returned. Both fields are absent until their step happened — presence is the whole state. */
data class CollectedChannel(val answer: String?, val envelope: String?)

/**
 * The device-pairing ceremony (ADR 0059), server side.
 *
 * **What this handler is not allowed to know.** It never learns which of the two directions is running: `answer`
 * is an ephemeral transport key when *adding a device* and a commitment to a transcribed code when *signing in
 * with a QR code*, and §6 puts the check of that code on the trusted device precisely so no server-side gate can
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
    config: VeyqConfig,
) {
    private val ttl = config.pairing().channel().ttl()

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
     * Three things happen in one transaction, and the order is deliberate: the family is minted, the channel
     * records it, and *then* the account's other live channels are superseded — each of which surrenders its own
     * delegated family, because a ceremony the user walked away from must not leave a live 30-day refresh token
     * behind (§8, and the same reason the sweep revokes on expiry).
     *
     * A channel may be delegated **once**: `userId` already being set is a wrong-state channel like any other. A
     * second delegation would mint a second family and orphan the first, and an orphan is exactly the token no
     * sweep can find.
     *
     * The caller gets the refresh token and no access token: the granted device rotates it into its own pair,
     * and the trusted device has no business holding an access token for a family that is not its own.
     */
    fun delegate(handle: String, userId: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        val row = liveChannel(handle) ?: return@call null
        if (row.status != DevicePairingEntity.ANSWERED || row.userId != null) return@call null

        val delegated = sessions.issueDelegated(userId)
        store.bindDelegation(row.id, userId, delegated.familyId)

        val superseded = store.otherLiveChannels(userId, row.id)
        superseded.mapNotNull { it.delegatedFamily() }
            .forEach { sessions.revokeFamilyForUser(it.userId, it.familyId) }
        store.supersede(superseded.map { it.id })

        delegated.refreshToken
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
     */
    fun collect(handle: String): CollectedChannel? = QuarkusTransaction.requiringNew().call<CollectedChannel?> {
        val row = liveChannel(handle) ?: return@call null
        if (row.envelope != null) store.markCollected(row.id)
        CollectedChannel(row.answer, row.envelope)
    }

    private fun liveChannel(handle: String): DevicePairingEntity? {
        val row = store.findByHandleHash(Tokens.hash(handle)) ?: return null
        return row.takeIf { store.isLive(it, Instant.now()) }
    }
}
