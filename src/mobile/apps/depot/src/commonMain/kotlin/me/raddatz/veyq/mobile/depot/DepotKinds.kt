package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.tree.KindFacts
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.ApplicationKinds
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.export.SubtreeImport
import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.HeaderAction as H
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.RowDetails
import me.raddatz.veyq.mobile.navigation.ShareAnswer
import me.raddatz.veyq.mobile.offline.PinKinds
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.search.KindSearch
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.ShareWords
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.sharing.GuestSession
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Piece

/**
 * Depot's part of the platform's registry ([AppKinds], the web's `features/depot/register.ts`): a file and a drop box —
 * a file's content its own download and copy (`DepotExport`), a drop box a plain folder when copied or imported —, and
 * for core's folder the answer to files shared to it (`FolderShare`) and a link guest's upload into it (`DepotGuest`).
 */
@HiddenFromObjC
object DepotKinds : ApplicationKinds {
    private const val FILE = DepotFiles.FILE
    private const val DROPBOX = DepotFiles.DROPBOX
    private const val FOLDER = "FOLDER"

    override val application = ApplicationId.DEPOT
    override val kinds = listOf(FILE, DROPBOX)
    override val word = Msg.APP_DEPOT

    override fun tabWord(key: String, objectKind: String?): Msg? =
        if (key == "depot.file") (if (objectKind == DROPBOX) Msg.TAB_DEPOT_DROPBOX else Msg.TAB_DEPOT_FILE) else null

    @PlatformApi
    @HiddenFromObjC
    override fun queuedSender(q: Queued): QueuedSender? = if (q == Queued.DEPOT_FILE_ADD) QueuedDepot.fileSender else null

    /** A file folds by `fileNameSlug`, a drop box as a container; Depot claims no route; both at the top or in a folder. */
    override val kindFacts = listOf(
        KindFacts.Kind(FILE, null, KindFacts.FILE_NAME, root = true, parents = listOf(FOLDER)),
        KindFacts.Kind(DROPBOX, null, KindFacts.NAME, root = true, parents = listOf(FOLDER)),
    )

    // nothing of their own under the node's key: their blobs are core's, the re-key moves them
    override fun rekey(kind: String): NodeRekey.ContentRekey? = if (kind in kinds) NodeRekey.ContentRekey.NONE else null

    override fun exports(deletedName: String): NodeExport.Kinds {
        val depot = DepotExport() // one per run: Depot's files are listed once
        return NodeExport.Kinds(
            exports = { it == FILE || it == DROPBOX },
            content = { tenant, node ->
                when (node.kind) {
                    FILE -> depot.file(tenant, node)
                    DROPBOX -> DepotExport.dropBox()
                    else -> null
                }
            },
        )
    }

    // a file downloads as itself, under its own name (ADR 0136's Consequences)
    override fun downloadsItself(kind: String): Boolean = kind == FILE

    override suspend fun ownDownload(tenant: TenantSession, ref: NodeAt, sink: (ByteArray) -> Unit): Boolean =
        DepotFiles.download(tenant, ref.scopeId, ref.nodeId, sink)

    override suspend fun downloadName(tenant: TenantSession, ref: NodeAt): String =
        DepotFiles.downloadName(runCatching { DepotFiles.one(tenant, ref.scopeId, ref.nodeId) }.getOrNull(), ref.name)

    override fun copies(deletedName: String, uuid: () -> String): SubtreeCopy.Kinds {
        val depot = DepotExport()
        return SubtreeCopy.Kinds(
            copies = { it == FILE || it == DROPBOX },
            asFolder = { it == DROPBOX },
            duplicate = { tenant, node, _, parentId, name ->
                if (node.kind == FILE) depot.duplicate(tenant, node, parentId, name, AppKinds::tokenInput) else SubtreeCopy.Made(null)
            },
        )
    }

    override fun imports(deletedName: String, uuid: () -> String, deriver: Derivation.Deriver?, now: () -> Long): SubtreeImport.Kinds {
        val file = DepotImport.file(AppKinds::tokenInput)
        return SubtreeImport.Kinds { kind ->
            when (kind) {
                FILE -> file
                DROPBOX -> DepotImport.dropBox
                else -> null
            }
        }
    }

    override fun headerOrder(kind: String): List<H>? = when (kind) {
        FILE -> listOf(H.FAVORITE, H.DOWNLOAD, H.SHARE, H.REPLACE, H.MOVE, H.COPY)
        DROPBOX -> listOf(H.FAVORITE, H.SHARE, H.DOWNLOAD, H.MOVE, H.COPY)
        else -> null
    }

    override fun header(kind: String): AppKinds.Header? = when (kind) {
        FILE -> AppKinds.Header(true, false, true, Msg.FILE_DELETE, Msg.FILE_DELETE_CONFIRM)
        // a drop box is never renamed: its name is its guests' address
        DROPBOX -> AppKinds.Header(true, true, false, Msg.DROPBOX_DELETE, Msg.DROPBOX_DELETE_CONFIRM)
        else -> null
    }

    override fun doors(kind: String, facts: AppKinds.Facts): AppKinds.Doors? = when (kind) {
        DROPBOX -> AppKinds.Doors(coverOffered = true, copies = true, downloads = true)
        FILE -> AppKinds.Doors(coverOffered = true, copies = facts.hasContent, downloads = facts.hasContent)
        else -> null
    }

    override fun lossesOfCopy(kind: String): List<Msg>? = when (kind) {
        DROPBOX -> listOf(Msg.COPY_LOSS_DROPBOX)
        FILE -> emptyList()
        else -> null
    }

    @PlatformApi
    @HiddenFromObjC
    override val kept = DepotKept

    // a file's row says its size and change (or that it is not uploaded), a drop box's its guests — each read on its own
    override val rowParts: List<RowDetails.Part> = listOf(
        RowDetails.Part("depot.files") { tenant -> DepotFiles.list(tenant).mapValues { DepotFiles.rowDetails(it.value) } },
        RowDetails.Part("depot.boxes") { tenant -> DropBoxes.people(tenant.nodes()).mapValues { listOf(Piece.Said(it.value)) } },
    )

    override val pins: PinKinds = object : PinKinds {
        override fun pinnable(kind: String): Boolean = kind == FILE
        override suspend fun blobs(tenant: TenantSession, node: OpenNode) = DepotFiles.pinned(tenant, node)
    }

    override fun search(kind: String): KindSearch? = when (kind) {
        FILE -> KindSearch(Msg.SEARCH_KIND_FILE, "file")
        DROPBOX -> KindSearch(Msg.SEARCH_KIND_DROPBOX, "inbox")
        else -> null
    }

    override fun shareWords(kind: String): ShareWords? = when (kind) {
        DROPBOX -> ShareWords(
            Msg.DROPBOX_SHARE_TITLE, Msg.DROPBOX_SHARE_HINT, Msg.SHARE_ROLE_UPLOADER,
            Msg.DROPBOX_UPLOAD_HINT, Msg.DROPBOX_UPLOAD_HINT, Msg.DROPBOX_SHARE_DELETE_CONFIRM,
        )
        FILE -> ShareWords(
            Msg.SHARE_FILE_TITLE, Msg.SHARE_FILE_INTRO, Msg.SHARE_FILE_ROLE_CONTRIBUTOR,
            Msg.SHARE_FILE_ROLE_VIEWER_HINT, Msg.SHARE_FILE_ROLE_CONTRIBUTOR_HINT, Msg.SHARE_FILE_DELETE_CONFIRM,
        )
        else -> null
    }

    // a folder — a space's top asks as one — takes shared files into Depot's upload queue
    override fun share(kind: String): ShareAnswer? = if (kind == FOLDER) FolderShare.answer else null

    override fun guestDoor(kind: String, role: String): GuestDoor? = when (role) {
        GuestAccess.WRITE_ONLY -> if (kind == DROPBOX) DepotGuest.dropBox else null
        GuestAccess.READ, GuestAccess.READ_WRITE -> if (kind == FILE) DepotGuest.file else null
        else -> null
    }

    // a guest moves a file of a folder (the web's Depot `guestRights`); a drop box below the link is read-only for it
    override fun guestMoves(kind: String): Boolean = kind == FILE

    override fun guestLevelUpload(access: GuestAccess, session: GuestSession, parent: OpenNode, source: UploadSource): UploadQueue.Job =
        DepotGuest.LevelUpload(access, session, parent, source)
}
