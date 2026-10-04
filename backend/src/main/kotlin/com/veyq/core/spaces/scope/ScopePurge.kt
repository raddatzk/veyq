package com.veyq.core.spaces.scope

import com.veyq.core.blob.BlobEntity
import com.veyq.core.blob.UploadSessionEntity
import com.veyq.core.scope.ScopeEraser
import com.veyq.core.tasks.JobDeclineEntity
import com.veyq.core.tasks.JobEntity
import com.veyq.core.blobstore.StoreObjectRef
import com.veyq.core.blobstore.StoreReclaimStore
import com.veyq.core.config.VeyqConfig
import com.veyq.core.membership.MembershipEntity
import com.veyq.core.scope.ScopeEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import org.jboss.logging.Logger

/**
 * Scheduled hard-purge of soft-deleted groups (uc.spaces.delete-group, cr-0090, ADR 0014 §6). A group the owner
 * soft-deleted enters a grace period; once its `deletedAt` is older than the retention window, this erases it for
 * good — every row that references the scope, plus a reclaim entry for each blob's store objects (originals +
 * derivatives + covers all live in `veyq_blob`, which carries the scope) and for each upload session still open in
 * it (`veyq_upload_session`, the other table whose row is the only pointer to a `storage_key`).
 * Operator-blind-safe: erasure only, no key/plaintext (unlike a removal's key rotation). Mirrors the
 * soft-then-hard purge pattern (`TenantInvitePurge`/`SessionPurge`, cr-0011).
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
    // MutableList, not List: ArC rejects an injection point typed kotlin.collections.List with @All (Kotlin's List is
    // a java.util.List<? extends E> wildcard); the explicit phase, not bean order, decides who runs first.
    @All erasers: MutableList<ScopeEraser>,
) {
    // Sorted by the explicit phase, never by bean order; split once at core's own blob delete (ScopeEraser).
    private val sorted = erasers.sortedBy { it.phase }
    private val beforeBlobRows = sorted.filter { it.phase < ScopeEraser.BLOB_ROWS_PHASE }
    private val afterBlobRows = sorted.filter { it.phase > ScopeEraser.BLOB_ROWS_PHASE }

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
        val cutoff = Instant.now().minus(retention)
        val purgeable = QuarkusTransaction.requiringNew().call { store.listPurgeable(cutoff) }
        purgeable.forEach { scopeId ->
            try {
                QuarkusTransaction.requiringNew().run { eraseScope(scopeId, cutoff) }
            } catch (e: Exception) {
                log.error("hard-erasure of scope $scopeId failed; it stays purgeable and the sweep continues", e)
            }
        }
    }

    /** Hard-erase one scope now (external entry — transactional). */
    @Transactional
    fun purge(scopeId: String) = eraseScope(scopeId)

    /**
     * Erase one scope and everything under it, FK-safe: each blob's and each open upload session's store objects
     * are recorded for reclaim while their rows still name the storage keys, then the DB rows go child-first —
     * epoch links before the scope row that owns the FK. Most child tables carry `scope_id`;
     * `veyq_job_decline` is deleted by its job ids. The media, favorites, node and share rows are the
     * [ScopeEraser] implementations' (their owning packages), run in `phase` order around core's blob delete.
     *
     * The read-before-delete order survives; only what it feeds changed. The objects themselves are
     * `StoreReclaimSweep`'s, which is why nothing here can leave a row pointing at bytes that are already gone.
     *
     * **This list plus every [ScopeEraser] is the enumeration, and `scripts/validate_scope_erasure_arity.py` is what keeps it complete.**
     * The rule is not "remember to add the new table" but *every table reachable by foreign key from
     * `veyq_scope` is deleted here or carries `ON DELETE CASCADE`* — anything else refuses the final
     * `ScopeEntity.deleteById` and the group can never be erased at all. Four tables were missing when the guard
     * was written, and the two job ones had been blocking every real tick since `veyq_job` was introduced.
     */
    private fun eraseScope(scopeId: String, cutoff: Instant? = null) {
        // The scope row's lock first, and the worklist's predicate again under it: a restore takes the same lock
        // ([ScopesStore.restore]). Decided only by the worklist's earlier read, a group restored in between was erased
        // anyway (developer-styleguide §12). [purge] is the forced erase, and asks no cutoff.
        val scope = ScopeEntity.lockRow(scopeId) ?: return
        if (cutoff != null && scope.deletedAt?.isBefore(cutoff) != true) return
        val blobs = BlobEntity.find("scopeId", scopeId).list()
        reclaim.record(blobs.map { StoreObjectRef(it.storageKey, it.chunkCount) })
        // An upload still OPEN in the scope — last windowed inside `veyq.media.upload.retention`, so its own sweep has
        // not reached it — may already have chunks in the store, and its row is their only pointer. Recorded
        // exactly as `MediaStore.purgeSessionsIdleSince` does; a session a budget rejection already recorded
        // is recorded twice, which the reclaim list tolerates (no unique key, idempotent object delete).
        val sessions = UploadSessionEntity.find("scopeId", scopeId).list()
        reclaim.record(sessions.map { StoreObjectRef(it.storageKey, it.chunkCount) })

        // Phases below BLOB_ROWS_PHASE: rows that reference veyq_blob (media, derivatives) go first.
        beforeBlobRows.forEach { it.eraseScope(scopeId) }
        BlobEntity.delete("scopeId", scopeId)
        UploadSessionEntity.delete("scopeId", scopeId)
        // Phases above it: favorites, item favorites, nodes, the group's links (ADR 0029 §6/§7).
        afterBlobRows.forEach { it.eraseScope(scopeId) }
        ScopeEpochEntity.delete("scopeId", scopeId)
        MembershipEntity.delete("scopeId", scopeId)

        // `veyq_job_decline` references `veyq_job`, so declines go first, by job id.
        val jobIds = JobEntity.find("scopeId", scopeId).list().map { it.id }
        if (jobIds.isNotEmpty()) JobDeclineEntity.delete("jobId in ?1", jobIds)
        JobEntity.delete("scopeId", scopeId)

        ScopeEntity.deleteById(scopeId)
    }
}
