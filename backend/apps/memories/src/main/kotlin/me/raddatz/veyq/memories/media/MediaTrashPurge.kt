package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.config.VeyqConfig
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
 *
 * **The rows and the reclaim entries go in one transaction; the objects are not this job's** (ADR 0062): it hands
 * the keys to `veyq_store_reclaim` and `StoreReclaimSweep` deletes them once no URL signed for them can still land. The
 * post-commit `blobStore.delete` this used to make could fail silently, and by then the row that named the key
 * was gone.
 */
@ApplicationScoped
class MediaTrashPurge(
    private val store: MediaStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.media().trash().retention()

    @Scheduled(every = "{veyq.media.trash.purge.every}")
    fun run() {
        store.purgeTrashedBefore(Instant.now().minus(retention))
    }
}
