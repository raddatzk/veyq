package me.raddatz.veyq.memories.node

import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareRoots
import jakarta.enterprise.context.ApplicationScoped

/**
 * Which share roles Memories' kinds may be the root of: a `MEMORY` with all three (viewer, contributor, uploader —
 * ADR 0029, 0030, 0031). A `CHAPTER` is declared by nobody yet, so a chapter share stays refused: it is
 * cryptographically ready (ADR 0080 decision 13), but Memories has not decided its guest writes
 * (`MediaShareGuestWrites`, `MediaUploadAdmission` answer for a `MEMORY` root only).
 */
@ApplicationScoped
class MemoryShareRoots : ShareRoots {
    override fun roles(kind: String): Set<String>? =
        if (kind == MemoriesNodeKinds.MEMORY) setOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY) else null
}
