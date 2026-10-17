package me.raddatz.veyq.core.blobstore

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import org.hibernate.annotations.DynamicUpdate
import java.time.Duration
import java.time.Instant

/** Why a tick stopped — the wire's `StoreReconcileFailure.reason`. */
enum class ReconcileFailure { STORE_UNREACHABLE, LIST_DENIED, NO_STORE, NO_STORE_CLOCK, DATABASE }

/** Why every first-seen clock last restarted — the wire's `clocksRestartReason`. A restore leaves no reason: it empties
 *  both tables (`scripts/instance_backup.py`), so after one there is no row to carry it. */
enum class ClocksRestart { GAP, STORE_CHANGED }

/**
 * The bucket reconcile's state (`veyq_store_reconcile`, ADR 0062 § Consequences): one row, [ID], created by the first
 * tick and by no migration. Two clocks live here, on purpose apart: [lastListedAt] is the **store's** (a listing's
 * `Date`), because the gap rule compares it with the next listing's; every other instant is this server's, because it
 * says when something happened here (a pass started, a failure, the Server Owner's switches).
 *
 * The pass in progress is [passStartedAt] non-null; [passPage] is the compare-and-set every page's write goes through
 * ([StoreReconcileStore.writePage]), so a second backend process that listed the same page writes nothing. No key but
 * [passCursor], which is the store's own name for a position.
 */
@Entity
@Table(name = "veyq_store_reconcile")
@DynamicUpdate
class StoreReconcileEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = ID

    /** The bucket the pass in progress and the first-seen clocks belong to ([BlobStore.bucketDigest]). */
    @Column(name = "store_digest", length = 64)
    var storeDigest: String? = null

    @Column(name = "pass_no", nullable = false)
    var passNo: Long = 0

    @Column(name = "pass_started_at")
    var passStartedAt: Instant? = null

    @Column(name = "pass_page", nullable = false)
    var passPage: Long = 0

    @Column(name = "pass_cursor", length = 1024)
    var passCursor: String? = null

    @Column(name = "pass_objects", nullable = false)
    var passObjects: Long = 0

    @Column(name = "pass_orphan_objects", nullable = false)
    var passOrphanObjects: Long = 0

    @Column(name = "pass_orphan_bytes", nullable = false)
    var passOrphanBytes: Long = 0

    @Column(name = "pass_not_veyq_objects", nullable = false)
    var passNotVeyqObjects: Long = 0

    /** The store's clock at the last listed page — the gap rule's other end. */
    @Column(name = "last_listed_at")
    var lastListedAt: Instant? = null

    @Column(name = "completed_started_at")
    var completedStartedAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "completed_objects")
    var completedObjects: Long? = null

    @Column(name = "completed_orphan_objects")
    var completedOrphanObjects: Long? = null

    @Column(name = "completed_orphan_bytes")
    var completedOrphanBytes: Long? = null

    @Column(name = "completed_not_veyq_objects")
    var completedNotVeyqObjects: Long? = null

    @Column(name = "completed_enqueued_keys")
    var completedEnqueuedKeys: Long? = null

    @Column(name = "failed_at")
    var failedAt: Instant? = null

    @Column(name = "failure", length = 32)
    var failure: String? = null

    /** The Server Owner's confirmation that the bucket is this instance's alone, for [exclusiveStoreDigest] only. */
    @Column(name = "exclusive_confirmed_at")
    var exclusiveConfirmedAt: Instant? = null

    @Column(name = "exclusive_store_digest", length = 64)
    var exclusiveStoreDigest: String? = null

    @Column(name = "paused_at")
    var pausedAt: Instant? = null

    @Column(name = "clocks_restarted_at")
    var clocksRestartedAt: Instant? = null

    @Column(name = "clocks_restart_reason", length = 16)
    var clocksRestartReason: String? = null

    /** Whether a due key would be put on the reclaim list now: confirmed for the bucket this row lists, not paused. */
    val armed: Boolean
        get() = exclusiveConfirmedAt != null && exclusiveStoreDigest != null && exclusiveStoreDigest == storeDigest &&
            pausedAt == null

    companion object : PanacheCompanionBase<StoreReconcileEntity, Int> {
        const val ID = 1
    }
}

/**
 * A candidate (`veyq_store_orphan`): a storage key no row claimed in any pass since [firstSeenAt] — the **store's**
 * clock, like the safe age. [lastSeenPass] is what makes "in every pass" true: a completed pass drops every row it did
 * not see. [chunkCount] is the highest orphan index seen plus one, which is what a reclaim row deletes (`0 … n−1`);
 * [objects] and [bytes] are the last pass's count of them. An opaque key and numbers — no scope (ADR 0062 Decision 6).
 */
@Entity
@Table(name = "veyq_store_orphan")
@DynamicUpdate
class StoreOrphanEntity : PanacheEntityBase {
    @Id
    @Column(name = "storage_key", length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "objects", nullable = false)
    var objects: Long = 0

    @Column(name = "bytes", nullable = false)
    var bytes: Long = 0

    @Column(name = "first_seen_at", nullable = false)
    lateinit var firstSeenAt: Instant

    @Column(name = "last_seen_pass", nullable = false)
    var lastSeenPass: Long = 0

    companion object : PanacheCompanionBase<StoreOrphanEntity, String>
}

/** Where the pass in progress stands, as one tick read it — the compare-and-set's expected values. */
data class PassCursor(val digest: String, val passNo: Long, val page: Long, val cursor: String?)

/** One storage key's orphans on one page: how many, their size, and the highest index among them. */
data class OrphanKey(val objects: Long, val bytes: Long, val maxIndex: Int)

/** A judged page, ready to write: its counts, its candidates, and the keys that are no candidate in this pass. */
data class JudgedPage(
    val objects: Long,
    val orphanObjects: Long,
    val orphanBytes: Long,
    val notVeyqObjects: Long,
    val candidates: Map<String, OrphanKey>,
    val notCandidates: Set<String>,
)

/** What a page's write did. */
sealed interface PageWrite {
    /** Another tick wrote this page first (or restarted the pass); this one wrote nothing. */
    data object Lost : PageWrite

    data class Continued(val next: PassCursor, val clocksRestarted: Boolean) : PageWrite

    data class Completed(val pass: CompletedPass, val clocksRestarted: Boolean) : PageWrite
}

data class CompletedPass(
    val objects: Long,
    val orphanObjects: Long,
    val orphanBytes: Long,
    val notVeyqObjects: Long,
    val candidateKeys: Long,
    val enqueuedKeys: Long,
)

/** The reconcile as the Server Owner reads it (decision 9). */
data class StoreReconcileView(
    val verdict: Verdict,
    val grace: Duration,
    val lastPass: LastPass?,
    val passInProgress: Progress?,
    val failure: Failure?,
    val candidates: Candidates,
    val exclusiveBucketConfirmedAt: Instant?,
    val pausedAt: Instant?,
    val clocksRestartedAt: Instant?,
    val clocksRestartReason: ClocksRestart?,
    val reclaimPending: Long,
    val reclaimAbandoned: Long,
) {
    enum class Verdict { ARMED, NOT_CONFIRMED, PAUSED, NO_STORE }

    data class LastPass(
        val startedAt: Instant,
        val completedAt: Instant,
        val objects: Long,
        val orphanObjects: Long,
        val orphanBytes: Long,
        val notVeyqObjects: Long,
        val enqueuedKeys: Long,
    )

    data class Progress(val startedAt: Instant, val objects: Long)

    data class Failure(val at: Instant, val reason: ReconcileFailure)

    data class Candidates(
        val keys: Long,
        val objects: Long,
        val bytes: Long,
        val dueKeys: Long,
        val oldestFirstSeenAt: Instant?,
        val newestFirstSeenAt: Instant?,
        val firstDueAt: Instant?,
    )
}

/**
 * The reconcile's persistence: the claim query, the state row's transitions, the candidates, and the read the console
 * gets. Every write runs in a transaction of its own; [StoreReconcileSweep] calls the store and lists the bucket
 * BETWEEN them, so no transaction is held across a call to the store.
 */
@ApplicationScoped
class StoreReconcileStore {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var reclaim: StoreReclaimStore

    /**
     * For each of [storageKeys] that some row names, the highest `chunk_count` any row gives it — the claim set (ADR 0062
     * § Consequences). **One statement**, a `UNION ALL` over every [CLAIM_TABLES] entry, because one statement is one
     * consistent read: two SELECTs would miss a key promoted from an upload session to a blob between them. A key is
     * explained for an object when its count is above the object's index. Empty input reads nothing. Joins the
     * completing page's transaction for the enqueue's re-check, opens its own for a page's.
     */
    @Transactional
    fun claims(storageKeys: Collection<String>): Map<String, Int> {
        if (storageKeys.isEmpty()) return emptyMap()
        val sql = CLAIM_TABLES.joinToString(" UNION ALL ") { table ->
            "SELECT storage_key, chunk_count FROM $table WHERE storage_key IN (:keys)"
        }
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(sql).setParameter("keys", storageKeys.distinct()).resultList as List<Array<Any?>>
        val claimed = HashMap<String, Int>()
        rows.forEach { row ->
            val key = row[0] as String
            val count = (row[1] as Number).toInt()
            claimed.merge(key, count, ::maxOf)
        }
        return claimed
    }

    /**
     * Where this tick starts, or null when no pass is due. Creates the state row on the first tick. A bucket other than
     * the one the row was listing ([digest]) restarts the pass from its first key, empties the candidates
     * ([ClocksRestart.STORE_CHANGED]: their clocks belong to another bucket) and voids a confirmation given for
     * another bucket (decision 16). With no pass in progress, a new one starts once [period] has passed since the last
     * one started.
     */
    @Transactional
    fun open(digest: String, now: Instant, period: Duration): PassCursor? {
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID)
            ?: StoreReconcileEntity().also { it.persist() }
        if (row.storeDigest != digest) {
            if (row.storeDigest != null) restartClocks(row, ClocksRestart.STORE_CHANGED, now) else dropCandidates()
            row.storeDigest = digest
            row.passStartedAt = null
            row.passCursor = null
            // The other bucket's clock is no end for the gap rule.
            row.lastListedAt = null
        }
        if (row.exclusiveStoreDigest != null && row.exclusiveStoreDigest != digest) {
            row.exclusiveConfirmedAt = null
            row.exclusiveStoreDigest = null
        }
        if (row.passStartedAt == null) {
            val last = row.completedStartedAt
            if (last != null && last.plus(period) > now) return null
            row.passNo += 1
            row.passStartedAt = now
            row.passPage = 0
            row.passCursor = null
            row.passObjects = 0
            row.passOrphanObjects = 0
            row.passOrphanBytes = 0
            row.passNotVeyqObjects = 0
        }
        return PassCursor(digest, row.passNo, row.passPage, row.passCursor)
    }

    /**
     * Write one judged page — **only if no other tick wrote it first**: a compare-and-set on the pass and its page
     * counter (decision 17), so two backend processes listing the same page count it once. Then, in the same
     * transaction: the gap rule (decision 13), the pass's counts and cursor, the candidates, and — on the last page —
     * the pass's completion with its enqueue ([complete]).
     */
    @Transactional
    fun writePage(
        at: PassCursor,
        page: JudgedPage,
        lastKey: String?,
        storeTime: Instant,
        truncated: Boolean,
        now: Instant,
        grace: Duration,
        maxGap: Duration,
    ): PageWrite {
        val won = em.createQuery(
            "UPDATE StoreReconcileEntity r SET r.passPage = r.passPage + 1 " +
                "WHERE r.id = :id AND r.storeDigest = :digest AND r.passNo = :no AND r.passPage = :page " +
                "AND r.passStartedAt IS NOT NULL",
        ).setParameter("id", StoreReconcileEntity.ID).setParameter("digest", at.digest)
            .setParameter("no", at.passNo).setParameter("page", at.page)
            .executeUpdate()
        if (won == 0) return PageWrite.Lost
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID)!!

        // The grace counts only time the reconcile observed (decision 13): a database brought back from before the last
        // listed page, by anything but the restore tool, or plain downtime, lies across a gap no healthy instance has.
        val last = row.lastListedAt
        val gap = last != null && Duration.between(last, storeTime).abs() > maxGap
        if (gap) restartClocks(row, ClocksRestart.GAP, now)
        row.lastListedAt = storeTime

        row.passCursor = lastKey ?: row.passCursor
        row.passObjects += page.objects
        row.passOrphanObjects += page.orphanObjects
        row.passOrphanBytes += page.orphanBytes
        row.passNotVeyqObjects += page.notVeyqObjects
        row.failedAt = null
        row.failure = null

        if (page.notCandidates.isNotEmpty()) StoreOrphanEntity.delete("storageKey in ?1", page.notCandidates.toList())
        page.candidates.forEach { (key, seen) ->
            val orphan = StoreOrphanEntity.findById(key)
            when {
                orphan == null -> StoreOrphanEntity().apply {
                    storageKey = key
                    chunkCount = seen.maxIndex + 1
                    objects = seen.objects
                    bytes = seen.bytes
                    firstSeenAt = storeTime
                    lastSeenPass = row.passNo
                }.persist()
                // A key whose objects span two pages of this pass: add this page's.
                orphan.lastSeenPass == row.passNo -> {
                    orphan.chunkCount = maxOf(orphan.chunkCount, seen.maxIndex + 1)
                    orphan.objects += seen.objects
                    orphan.bytes += seen.bytes
                }
                else -> {
                    orphan.chunkCount = maxOf(orphan.chunkCount, seen.maxIndex + 1)
                    orphan.objects = seen.objects
                    orphan.bytes = seen.bytes
                    orphan.lastSeenPass = row.passNo
                }
            }
        }

        if (truncated) return PageWrite.Continued(PassCursor(at.digest, row.passNo, row.passPage, row.passCursor), gap)
        return PageWrite.Completed(complete(row, storeTime, now, grace), gap)
    }

    /**
     * The pass listed its last page. Drop every candidate it did not see — so a candidate that survives was an orphan
     * in every pass since it was first seen, and one whose key a row claims again is gone — publish the pass, and,
     * while [StoreReconcileEntity.armed], put every due candidate onto the reclaim list (decision 15). Due is
     * `storeTime − firstSeen ≥ grace`, both on the store's clock. The claim query runs once more over the due keys in
     * this transaction, so a row that appeared after a key's page was listed still saves it. **Never a delete**: the
     * reclaim list's sweep is the only deleter (ADR 0062 Decision 3), and its not-before applies on top.
     */
    private fun complete(row: StoreReconcileEntity, storeTime: Instant, now: Instant, grace: Duration): CompletedPass {
        StoreOrphanEntity.delete("lastSeenPass <> ?1", row.passNo)
        var enqueued = 0L
        if (row.armed) {
            val due = StoreOrphanEntity.list("firstSeenAt <= ?1", storeTime.minus(grace))
            if (due.isNotEmpty()) {
                val claimed = claims(due.map { it.storageKey }).keys
                val record = due.filter { it.storageKey !in claimed }
                reclaim.record(record.map { StoreObjectRef(it.storageKey, it.chunkCount) })
                StoreOrphanEntity.delete("storageKey in ?1", due.map { it.storageKey })
                enqueued = record.size.toLong()
            }
        }
        row.completedStartedAt = row.passStartedAt
        row.completedAt = now
        row.completedObjects = row.passObjects
        row.completedOrphanObjects = row.passOrphanObjects
        row.completedOrphanBytes = row.passOrphanBytes
        row.completedNotVeyqObjects = row.passNotVeyqObjects
        row.completedEnqueuedKeys = enqueued
        row.passStartedAt = null
        row.passCursor = null
        return CompletedPass(
            objects = row.passObjects,
            orphanObjects = row.passOrphanObjects,
            orphanBytes = row.passOrphanBytes,
            notVeyqObjects = row.passNotVeyqObjects,
            candidateKeys = StoreOrphanEntity.count(),
            enqueuedKeys = enqueued,
        )
    }

    /** Record [reason] as the last failure; true when it is a new one (the caller logs once, at the transition). */
    @Transactional
    fun recordFailure(reason: ReconcileFailure, now: Instant): Boolean {
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID)
            ?: StoreReconcileEntity().also { it.persist() }
        if (row.failure == reason.name) return false
        row.failure = reason.name
        row.failedAt = now
        return true
    }

    private fun restartClocks(row: StoreReconcileEntity, reason: ClocksRestart, now: Instant) {
        dropCandidates()
        row.clocksRestartedAt = now
        row.clocksRestartReason = reason.name
    }

    private fun dropCandidates() {
        StoreOrphanEntity.deleteAll()
    }

    /** Confirm that the bucket [digest] names holds only this instance's objects (decision 11). */
    @Transactional
    fun confirmExclusive(digest: String, now: Instant) {
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID)
            ?: StoreReconcileEntity().also { it.persist() }
        if (row.exclusiveStoreDigest == digest && row.exclusiveConfirmedAt != null) return
        row.exclusiveConfirmedAt = now
        row.exclusiveStoreDigest = digest
    }

    @Transactional
    fun withdrawExclusive() {
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID) ?: return
        row.exclusiveConfirmedAt = null
        row.exclusiveStoreDigest = null
    }

    /** Pause (or resume) the enqueueing; the listing and the counting go on either way (decision 14). */
    @Transactional
    fun pause(paused: Boolean, now: Instant) {
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID)
            ?: StoreReconcileEntity().also { it.persist() }
        if (paused && row.pausedAt == null) row.pausedAt = now
        if (!paused) row.pausedAt = null
    }

    /**
     * The read (decision 9). [digest] is the bucket the store in use lists, null when none: a confirmation given for
     * another bucket reads as none, which is what it is — the next tick clears it.
     */
    @Transactional
    fun view(digest: String?, grace: Duration, now: Instant): StoreReconcileView {
        val row = StoreReconcileEntity.findById(StoreReconcileEntity.ID)
        val confirmed = row?.exclusiveConfirmedAt?.takeIf { digest != null && row.exclusiveStoreDigest == digest }
        val verdict = when {
            digest == null -> StoreReconcileView.Verdict.NO_STORE
            confirmed == null -> StoreReconcileView.Verdict.NOT_CONFIRMED
            row.pausedAt != null -> StoreReconcileView.Verdict.PAUSED
            else -> StoreReconcileView.Verdict.ARMED
        }
        @Suppress("UNCHECKED_CAST")
        val aggregate = em.createQuery(
            "SELECT count(o), coalesce(sum(o.objects), 0), coalesce(sum(o.bytes), 0), min(o.firstSeenAt), max(o.firstSeenAt) " +
                "FROM StoreOrphanEntity o",
        ).singleResult as Array<Any?>
        val oldest = aggregate[3] as Instant?
        val due = StoreOrphanEntity.count("firstSeenAt <= ?1", now.minus(grace))
        return StoreReconcileView(
            verdict = verdict,
            grace = grace,
            lastPass = row?.completedAt?.let { completedAt ->
                StoreReconcileView.LastPass(
                    startedAt = row.completedStartedAt ?: completedAt,
                    completedAt = completedAt,
                    objects = row.completedObjects ?: 0,
                    orphanObjects = row.completedOrphanObjects ?: 0,
                    orphanBytes = row.completedOrphanBytes ?: 0,
                    notVeyqObjects = row.completedNotVeyqObjects ?: 0,
                    enqueuedKeys = row.completedEnqueuedKeys ?: 0,
                )
            },
            passInProgress = row?.passStartedAt?.let { StoreReconcileView.Progress(it, row.passObjects) },
            failure = row?.failedAt?.let { at -> row.failure?.let { StoreReconcileView.Failure(at, ReconcileFailure.valueOf(it)) } },
            candidates = StoreReconcileView.Candidates(
                keys = (aggregate[0] as Number).toLong(),
                objects = (aggregate[1] as Number).toLong(),
                bytes = (aggregate[2] as Number).toLong(),
                dueKeys = due,
                oldestFirstSeenAt = oldest,
                newestFirstSeenAt = aggregate[4] as Instant?,
                firstDueAt = oldest?.plus(grace),
            ),
            exclusiveBucketConfirmedAt = confirmed,
            pausedAt = row?.pausedAt,
            clocksRestartedAt = row?.clocksRestartedAt,
            clocksRestartReason = row?.clocksRestartReason?.let { ClocksRestart.valueOf(it) },
            reclaimPending = StoreReclaimEntity.count("abandonedAt is null"),
            reclaimAbandoned = StoreReclaimEntity.count("abandonedAt is not null"),
        )
    }

    companion object {
        /**
         * The claim set: every table a migration gives a `storage_key` column, except this reconcile's own
         * `veyq_store_orphan` (whose rows are what NO row claims). A table that keeps a key alive and is missing here
         * turns a wrong count into lost media once the key is due, so `scripts/validate_store_reconcile_claims.py`
         * derives the set from the migrations and holds this list to it — never the other way round.
         */
        val CLAIM_TABLES = listOf("veyq_upload_session", "veyq_blob", "veyq_store_reclaim")
    }
}
