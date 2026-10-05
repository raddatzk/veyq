package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.StoreObjectRef
import me.raddatz.veyq.core.blobstore.StoreReclaimStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Persistence of the generic blob upload (ADR 0013): the upload sessions, the blob rows a verified session becomes,
 * and their manifest. Operator-blind: it stores ciphertext wraps and opaque metadata only. It reads no application
 * table — what a blob is attached to is the application's ([UploadCompletion]), which calls [persistBlob] and
 * [deleteBlobRow] inside its own transaction. Who may act on a scope is decided before this store is reached, by the
 * caller's `ScopeAccess` gate.
 */
@ApplicationScoped
class BlobUploadStore(private val reclaim: StoreReclaimStore) {

    /** Is the client-minted [blobId] already a blob, or promised to another open session — in any scope? */
    fun blobIdTaken(blobId: String): Boolean =
        BlobEntity.findById(blobId) != null || UploadSessionEntity.count("blobId", blobId) > 0

    @Transactional
    fun createSession(scopeId: String, opener: UploadOpener, storageKey: String, d: UploadDeclare): String {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        UploadSessionEntity().apply {
            this.id = id
            this.scopeId = scopeId
            openerUserId = (opener as? UploadOpener.Member)?.userId
            openerShareIdentityId = (opener as? UploadOpener.Contributor)?.shareIdentityId
            this.storageKey = storageKey
            chunkCount = d.chunkCount
            byteSize = d.byteSize
            wrappedBlobKey = d.wrappedBlobKey
            blobId = d.blobId
            ownerId = d.ownerId
            blobRole = d.role
            createdAt = now
            lastWindowAt = now
        }.persist()
        return id
    }

    /**
     * The open session [uploadId] in [scopeId] — **only for the [opener] who started it** (ADR 0013 §2). Every
     * operation that acts on a session (a window, a complete, a cover commit) resolves it here, so a second member
     * or a second identity on the same link cannot finish, extend or attach another's upload by knowing its id.
     * A session from before the opener was recorded carries none and matches nobody.
     */
    fun findSession(scopeId: String, uploadId: String, opener: UploadOpener): UploadSessionEntity? =
        UploadSessionEntity.findById(uploadId)?.takeIf { it.scopeId == scopeId && opener.opened(it) }

    /** Is session [uploadId] gone — spent by a complete that committed since it was read? A count, not [findSession]:
     *  outside a transaction the request's persistence context still holds the row it read, so a second `findById`
     *  would answer from that cache and never see the delete. */
    fun sessionSpent(uploadId: String): Boolean = UploadSessionEntity.count("id", uploadId) == 0L

    /** Mark session [uploadId] live now: a window was issued for it, which is the stale-session purge's clock. */
    @Transactional
    fun touchWindow(uploadId: String) {
        UploadSessionEntity.update("lastWindowAt = ?1 where id = ?2", Instant.now(), uploadId)
    }

    /** Persist a blob row from a verified session, under the client-minted id its wrap is bound to (ADR 0072).
     *  The handler checked [UploadSessionEntity.boundTo] first, so the id is present. Joins the caller's transaction:
     *  the application that attaches the blob spends the session and writes its own rows in the same one. */
    fun persistBlob(session: UploadSessionEntity, manifest: List<Long>?): String {
        val blobId = checkNotNull(session.blobId) { "a session without a blob id is refused before it is attached" }
        BlobEntity().apply {
            id = blobId
            scopeId = session.scopeId
            storageKey = session.storageKey
            chunkCount = session.chunkCount
            byteSize = session.byteSize
            wrappedBlobKey = session.wrappedBlobKey
            this.manifest = manifest?.let { encodeManifest(it) }
            createdAt = Instant.now()
        }.persist()
        return blobId
    }

    /**
     * Hard-delete every OPEN upload session whose last window was issued before [cutoff] (its `start` is its first
     * window), across all scopes, recording the objects
     * its client may already have staged in the store (uc.memory.upload E2 / en.memory.media E3). The row is the
     * **only** pointer to its `storage_key`, so dropping it without recording orphans those objects permanently
     * — and the record is written **in this transaction**, which is the guarantee the old post-commit delete
     * could not give. Two sources feed this: an upload cancelled or killed mid-flight, and a session the
     * `complete` budget gate rejected ([BlobUploadHandler] reclaims that one's objects but leaves the row). The
     * reclaim row carries the full declared `chunk_count`; an abandoned session has an unknown number of those
     * actually present, so the store delete must tolerate absent keys — which it does, `deleteObject` being
     * idempotent. Returns how many sessions were purged.
     */
    @Transactional
    fun purgeSessionsIdleSince(cutoff: Instant): Int {
        // The selection is the lock, re-checked on what it locked: an upload whose next window committed while this
        // waited keeps its session. Selected by a plain read, a live upload's session was dropped mid-transfer.
        val stale = UploadSessionEntity.find("lastWindowAt < ?1", cutoff)
            .withLock(LockModeType.PESSIMISTIC_WRITE).list()
            .filter { it.lastWindowAt.isBefore(cutoff) }
        reclaim.record(stale.map { StoreObjectRef(it.storageKey, it.chunkCount) })
        stale.forEach { UploadSessionEntity.deleteById(it.id) }
        return stale.size
    }

    fun findBlob(scopeId: String, blobId: String): BlobEntity? =
        BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId }

    /** Delete a blob row by id (if present) and put its store objects on the reclaim list, in the caller's
     *  transaction (cover + trash purge). The row is the only thing that knows the `storage_key`, so the record
     *  and the delete are one step, not two. */
    fun deleteBlobRow(blobId: String?) {
        val blob = blobId?.let { BlobEntity.findById(it) } ?: return
        reclaim.record(blob.storageKey, blob.chunkCount)
        BlobEntity.deleteById(blob.id)
    }

    // Manifest = a JSON array of per-chunk ciphertext sizes; tiny and integer-only, so encoded by hand.
    fun encodeManifest(sizes: List<Long>): String = sizes.joinToString(",", "[", "]")

    fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }
}
