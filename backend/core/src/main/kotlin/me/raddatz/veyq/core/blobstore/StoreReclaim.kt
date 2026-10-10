package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.DynamicUpdate

/**
 * One store-object prefix to reclaim: the chunk objects `storageKey/0` … `storageKey/(chunkCount - 1)`.
 *
 * The prefix, not the expanded key list, is what crosses into [StoreReclaimStore] — a row is one unit of work
 * and one retry, and the sweep re-derives the keys with the same [BlobStore.chunkKey] every writer used.
 */
data class StoreObjectRef(val storageKey: String, val chunkCount: Int)

/**
 * A pending store-object deletion (ADR 0062, `veyq_store_reclaim`).
 *
 * **Deliberately says nothing about where it came from.** No scope, no media, no node: after a group erasure
 * these rows are the only surviving trace, and a per-scope tally of object counts would describe the erased
 * group (Decision 6). The cost is on the operator side and is accepted, not hidden — a stuck object is a number,
 * never a space.
 *
 * [abandonedAt] is the point of the whole table. A permanently unreachable store still loses bytes; what changes
 * is that the loss is `SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL` instead of
 * nothing at all. Abandoned rows are therefore **kept**, not deleted.
 *
 * **Trap for whoever adds a second store — and it is not where you would look for it.** ADR 0067 puts a `type`
 * on the store connection so a SaaS instance can one day have several (`docs/inbox.md` #41). The moment there
 * is more than one, [StoreReclaimSweep] cannot know *which* store to delete a row's objects from, and the bytes
 * leak silently. The reason this row cannot tell it is structural: [StoreReclaimStore.record] is
 * `@Transactional(MANDATORY)`, so the tombstone is written **in the same transaction that deletes the row it
 * came from** — precisely so objects outlive their metadata — and `ScopePurge` is one of its callers, erasing
 * the scope along with it. A live blob still reaches its tenant in one join (`veyq_blob.scope_id` →
 * `veyq_scope.tenant_id`); a reclaim row reaches nothing, **by design**.
 *
 * **And the obvious remedy is not free, which is the half that gets missed.** "One store-id column, filled at
 * insert while the store is still known" is a five-minute change and ADR 0062 Decision 6 forbids exactly that
 * shape on exactly this row: *"No `scope_id`, no `media_id`, no node. Group erasure must
 * leave nothing behind that describes the erased group."* A store id that is one-to-one with a tenant **is**
 * such a residue, so the column would trade a byte leak for an erasure leak. Whoever takes #41 owes a design
 * that satisfies both constraints, not a column — and ADR 0067 § Consequences states the collision so that it
 * is a known cost rather than a discovery.
 */
@Entity
@Table(name = "veyq_store_reclaim")
@DynamicUpdate
class StoreReclaimEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "storage_key", nullable = false, length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** How many sweep ticks have handed this row's keys to the store and been refused. */
    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    /** The earliest tick that may attempt this row: `createdAt` plus the presign bound on insert
     *  ([StoreReclaimStore.record]), then pushed out by the backoff. */
    @Column(name = "next_attempt_at", nullable = false)
    lateinit var nextAttemptAt: Instant

    /** Set once `attempts` reaches the configured maximum: the sweep gave up and the bytes are countably lost. */
    @Column(name = "abandoned_at")
    var abandonedAt: Instant? = null

    companion object : PanacheCompanionBase<StoreReclaimEntity, String>
}

/**
 * Writes and drains the reclaim list (ADR 0062 Decision 1).
 *
 * **[record] is `MANDATORY` on purpose, and that annotation is the whole guarantee.** The rule this table exists
 * for is *the keys are written in the same transaction as the row delete* — a caller that records outside a
 * transaction gets the old defect back in a new shape (a committed row delete and a lost key), and it would be
 * invisible. `MANDATORY` turns that into a boot-loud failure at the call site instead. Two paths therefore open
 * their own transaction before calling in ([me.raddatz.veyq.core.spaces.scope.ScopePurge] per scope, and the
 * over-budget purge in `BlobUploadHandler`, which deliberately leaves its session rows standing); every other caller
 * is already inside a `@Transactional` store method.
 */
@ApplicationScoped
class StoreReclaimStore {

    /** Field-injected, not a constructor parameter: a test `@Alternative` subclasses this bean, and ArC can give
     *  that normal-scoped subclass its proxy's no-args constructor only if this class keeps one. */
    @Inject
    lateinit var config: VeyqConfig

    /**
     * How long a recorded key waits before the sweep may delete its objects: `veyq.blob.upload.retention`, the
     * bound that already reads "after this, no PUT signed for a key can still land" (presign TTL plus one chunk
     * still streaming). **Not a second knob on purpose** — one would have to be kept at least this large by hand.
     *
     * Why a recorded key must wait at all: the row a caller drops can name a key whose last window was signed
     * moments earlier — a media purged straight from the trash, a replaced or cleared cover, the budget purge —
     * and S3 accepts a PUT on that still-valid URL onto a key that was deleted in the meantime, because a key is
     * only a name. A sweep that deleted on the next tick would let that PUT re-create the object under a key no
     * row, no reclaim row and no quota names. The wait is sufficient because no URL for a key is signed after its
     * row drop: a window is signed only for a live upload session.
     */
    private val notBefore: Duration get() = config.blob().upload().retention()

    /** Put [refs] on the reclaim list, **joining the caller's transaction**. Empty input is a no-op. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun record(refs: Collection<StoreObjectRef>) {
        if (refs.isEmpty()) return
        val now = Instant.now()
        refs.forEach { ref ->
            StoreReclaimEntity().apply {
                id = UUID.randomUUID().toString()
                storageKey = ref.storageKey
                chunkCount = ref.chunkCount
                createdAt = now
                attempts = 0
                nextAttemptAt = now.plus(notBefore) // the backoff only starts after a refusal
            }.persist()
        }
    }

    /** Convenience for the single-prefix callers (a blob row, an upload session). */
    @Transactional(Transactional.TxType.MANDATORY)
    fun record(storageKey: String, chunkCount: Int) = record(listOf(StoreObjectRef(storageKey, chunkCount)))

    /** Rows the sweep may attempt now: not given up on, and past their backoff. Oldest first, at most [limit]. */
    fun due(now: Instant, limit: Int): List<StoreReclaimEntity> =
        StoreReclaimEntity.find("abandonedAt is null and nextAttemptAt <= ?1 order by createdAt, id", now)
            .page(0, limit).list()

    /**
     * The row's objects are gone from the store: drop it.
     *
     * A **bulk** delete by id, not `deleteById`, and the difference is not style. `deleteById` loads the row and
     * removes the managed entity, so a row that vanished between this sweep's read and this write fails the
     * flush with a `StaleStateException` and takes the rest of the tick's rows with it. That is a reachable
     * state, not a theoretical one: two instances of a self-hosted deployment run this sweep against one
     * database, and both legitimately hand the same keys to the store (`deleteObject` is idempotent, so the
     * duplicate costs nothing). Settling must be idempotent for the same reason the store delete is.
     */
    @Transactional
    fun settle(id: String) {
        StoreReclaimEntity.delete("id", id)
    }

    /**
     * The store refused at least one of the row's keys. Count the attempt and push the retry out; at [maxAttempts]
     * stamp [StoreReclaimEntity.abandonedAt] and **keep the row** — that row is the countable leak (Decision 5).
     * Returns true when this call abandoned it, so the caller can log that once.
     */
    @Transactional
    fun defer(id: String, now: Instant, backoff: Duration, maxAttempts: Int): Boolean {
        val row = StoreReclaimEntity.findById(id) ?: return false
        row.attempts += 1
        if (row.attempts >= maxAttempts) {
            row.abandonedAt = now
            row.persist()
            return true
        }
        // Exponential, doubling per attempt and capped so a long-dead store cannot push a retry past its own
        // give-up point. The sweep interval is the resolution floor: a backoff shorter than it means "next tick".
        val factor = 1L shl (row.attempts - 1).coerceAtMost(6)
        row.nextAttemptAt = now.plus(backoff.multipliedBy(factor))
        row.persist()
        return false
    }
}
