package me.raddatz.veyq.core.moderation.block

import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * **A measure never stands on an erased target** (GDPR Art. 5(1)(e); ADR 0093 §§ 6, 11; ADR 0101 decision 7). A measure
 * in force holds its case's records (`ModerationRecordsPurge`), which is right while its target lives and wrong once the
 * target is gone: lifted by nobody, it would stand forever on nothing, and its statement and its case's records with it.
 * So every eraser of a measure's target ends the measures on it **in the erasure's transaction** ([erased]) — the measure
 * stays as the record it is, lifted by no one and logged so, and the case's § 11 date counts from then. A hold is not
 * ended by this: it keeps its own end (ADR 0093 § 4), and a hold on erased content is exactly what a hold is for.
 *
 * What a kind's target is, and so which erasers owe the call, is [lockStanding]'s `when` — read by
 * `scripts/validate_measure_target_erasure.py`, which holds every function that deletes such a row to the call.
 *
 * **The race with a decision** (developer-styleguide § 12): the target's row is the lock both sides take. An eraser
 * deletes (or, a link, tombstones) the row before [erased] reads the measures, and a measure-taker locks the row through
 * [lockStanding] before it commits its measure. At READ COMMITTED (`application.properties`) either the taker locked first
 * — the eraser's delete waits for its commit, and [erased] then reads its measure — or the eraser did, and the taker
 * finds the row gone and ends its own measure at once ([erased] in the taker's transaction). No measure is left standing
 * on a target that is gone (`MeasureTargetErasureRacesSliceTest`).
 */
@ApplicationScoped
class MeasureTargets(
    private val log: ModerationLog,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All items: MutableList<ReportableItems>,
) {
    private val items: List<ReportableItems> = items

    /**
     * Lock the row a measure of [kind] on [targetId] stands on and answer whether it still stands, **in the caller's
     * transaction**: a link not deleted (a tombstone is a deleted link, ADR 0029 § 7 — its id stays held, nothing of it
     * serves again), a node, an application's item ([ReportableItems.lockExisting]), a guest identity, an account, a
     * tenant. A trashed node or item still stands: only its purge erases it.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun lockStanding(kind: BlockKind, targetId: String): Boolean = when (kind) {
        BlockKind.SHARE, BlockKind.REPORTER_LINK ->
            ShareEntity.find("id", targetId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                ?.also { ShareEntity.getEntityManager().refresh(it) }?.deleted == false
        BlockKind.CONTENT_NODE -> NodeEntity.find("id", targetId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null
        BlockKind.CONTENT_ITEM -> items.any { it.lockExisting(targetId) }
        BlockKind.GUEST_IDENTITY ->
            ShareIdentityEntity.find("id", targetId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null
        BlockKind.MEMBER_SIGN_IN, BlockKind.REPORTER_ACCOUNT ->
            VeyqUser.find("id", targetId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null
        BlockKind.TENANT_OFFLINE, BlockKind.TENANT_DELETED ->
            TenantEntity.find("id", targetId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null
    }

    /**
     * [targetIds] were erased, **in the eraser's transaction**: every measure of [kinds] on one of them still in force ends,
     * lifted by no one and logged so (`MEASURE_LIFTED` on its case, or an instance event for a Server Owner's step on no
     * case), dated from [now]. The pending deletes are flushed first, so the eraser holds every erased row's lock before
     * the measures are read — a taker that locked one before has committed by then (the race above).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun erased(kinds: Collection<BlockKind>, targetIds: Collection<String>, now: Instant = Instant.now()) {
        if (kinds.isEmpty() || targetIds.isEmpty()) return
        ModerationBlockEntity.getEntityManager().flush()
        targetIds.distinct().chunked(CHUNK).forEach { chunk ->
            ModerationBlockEntity.list("targetId in ?1 and kind in ?2 and activeKey is not null", chunk, kinds.map { it.name })
                .forEach { row ->
                    row.liftedAt = now
                    row.activeKey = null
                    val caseId = row.caseId
                    if (caseId != null) {
                        log.appendCase(ModerationEvent.MEASURE_LIFTED, now, caseId, reason = row.kind, targetId = row.targetId)
                    } else {
                        log.append(
                            ModerationEvent.MEASURE_LIFTED, now, moderatorId = null, actorId = null, reason = row.kind,
                            targetId = row.targetId, datedFrom = now,
                        )
                    }
                }
        }
    }

    companion object {
        /** A link's measures: the link blocked, and the link a barred guest reporter reports through. */
        val ON_LINK = listOf(BlockKind.SHARE, BlockKind.REPORTER_LINK)

        /** Ids per read — a scope's purge hands over every node of the scope. */
        private const val CHUNK = 500
    }
}
