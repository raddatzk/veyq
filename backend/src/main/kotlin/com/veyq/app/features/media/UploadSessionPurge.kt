package com.veyq.app.features.media

import com.veyq.app.shared.blobstore.BlobStore
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
 * **The row and the bytes are one unit of work, and the order is load-bearing in one direction only:** rows in
 * a transaction first, store objects after it commits. A failed object delete then leaves a reclaimable object
 * and no pointer to it; deleting objects first would leave a row pointing at nothing if the tx rolled back. A
 * row-only delete is strictly *worse* than no GC at all — the session row is the only thing that knows the
 * `storage_key`, so dropping it alone orphans those bytes for the life of the bucket.
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
    private val blobStore: BlobStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.media().upload().retention()

    @Scheduled(every = "{veyq.media.upload.purge.every}")
    fun run() {
        val reclaimKeys = store.purgeSessionsStartedBefore(Instant.now().minus(retention))
        // Best-effort store purge, after the DB tx commits. Unconditional — NOT behind `canPresign` like the
        // budget-failure purge in MediaHandler: a store that cannot presign still has to be told, and gating
        // here would make the half this job exists for unobservable in the hermetic suite.
        if (reclaimKeys.isNotEmpty()) blobStore.delete(reclaimKeys)
    }
}
