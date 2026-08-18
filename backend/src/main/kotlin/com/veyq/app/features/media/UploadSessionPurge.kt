package com.veyq.app.features.media

import com.veyq.app.shared.config.VeyqConfig
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
 * objects at all: `purgeSessionsStartedBefore` writes each session's key prefix to `veyq_store_reclaim` inside
 * the same transaction that deletes the row, and `StoreReclaimSweep` — the one deleter — drains that list. The
 * old shape (rows in a tx, a best-effort `blobStore.delete` after the commit) could not fail out loud: the
 * delete swallowed every per-key failure and the pointer was already gone.
 *
 * **The retention window is bounded by construction, not by taste** (`veyq.media.upload.retention`): every
 * presigned PUT of a session is signed once at `start` under `veyq.blob-store.presign-ttl-seconds` and no
 * operation re-signs one, so a client past that TTL holds only dead URLs. The window only has to outlast a PUT
 * that started inside the TTL and is still streaming. Resumable uploads would change that — they are ADR 0013
 * § Rejected's most expensive add, and would need a re-presign path before this window mattered.
 *
 * Operator-blind: this reads `created_at` + `storage_key` and deletes opaque objects. It opens nothing and
 * needs no key.
 */
@ApplicationScoped
class UploadSessionPurge(
    private val store: MediaStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.media().upload().retention()

    @Scheduled(every = "{veyq.media.upload.purge.every}")
    fun run() {
        // Unconditional — NOT behind `canPresign`: a store that cannot presign still has to be told, and gating
        // would make the half this job exists for unobservable in the hermetic suite. That reasoning moved with
        // the delete itself and now governs `StoreReclaimSweep`, which is not gated either.
        store.purgeSessionsStartedBefore(Instant.now().minus(retention))
    }
}
