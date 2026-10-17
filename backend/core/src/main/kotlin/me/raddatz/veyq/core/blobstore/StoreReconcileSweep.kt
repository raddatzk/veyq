package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration
import java.time.Instant

/**
 * The bucket reconcile (ADR 0062 § Consequences, uc.admin.configure-object-store A7): lists the store in use page by
 * page — a few pages per tick, resumable from a key cursor — and counts the objects **no row claims**; a storage key
 * that stayed one in every pass for the grace goes onto the reclaim list, while the Server Owner's switches allow it.
 *
 * **It deletes nothing.** Its only write that leads to a deletion is `StoreReclaimStore.record`, inside the transaction
 * that completes a pass ([StoreReconcileStore.writePage]); [StoreReclaimSweep] stays the one caller of
 * `BlobStore.delete` (`scripts/validate_store_reclaim_arity.py`).
 *
 * Per page, in this order (ADR 0062's read-order rules): **list** → classify each key ([StoreKeys]: the probe, veyq's
 * `<uuid>/<index>`, or not veyq's) → **one claim query** over every claim table for the page's veyq-shaped keys (one
 * statement, one snapshot, after the listing — it catches an upload that began during it) → an object no claim row
 * explains (no row names its key with a `chunk_count` above its index) and older than the **safe age**
 * (`veyq.blob.upload.retention`, measured `storeTime − lastModified`, both the store's clock) is **stat**ed, and only one
 * still present is an orphan — a row deleted and reclaim-drained during the listing deleted its object first, so the
 * `stat` does the read before. A key any row claims is never a candidate, even for an object past its count: a reclaim
 * row deletes `0 … n−1`. A key with an unexplained object younger than the safe age is no candidate in this pass.
 *
 * **A failure ends the tick, keeps the cursor, publishes and enqueues nothing**, and is logged once at the transition
 * into it (the reclaim sweep's rule): the last completed pass stays what the console shows, with the failure beside it.
 * One INFO line per completed pass, counts only — never a key.
 *
 * Operator-blind: opaque keys, sizes and the store's timestamps; it opens nothing and attributes nothing to a scope.
 */
@ApplicationScoped
class StoreReconcileSweep(
    private val store: StoreReconcileStore,
    private val blobStore: BlobStore,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(StoreReconcileSweep::class.java)
    private val settings = config.storeReconcile()
    private val safeAge: Duration = config.blob().upload().retention()

    @Scheduled(every = "{veyq.store-reconcile.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun run() = tick()

    /** One tick: at most `pages-per-tick` pages of the pass in progress (or of a new one, when one is due). */
    fun tick() {
        val digest = blobStore.bucketDigest ?: return fail(ReconcileFailure.NO_STORE)
        var at = runCatching { store.open(digest, Instant.now(), settings.period()) }
            .getOrElse { e -> return fail(ReconcileFailure.DATABASE, e) }
            ?: return
        repeat(settings.pagesPerTick()) {
            val listing = try {
                blobStore.list(at.cursor, settings.pageSize())
            } catch (e: Exception) {
                return fail(reasonOf(e), e)
            } ?: return fail(ReconcileFailure.NO_STORE)
            // The setting moved while this page was listed: the next tick's open() restarts the pass for the new bucket.
            if (blobStore.bucketDigest != at.digest) return

            val keyed = listing.objects.map { it to StoreKeys.classify(it.key) }
            val veyqKeys = keyed.mapNotNull { (_, k) -> (k as? ListedKey.Veyq)?.storageKey }
            val claims = runCatching { store.claims(veyqKeys) }.getOrElse { e -> return fail(ReconcileFailure.DATABASE, e) }
            val judged = try {
                judge(keyed, claims, listing.storeTime)
            } catch (e: Exception) {
                return fail(ReconcileFailure.STORE_UNREACHABLE, e)
            }
            val written = runCatching {
                store.writePage(
                    at, judged, listing.objects.lastOrNull()?.key, listing.storeTime, listing.truncated,
                    Instant.now(), settings.grace(), settings.maxGap(),
                )
            }.getOrElse { e -> return fail(ReconcileFailure.DATABASE, e) }
            when (written) {
                PageWrite.Lost -> return
                is PageWrite.Continued -> {
                    if (written.clocksRestarted) logGap()
                    at = written.next
                }
                is PageWrite.Completed -> {
                    if (written.clocksRestarted) logGap()
                    with(written.pass) {
                        log.info(
                            "store reconcile: a pass listed $objects object(s) — $orphanObjects orphan object(s) " +
                                "($orphanBytes bytes) no row claims, $notVeyqObjects not veyq's; $candidateKeys candidate " +
                                "key(s), $enqueuedKeys put on the reclaim list",
                        )
                    }
                    return
                }
            }
        }
    }

    /**
     * Classify and judge one page against [claims] (a key → the highest `chunk_count` a row gives it). The `stat` of an
     * unexplained old object happens here, outside any transaction.
     */
    private fun judge(keyed: List<Pair<StoredObject, ListedKey>>, claims: Map<String, Int>, storeTime: Instant): JudgedPage {
        var notVeyq = 0L
        var orphanObjects = 0L
        var orphanBytes = 0L
        val candidates = HashMap<String, OrphanKey>()
        val notCandidates = HashSet<String>()
        keyed.forEach { (obj, key) ->
            when (key) {
                ListedKey.Probe -> Unit
                ListedKey.NotVeyq -> notVeyq += 1
                is ListedKey.Veyq -> {
                    val claimed = claims[key.storageKey]
                    if (claimed != null) notCandidates += key.storageKey
                    if (claimed != null && claimed > key.index) return@forEach
                    // Unexplained. Old enough, on the store's own clock, and still there when asked again?
                    if (Duration.between(obj.lastModified, storeTime) < safeAge) {
                        if (claimed == null) notCandidates += key.storageKey
                        return@forEach
                    }
                    if (blobStore.stat(obj.key) == null) return@forEach
                    orphanObjects += 1
                    orphanBytes += obj.size
                    if (claimed == null) {
                        candidates.merge(key.storageKey, OrphanKey(1, obj.size, key.index)) { a, b ->
                            OrphanKey(a.objects + b.objects, a.bytes + b.bytes, maxOf(a.maxIndex, b.maxIndex))
                        }
                    }
                }
            }
        }
        return JudgedPage(
            objects = keyed.size.toLong(),
            orphanObjects = orphanObjects,
            orphanBytes = orphanBytes,
            notVeyqObjects = notVeyq,
            candidates = candidates.filterKeys { it !in notCandidates },
            notCandidates = notCandidates,
        )
    }

    private fun fail(reason: ReconcileFailure, cause: Throwable? = null) {
        val fresh = runCatching { store.recordFailure(reason, Instant.now()) }.getOrDefault(reason == ReconcileFailure.DATABASE)
        if (fresh) {
            // Once, at the transition — a store that stays down writes one line, not one per minute. Never a key.
            log.warnf("store reconcile stopped: %s — the pass resumes from its place at the next tick (%s)",
                reason, cause?.javaClass?.simpleName ?: "no detail")
        }
    }

    private fun logGap() =
        log.info("store reconcile: two listed pages lay further apart than veyq.store-reconcile.max-gap, so every " +
            "first-seen clock restarted — the grace counts only time the reconcile observed")

    private fun reasonOf(e: Exception): ReconcileFailure = when {
        e is MissingStoreClock -> ReconcileFailure.NO_STORE_CLOCK
        e is S3Exception && e.statusCode() == 403 -> ReconcileFailure.LIST_DENIED
        else -> ReconcileFailure.STORE_UNREACHABLE
    }
}

/** What a listed key is, before it is judged: the probe and a key not shaped like veyq's are never orphans (ADR 0062). */
sealed interface ListedKey {
    /** The store check's probe ([StoreCheck.PROBE_KEY]): overwritten by every check, never anyone's, never counted. */
    data object Probe : ListedKey

    /** `<uuid>/<index>` — a chunk veyq's upload path names. */
    data class Veyq(val storageKey: String, val index: Int) : ListedKey

    /** Anything else: another writer's (a shared bucket, an object pool kept in it, a hand-copied file). Never an orphan. */
    data object NotVeyq : ListedKey
}

object StoreKeys {
    /** A server-minted UUID (`BlobUploadHandler`) and a chunk index without a leading zero, as `BlobStore.chunkKey`
     *  writes it — at most nine digits, so it is an `Int` and never a number a key could overflow. */
    private val VEYQ = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(0|[1-9][0-9]{0,8})$")

    fun classify(key: String): ListedKey {
        if (key == StoreCheck.PROBE_KEY) return ListedKey.Probe
        val m = VEYQ.matchEntire(key) ?: return ListedKey.NotVeyq
        return ListedKey.Veyq(m.groupValues[1], m.groupValues[2].toInt())
    }
}
