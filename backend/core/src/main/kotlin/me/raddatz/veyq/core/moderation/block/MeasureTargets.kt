package me.raddatz.veyq.core.moderation.block

import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.hold.HeldRow
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
    private val ledger: ErasureLedger,
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
     * A measure of [kind] taken on [targetId] **already erased** ([lockStanding] answered false), **in the taker's
     * transaction**: the stored objects of the erased item that another blob row still names — what the measure hangs on
     * instead of ending at once (owner 2026-10-19, ADR 0093 § 6), handed to [erased] as its `holding`. The item's own rows
     * are gone, so its objects are read from its case's hold ([held], `CaseSnapshots.rowsOf`): an application names them
     * ([ReportableItems.heldObjectsOf]). None for any other kind, or where no hold holds the item.
     *
     * Each surviving object's blob rows are locked, as `BlobUploadStore.deleteBlobRow` locks them (by id, key by key): the
     * race with the object's last row going is the one with the target's — either that delete committed first and the
     * object is not surviving (the measure ends at once), or it waits for this commit and its [objectsGone] then reads the
     * measure. Two such that cross on two keys are a deadlock the database breaks by rolling one back whole.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun survivingObjects(kind: BlockKind, targetId: String, held: List<HeldRow>?): Set<String> {
        if (kind != BlockKind.CONTENT_ITEM || held.isNullOrEmpty()) return emptySet()
        val keys = items.flatMapTo(sortedSetOf()) { it.heldObjectsOf(targetId, held) }
        return keys.filterTo(LinkedHashSet()) { key ->
            BlobEntity.find("storageKey = ?1 order by id", key).withLock(LockModeType.PESSIMISTIC_WRITE).list().isNotEmpty()
        }
    }

    /**
     * [targetIds] were erased, **in the eraser's transaction**: every measure of [kinds] on one of them still in force ends,
     * lifted by no one and logged so (`MEASURE_LIFTED` on its case, or an instance event for a Server Owner's step on no
     * case), dated from [now]. The pending deletes are flushed first, so the eraser holds every erased row's lock before
     * the measures are read — a taker that locked one before has committed by then (the race above).
     *
     * **Except where the stored object survives** (owner 2026-10-19, ADR 0093 § 6): [holding] names, per erased item, the
     * storage keys of its stored objects that other blob rows of its node still name (a cover or a banner sharing a
     * medium's object, ADR 0013 § 5). A [BlockKind.CONTENT_ITEM] measure on such an item keeps standing, hanging on those
     * objects ([ModerationBlockEntity.heldObjects]): every row naming one is still refused as blocked, and it ends with the
     * objects' last row ([objectsGone]) or a moderator's lift.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun erased(
        kinds: Collection<BlockKind>, targetIds: Collection<String>, now: Instant = Instant.now(),
        holding: Map<String, Set<String>> = emptyMap(),
    ) {
        if (kinds.isEmpty() || targetIds.isEmpty()) return
        ModerationBlockEntity.getEntityManager().flush()
        targetIds.distinct().chunked(CHUNK).forEach { chunk ->
            ModerationBlockEntity.list("targetId in ?1 and kind in ?2 and activeKey is not null", chunk, kinds.map { it.name })
                .forEach { row ->
                    val held = holding[row.targetId].orEmpty()
                    if (row.blockKind == BlockKind.CONTENT_ITEM && held.isNotEmpty()) {
                        row.heldObjects = (row.heldKeys + held).sorted().joinToString(" ")
                    } else {
                        end(row, now)
                    }
                }
        }
    }

    /**
     * The last blob row naming each of [storageKeys] went, **in the eraser's transaction** (`BlobUploadStore.deleteBlobRow`
     * records the object's reclaim then; `ScopePurge` erases a space's rows at once): a measure that hangs on such an object
     * ([erased]'s `holding`) lets go of it, and one that holds no object any more ends, as with its target.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun objectsGone(storageKeys: Collection<String>, now: Instant = Instant.now()) {
        if (storageKeys.isEmpty()) return
        val gone = storageKeys.toSet()
        gone.forEach { key ->
            ModerationBlockEntity.list("activeKey is not null and heldObjects like ?1", "%$key%").forEach { row ->
                val left = row.heldKeys - gone
                if (left.size == row.heldKeys.size) return@forEach // the key matched only as a substring of another
                row.heldObjects = left.sorted().joinToString(" ").ifEmpty { null }
                if (left.isEmpty()) end(row, now)
            }
        }
    }

    /** End [row] with its target: lifted by no one, logged so, its lift in the erasure ledger. */
    private fun end(row: ModerationBlockEntity, now: Instant) {
        row.liftedAt = now
        row.activeKey = null
        // The lift's line in the erasure ledger (ADR 0056 decision 8): an item's last line is a lift, so a
        // restore of a capture before the erasure never blocks it again.
        ledger.blockLifted(row)
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

    companion object {
        /** A link's measures: the link blocked, and the link a barred guest reporter reports through. */
        val ON_LINK = listOf(BlockKind.SHARE, BlockKind.REPORTER_LINK)

        /** Ids per read — a scope's purge hands over every node of the scope. */
        private const val CHUNK = 500
    }
}
