package me.raddatz.veyq.files

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareRoots
import jakarta.enterprise.context.ApplicationScoped

/** The node kinds Files owns (ADR 0079 Decision 3) — the values of `veyq_node.kind` core does not interpret. */
object FilesNodeKinds {
    /**
     * A file: a node holding ONE sealed blob (replaced, never versioned), with its name sealed under its parent's key like
     * every node (docs/apps/files.md). It has no children — no rule admits a `FILE` as a parent — and its directories
     * are core's `FOLDER`s. Not a share root: a file is shared through its folder.
     */
    const val FILE = "FILE"

    /**
     * A drop box (ADR 0080 decision 11, docs/apps/files.md § The drop box): a node of its own, at the root or in a
     * `FOLDER`, shared only `WRITE_ONLY` ([FilesShareRoots]). Its children are its guests' **identity folders** — core
     * `FOLDER`s, one per guest identity, which only core's `IdentityFolders` places (Files' `ensureShareDropFolder`): no
     * placement rule admits anything under a drop box, so a member creates, moves and renames nothing into it, and only
     * deletes or moves out.
     */
    const val DROPBOX = "FILES_DROPBOX"
}

/**
 * Where Files' kinds live in core's tree: a `FILE` or a `FILES_DROPBOX` at the root or under a `FOLDER` (the place a folder
 * may take), never under another kind — so never under a `FILE` or a drop box either; and since no implementation admits
 * either as a parent, neither takes a placed child (a drop box's identity folders are core's own placement, see
 * [FilesNodeKinds.DROPBOX]). `FilesNodeKindRulesTest` pins the matrix, with core's
 * [me.raddatz.veyq.core.node.FolderNodeRules] beside it; `ModuleCompositionTest` the partition across core and every
 * application.
 */
@ApplicationScoped
class FilesNodeKindRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == FilesNodeKinds.FILE || kind == FilesNodeKinds.DROPBOX

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        handles(kind) && (parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER))
}

/**
 * Which share roles Files' kinds take (ADR 0080 decision 6: shareability is separate from guest writes): a drop box is
 * shared `WRITE_ONLY` only — its guests hold no key of it and get one identity folder each (decision 11); a `FILE` is no
 * share root (it is shared through its folder).
 */
@ApplicationScoped
class FilesShareRoots : ShareRoots {
    override fun roles(kind: String): Set<String>? =
        if (kind == FilesNodeKinds.DROPBOX) setOf(ShareEntity.WRITE_ONLY) else null
}
