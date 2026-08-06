package com.veyq.app.features.media

import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant

/**
 * The media trash retention purge (uc.memory.delete-media, cr-0110). A first-party delete soft-deletes a media
 * into its per-Memory trash (`deleted_at`/`deleted_by`); this job hard-deletes those trashed longer than the
 * retention window ago — their derivative + media + blob rows (FK order) and the store chunk objects. It is the
 * **irreversible** half of the reversible/irreversible split (security-model `Deletion & erasure`): any member
 * trashes (reversible), only this system job purges (never a member hand-action). Soft-delete now, hard-delete
 * later — the same `@Scheduled` seam as the session/invite/scope purges (cr-0011/0053). Trashed rows are already
 * excluded from every list, so the purge needs no revision bump. Operator-blind: it removes opaque blob objects,
 * never content.
 */
@ApplicationScoped
class MediaTrashPurge(
    private val store: MediaStore,
    private val blobStore: BlobStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.media().trash().retention()

    @Scheduled(every = "{veyq.media.trash.purge.every}")
    fun run() {
        val reclaimKeys = store.purgeTrashedBefore(Instant.now().minus(retention))
        if (reclaimKeys.isNotEmpty()) blobStore.delete(reclaimKeys) // best-effort store purge, after the DB tx commits
    }
}
