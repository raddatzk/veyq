package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.content.favorites.FavoritesEntity
import com.veyq.app.features.content.itemfavorites.ItemFavoritesEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.MediaDerivativeEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.UploadSessionEntity
import com.veyq.app.features.sharing.ShareEntity
import com.veyq.app.features.sharing.ShareIdentityEntity
import com.veyq.app.features.tasks.JobDeclineEntity
import com.veyq.app.features.tasks.JobEntity
import com.veyq.app.shared.blobstore.StoreObjectRef
import com.veyq.app.shared.blobstore.StoreReclaimStore
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant

/**
 * Scheduled hard-purge of soft-deleted groups (uc.spaces.delete-group, cr-0090, ADR 0014 §6). A group the owner
 * soft-deleted enters a grace period; once its `deletedAt` is older than the retention window, this erases it for
 * good — every row that references the scope, plus a reclaim entry for each blob's store objects (originals +
 * derivatives + covers all live in `veyq_blob`, which carries the scope). Operator-blind-safe: erasure only, no
 * key/plaintext (unlike a removal's key rotation). Mirrors the soft-then-hard purge pattern
 * (`TenantInvitePurge`/`SessionPurge`, cr-0011).
 *
 * **This job no longer deletes store objects, and that removed a hazard rather than adding latency** (ADR 0062
 * Decision 4). It used to delete them *first, inside* the per-scope transaction — so a rollback restored rows
 * pointing at bytes that were already gone, a presigned URL that 404s, which ADR 0056 § Context 2 names as the
 * skew the read path does not handle. Writing reclaim rows in that same transaction has no such mirror: a
 * rollback takes the reclaim entries with it, exactly as it takes the row deletes.
 *
 * Deliberately **not** routed through `MediaStore.purgeMediaRows` (the shared FK-ordered per-media purge,
 * cr-0135): that helper erases one media's rows, while this erases set-based by scope. It is the wrong shape
 * here, not a fourth copy of it.
 */
@ApplicationScoped
class ScopePurge(
    private val store: ScopesStore,
    private val reclaim: StoreReclaimStore,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(ScopePurge::class.java)
    private val retention: Duration = config.scope().delete().retention()

    // One transaction PER SCOPE, not one for the tick. A tick-wide `@Transactional` here meant any single scope
    // that could not be erased rolled back every *other* scope in the same sweep — and did so again on every
    // later tick, so one stuck group halted all reclamation permanently and silently, on the operator's own disk
    // (uc.spaces.delete-group § main flow 5). A failure is therefore caught, LOGGED and stepped over: swallowing
    // it would rebuild the silent `runCatching` of docs/inbox.md #130 one level up, and isolation nobody can see
    // is worse than none. `QuarkusTransaction` rather than `@Transactional` for the reason `NodeSubtreePurge`
    // uses it — a self-invoked `@Transactional` method is never intercepted, so the annotation would be a lie.
    // `purge()` stays the externally-callable transactional entry (tests / a future manual re-key).
    @Scheduled(every = "{veyq.scope.delete.purge.every}")
    fun run() {
        val purgeable = QuarkusTransaction.requiringNew().call { store.listPurgeable(Instant.now().minus(retention)) }
        purgeable.forEach { scopeId ->
            try {
                QuarkusTransaction.requiringNew().run { eraseScope(scopeId) }
            } catch (e: Exception) {
                log.error("hard-erasure of scope $scopeId failed; it stays purgeable and the sweep continues", e)
            }
        }
    }

    /** Hard-erase one scope now (external entry — transactional). */
    @Transactional
    fun purge(scopeId: String) = eraseScope(scopeId)

    /**
     * Erase one scope and everything under it, FK-safe: each blob's store objects are recorded for reclaim while
     * their rows still name the storage keys, then the DB rows go child-first — epoch links before the scope row
     * that owns the FK. Most child tables carry `scope_id`; `veyq_media_derivative` is deleted by its media ids,
     * `veyq_job_decline` by its job ids and `veyq_share_identity` by its share ids.
     *
     * The read-before-delete order survives; only what it feeds changed. The objects themselves are
     * `StoreReclaimSweep`'s, which is why nothing here can leave a row pointing at bytes that are already gone.
     *
     * **This list is the enumeration, and `scripts/validate_scope_erasure_arity.py` is what keeps it complete.**
     * The rule is not "remember to add the new table" but *every table reachable by foreign key from
     * `veyq_scope` is deleted here or carries `ON DELETE CASCADE`* — anything else refuses the final
     * `ScopeEntity.deleteById` and the group can never be erased at all. Four tables were missing when the guard
     * was written, and the two job ones had been blocking every real tick since `veyq_job` was introduced.
     */
    private fun eraseScope(scopeId: String) {
        val blobs = BlobEntity.find("scopeId", scopeId).list()
        reclaim.record(blobs.map { StoreObjectRef(it.storageKey, it.chunkCount) })

        val mediaIds = MediaEntity.find("scopeId", scopeId).list().map { it.id }
        if (mediaIds.isNotEmpty()) MediaDerivativeEntity.delete("mediaId in ?1", mediaIds)
        MediaEntity.delete("scopeId", scopeId)
        BlobEntity.delete("scopeId", scopeId)
        UploadSessionEntity.delete("scopeId", scopeId)
        FavoritesEntity.delete("scopeId", scopeId)
        ItemFavoritesEntity.delete("scopeId", scopeId)
        NodeEntity.delete("scopeId", scopeId)
        ScopeEpochEntity.delete("scopeId", scopeId)
        MembershipEntity.delete("scopeId", scopeId)

        val jobIds = JobEntity.find("scopeId", scopeId).list().map { it.id }
        if (jobIds.isNotEmpty()) JobDeclineEntity.delete("jobId in ?1", jobIds)
        JobEntity.delete("scopeId", scopeId)

        // Dormant today — v1 shares are personal-scope-only (ADR 0029 §6) and this runs on GROUP scopes — and
        // erasing rather than retaining is the operator-blind answer, not a concession to the foreign key: the
        // row holds the owner, the node and a sealed name of a group whose erasure its owner asked for, and with
        // the scope gone there is no capability left for it to revoke.
        val shareIds = ShareEntity.find("scopeId", scopeId).list().map { it.id }
        if (shareIds.isNotEmpty()) ShareIdentityEntity.delete("shareId in ?1", shareIds)
        ShareEntity.delete("scopeId", scopeId)

        ScopeEntity.deleteById(scopeId)
    }
}
