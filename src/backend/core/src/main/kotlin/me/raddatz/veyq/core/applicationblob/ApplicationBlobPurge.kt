package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.time.Instant
import org.jboss.logging.Logger

/**
 * An application's erase at once of the blobs its owners hold (ADR 0113 decision 5): in the application's own write,
 * which erases or has erased the owners themselves — a member emptying a medium from the trash, a guest deleting its own
 * upload, the erasure ledger erasing a medium again, the application's own trash purge. The irreversible half is still
 * the platform's: the rows go, their blob rows go through core's `BlobUploadStore.deleteBlobRows`, which puts their
 * store objects on the reclaim list in this transaction (ADR 0062), the guest-write records naming them go, and a block
 * on one ends with it. Released or kept alike. It moves no item revision: the caller's write does, where a listed row
 * went.
 *
 * **Lock order:** the caller holds its own rows; this locks the owners' rows (step 3 of [ApplicationBlobs]' order),
 * then the blob rows (`StoredObjectLocks`, inside `deleteBlobRows`).
 */
@ApplicationScoped
class ApplicationBlobErasure(
    private val blobs: BlobUploadStore,
    private val measures: MeasureTargets,
    private val guestWriteRecords: GuestWriteRecords,
) {
    /** Erase every blob the owners [ownerIds] hold in [scopeId], at once. Answers how many went. */
    fun eraseOwners(scopeId: String, ownerIds: Collection<String>): Int {
        val rows = ownerIds.distinct().sorted().chunked(ApplicationBlobs.CHUNK).flatMap {
            ApplicationBlobEntity.find("scopeId = ?1 and ownerId in ?2 order by id", scopeId, it)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
        if (rows.isEmpty()) return 0
        val ids = rows.map { it.id }
        guestWriteRecords.forget(ids)
        ids.chunked(ApplicationBlobs.CHUNK).forEach { ApplicationBlobEntity.delete("id in ?1", it) }
        blobs.deleteBlobRows(scopeId, rows.map { it.blobId }) // the service rows first: fk_application_blob_blob
        measures.erased(listOf(BlockKind.CONTENT_ITEM), ids)
        return rows.size
    }
}

/**
 * The service's retention purge (ADR 0112 decision 2): an application blob goes once the trash retention
 * (`veyq.node.trash.retention`, core's node retention — no application has a key of its own) has passed since
 *
 * - it was released ([ApplicationBlobEntity.releasedAt]) — by name, or with its owner's end; an owner's later keep (a
 *   restore within the retention) clears the release, so the rule reads the row when it runs;
 * - it was completed, while nothing ever kept it ([ApplicationBlobEntity.keptAt] null and no release: a record whose
 *   first write never came).
 *
 * [ApplicationBlobEntity.purgeAfter] serves the same rule as each blob's `purgeAfter`. The irreversible half of the
 * reversible/irreversible split: no member's or guest's action erases an application blob at once but through its
 * application's own erase of the owner ([ApplicationBlobErasure], ADR 0113 decision 5) — otherwise only this system job.
 * It reads only the service's own rows, so it takes no application lock: the application's writes keep and release
 * under the service's row locks, which this takes too. Its blob rows go through core's `BlobUploadStore.deleteBlobRows`,
 * which puts the store objects on the reclaim list in the same transaction (ADR 0062: `StoreReclaimSweep` deletes them,
 * never this). A content block on a purged blob ends with it ([MeasureTargets.erased]); a guest's write record of it goes
 * with it.
 *
 * **One transaction per scope**, a failure logged and stepped over (`NodeSubtreePurge.run`'s shape). Under the scope
 * row's lock the candidates are selected, then locked in the order every writer of a node's blobs takes
 * ([ApplicationBlobs]): the service's rows, the nodes' rows — and judged again on what is committed then, so a keep that
 * committed while this waited (a restore) is seen and its blobs stay (`PotReceiptRaceSliceTest`, run with its first
 * application in `:apps:expenses`). The cutoff
 * therefore holds in two layers, the candidates' query and that judging again: either alone keeps a blob that is not yet
 * due, so a test that breaks only one stays green — a bite of the cutoff breaks both (`PotReceiptSliceTest`).
 */
@ApplicationScoped
class ApplicationBlobPurge(
    private val blobs: BlobUploadStore,
    private val items: NodeItemsRevisions,
    private val measures: MeasureTargets,
    private val guestWriteRecords: GuestWriteRecords,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(ApplicationBlobPurge::class.java)
    private val retention: Duration = config.node().trash().retention()

    @Scheduled(every = "{veyq.node.trash.purge.every}")
    fun run() {
        purgeBefore(Instant.now().minus(retention))
    }

    /** Erase every application blob due before [cutoff] — the job's body, with the cutoff (and, for a test, the one scope
     *  [onlyScope]) a caller chooses. Answers how many went. */
    fun purgeBefore(cutoff: Instant, onlyScope: String? = null): Int {
        val scopes = QuarkusTransaction.requiringNew().call { candidates(onlyScope, cutoff).map { it.scopeId }.distinct().sorted() }
        var purged = 0
        scopes.forEach { scopeId ->
            try {
                purged += QuarkusTransaction.requiringNew().call { purgeIn(scopeId, cutoff) }
            } catch (e: Exception) {
                log.error("purge of the expired application blobs in scope $scopeId failed; they stay due and the sweep continues", e)
            }
        }
        return purged
    }

    /** The purge of one scope, in its caller's transaction (see the class). */
    private fun purgeIn(scopeId: String, cutoff: Instant): Int {
        ScopeEntity.lockRow(scopeId) ?: return 0 // the scope's own purge erased its blobs with it
        val selected = candidates(scopeId, cutoff)
        if (selected.isEmpty()) return 0
        val locked = selected.map { it.id }.distinct().sorted().chunked(ApplicationBlobs.CHUNK).flatMap {
            ApplicationBlobEntity.find("id in ?1 order by id", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
        locked.map { it.nodeId }.distinct().sorted().forEach { lockNode(it) }
        // Judged again on what is committed now: every writer of these rows held the locks taken above.
        locked.forEach { ApplicationBlobEntity.getEntityManager().refresh(it) }
        val doomed = locked.filter { ApplicationBlobEntity.purgeAfter(it, Duration.ZERO)?.isBefore(cutoff) == true }
        if (doomed.isEmpty()) return 0
        val blobIds = doomed.map { it.blobId }
        StoredObjectLocks.lockOf(scopeId, blobIds) // every object at once, in the one order, before the first goes
        val ids = doomed.map { it.id }
        guestWriteRecords.forget(ids)
        ids.chunked(ApplicationBlobs.CHUNK).forEach { ApplicationBlobEntity.delete("id in ?1", it) }
        blobs.deleteBlobRows(scopeId, blobIds)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), ids)
        doomed.map { it.nodeId }.distinct().forEach { items.bump(scopeId, it) } // listed rows went: open lists re-read
        return doomed.size
    }

    /** Every application blob (of [scopeId], or of any scope) due before [cutoff] by a plain read — what a scope's pass
     *  then locks and judges again. */
    private fun candidates(scopeId: String?, cutoff: Instant): List<ApplicationBlobEntity> {
        val scope = if (scopeId == null) "" else "a.scopeId = :scope and "
        return ApplicationBlobEntity.getEntityManager()
            .createQuery(
                "select a from ApplicationBlobEntity a where $scope((a.releasedAt is not null and a.releasedAt < :cutoff) or " +
                    "(a.releasedAt is null and a.keptAt is null and a.createdAt < :cutoff))",
                ApplicationBlobEntity::class.java,
            )
            .setParameter("cutoff", cutoff)
            .also { q -> if (scopeId != null) q.setParameter("scope", scopeId) }
            .resultList
    }

    /** A node's row, `FOR UPDATE` — the lock every inserter of its blobs holds. */
    private fun lockNode(nodeId: String) {
        NodeEntity.find("id", nodeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
    }
}
