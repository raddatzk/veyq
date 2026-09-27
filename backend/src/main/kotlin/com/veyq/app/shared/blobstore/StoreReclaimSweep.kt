package com.veyq.app.shared.blobstore

import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKeys
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant

/**
 * **The only component in veyq that deletes store objects** (ADR 0062 Decision 3). Every path that removes a row
 * carrying a `storage_key` puts the prefix on [StoreReclaimEntity] inside its own transaction; this drains that
 * list. Nine call sites of `blobStore.delete` collapsed to this one, so there is exactly one place that can fail,
 * one that retries, and one to read to know what the system does with bytes —
 * `scripts/validate_store_reclaim_arity.py` is what keeps that true.
 *
 * **The cost of being the only deleter is latency, and it is accepted:** bytes linger up to one sweep interval
 * after the rows that named them are gone. Nothing user-facing depends on the object being absent at the instant
 * the row is — every one of those paths already removed the item from every read.
 *
 * **Not gated on [BlobStore.canPresign].** The reason is [com.veyq.app.features.media.UploadSessionPurge]'s: a
 * store that cannot presign still has to be told, and gating here would make the half this sweep exists for
 * unobservable in the hermetic suite — which runs on the store whose `delete` is a no-op.
 *
 * **A refusal is a deferral, never a drop.** A row is settled only when [BlobStore.delete] reports that none of
 * its keys failed; otherwise it keeps its place with `attempts` counted and the retry pushed out, and at the
 * configured maximum it is stamped abandoned and logged once. That last row is the whole point of the design: a
 * permanently unreachable store still loses the bytes, but the loss is now a row you can count.
 *
 * Operator-blind: this reads opaque key prefixes and deletes opaque objects. It opens nothing and needs no key.
 */
@ApplicationScoped
class StoreReclaimSweep(
    private val store: StoreReclaimStore,
    private val blobStore: BlobStore,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(StoreReclaimSweep::class.java)
    private val maxAttempts: Int = config.storeReclaim().maxAttempts()
    private val backoff: Duration = config.storeReclaim().backoff()
    private val batchSize: Int = config.storeReclaim().batchSize()

    @Scheduled(every = "{veyq.store-reclaim.sweep.every}")
    fun run() {
        val now = Instant.now()
        // `QuarkusTransaction`, not `@Transactional` on this method: the object deletes must happen OUTSIDE a
        // transaction (they are a network call to a foreign system, and holding a DB transaction across one is
        // how a slow store becomes a database problem), and a self-invoked `@Transactional` would not be
        // intercepted anyway — the reason `NodeSubtreePurge` and `ScopePurge` give for the same choice.
        val due = QuarkusTransaction.requiringNew().call { store.due(now, batchSize) }
        if (due.isEmpty()) return

        // One batch call, then attribute the failures back per row: a row is settled only if NONE of its keys
        // came back refused. Partial success within a row is not a settlement — the remaining objects would
        // become exactly the untracked orphan this table exists to abolish.
        val keysByRow = due.associate { row -> row.id to chunkKeys(row.storageKey, row.chunkCount) }
        val refused = handOver(keysByRow.values.flatten())

        keysByRow.forEach { (id, keys) ->
            if (keys.none { it in refused }) {
                store.settle(id)
            } else if (store.defer(id, now, backoff, maxAttempts)) {
                // Logged once, at the transition — not on every tick, or a dead store writes a log per row per
                // hour forever. The countable form of this is the row itself, which is why it is kept.
                log.error(
                    "store reclaim gave up on an object after $maxAttempts attempts; its bytes are leaked and " +
                        "counted — SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL",
                )
            }
        }
    }

    /**
     * Hand the keys to the store and return the ones it could not delete. The port is specified not to throw
     * (ADR 0062 Decision 2 made the failure a return value precisely so a partly-deletable batch reports which
     * half failed), but an implementation that throws anyway must not cost the tick its other rows — so a throw
     * is read as "the whole batch was refused", which defers every row rather than dropping any.
     */
    private fun handOver(keys: List<String>): Set<String> =
        runCatching { blobStore.delete(keys).toSet() }
            .getOrElse { e ->
                log.error("the store refused the whole reclaim batch; every row in it is retried", e)
                keys.toSet()
            }
}
