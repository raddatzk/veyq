package me.raddatz.veyq.core.account.rotation

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** One staged self-grant as the store reads and writes it. */
data class StagedSelfGrantRow(val scopeId: String, val epoch: Int, val wrappedGrant: String, val grantSignature: String)

/** One staged invite as the store reads and writes it (ADR 0111 decision 20): re-signed at
 *  [anchorEpoch], or declared — both signatures null. */
data class StagedInviteRow(val inviteId: String, val anchorEpoch: Int, val inviteKeySignature: String?, val anchorGrantSignature: String?) {
    val declared: Boolean get() = inviteKeySignature == null
}

/** One staged join-record entry (ADR 0111 decision 21): the record's digest it was staged against, and its endorsement or null
 *  (declared). */
data class StagedEndorsementRow(val tenantId: String, val userId: String, val recordDigest: String, val endorsement: String?)

/**
 * Persistence of an account's stage of self-grants (ADR 0111 decision 6). Every method runs in its
 * caller's transaction, which holds the account's lock (`AccountStore.lock`): the stage is the account's, and its open,
 * its chunks and the publish that applies it serialize there, so no chunk lands between the publish's digest and its
 * delete.
 */
@ApplicationScoped
class RotationStageStore {

    /** Open the account's one stage, declaring [identityPublicKey]: any stage before it goes, with its grants. */
    fun open(userId: String, identityPublicKey: String, expiresAt: Instant): RotationStageEntity {
        deleteFor(userId)
        return RotationStageEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.identityPublicKey = identityPublicKey
            this.expiresAt = expiresAt
        }.also { it.persist() }
    }

    /** [userId]'s stage [stageId] while it lives at [now] (server-observed), or null — unknown, expired, replaced or
     *  another account's read alike. */
    fun live(userId: String, stageId: String, now: Instant): RotationStageEntity? =
        RotationStageEntity.find("id = ?1 and userId = ?2 and expiresAt > ?3", stageId, userId, now).firstResult()

    /** Upsert [grants] into [stageId] by scope. */
    fun upsert(stageId: String, grants: Collection<StagedSelfGrantRow>) {
        val existing = RotationStageGrantEntity.find("stageId = ?1 and scopeId in ?2", stageId, grants.map { it.scopeId }.toSet())
            .list().associateBy { it.scopeId }
        grants.forEach { g ->
            (existing[g.scopeId] ?: RotationStageGrantEntity().apply {
                id = UUID.randomUUID().toString()
                this.stageId = stageId
                scopeId = g.scopeId
            }).apply {
                epoch = g.epoch
                wrappedGrant = g.wrappedGrant
                grantSignature = g.grantSignature
            }.persist()
        }
    }

    /** Upsert [invites] into [stageId] by invite. */
    fun upsertInvites(stageId: String, invites: Collection<StagedInviteRow>) {
        val existing = RotationStageInviteEntity.find("stageId = ?1 and inviteId in ?2", stageId, invites.map { it.inviteId }.toSet())
            .list().associateBy { it.inviteId }
        invites.forEach { i ->
            (existing[i.inviteId] ?: RotationStageInviteEntity().apply {
                id = UUID.randomUUID().toString()
                this.stageId = stageId
                inviteId = i.inviteId
            }).apply {
                anchorEpoch = i.anchorEpoch
                inviteKeySignature = i.inviteKeySignature
                anchorGrantSignature = i.anchorGrantSignature
            }.persist()
        }
    }

    /** The invites staged into [stageId], by invite id. */
    fun invites(stageId: String): Map<String, StagedInviteRow> =
        RotationStageInviteEntity.find("stageId", stageId).list()
            .associate { it.inviteId to StagedInviteRow(it.inviteId, it.anchorEpoch, it.inviteKeySignature, it.anchorGrantSignature) }

    /** Upsert [entries] into [stageId] by (tenant, member). */
    fun upsertEndorsements(stageId: String, entries: Collection<StagedEndorsementRow>) {
        val existing = RotationStageEndorsementEntity.find("stageId", stageId).list().associateBy { it.tenantId to it.userId }
        entries.forEach { e ->
            (existing[e.tenantId to e.userId] ?: RotationStageEndorsementEntity().apply {
                id = UUID.randomUUID().toString()
                this.stageId = stageId
                tenantId = e.tenantId
                userId = e.userId
            }).apply {
                recordDigest = e.recordDigest
                endorsement = e.endorsement
            }.persist()
        }
    }

    /** The join-record entries staged into [stageId], by (tenant, member). */
    fun endorsements(stageId: String): Map<Pair<String, String>, StagedEndorsementRow> =
        RotationStageEndorsementEntity.find("stageId", stageId).list()
            .associate { (it.tenantId to it.userId) to StagedEndorsementRow(it.tenantId, it.userId, it.recordDigest, it.endorsement) }

    /** The grants staged into [stageId], by scope. */
    fun grants(stageId: String): Map<String, StagedSelfGrantRow> =
        RotationStageGrantEntity.find("stageId", stageId).list()
            .associate { it.scopeId to StagedSelfGrantRow(it.scopeId, it.epoch, it.wrappedGrant, it.grantSignature) }

    /** Delete [userId]'s stage and its grants, invites and endorsements (the database cascades them) — the publish's last step, and a re-open's
     *  first. */
    fun deleteFor(userId: String) {
        RotationStageEntity.delete("userId", userId)
    }

    /** Delete every stage expired at [now]; their rows cascade. Returns how many. */
    fun purgeExpired(now: Instant): Long = RotationStageEntity.delete("expiresAt <= ?1", now)
}

/**
 * Scheduled sweep over abandoned stages (ADR 0111 decision 6) — the `PairingPurge` pattern. Hygiene, not
 * the boundary: the publish and every chunk refuse an expired stage themselves ([RotationStageStore.live]).
 */
@ApplicationScoped
class RotationStagePurge(private val store: RotationStageStore) {

    // @Transactional on run() so the whole sweep shares one session (the Quarkus @Scheduled + Panache pattern, cr-0053).
    @Transactional
    @Scheduled(every = "{veyq.account.rotation.stage.purge.every}")
    fun run() = sweep(Instant.now())

    /** [run] at the clock [now] — a test's way to sweep a stage as expired without waiting out its TTL. */
    @Transactional
    fun sweep(now: Instant) {
        store.purgeExpired(now)
    }
}
