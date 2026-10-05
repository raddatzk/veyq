package me.raddatz.veyq.memories.node

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import jakarta.enterprise.context.ApplicationScoped

/** The node kinds Memories owns (ADR 0079 Decision 3) — the values of `veyq_node.kind` core does not interpret. */
object MemoriesNodeKinds {
    /** The content unit: holds media, carries its own `K_mem` under `K_SC`, the root of a share. */
    const val MEMORY = "MEMORY"

    /** A part of a Memory: holds media, carries its own `K_mem` wrapped under the parent Memory's. */
    const val CHAPTER = "CHAPTER"
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
}
