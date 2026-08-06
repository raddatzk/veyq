package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.content.favorites.FavoritesEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.MediaDerivativeEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.UploadSessionEntity
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Scheduled hard-purge of soft-deleted groups (uc.spaces.delete-group, cr-0090, ADR 0014 §6). A group the owner
 * soft-deleted enters a grace period; once its `deletedAt` is older than the retention window, this erases it for
 * good — the blob **objects** in the store (originals + derivatives + covers all live in `veyq_blob`, which carries
 * the scope) and then every row that references the scope. Operator-blind-safe: erasure only, no key/plaintext
 * (unlike a removal's key rotation). Mirrors the soft-then-hard purge pattern (`TenantInvitePurge`/`SessionPurge`,
 * cr-0011).
 *
 * Deliberately **not** routed through `MediaStore.purgeMediaRows` (the shared FK-ordered per-media purge,
 * cr-0135): that helper erases one media's rows, while this erases set-based by scope and objects-first. It is
 * the wrong shape here, not a fourth copy of it.
 */
@ApplicationScoped
class ScopePurge(
    private val store: ScopesStore,
    private val blobStore: BlobStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.scope().delete().retention()

    // @Transactional on run() so the whole sweep shares one session (the Quarkus @Scheduled + Panache pattern,
    // cr-0053) — and so the internal eraseScope() calls run transactionally (a self-invoked @Transactional would
    // bypass the interceptor). `purge()` is the externally-callable transactional entry (tests / a future manual
    // re-key), which the interceptor DOES wrap.
    @Transactional
    @Scheduled(every = "{veyq.scope.delete.purge.every}")
    fun run() {
        store.listPurgeable(Instant.now().minus(retention)).forEach { eraseScope(it) }
    }

    /** Hard-erase one scope now (external entry — transactional). */
    @Transactional
    fun purge(scopeId: String) = eraseScope(scopeId)

    /**
     * Erase one scope and everything under it, FK-safe: the blob **objects** go to the store first (their rows
     * still name the storage keys), then the DB rows child-first — epoch links before the scope row that owns the
     * FK. All child tables carry `scope_id` except `veyq_media_derivative`, deleted by its media ids.
     */
    private fun eraseScope(scopeId: String) {
        val blobs = BlobEntity.find("scopeId", scopeId).list()
        blobStore.delete(blobs.flatMap { b -> (0 until b.chunkCount).map { chunkKey(b.storageKey, it) } })

        val mediaIds = MediaEntity.find("scopeId", scopeId).list().map { it.id }
        if (mediaIds.isNotEmpty()) MediaDerivativeEntity.delete("mediaId in ?1", mediaIds)
        MediaEntity.delete("scopeId", scopeId)
        BlobEntity.delete("scopeId", scopeId)
        UploadSessionEntity.delete("scopeId", scopeId)
        FavoritesEntity.delete("scopeId", scopeId)
        NodeEntity.delete("scopeId", scopeId)
        ScopeEpochEntity.delete("scopeId", scopeId)
        MembershipEntity.delete("scopeId", scopeId)
        ScopeEntity.deleteById(scopeId)
    }
}
