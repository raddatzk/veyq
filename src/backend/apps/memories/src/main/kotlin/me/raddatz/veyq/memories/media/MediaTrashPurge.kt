package me.raddatz.veyq.memories.media

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * The media trash retention purge (uc.memory.delete-media, cr-0110). A first-party delete soft-deletes a media
 * into its per-Memory trash (`deleted_at`/`deleted_by`); this job hard-deletes those trashed longer than the
 * retention window ago — the media rows and, with them, their blobs through core's blob service and the store chunk
 * objects. The window is core's node trash retention (`veyq.node.trash.retention`, ADR 0113 decision 5): the one the
 * service's own purge erases a released blob after, so a medium and its bytes are due at the same instant. It is the
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
class MediaTrashPurge(private val store: MediaStore) {

    @Scheduled(every = "{veyq.node.trash.purge.every}")
    fun run() {
        store.purgeTrashedBefore(Instant.now().minus(store.retention))
    }
}
