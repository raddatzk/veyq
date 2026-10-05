package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant

/**
 * The abandoned-upload-session reclaim (uc.memory.upload E2, en.memory.media E3, ADR 0013 — the "stale OPEN
 * sessions" half of its GC; the unreferenced-blob half is a separate job with a far larger blast radius and is
 * deliberately not folded in here). An upload that is cancelled, dies mid-flight, or is rejected by the
 * `complete` budget gate leaves a `veyq_upload_session` row behind, and — for the first two — the chunks the
 * client already PUT **straight into the object store**. This is what reclaims both.
 *
 * **The row and the bytes are one unit of work, and ADR 0062 finally makes them one transaction.** A row-only
 * delete is strictly *worse* than no GC at all — the session row is the only thing that knows the `storage_key`,
 * so dropping it alone orphans those bytes for the life of the bucket. This job therefore no longer deletes
 * objects at all: `purgeSessionsIdleSince` writes each session's key prefix to `veyq_store_reclaim` inside
 * the same transaction that deletes the row, and `StoreReclaimSweep` — the one deleter — drains that list. The
 * old shape (rows in a tx, a best-effort `blobStore.delete` after the commit) could not fail out loud: the
 * delete swallowed every per-key failure and the pointer was already gone.
 *
 * **It keys on a session's last issued window, not on its start** (ADR 0013 §3). An upload is signed window by
 * window, so a large file on a slow link is legitimately open for far longer than any retention — what bounds it
 * is that each window's PUTs are signed under `veyq.blob-store.presign-ttl-seconds`, so a session with no new
 * window past that TTL holds only dead URLs. That is also why the retention window (`veyq.media.upload.retention`)
 * is bounded by construction, not by taste: it only has to outlast a PUT that started inside its window's TTL and
 * is still streaming.
 *
 * Operator-blind: this reads `last_window_at` + `storage_key` and deletes opaque objects. It opens nothing and
 * needs no key.
 */
@ApplicationScoped
class UploadSessionPurge(
    private val store: BlobUploadStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.media().upload().retention()

    @Scheduled(every = "{veyq.media.upload.purge.every}")
    fun run() {
        // Unconditional — NOT behind `canPresign`: a store that cannot presign still has to be told, and gating
        // would make the half this job exists for unobservable in the hermetic suite. That reasoning moved with
        // the delete itself and now governs `StoreReclaimSweep`, which is not gated either.
        store.purgeSessionsIdleSince(Instant.now().minus(retention))
    }
}
