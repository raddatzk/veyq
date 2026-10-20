package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.backup.ApplicationErasures
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.items.NodeItemsRevisions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * Memories' half of the erasure ledger (ADR 0056 decision 8): the kinds its erasures write ([Kind], through
 * `ErasureLedger.erasedByApplication`) and their re-applier, which core calls at start for each such line. The `when`
 * over [Kind] is the sealed set's property for this application: a kind added without its re-applier does not compile.
 */
@ApplicationScoped
class MemoriesErasures(
    private val store: MediaStore,
    private val tree: NodesStore,
    private val items: NodeItemsRevisions,
) : ApplicationErasures {

    /** What Memories erases for good that no restored row would erase again: a medium ([MediaStore.purgeMediaRows]). */
    enum class Kind { MEDIA }

    override val kinds: Set<String> = Kind.entries.map { it.name }.toSet()

    override fun reapply(kind: String, id: String, scopeId: String?): Boolean = when (Kind.valueOf(kind)) {
        Kind.MEDIA -> reapplyMedia(id, scopeId)
    }

    /**
     * The medium [id], back after a restore: erased again through [MediaStore.purgeMediaRows] — its derivatives, its row,
     * its blob rows onto the reclaim list. The lock order of `MediaShareGuestWrites.deleteOwn`, scope then media; a medium
     * that was live (the restore predates its trash too) moves the item revision as that delete does, so an open gallery
     * drops it.
     */
    private fun reapplyMedia(id: String, scopeId: String?): Boolean {
        if (scopeId != null) tree.lockScope(scopeId)
        val media = MediaEntity.findById(id, LockModeType.PESSIMISTIC_WRITE) ?: return false
        val live = media.deletedAt == null
        val nodeId = media.nodeId
        store.purgeMediaRows(media)
        if (live) items.bump(media.scopeId, nodeId, deleted = true)
        return true
    }
}
