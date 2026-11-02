package me.raddatz.veyq.memories.testing

import me.raddatz.veyq.core.applicationblob.ApplicationBlobEntity
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.memories.media.MediaEntity
import java.time.Instant
import java.util.UUID

/**
 * A medium's blobs as core's blob service holds them (ADR 0113): one `veyq_application_blob` row per role, owned by the
 * medium, its id its blob's. For the tests that seed media straight into the tables, and those that read which blobs a
 * medium holds — the media row names none since the move.
 */
object MediaBlobRows {
    const val ORIGINAL = "ORIGINAL"

    /**
     * Seed [blobId] (a `veyq_blob` row the caller wrote) as the medium [mediaId]'s blob in [role], in the caller's
     * transaction, kept — or released at [releasedAt], as a trash leaves it. Its writer is [writerIdentityId] (a guest)
     * or [writerUserId] (a member; a fresh id when neither is given).
     */
    fun hold(
        scopeId: String, nodeId: String, mediaId: String, role: String, blobId: String,
        writerUserId: String? = null, writerIdentityId: String? = null, releasedAt: Instant? = null, now: Instant = Instant.now(),
    ): ApplicationBlobEntity = ApplicationBlobEntity().apply {
        id = blobId
        this.scopeId = scopeId
        this.nodeId = nodeId
        ownerId = mediaId
        this.role = role
        this.blobId = blobId
        this.writerIdentityId = writerIdentityId
        this.writerUserId = if (writerIdentityId == null) writerUserId ?: UUID.randomUUID().toString() else null
        createdAt = now
        keptAt = now
        this.releasedAt = releasedAt
    }.also { it.persist() }

    /**
     * Seed [blobId] as the original of the medium [medium] the caller just persisted, as its attach would have: its
     * writer the medium's guest identity, or the member its `created_by` names; released at its `deleted_at` when it is
     * seeded trashed, as a trash leaves it. [role] for a derivative.
     */
    fun holdFor(medium: MediaEntity, blobId: String, role: String = ORIGINAL): ApplicationBlobEntity = hold(
        medium.scopeId, medium.nodeId, medium.id, role, blobId,
        writerUserId = (medium.createdBy?.let { runCatching { PrincipalKey.parse(it) }.getOrNull() } as? PrincipalKey.User)?.userId,
        writerIdentityId = medium.shareIdentityId,
        releasedAt = medium.deletedAt,
    )

    /** The blob the medium [mediaId] holds in [role], or null — read in the caller's transaction. */
    fun blobOf(mediaId: String, role: String = ORIGINAL): String? =
        ApplicationBlobEntity.find("ownerId = ?1 and role = ?2", mediaId, role).firstResult()?.blobId

    /** Every blob the medium [mediaId] holds, its original first. */
    fun blobsOf(mediaId: String): List<String> =
        ApplicationBlobEntity.list("ownerId", mediaId).sortedBy { if (it.role == ORIGINAL) 0 else 1 }.map { it.blobId }

    /** How many service rows the medium [mediaId] still owns. */
    fun countOf(mediaId: String): Long = ApplicationBlobEntity.count("ownerId", mediaId)
}
