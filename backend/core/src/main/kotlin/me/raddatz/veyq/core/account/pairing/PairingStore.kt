package me.raddatz.veyq.core.account.pairing

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/**
 * A session family a channel delegated but never handed over, named together with the account that owns it.
 *
 * The account travels with the id on purpose: revoking goes through `SessionService.revokeFamilyForUser`, whose
 * statement is account-scoped, so no unscoped "revoke this family" exists anywhere for a later caller to reach
 * for. A family without a bound account cannot occur — `userId` is claimed before the family is minted, and
 * `delegatedFamilyId` is written in the same transaction — and if one ever did, it is skipped rather than revoked
 * blind.
 */
data class DelegatedFamilyRef(val userId: String, val familyId: String)

/** The family this channel delegated, if it minted one and bound an account — see [DelegatedFamilyRef]. */
fun DevicePairingEntity.delegatedFamily(): DelegatedFamilyRef? {
    val account = userId ?: return null
    val family = delegatedFamilyId ?: return null
    return DelegatedFamilyRef(account, family)
}

/**
 * Persistence for the device-pairing channel (ADR 0059).
 *
 * `RecoveryStore`'s division, copied exactly and for the same reason: [findByHandleHash] returns the row
 * **unfiltered** and [isLive] is a separate predicate, so the *handler* — never the query — owns the
 * indistinguishability of unknown / expired / spent. A store that filtered would make an unknown handle and a
 * dead one two different code paths, and two code paths are two response shapes waiting to happen
 * (uc.account.onboard-device E1).
 *
 * Nothing here interprets [DevicePairingEntity.answer] or [DevicePairingEntity.envelope]. They are written once,
 * read once, and the server never learns which ceremony direction produced them (§6).
 */
@ApplicationScoped
class PairingStore {

    /**
     * Open a channel under a handle hash, unless one already answers to it.
     *
     * Returns whether a row was created — for logging and tests only. The endpoint answers 204 either way: a
     * refusal would confirm that a channel exists, which is the oracle E1 closes. A collision is not a real
     * case (the handle is the client's high-entropy secret); the branch exists so that if one ever happens it
     * cannot overwrite a live ceremony.
     */
    @Transactional
    fun open(handleHash: String, expiresAt: Instant): Boolean {
        if (DevicePairingEntity.find("handleHash", handleHash).firstResult() != null) return false
        DevicePairingEntity().apply {
            this.id = UUID.randomUUID().toString()
            this.handleHash = handleHash
            this.status = DevicePairingEntity.PENDING
            this.expiresAt = expiresAt
            this.createdAt = Instant.now()
        }.persist()
        return true
    }

    /** The row behind a presented handle, or null. Liveness is [isLive]'s job, not this lookup's. */
    @Transactional
    fun findByHandleHash(handleHash: String): DevicePairingEntity? =
        DevicePairingEntity.find("handleHash", handleHash).firstResult()

    fun isLive(row: DevicePairingEntity, now: Instant): Boolean =
        row.status !in DevicePairingEntity.TERMINAL && row.expiresAt.isAfter(now)

    /** Record the one relayed answer and move the channel on. */
    @Transactional
    fun recordAnswer(id: String, answer: String) {
        DevicePairingEntity.update(
            "answer = ?1, status = ?2 where id = ?3 and status = ?4",
            answer, DevicePairingEntity.ANSWERED, id, DevicePairingEntity.PENDING,
        )
    }

    /**
     * Claim the channel for the account that delegates — a **compare-and-set**, and the delegation's first write:
     * only an `ANSWERED` channel no account holds yet takes [userId], and the caller goes on only if exactly one row
     * did (developer-styleguide §12).
     *
     * This is the first and only moment the account exists on the row (ADR 0059 §3). The status is unchanged —
     * a delegated channel is still awaiting its seal — but `userId` being set is what makes a second delegation
     * on the same channel impossible, and that matters: a second one would mint a second family and orphan the
     * first, leaving a live refresh token no sweep could find. The `and userId is null` is what decides that, not
     * the caller's earlier read: two delegations that both read the channel unclaimed reach this line together.
     */
    @Transactional
    fun claimDelegation(id: String, userId: String): Boolean =
        DevicePairingEntity.update(
            "userId = ?1 where id = ?2 and status = ?3 and userId is null",
            userId, id, DevicePairingEntity.ANSWERED,
        ) == 1

    /** Record the family the claimed channel minted — a plain update on a row this transaction already claimed with
     *  [claimDelegation], so nothing else can be writing it. */
    @Transactional
    fun bindDelegation(id: String, familyId: String) {
        DevicePairingEntity.update("delegatedFamilyId = ?1 where id = ?2", familyId, id)
    }

    /**
     * Every live (non-terminal) channel of an account.
     *
     * A key rotation supersedes all of them (ADR 0060): a `PENDING`, `ANSWERED` or `SEALED` channel's envelope
     * carries — or is about to carry — the **old** Master Key and a delegated refresh token, so a later collect
     * would hand a device a dead key and a working login. `COLLECTED`, `SUPERSEDED` and `EXPIRED` are terminal
     * and need nothing: the first is covered by the wrap deletion and the session revoke, the other two are
     * already handled by `PairingPurge`.
     */
    @Transactional
    fun liveChannels(userId: String): List<DevicePairingEntity> =
        DevicePairingEntity.find("userId = ?1 and status not in ?2", userId, DevicePairingEntity.TERMINAL).list()

    /** The account's other live channels — what §8's one-outstanding-pairing rule supersedes at delegation. */
    @Transactional
    fun otherLiveChannels(userId: String, exceptId: String): List<DevicePairingEntity> =
        liveChannels(userId).filter { it.id != exceptId }

    @Transactional
    fun supersede(ids: List<String>): Long =
        if (ids.isEmpty()) 0L
        else DevicePairingEntity.update(
            "status = ?1 where id in ?2", DevicePairingEntity.SUPERSEDED, ids,
        ).toLong()

    /** Record the sealed grant. The status guard makes this a compare-and-set, so a second seal writes nothing;
     *  *whose* channel it is was checked by the handler, which owns every account-scoped refusal here. */
    @Transactional
    fun recordEnvelope(id: String, envelope: String) {
        DevicePairingEntity.update(
            "envelope = ?1, status = ?2 where id = ?3 and status = ?4",
            envelope, DevicePairingEntity.SEALED, id, DevicePairingEntity.ANSWERED,
        )
    }

    /**
     * Spend the channel. Only handing the grant over does this — reading the answer spends nothing.
     *
     * A compare-and-set like its two siblings, and unlike them its result is load-bearing: two collects that both
     * read `SEALED` both reach this line, and the one that changed nothing must not hand the grant over.
     */
    @Transactional
    fun markCollected(id: String): Boolean =
        DevicePairingEntity.update(
            "status = ?1 where id = ?2 and status = ?3",
            DevicePairingEntity.COLLECTED, id, DevicePairingEntity.SEALED,
        ) == 1

    // --- scheduled sweep (see PairingPurge) ---

    /**
     * Flip overdue channels to EXPIRED and return the families they had delegated but never handed over.
     *
     * The ids are read **before** the update, not after: once the rows are EXPIRED they are indistinguishable
     * from ones an earlier sweep already handled, and revoking is not idempotent in cost — it would re-scan the
     * whole retention window on every tick.
     */
    @Transactional
    fun expireOverdue(now: Instant): List<DelegatedFamilyRef> {
        val overdue = DevicePairingEntity.find(
            "status not in ?1 and expiresAt < ?2", DevicePairingEntity.TERMINAL, now,
        ).list()
        if (overdue.isEmpty()) return emptyList()
        DevicePairingEntity.update(
            "status = ?1 where id in ?2", DevicePairingEntity.EXPIRED, overdue.map { it.id },
        )
        return overdue.mapNotNull { it.delegatedFamily() }
    }

    /** Delete terminal rows older than the retention window. */
    @Transactional
    fun purgeTerminal(before: Instant): Long = DevicePairingEntity.delete(
        "status in ?1 and createdAt < ?2", DevicePairingEntity.TERMINAL, before,
    )
}
