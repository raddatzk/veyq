package me.raddatz.veyq.memories.node

import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareRoots
import jakarta.enterprise.context.ApplicationScoped

/**
 * Which share roles Memories' kinds may be the root of: a `MEMORY` and a `CHAPTER`, each with all three (viewer,
 * contributor, uploader — ADR 0029, 0030, 0031). A chapter link is a smaller Memory link (ADR 0080 decisions 9 and
 * 13): the chapter has its own `K_mem` and its media's `K_blob` hang under it, so its guest reads the chapter and
 * nothing of the Memory above, a contributor's or an uploader's media land in the chapter (the guest complete
 * attaches to the share's root), and an uploader seals to the chapter's own drop key. The role matrix is
 * `docs/apps/memories.md`.
 */
@ApplicationScoped
class MemoryShareRoots : ShareRoots {
    override fun roles(kind: String): Set<String>? =
        if (kind in MemoriesNodeKinds.SHARE_ROOTS) setOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY) else null
}
