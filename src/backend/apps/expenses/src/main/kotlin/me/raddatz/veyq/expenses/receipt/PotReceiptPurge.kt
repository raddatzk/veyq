package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.time.Instant
import org.jboss.logging.Logger

/**
 * The receipts' retention purge (ADR 0108 decision 7, docs/apps/expenses.md § Receipts): a receipt goes once the trash
 * retention (`veyq.node.trash.retention`, core's node retention — Expenses has no key of its own) has passed since
 *
 * - a revision of its entry dropped it ([PotReceiptEntity.droppedAt]);
 * - its entry's latest revision, a tombstone, was written — a restore within the retention is a later revision that is
 *   no tombstone, so it keeps them with nothing to clear: the rule reads the latest revision when it runs;
 * - it was completed, while its entry has no revision at all (an entry whose first revision never came).
 *
 * [PotReceiptStore.purgeAfter] serves the same rule as each receipt's `purgeAfter`. The irreversible half of the
 * reversible/irreversible split: no member's or guest's action erases a receipt at once, only this system job. Its
 * blob rows go through core's `BlobUploadStore.deleteBlobRows`, which puts the store objects on the reclaim list in the
 * same transaction (ADR 0062: `StoreReclaimSweep` deletes them, never this). A content block on a purged receipt ends
 * with it ([MeasureTargets.erased]); a guest's write record of it goes with it.
 *
 * **One transaction per scope**, a failure logged and stepped over (`NodeSubtreePurge.run`'s shape). Under the scope
 * row's lock the candidates are selected, then locked in the order every Pot writer takes ([PotReceiptStore]): the
 * records' revisions, the receipts, the Pots' node rows — and judged again on what is committed then, so a restore that
 * committed while this waited is seen and its receipts stay (`PotReceiptRaceSliceTest`). The cutoff therefore holds in
 * two layers, the candidates' query and that judging again: either alone keeps a receipt that is not yet due, so a test
 * that breaks only one stays green — a bite of the cutoff breaks both (`PotReceiptSliceTest`).
 */
@ApplicationScoped
class PotReceiptPurge(
    private val store: PotReceiptStore,
    private val blobs: BlobUploadStore,
    private val items: NodeItemsRevisions,
    private val measures: MeasureTargets,
    private val guestWriteRecords: GuestWriteRecords,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(PotReceiptPurge::class.java)
    private val retention: Duration = config.node().trash().retention()

    @Scheduled(every = "{veyq.node.trash.purge.every}")
    fun run() {
        purgeBefore(Instant.now().minus(retention))
    }

    /** Erase every receipt due before [cutoff] — the job's body, with the cutoff (and, for a test, the one scope [onlyScope])
     *  a caller chooses. Answers how many went. */
    fun purgeBefore(cutoff: Instant, onlyScope: String? = null): Int {
        val scopes = QuarkusTransaction.requiringNew().call { candidates(onlyScope, cutoff).map { it.scopeId }.distinct().sorted() }
        var purged = 0
        scopes.forEach { scopeId ->
            try {
                purged += QuarkusTransaction.requiringNew().call { purgeIn(scopeId, cutoff) }
            } catch (e: Exception) {
                log.error("purge of the expired receipts in scope $scopeId failed; they stay due and the sweep continues", e)
            }
        }
        return purged
    }

    /** The purge of one scope, in its caller's transaction (see the class). */
    private fun purgeIn(scopeId: String, cutoff: Instant): Int {
        ScopeEntity.lockRow(scopeId) ?: return 0 // the scope's own purge erased its receipts with it
        val selected = candidates(scopeId, cutoff)
        if (selected.isEmpty()) return 0
        val entryIds = selected.map { it.entryId }.distinct().sorted()
        entryIds.chunked(CHUNK).forEach {
            PotRevisionEntity.find("entryId in ?1 order by entryId, revision", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
        val locked = selected.map { it.id }.distinct().sorted().chunked(CHUNK).flatMap {
            PotReceiptEntity.find("id in ?1 order by id", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
        val potIds = locked.map { it.nodeId }.distinct().sorted()
        potIds.forEach { lockNode(it) }
        // Judged again on what is committed now: every writer of these rows held the locks taken above.
        locked.forEach { PotReceiptEntity.getEntityManager().refresh(it) }
        val latest = store.latestRevisions(entryIds)
        val doomed = locked.filter { r ->
            PotReceiptStore.purgeAfter(r, latest[r.entryId]?.takeIf { it.nodeId == r.nodeId }, Duration.ZERO)?.isBefore(cutoff) == true
        }
        if (doomed.isEmpty()) return 0
        val blobIds = doomed.map { it.blobId }
        StoredObjectLocks.lockOf(scopeId, blobIds) // every object at once, in the one order, before the first goes
        val ids = doomed.map { it.id }
        guestWriteRecords.forget(ids)
        ids.chunked(CHUNK).forEach { PotReceiptEntity.delete("id in ?1", it) }
        blobs.deleteBlobRows(scopeId, blobIds)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), ids)
        doomed.map { it.nodeId }.distinct().forEach { items.bump(scopeId, it) } // listed rows went: open lists re-read
        return doomed.size
    }

    /** Every receipt (of [scopeId], or of any scope) due before [cutoff] by a plain read — what a scope's pass then locks
     *  and judges again. */
    private fun candidates(scopeId: String?, cutoff: Instant): List<PotReceiptEntity> {
        val scope = if (scopeId == null) "" else "r.scopeId = :scope and "
        fun query(where: String) = PotReceiptEntity.getEntityManager()
            .createQuery("select r from PotReceiptEntity r where $scope$where", PotReceiptEntity::class.java)
            .setParameter("cutoff", cutoff)
            .also { q -> if (scopeId != null) q.setParameter("scope", scopeId) }
            .resultList
        val dropped = query("r.droppedAt is not null and r.droppedAt < :cutoff")
        // A tombstone older than the cutoff exists for the record; whether it is still the latest is judged per row.
        val deleted = query(
            "r.droppedAt is null and exists (select 1 from PotRevisionEntity v where v.entryId = r.entryId " +
                "and v.nodeId = r.nodeId and v.tombstone = true and v.createdAt < :cutoff)",
        )
        val orphaned = query(
            "r.droppedAt is null and r.createdAt < :cutoff and not exists (select 1 from PotRevisionEntity v " +
                "where v.entryId = r.entryId and v.nodeId = r.nodeId)",
        )
        val all = (dropped + deleted + orphaned).distinctBy { it.id }
        if (deleted.isEmpty()) return all
        val latest = store.latestRevisions(deleted.map { it.entryId })
        return all.filter { r ->
            PotReceiptStore.purgeAfter(r, latest[r.entryId]?.takeIf { it.nodeId == r.nodeId }, Duration.ZERO)?.isBefore(cutoff) == true
        }
    }

    /** A Pot's node row, `FOR UPDATE` — the lock every inserter of its revisions and receipts holds. */
    private fun lockNode(nodeId: String) {
        NodeEntity.find("id", nodeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
    }

    companion object {
        private const val CHUNK = 500
    }
}
