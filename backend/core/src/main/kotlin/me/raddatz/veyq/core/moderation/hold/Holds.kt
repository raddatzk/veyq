package me.raddatz.veyq.core.moderation.hold

import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Taking and ending holds (ADR 0093 § 4, en.moderation.hold). A hold is a snapshot plus a pin, and **never** blocks: no
 * delete, overwrite or revoke reads it. The one hook into existing code is `StoreReclaimStore.due`, which leaves a pinned
 * key out until the pin's end — so the reclaim row of a held object is never attempted, and its `attempts` stay.
 */
@ApplicationScoped
class Holds(
    private val em: EntityManager,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All contributors: MutableList<HoldSnapshotContributor>,
) {
    private val contributors: List<HoldSnapshotContributor> = contributors.sortedBy { it.phase }

    /** Whether [caseId] holds a hold alive at [now] — a report that attaches takes none then (ADR 0093 § 5). */
    fun aliveFor(caseId: String, now: Instant): Boolean = HoldEntity.count("caseId = ?1 and expiresAt > ?2", caseId, now) > 0

    /**
     * Take a hold on [target] for [caseId], ending at [expiresAt], **in the caller's transaction** (the report's, under
     * the reported space's lock): the contributors copy their rows in phase order, then core copies the blob rows they
     * named and pins each one's storage key, the guest identities they named, and the space's epoch rows (the wraps a
     * held key chain needs). Answers the hold's id.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun take(caseId: String?, target: HoldTarget, trigger: HoldTrigger, expiresAt: Instant): String {
        val hold = HoldEntity().apply {
            id = UUID.randomUUID().toString()
            this.caseId = caseId
            this.trigger = trigger.name
            this.expiresAt = expiresAt
        }
        hold.persist()
        val snapshot = HoldSnapshot(hold.id, em)
        contributors.forEach { it.contribute(target, snapshot) }
        snapshot.copy("veyq_blob", "id", snapshot.blobIds).forEach { blob ->
            HoldPinEntity().apply {
                id = UUID.randomUUID().toString()
                holdId = hold.id
                storageKey = blob.getValue("storage_key")!!
                chunkCount = blob.getValue("chunk_count")!!.toInt()
                this.expiresAt = expiresAt
            }.persist()
        }
        snapshot.copy("veyq_share_identity", "id", snapshot.identityIds)
        snapshot.copy("veyq_scope_epoch", "scope_id", listOf(target.scopeId))
        return hold.id
    }

    /** Delete every hold past its end with its snapshot and its pins; the sweep then catches up on its own. Answers how many. */
    @Transactional
    fun purgeExpired(now: Instant): Int {
        val ended = HoldEntity.list("expiresAt <= ?1", now).map { it.id }
        if (ended.isEmpty()) return 0
        ended.chunked(500).forEach { ids ->
            HoldPinEntity.delete("holdId in ?1", ids)
            HoldRowEntity.delete("holdId in ?1", ids)
            HoldEntity.delete("id in ?1", ids)
        }
        return ended.size
    }

    companion object {
        /** A case-open hold's bound (ADR 0093 § 4): the decision, at the latest 90 days after the report. */
        val CASE_OPEN: Duration = Duration.ofDays(90)
    }
}
