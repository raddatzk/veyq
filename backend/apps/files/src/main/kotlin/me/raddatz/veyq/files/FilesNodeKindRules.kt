package me.raddatz.veyq.files

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import jakarta.enterprise.context.ApplicationScoped

/** The node kinds Files owns (ADR 0079 Decision 3) — the values of `veyq_node.kind` core does not interpret. */
object FilesNodeKinds {
    /**
     * A file: a node holding ONE sealed blob (replaced, never versioned), with its name sealed under its parent's key like
     * every node (docs/apps/files.md). It has no children — no rule admits a `FILE` as a parent — and its directories
     * are core's `FOLDER`s. Not a share root (Files declares no `ShareRoots`): a file is shared through its folder.
     */
    const val FILE = "FILE"
}

/**
 * Where Files' kind lives in core's tree: a `FILE` at the root or under a `FOLDER` (the place a folder may take), never
 * under another kind — so never under a `FILE` either, and since no implementation admits a `FILE` as a parent, a file
 * has no children. `FilesNodeKindRulesTest` pins the matrix, with core's [me.raddatz.veyq.core.node.FolderNodeRules]
 * beside it; `ModuleCompositionTest` the partition across core and every application.
 */
@ApplicationScoped
class FilesNodeKindRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == FilesNodeKinds.FILE

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        kind == FilesNodeKinds.FILE &&
            (parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER))
}
