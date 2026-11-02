package me.raddatz.veyq.memories.node

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import jakarta.enterprise.context.ApplicationScoped

/** The node kinds Memories owns (ADR 0079 Decision 3) — the values of `veyq_node.kind` core does not interpret. */
object MemoriesNodeKinds {
    /** The content unit: holds media, carries its own `K_node` under `K_SC`, the root of a share. */
    const val MEMORY = "MEMORY"

    /** A part of a Memory: holds media, carries its own `K_node` wrapped under the parent Memory's, the root of a share
     *  (a smaller Memory link: its key opens nothing of the parent, ADR 0080 decisions 9 and 13). */
    const val CHAPTER = "CHAPTER"

    /**
     * The kinds a share of Memories is rooted at, with every role, and so the kinds Memories runs a guest's media writes
     * under: [MemoryShareRoots] and `MediaShareGuestWrites` answer from this one set, so the role a link was created with
     * and its writer cannot disagree about a kind (a link's usage is core's blob service's, ADR 0113).
     */
    val SHARE_ROOTS: Set<String> = setOf(MEMORY, CHAPTER)
}

/**
 * Where Memories' kinds live in core's tree: a `MEMORY` at the root or under a `FOLDER` (the place a folder may take);
 * a `CHAPTER` only directly inside a `MEMORY`. `NodeKindMatrixSliceTest` pins the whole matrix, with core's
 * [me.raddatz.veyq.core.node.FolderNodeRules] beside it.
 */
@ApplicationScoped
class MemoryNodeKindRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == MemoriesNodeKinds.MEMORY || kind == MemoriesNodeKinds.CHAPTER

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean = when (kind) {
        MemoriesNodeKinds.MEMORY -> parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER)
        MemoriesNodeKinds.CHAPTER -> parent is ParentSlot.Node && parent.kind == MemoriesNodeKinds.MEMORY
        else -> false
    }

    /** A Memory and a Chapter carry a cover (inbox #411: core's `COVER` slot, since #341). */
    override fun takesCover(kind: String): Boolean = handles(kind)

    /** Both kinds are Memories': switched off, neither is written (uc.platform.switch-application-off). */
    override fun applicationOf(kind: String): String? = if (handles(kind)) CapabilityIds.MEMORIES else null
}
