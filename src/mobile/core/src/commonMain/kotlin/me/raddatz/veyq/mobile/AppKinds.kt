package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.crypto.Naming
import me.raddatz.veyq.mobile.expenses.PotCreate
import me.raddatz.veyq.mobile.expenses.PotExport
import me.raddatz.veyq.mobile.expenses.PotRekey
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.session.NodeRekey

/**
 * Every node kind this build knows, as core's writes need it: the composition root of kinds (the web's registry, filled by
 * each application's `register.ts`), the one place core's session meets an application. Core's session never names a
 * kind; the apps hand it these.
 */
object AppKinds {
    /**
     * The input a kind folds a node's name to for its slug token (the web's `registry.tokenInputFor`: the kind's own slug
     * rule — a file's stricter `fileNameSlug`, else `slugify` — behind its application's route slug where it claimed one,
     * pinned by `v1.json#name_folding`); null for a kind this build does not know, whose names it then does not write.
     */
    fun tokenInput(kind: String, name: String): String? = when (kind) {
        FOLDER, DROPBOX, SHARE_BOOKMARK -> Naming.slugify(name)
        FILE -> Naming.fileNameSlug(name)
        MEMORY, CHAPTER -> "memories/" + Naming.slugify(name)
        PotSession.KIND -> PotCreate.tokenInput(name)
        else -> null
    }

    /**
     * How the app re-keys a node of a kind (ADR 0114): core's kinds and Depot's with nothing of their own under the node's
     * key (their blobs are core's, the re-key moves them), a Pot its revisions' keys (`PotRekey`); Memories' media rows are
     * the web's re-key, so a Memory and a chapter stay due for a web device.
     */
    val rekeys = NodeRekey.Kinds { kind ->
        when (kind) {
            FOLDER, FILE, DROPBOX, SHARE_BOOKMARK -> NodeRekey.ContentRekey.NONE
            PotSession.KIND -> PotRekey.content
            else -> null
        }
    }

    /** What a tenant session re-keys and folds with (`TenantSession.rekeyRules`). */
    val rules = NodeRekey.Rules(rekeys, ::tokenInput)

    /**
     * How the build's kinds export into a download (the web's `exportContent` hooks, `NodeExport.walk`): a Pot its ledger and
     * receipts (`PotExport`, its erased people named [deletedName]), a Depot file its content streamed and a drop box a
     * directory of its guests' folders (`DepotExport`); every other kind but a folder is left out and said. One per run:
     * Depot's files are listed once.
     */
    fun exports(deletedName: String): me.raddatz.veyq.mobile.export.NodeExport.Kinds {
        val depot = me.raddatz.veyq.mobile.depot.DepotExport()
        return me.raddatz.veyq.mobile.export.NodeExport.Kinds(
            exports = { it == PotSession.KIND || it == FILE || it == DROPBOX },
            content = { tenant, node ->
                when (node.kind) {
                    PotSession.KIND -> PotSession.open(tenant, node.scopeId, node.id)?.let { PotExport.content(it, deletedName) }
                    FILE -> depot.file(tenant, node)
                    DROPBOX -> me.raddatz.veyq.mobile.depot.DepotExport.dropBox()
                    else -> null
                }
            },
        )
    }

    /**
     * How the build's kinds copy (the web's `importContent` for a copy, `SubtreeCopy`): a Pot by Expenses' copy (`PotCopy`,
     * its erased people named [deletedName]; it names itself), a Depot file read here and uploaded again under a fresh key
     * (`DepotExport.duplicate`), a drop box as a plain folder (the web's `importsAsFolder`); every other kind but a folder is
     * left out and said. One per run: Depot's files are listed once.
     */
    fun copies(deletedName: String, uuid: () -> String): me.raddatz.veyq.mobile.export.SubtreeCopy.Kinds {
        val depot = me.raddatz.veyq.mobile.depot.DepotExport()
        return me.raddatz.veyq.mobile.export.SubtreeCopy.Kinds(
            copies = { it == PotSession.KIND || it == FILE || it == DROPBOX },
            asFolder = { it == DROPBOX },
            duplicate = { tenant, node, scopeId, parentId, name ->
                when (node.kind) {
                    PotSession.KIND -> me.raddatz.veyq.mobile.export.SubtreeCopy.Made(
                        PotSession.open(tenant, scopeId, node.id)?.let { source ->
                            me.raddatz.veyq.mobile.expenses.PotCopy.duplicate(source, tenant, scopeId, parentId, deletedName, uuid)
                                .takeIf { it.outcome == me.raddatz.veyq.mobile.expenses.PotCopy.Outcome.COPIED }?.nodeId
                        },
                    )
                    FILE -> depot.duplicate(tenant, node, parentId, name, ::tokenInput)
                    else -> me.raddatz.veyq.mobile.export.SubtreeCopy.Made(null)
                }
            },
        )
    }

    /**
     * Where a node of a kind may be placed (each kind's `placement`, the web's `registry.placementAllows` mirroring the
     * backend's `NodeKindRules`): a folder, a Pot, a Memory, a Depot file and drop box and a kept link at the space's top or
     * in a folder, a chapter in a Memory; a kind this build does not know nowhere. A hint: the server decides.
     */
    val placement = me.raddatz.veyq.mobile.session.NodeMove.PlacementAllows { kind, parent ->
        when (kind) {
            FOLDER, PotSession.KIND, MEMORY, FILE, DROPBOX, SHARE_BOOKMARK -> parent == null || parent == FOLDER
            CHAPTER -> parent == MEMORY
            else -> false
        }
    }

    /**
     * A kind's header priority (the web's `header.order` of its descriptor, `NodeHeader.plan`): the ids listed first, in
     * this order. A kind not listed has none: core's merge order alone.
     */
    fun headerOrder(kind: String): List<String> = when (kind) {
        FOLDER, DROPBOX, PotSession.KIND -> listOf("favorite", "share", "download", "move", "copy")
        FILE -> listOf("favorite", "download", "share", "replace", "move", "copy")
        MEMORY, CHAPTER -> listOf("favorite", "upload", "share", "download", "banner", "move", "copy")
        SHARE_BOOKMARK -> listOf("open-own-page", "favorite", "move")
        else -> emptyList()
    }

    private const val FOLDER = "FOLDER"
    private const val FILE = "FILE"
    private const val DROPBOX = "DEPOT_DROPBOX"
    private const val SHARE_BOOKMARK = "SHARE_BOOKMARK"
    private const val MEMORY = "MEMORY"
    private const val CHAPTER = "CHAPTER"
}
