package me.raddatz.veyq.depot

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.sharing.GuestCreatableKinds
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareRoots
import me.raddatz.veyq.core.sharing.WriteOnlyGuestKinds
import jakarta.enterprise.context.ApplicationScoped

/** The node kinds Depot owns (ADR 0079 Decision 3) — the values of `veyq_node.kind` core does not interpret. */
object DepotNodeKinds {
    /**
     * A file: a node holding ONE sealed blob (replaced, never versioned), with its name sealed under its parent's key like
     * every node (docs/apps/depot.md). It has no children — no rule admits a `FILE` as a parent — and its directories
     * are core's `FOLDER`s. A share root with `READ` or `READ_WRITE` ([DepotShareRoots]); it is also shared through
     * any folder above it.
     */
    const val FILE = "FILE"

    /**
     * A drop box (ADR 0080 decision 11, docs/apps/depot.md § The drop box): a node of its own, at the root or in a
     * `FOLDER`, shared only `WRITE_ONLY` ([DepotShareRoots]). Its children are its guests' **identity folders** — core
     * `FOLDER`s, one per guest identity, which only core's `IdentityFolders` places (Depot's `ensureShareDropFolder`): no
     * placement rule admits anything under a drop box, so a member creates, moves and renames nothing into it, and only
     * deletes or moves out.
     */
    const val DROPBOX = "DEPOT_DROPBOX"
}

/**
 * Where Depot's kinds live in core's tree: a `FILE` or a `DEPOT_DROPBOX` at the root or under a `FOLDER` (the place a folder
 * may take), never under another kind — so never under a `FILE` or a drop box either; and since no implementation admits
 * either as a parent, neither takes a placed child (a drop box's identity folders are core's own placement, see
 * [DepotNodeKinds.DROPBOX]). `DepotNodeKindRulesTest` pins the matrix, with core's
 * [me.raddatz.veyq.core.node.FolderNodeRules] beside it; `ModuleCompositionTest` the partition across core and every
 * application.
 */
@ApplicationScoped
class DepotNodeKindRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == DepotNodeKinds.FILE || kind == DepotNodeKinds.DROPBOX

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        handles(kind) && (parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER))

    /** A drop box takes a cover (inbox #411); a file does not - its one blob is its content, and a file's own thumbnail is a different idea. */
    override fun takesCover(kind: String): Boolean = kind == DepotNodeKinds.DROPBOX

    /**
     * A new drop box needs the drop-box links (#440 gap 2): it exists to carry a `WRITE_ONLY` link, so with
     * `core.public-shares.drop-box` off — or its parent, by the AND along the path — none is created (`capability_disabled`).
     * A core id, because Depot has no catalog entry of its own yet: the CR that adds the first `<application>.*` entries
     * re-points it. A file needs none.
     */
    override fun capabilityToCreate(kind: String): String? =
        if (kind == DepotNodeKinds.DROPBOX) CapabilityIds.PUBLIC_SHARES_DROP_BOX else null
}

/**
 * Which share roles Depot's kinds take (ADR 0080 decision 6: shareability is separate from guest writes): a drop box is
 * shared `WRITE_ONLY` only — its guests hold no key of it and get one identity folder each (decision 11); a `FILE` is
 * shared `READ` (see and download it) or `READ_WRITE` (and replace its content, as a contributor of a folder above may).
 * **Never `WRITE_ONLY` on a file**: a write-only guest holds no key and reads nothing back, and a file has no identity
 * folder to send into, so the role would mean nothing — `illegal_scope`, fail closed (a guest then creates nothing under
 * a file either: no [me.raddatz.veyq.core.sharing.GuestNodeRules] answers for a `FILE` container).
 */
@ApplicationScoped
class DepotShareRoots : ShareRoots {
    override fun roles(kind: String): Set<String>? = when (kind) {
        DepotNodeKinds.DROPBOX -> setOf(ShareEntity.WRITE_ONLY)
        DepotNodeKinds.FILE -> setOf(ShareEntity.READ, ShareEntity.READ_WRITE)
        else -> null
    }
}

/**
 * What a drop box's `WRITE_ONLY` guest may create inside its own identity folder (ADR 0080 decision 11, core's
 * [WriteOnlyGuestKinds]): **`FILE`s only** — no sub-folder, no other kind (owner decision 2026-10-08, inbox #377 (c));
 * it uploads into those files and does nothing else. A `FILE` is never a `WRITE_ONLY` root ([DepotShareRoots]), so Depot handles the drop box only.
 */
@ApplicationScoped
class DepotWriteOnlyGuestKinds : WriteOnlyGuestKinds {
    override fun creatable(rootKind: String): Set<String>? =
        if (rootKind == DepotNodeKinds.DROPBOX) setOf(DepotNodeKinds.FILE) else null
}

/**
 * What a folder link's `READ_WRITE` guest may create in a folder besides a folder (ADR 0080 decision 6, core's
 * [GuestCreatableKinds]): a `FILE` — the contributor's upload lands in one, created with core's `createShareNode`.
 * **Never a drop box** (owner decision 2026-10-14): a guest makes no link-bearing node of the owner's.
 */
@ApplicationScoped
class DepotGuestCreatableKinds : GuestCreatableKinds {
    override fun kinds(): Set<String> = setOf(DepotNodeKinds.FILE)
}
