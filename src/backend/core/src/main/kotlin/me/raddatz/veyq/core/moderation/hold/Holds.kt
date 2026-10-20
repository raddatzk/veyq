package me.raddatz.veyq.core.moderation.hold

import me.raddatz.veyq.core.backup.ErasureLedger
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
    private val ledger: ErasureLedger,
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
     * held key chain needs). Answers the hold's id. [floorUntil] is the end an authority's order sets (ADR 0093 § 4): no
     * later move or [endNow] ends the hold before it. [ceilingUntil] is a child-sexual-abuse case's bound ([capAt]).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun take(
        caseId: String?, target: HoldTarget, trigger: HoldTrigger, expiresAt: Instant, floorUntil: Instant? = null,
        ceilingUntil: Instant? = null,
    ): String {
        val hold = HoldEntity().apply {
            id = UUID.randomUUID().toString()
            this.caseId = caseId
            this.trigger = trigger.name
            this.expiresAt = expiresAt
            this.floorUntil = floorUntil
            this.ceilingUntil = ceilingUntil
        }
        hold.persist()
        fill(hold, target, HoldSnapshot(hold.id, em), pinned = emptySet())
        return hold.id
    }

    /**
     * Widen [hold] to [target], **in the caller's transaction** (a decision's, under the case's row lock): the rows the
     * target names are copied into the same hold and their objects pinned to its end — "later-held" by the hold's own
     * rules (ADR 0093 § 4), so they end with it, move with it and are extended with it. A row or a pin the hold already
     * holds is not taken twice (a moderator's `UPLOADER_CONTENT` step over items the report's subtree already held).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun widen(hold: HoldEntity, target: HoldTarget) {
        val already = HoldRowEntity.list("holdId", hold.id).mapTo(HashSet()) { "${it.sourceTable}:${it.sourceId}" }
        val pinned = HoldPinEntity.list("holdId", hold.id).mapTo(HashSet()) { it.storageKey }
        fill(hold, target, HoldSnapshot(hold.id, em, already), pinned)
    }

    private fun fill(hold: HoldEntity, target: HoldTarget, snapshot: HoldSnapshot, pinned: Set<String>) {
        contributors.forEach { it.contribute(target, snapshot) }
        // One pin per stored object: two held rows may name one (a cover sharing a medium's, ADR 0013 § 5).
        snapshot.copy("veyq_blob", "id", snapshot.blobIds).distinctBy { it.getValue("storage_key") }
            .filter { it.getValue("storage_key") !in pinned }
            .forEach { blob ->
                HoldPinEntity().apply {
                    id = UUID.randomUUID().toString()
                    holdId = hold.id
                    storageKey = blob.getValue("storage_key")!!
                    chunkCount = blob.getValue("chunk_count")!!.toInt()
                    this.expiresAt = hold.expiresAt
                }.persist()
            }
        snapshot.copy("veyq_share_identity", "id", snapshot.identityIds)
        snapshot.copy("veyq_scope_epoch", "scope_id", listOf(target.scopeId))
    }

    /** [caseId]'s hold alive at [now], or null. */
    fun aliveOf(caseId: String, now: Instant): HoldEntity? =
        HoldEntity.find("caseId = ?1 and expiresAt > ?2 order by expiresAt desc", caseId, now).firstResult()

    /**
     * Move [hold]'s end to [until] for [trigger], with its pins, **in the caller's transaction** (a decision's, under the
     * case's row lock): a measure sets six months after the decision, an extension the end it names (ADR 0093 § 4). Never
     * before the hold's floor — an authority's order's end — which the move keeps. **A measure never passes the hold's
     * ceiling** (a child-sexual-abuse case's 90 days, [capAt]): it moves the end at most to it, and not at all where the
     * end already lies there or beyond — so a measure or a statement never lengthens such a hold, nor shortens an
     * extension. A logged extension ([HoldTrigger.EXTENDED]) may pass the ceiling.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun moveEnd(hold: HoldEntity, until: Instant, trigger: HoldTrigger) {
        val ceiling = hold.ceilingUntil
        val wanted = if (trigger == HoldTrigger.MEASURE && ceiling != null) minOf(until, ceiling) else until
        if (trigger == HoldTrigger.MEASURE && ceiling != null && !wanted.isAfter(hold.expiresAt)) return
        setEnd(hold, hold.floorUntil?.let { maxOf(it, wanted) } ?: wanted, trigger)
    }

    /**
     * Bound [hold] — a case that became one of child sexual abuse — to [ceiling], **in the caller's transaction** under
     * the case's lock (ADR 0093 § 4; § 184b(5) StGB: keeping it serves only the report to an authority): its first
     * ceiling stays; an end a report or a measure set past it comes back to it, never before the floor; an extension's
     * end stays (a logged reason set it).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun capAt(hold: HoldEntity, ceiling: Instant) {
        if (hold.ceilingUntil != null) return
        hold.ceilingUntil = ceiling
        if (hold.trigger == HoldTrigger.EXTENDED.name || hold.trigger == HoldTrigger.ORDER.name) return
        if (hold.expiresAt.isAfter(ceiling)) setEnd(hold, hold.floorUntil?.let { maxOf(it, ceiling) } ?: ceiling, HoldTrigger.valueOf(hold.trigger))
    }

    private fun setEnd(hold: HoldEntity, end: Instant, trigger: HoldTrigger) {
        hold.expiresAt = end
        hold.trigger = trigger.name
        HoldPinEntity.update("expiresAt = ?1 where holdId = ?2", end, hold.id)
    }

    /**
     * End every hold of [caseId] at once (a dismissal, a lift of the case's last measure), **in the caller's
     * transaction**: the snapshot and the pins go now, so the reclaim sweep catches up on whatever was deleted meanwhile.
     * A hold whose floor — an authority's order's end — lies ahead is not ended: it runs to its floor (ADR 0093 § 4: no
     * decision ends a hold before the duration an order sets). Answers whether a hold alive was ended.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun endNow(caseId: String, now: Instant): Boolean {
        val holds = HoldEntity.list("caseId", caseId)
        if (holds.isEmpty()) return false
        val (floored, ending) = holds.partition { h -> h.floorUntil?.isAfter(now) == true && h.expiresAt.isAfter(now) }
        floored.forEach { h -> moveEnd(h, h.floorUntil!!, HoldTrigger.ORDER) }
        val alive = ending.any { it.expiresAt.isAfter(now) }
        end(ending)
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
        end(ended)
        return ended.mapNotNull { it.caseId }.distinct()
    }

    /**
     * End [holds] — their snapshot and pins go — each with its erasure-ledger line, in the caller's transaction (ADR 0056
     * decision 8): a restore of a capture older than the end brings the hold back with its end still ahead, and the start
     * ends it again ([reend]). Every end runs through here, so no path ends a hold without its line.
     */
    private fun end(holds: List<HoldEntity>) {
        if (holds.isEmpty()) return
        holds.forEach { ledger.holdEnded(it.id, it.caseId) }
        drop(holds.map { it.id })
    }

    /**
     * End again the hold [holdId] a restore brought back after it had ended (its `HOLD_ENDED` line, ADR 0056 decision 8),
     * **in the caller's transaction**: its snapshot and pins go, and no second line is written. Answers whether its row
     * existed — a second start finds none, so a line applied twice changes nothing.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun reend(holdId: String): Boolean {
        if (HoldEntity.findById(holdId) == null) return false
        drop(listOf(holdId))
        return true
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

        /**
         * A child-sexual-abuse case's bound (ADR 0093 § 4, § 184b(5) StGB): its hold ends at the handover to an authority,
         * at the latest 90 days after the report — a measure or a statement never moves it further.
         */
        val CHILD_SEXUAL_ABUSE: Duration = Duration.ofDays(90)

        /** A European Preservation Order's hold (ADR 0093 § 4, Regulation 2023/1543 Art. 11): 60 days from receipt. */
        val PRESERVATION: Duration = Duration.ofDays(60)
    }
}
