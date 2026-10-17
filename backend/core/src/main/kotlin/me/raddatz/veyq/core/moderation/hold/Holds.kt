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

    /** [caseId]'s hold alive at [now], or null. */
    fun aliveOf(caseId: String, now: Instant): HoldEntity? =
        HoldEntity.find("caseId = ?1 and expiresAt > ?2 order by expiresAt desc", caseId, now).firstResult()

    /**
     * Move [hold]'s end to [until] for [trigger], with its pins, **in the caller's transaction** (a decision's, under the
     * case's row lock): a measure sets six months after the decision, an extension the end it names (ADR 0093 § 4).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun moveEnd(hold: HoldEntity, until: Instant, trigger: HoldTrigger) {
        hold.expiresAt = until
        hold.trigger = trigger.name
        HoldPinEntity.update("expiresAt = ?1 where holdId = ?2", until, hold.id)
    }

    /**
     * End every hold of [caseId] at once (a dismissal, a lift of the case's last measure), **in the caller's
     * transaction**: the snapshot and the pins go now, so the reclaim sweep catches up on whatever was deleted meanwhile.
     * Answers whether one was alive.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun endNow(caseId: String, now: Instant): Boolean {
        val ids = HoldEntity.list("caseId", caseId).map { it.id }
        if (ids.isEmpty()) return false
        val alive = HoldEntity.count("caseId = ?1 and expiresAt > ?2", caseId, now) > 0
        drop(ids)
        return alive
    }

    /**
     * Delete every hold past its end with its snapshot and its pins; the sweep then catches up on its own. Answers the
     * cases whose hold ended here (each gets its `HOLD_ENDED` event from the caller).
     */
    @Transactional
    fun purgeExpired(now: Instant): List<String> {
        val ended = HoldEntity.list("expiresAt <= ?1", now)
        if (ended.isEmpty()) return emptyList()
        drop(ended.map { it.id })
        return ended.mapNotNull { it.caseId }.distinct()
    }

    private fun drop(holdIds: List<String>) = holdIds.chunked(500).forEach { ids ->
        HoldPinEntity.delete("holdId in ?1", ids)
        HoldRowEntity.delete("holdId in ?1", ids)
        HoldEntity.delete("id in ?1", ids)
    }

    companion object {
        /** A case-open hold's bound (ADR 0093 § 4): the decision, at the latest 90 days after the report. */
        val CASE_OPEN: Duration = Duration.ofDays(90)

        /** A measure's hold, and an extension's longest step (ADR 0093 § 4: six months), in days. */
        val SIX_MONTHS: Duration = Duration.ofDays(183)
    }
}
