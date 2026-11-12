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
     * Whether a node of [kind] downloads as its own content rather than the web's ZIP (a Depot file: the file itself, under
     * its own name, D3 of the Depot CR); every other kind downloads the ZIP of it with everything under it.
     */
    fun downloadsItself(kind: String): Boolean = kind == FILE

    /**
     * Node [ref]'s own download into [sink] where its kind has one ([downloadsItself]): true once whole, false where it has
     * nothing to download or did not finish; null for a kind that downloads as the ZIP.
     */
    @Throws(Exception::class)
    suspend fun ownDownload(tenant: me.raddatz.veyq.mobile.session.TenantSession, ref: me.raddatz.veyq.mobile.navigation.NodeAt, sink: (ByteArray) -> Unit): Boolean? =
        when (ref.kind) {
            FILE -> me.raddatz.veyq.mobile.depot.DepotFiles.download(tenant, ref.scopeId, ref.nodeId, sink)
            else -> null
        }

    /** The name node [ref]'s download is saved as: a file's own (`DepotFiles.downloadName`), else the ZIP's. */
    @Throws(Exception::class)
    suspend fun downloadName(tenant: me.raddatz.veyq.mobile.session.TenantSession, ref: me.raddatz.veyq.mobile.navigation.NodeAt): String =
        when (ref.kind) {
            FILE -> me.raddatz.veyq.mobile.depot.DepotFiles.downloadName(
                runCatching { me.raddatz.veyq.mobile.depot.DepotFiles.one(tenant, ref.scopeId, ref.nodeId) }.getOrNull(), ref.name,
            )
            else -> me.raddatz.veyq.mobile.export.NodeExport.fileName(ref.name)
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

    /** An application's name. */
    fun applicationWord(app: me.raddatz.veyq.mobile.navigation.ApplicationId): me.raddatz.veyq.mobile.words.Msg = when (app) {
        me.raddatz.veyq.mobile.navigation.ApplicationId.MEMORIES -> me.raddatz.veyq.mobile.words.Msg.APP_MEMORIES
        me.raddatz.veyq.mobile.navigation.ApplicationId.DEPOT -> me.raddatz.veyq.mobile.words.Msg.APP_DEPOT
        me.raddatz.veyq.mobile.navigation.ApplicationId.EXPENSES -> me.raddatz.veyq.mobile.words.Msg.APP_EXPENSES
    }

    /** A bar tab's name; Depot's one tab names the object open ([objectKind]): a file or a drop box. */
    fun tabWord(tab: me.raddatz.veyq.mobile.navigation.BarTab, objectKind: String?): me.raddatz.veyq.mobile.words.Msg = when (tab.key) {
        "memories.all" -> me.raddatz.veyq.mobile.words.Msg.TAB_MEMORIES_ALL
        "memories.map" -> me.raddatz.veyq.mobile.words.Msg.TAB_MEMORIES_MAP
        "depot.file" -> if (objectKind == DROPBOX) me.raddatz.veyq.mobile.words.Msg.TAB_DEPOT_DROPBOX else me.raddatz.veyq.mobile.words.Msg.TAB_DEPOT_FILE
        "expenses.entries" -> me.raddatz.veyq.mobile.words.Msg.TAB_EXPENSES_ENTRIES
        "expenses.overview" -> me.raddatz.veyq.mobile.words.Msg.TAB_EXPENSES_OVERVIEW
        "expenses.settings" -> me.raddatz.veyq.mobile.words.Msg.TAB_EXPENSES_SETTINGS
        else -> me.raddatz.veyq.mobile.words.Msg.NOT_BUILT
    }

    /** What "New" creates at a level (the web's menu); each app wires each to its own dialog or picker. */
    @kotlinx.serialization.Serializable
    enum class Create(val word: me.raddatz.veyq.mobile.words.Msg) {
        FOLDER(me.raddatz.veyq.mobile.words.Msg.FOLDER_NEW),
        POT(me.raddatz.veyq.mobile.words.Msg.CREATE_POT),
        UPLOAD_FILES(me.raddatz.veyq.mobile.words.Msg.DEPOT_UPLOAD),
        UPLOAD_FOLDER(me.raddatz.veyq.mobile.words.Msg.DEPOT_UPLOAD_FOLDER),
    }

    /** Home's "New": a folder, a Pot, files or a folder uploaded here. */
    val homeCreates: List<Create> = listOf(Create.FOLDER, Create.POT, Create.UPLOAD_FILES, Create.UPLOAD_FOLDER)

    /** The drawer's "New", opened on Home: a folder or a Pot. */
    val drawerCreates: List<Create> = listOf(Create.FOLDER, Create.POT)

    /**
     * What core's header reads of a kind (the web's `KindRegistration`): whether it brings a share sheet, takes a cover and
     * is renamed, and its delete's words — its title, and its question with the node's name.
     */
    class Header(
        val shareable: Boolean, val takesCover: Boolean, val renames: Boolean,
        val deleteTitle: me.raddatz.veyq.mobile.words.Msg, private val deleteQuestionMsg: me.raddatz.veyq.mobile.words.Msg,
    ) {
        fun deleteQuestion(name: String): me.raddatz.veyq.mobile.words.Notice = me.raddatz.veyq.mobile.words.Notice(deleteQuestionMsg, name)
    }

    /** The header of a node of [kind]; null for a kind this build registers no page for. */
    fun header(kind: String): Header? = when (kind) {
        FOLDER -> Header(true, true, true, me.raddatz.veyq.mobile.words.Msg.FOLDER_DELETE, me.raddatz.veyq.mobile.words.Msg.FOLDER_DELETE_CONFIRM)
        PotSession.KIND -> Header(true, true, true, me.raddatz.veyq.mobile.words.Msg.POT_DELETE, me.raddatz.veyq.mobile.words.Msg.POT_DELETE_CONFIRM)
        FILE -> Header(true, false, true, me.raddatz.veyq.mobile.words.Msg.FILE_DELETE, me.raddatz.veyq.mobile.words.Msg.FILE_DELETE_CONFIRM)
        // a drop box is never renamed: its name is its guests' address
        DROPBOX -> Header(true, true, false, me.raddatz.veyq.mobile.words.Msg.DROPBOX_DELETE, me.raddatz.veyq.mobile.words.Msg.DROPBOX_DELETE_CONFIRM)
        MEMORY -> Header(false, true, true, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_MEMORY, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_CONFIRM)
        CHAPTER -> Header(false, true, true, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_CHAPTER, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_CONFIRM)
        else -> null
    }

    /**
     * What a kind's page knows of its open object that its header's doors wait on (the web's page doors and its kind's
     * rights function): whether the object takes writes (a Pot of a switched-off Expenses reads only, `expensesRights`) and
     * whether a file has content to download and copy.
     */
    @kotlinx.serialization.Serializable
    data class Facts(val canWrite: Boolean = true, val hasContent: Boolean = true)

    /** A kind's doors on its header: its cover offered here, its copy and its download (absent: not offered). */
    data class Doors(val coverOffered: Boolean, val copies: Boolean, val downloads: Boolean)

    /**
     * The doors a node of [kind] opens, given what its page knows ([facts]): a folder and a drop box copy and download
     * whole; a Pot downloads, and takes a cover and a copy only while it takes writes (the web's `canCopy`); a file copies and
     * downloads only with content; a Memory and a chapter neither (yet). A kind this build does not know opens none.
     */
    fun doors(kind: String, facts: Facts): Doors = when (kind) {
        FOLDER, DROPBOX -> Doors(coverOffered = true, copies = true, downloads = true)
        PotSession.KIND -> Doors(coverOffered = facts.canWrite, copies = facts.canWrite, downloads = true)
        FILE -> Doors(coverOffered = true, copies = facts.hasContent, downloads = facts.hasContent)
        MEMORY, CHAPTER -> Doors(coverOffered = true, copies = false, downloads = false)
        else -> Doors(coverOffered = false, copies = false, downloads = false)
    }

    /**
     * Whether a node's page lets its cover change from its head (a tap on the cover): the kind takes one and its doors offer
     * it ([facts] null while the page cannot tell: as a writable one).
     */
    fun coverSettable(kind: String, facts: Facts?): Boolean = header(kind)?.takesCover == true && doors(kind, facts ?: Facts()).coverOffered

    /** What a copy of nodes of [kinds] leaves behind (the web descriptor's `copyLosses`), each said once, by kind in order. */
    fun lossesOfCopy(kinds: List<String>): List<me.raddatz.veyq.mobile.words.Msg> = kinds.sorted().flatMap { kind ->
        when (kind) {
            FOLDER -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_FOLDER_COVER)
            PotSession.KIND -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_POT, me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_COVER)
            DROPBOX -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_DROPBOX)
            else -> emptyList()
        }
    }.distinct()

    /**
     * Whether a node of [kind] copies by its open model rather than with everything under it (`SubtreeCopy`): a Pot by the
     * Pot open on its page (`PotModel.duplicate`: its session read, its write right known — the web's `canCopy`); every
     * other kind (a folder, a drop box, a file) through `SubtreeCopy`.
     */
    fun copiesByOpenModel(kind: String): Boolean = kind == PotSession.KIND

    /** Whether an object of [kind] can be chosen as the place for files shared to veyq (ADR 0131 decision 1): a Pot. */
    fun takesShared(kind: String): Boolean = kind == PotSession.KIND

    private const val FOLDER = "FOLDER"
    private const val FILE = "FILE"
    private const val DROPBOX = "DEPOT_DROPBOX"
    private const val SHARE_BOOKMARK = "SHARE_BOOKMARK"
    private const val MEMORY = "MEMORY"
    private const val CHAPTER = "CHAPTER"
}
