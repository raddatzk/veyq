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
     * key (their blobs are core's, the re-key moves them), a Pot its revisions' keys (`PotRekey`), a Memory and a chapter
     * the banner's wraps in their meta, their media's sealed tiers and their bucket names (`MemoryRekey`, ADR 0149 decision 2).
     */
    val rekeys = NodeRekey.Kinds { kind ->
        when (kind) {
            FOLDER, FILE, DROPBOX, SHARE_BOOKMARK -> NodeRekey.ContentRekey.NONE
            PotSession.KIND -> PotRekey.content
            MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoryRekey.content
            else -> null
        }
    }

    /** What a tenant session re-keys and folds with (`TenantSession.rekeyRules`). */
    val rules = NodeRekey.Rules(rekeys, ::tokenInput)

    /**
     * Each application's part of a scope rotation's scope-level move (the web's `registry.scopeRekeyParticipants()`, ADR
     * 0115 decision 2): Memories' item marks' value-key wraps (`ItemMarksRotation.participant`). Core's `ScopeRotation`
     * stages them after its own, under the same re-key id.
     */
    val scopeRekeyParticipants: List<me.raddatz.veyq.mobile.session.rotation.ScopeRekeyParticipant> =
        listOf(me.raddatz.veyq.mobile.memories.ItemMarksRotation.participant)

    /**
     * The account's Master Key replaced (an account rotation's `afterCommit`, the web's lifecycle `onMasterKeyRotated`):
     * each application's material derived from the Master Key carried from [oldMk] to [newMk] — Memories re-tags every one
     * of the account's own item marks the server lists, whatever scope (`ItemMarksRotation.retag`, as the web's
     * `retagItemMarksUnderNewMasterKey`). Best effort: nothing here fails the rotation, and neither key is kept or zeroed
     * (the caller owns both).
     */
    suspend fun masterKeyRotated(account: me.raddatz.veyq.mobile.session.VeyqAccount, oldMk: ByteArray, newMk: ByteArray) {
        try {
            me.raddatz.veyq.mobile.memories.ItemMarksRotation.retag(account, oldMk, newMk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // a mark whose tag the new key does not verify reads as someone else's set (ADR 0068 §9) until its next toggle
        }
    }

    /**
     * How the build's kinds export into a download (the web's `exportContent` hooks, `NodeExport.walk`): a Pot its ledger and
     * receipts (`PotExport`, its erased people named [deletedName]), a Depot file its content streamed and a drop box a
     * directory of its guests' folders (`DepotExport`), a Memory and a chapter their media, facts, cover and banner
     * (`MemoryExport`, ADR 0149 decision 3); every other kind but a folder is left out and said. One per run:
     * Depot's files are listed once.
     */
    fun exports(deletedName: String): me.raddatz.veyq.mobile.export.NodeExport.Kinds {
        val depot = me.raddatz.veyq.mobile.depot.DepotExport()
        return me.raddatz.veyq.mobile.export.NodeExport.Kinds(
            exports = { it == PotSession.KIND || it == FILE || it == DROPBOX || it == MEMORY || it == CHAPTER },
            content = { tenant, node ->
                when (node.kind) {
                    PotSession.KIND -> PotSession.open(tenant, node.scopeId, node.id)?.let { PotExport.content(it, deletedName) }
                    FILE -> depot.file(tenant, node)
                    DROPBOX -> me.raddatz.veyq.mobile.depot.DepotExport.dropBox()
                    MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoryExport.content(tenant, node)
                    else -> null
                }
            },
        )
    }

    /**
     * Whether a node of [kind] downloads as its own content rather than the web's ZIP (a Depot file: the file itself, under
     * its own name, ADR 0136's Consequences); every other kind downloads the ZIP of it with everything under it.
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
     * (`DepotExport.duplicate`), a drop box as a plain folder (the web's `importsAsFolder`), a Memory and a chapter by
     * Memories' copy (`MemoryCopy`: its media and their tiers read by frame, never decoded); every other kind but a folder is
     * left out and said. One per run: Depot's files are listed once.
     */
    fun copies(deletedName: String, uuid: () -> String): me.raddatz.veyq.mobile.export.SubtreeCopy.Kinds {
        val depot = me.raddatz.veyq.mobile.depot.DepotExport()
        return me.raddatz.veyq.mobile.export.SubtreeCopy.Kinds(
            copies = { it == PotSession.KIND || it == FILE || it == DROPBOX || it == MEMORY || it == CHAPTER },
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
                    MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoryCopy.duplicate(tenant, node, scopeId, parentId, name)
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
        MEMORY, CHAPTER -> listOf("favorite", "upload", "share", "music", "download", "banner", "move", "copy")
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
        /** A Memory made where the level is (`MemoryCreate`; a space's top or a folder, its placement's). */
        MEMORY(me.raddatz.veyq.mobile.words.Msg.MEMORY_NEW),
        /** A chapter made in a Memory: the Memory's page's own New (`GalleryModel.creates`), never a level's. */
        CHAPTER(me.raddatz.veyq.mobile.words.Msg.MEMORY_ADD_CHAPTER),
        /**
         * A kept link (ADR 0120, ADR 0150 decision 6) at a space's top or in a folder: the web's dialog
         * (`sharing.BookmarkCreate`). Offered by [createsWhere] where the platform says it draws the dialog (cr-app-received-links
         * phases 7–10).
         */
        BOOKMARK(me.raddatz.veyq.mobile.words.Msg.BOOKMARK_NEW),
    }

    /** Home's "New": a folder, a Pot, files or a folder uploaded here. */
    val homeCreates: List<Create> = listOf(Create.FOLDER, Create.POT, Create.UPLOAD_FILES, Create.UPLOAD_FOLDER)

    /** The drawer's "New", opened on Home: a folder or a Pot. */
    val drawerCreates: List<Create> = listOf(Create.FOLDER, Create.POT)

    /**
     * What a desktop's one "New" offers where the shell is ([isHome]: a level of Home, a space's top or a folder): Home's
     * — files and a folder uploaded there too, as on the phones —, else the drawer's; and a Memory, whose placement every
     * level New makes into allows (a space's top, a folder: `MemoryCreate.offered`). A chapter is a Memory's page's own.
     * [bookmarks]: the platform draws the bookmark's dialog (`sharing.BookmarkCreate`), so a kept link is offered last —
     * each platform switches it on with its dialog (cr-app-received-links phases 7–10; inbox #1020).
     */
    fun createsWhere(isHome: Boolean, bookmarks: Boolean = false): List<Create> =
        (if (isHome) homeCreates else drawerCreates) + Create.MEMORY + listOfNotNull(Create.BOOKMARK.takeIf { bookmarks })

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
        MEMORY -> Header(true, true, true, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_MEMORY, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_CONFIRM)
        CHAPTER -> Header(true, true, true, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_CHAPTER, me.raddatz.veyq.mobile.words.Msg.MEMORY_DELETE_CONFIRM)
        // a kept link is never shared on (ADR 0120 decision 7) and takes no cover; its delete says what it is
        SHARE_BOOKMARK -> Header(false, false, true, me.raddatz.veyq.mobile.words.Msg.BOOKMARK_DELETE, me.raddatz.veyq.mobile.words.Msg.BOOKMARK_DELETE_CONFIRM)
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
     * downloads only with content; a Memory and a chapter both (ADR 0149 decision 3). A kind this build does not know opens none.
     */
    fun doors(kind: String, facts: Facts): Doors = when (kind) {
        FOLDER, DROPBOX -> Doors(coverOffered = true, copies = true, downloads = true)
        PotSession.KIND -> Doors(coverOffered = facts.canWrite, copies = facts.canWrite, downloads = true)
        FILE -> Doors(coverOffered = true, copies = facts.hasContent, downloads = facts.hasContent)
        MEMORY, CHAPTER -> Doors(coverOffered = true, copies = true, downloads = true)
        else -> Doors(coverOffered = false, copies = false, downloads = false)
    }

    /**
     * Whether a node's page lets its cover change from its head (a tap on the cover): the kind takes one and its doors offer
     * it ([facts] null while the page cannot tell: as a writable one).
     */
    fun coverSettable(kind: String, facts: Facts?): Boolean = header(kind)?.takesCover == true && doors(kind, facts ?: Facts()).coverOffered

    /**
     * Whether a node of [kind] is reported by its name only (ADR 0150 decision 6): a kept link, whose key would hand this
     * instance's moderators the key of another operator's share (the backend refuses a `NODE` report of one).
     */
    fun reportsNameOnly(kind: String): Boolean = kind == SHARE_BOOKMARK

    /** The kind of a kept link (`SHARE_BOOKMARK`). */
    val BOOKMARK_KIND: String get() = SHARE_BOOKMARK

    /** What a copy of nodes of [kinds] leaves behind (the web descriptor's `copyLosses`), each said once, by kind in order. */
    fun lossesOfCopy(kinds: List<String>): List<me.raddatz.veyq.mobile.words.Msg> = kinds.sorted().flatMap { kind ->
        when (kind) {
            FOLDER -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_FOLDER_COVER)
            PotSession.KIND -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_POT, me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_COVER)
            DROPBOX -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_DROPBOX)
            MEMORY, CHAPTER -> listOf(me.raddatz.veyq.mobile.words.Msg.COPY_LOSS_MEMORY)
            else -> emptyList()
        }
    }.distinct()

    /**
     * Whether a node of [kind] copies by its open model rather than with everything under it (`SubtreeCopy`): a Pot by the
     * Pot open on its page (`PotModel.duplicate`: its session read, its write right known — the web's `canCopy`); every
     * other kind (a folder, a drop box, a file) through `SubtreeCopy`.
     */
    fun copiesByOpenModel(kind: String): Boolean = kind == PotSession.KIND

    /**
     * What a pin keeps of each kind (ADR 0140 decision 9, `OfflinePins`): a Memory and a chapter their media's tiles and
     * previews and a video's original (`MemoryMedia.pinned`, never a photo's original), a Depot file its content
     * (`DepotFiles.pinned`), a folder nothing of its own — the walk below it reaches what it holds; a Memory's and a
     * chapter's banner by the ids their meta names (`Banner.pinned`, ADR 0144 decision 7). A Pot is not kept
     * offline (Expenses offline is #898), nor is a kind this build does not know.
     */
    val pins: me.raddatz.veyq.mobile.offline.PinKinds = object : me.raddatz.veyq.mobile.offline.PinKinds {
        override fun pinnable(kind: String): Boolean = kind == MEMORY || kind == CHAPTER || kind == FOLDER || kind == FILE

        override suspend fun blobs(tenant: me.raddatz.veyq.mobile.session.TenantSession, node: me.raddatz.veyq.mobile.session.OpenNode) =
            when (node.kind) {
                MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoryMedia.pinned(tenant, node)
                FILE -> me.raddatz.veyq.mobile.depot.DepotFiles.pinned(tenant, node)
                else -> emptyList()
            }

        // a Memory's and a chapter's banner: only their sealed meta names it (ADR 0144 decision 7)
        override fun named(tenant: me.raddatz.veyq.mobile.session.TenantSession, node: me.raddatz.veyq.mobile.session.OpenNode) =
            if (node.kind == MEMORY || node.kind == CHAPTER) me.raddatz.veyq.mobile.memories.banner.Banner.pinned(tenant, node) else emptyList()
    }

    /**
     * What each kind and application adds to the search (ADR 0146 decision 4; the web's `KindDescriptor.search` and
     * `ApplicationRegistrar.search`): every kind its word and its icon — a folder and a kept link core's, a file and a drop
     * box Depot's, a Pot Expenses', a Memory and a chapter Memories' with their facets (a date range, a place, the
     * participants, from the meta core opens) and the photo and video documents of Memories' search over a whole space.
     * The one place an application's part reaches core's `search/`.
     */
    val search: me.raddatz.veyq.mobile.search.SearchKinds = object : me.raddatz.veyq.mobile.search.SearchKinds {
        private val memoryDocuments = listOf(
            me.raddatz.veyq.mobile.search.DocumentKind(
                me.raddatz.veyq.mobile.memories.MemoriesSearch.PHOTO, me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_PHOTO,
                me.raddatz.veyq.mobile.memories.MemoriesSearch.PHOTO_ICON,
            ),
            me.raddatz.veyq.mobile.search.DocumentKind(
                me.raddatz.veyq.mobile.memories.MemoriesSearch.VIDEO, me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_VIDEO,
                me.raddatz.veyq.mobile.memories.MemoriesSearch.VIDEO_ICON,
            ),
        )
        private val byKind = linkedMapOf(
            FOLDER to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_FOLDER, "folder"),
            SHARE_BOOKMARK to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_BOOKMARK, "bookmark"),
            FILE to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_FILE, "file"),
            DROPBOX to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_DROPBOX, "inbox"),
            PotSession.KIND to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_POT, "wallet"),
            MEMORY to me.raddatz.veyq.mobile.search.KindSearch(
                me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_MEMORY, "images", me.raddatz.veyq.mobile.memories.MemoriesSearch::facets, memoryDocuments,
            ),
            CHAPTER to me.raddatz.veyq.mobile.search.KindSearch(
                me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_CHAPTER, "images", me.raddatz.veyq.mobile.memories.MemoriesSearch::facets, memoryDocuments,
            ),
        )

        override fun kind(kind: String) = byKind[kind]
        override val kinds: List<String> = byKind.keys.toList()
        override val applications = listOf(
            me.raddatz.veyq.mobile.navigation.ApplicationId.MEMORIES.id to me.raddatz.veyq.mobile.memories.MemoriesSearch.search,
        )
    }

    /**
     * The answer of [kind] to files shared to veyq (ADR 0138 decision 2): each application's own — a Pot's new expense
     * (`PotShare`), a Memory's and a chapter's upload queue (`MemoryShare`), a folder's — a space's top asks as one — Depot's
     * upload queue (`FolderShare`); null for a kind that takes nothing.
     */
    fun share(kind: String): me.raddatz.veyq.mobile.navigation.ShareAnswer? = when (kind) {
        PotSession.KIND -> me.raddatz.veyq.mobile.expenses.PotShare.answer
        MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoryShare.answer
        FOLDER -> me.raddatz.veyq.mobile.depot.FolderShare.answer
        else -> null
    }

    /**
     * The answer of [kind] to a link's guest of [role] (ADR 0150 decision 5): each application's own door in its package —
     * a folder core's level, a Depot file its page with a guest's viewer and a drop box's WRITE_ONLY link its drop page, a
     * Memory and a chapter their gallery with a guest's access and a WRITE_ONLY link their uploader, a Pot its page over the
     * link's reads; null for a kind or role without one (the guest view says "not shown here").
     */
    fun guestDoor(kind: String, role: String): me.raddatz.veyq.mobile.sharing.GuestDoor? = when (role) {
        me.raddatz.veyq.mobile.sharing.GuestAccess.WRITE_ONLY -> when (kind) {
            DROPBOX -> me.raddatz.veyq.mobile.depot.DepotGuest.dropBox
            MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoriesGuest.uploader
            else -> null
        }
        me.raddatz.veyq.mobile.sharing.GuestAccess.READ, me.raddatz.veyq.mobile.sharing.GuestAccess.READ_WRITE -> when (kind) {
            FOLDER -> me.raddatz.veyq.mobile.sharing.GuestLevel.door
            FILE -> me.raddatz.veyq.mobile.depot.DepotGuest.file
            MEMORY, CHAPTER -> me.raddatz.veyq.mobile.memories.MemoriesGuest.gallery
            PotSession.KIND -> me.raddatz.veyq.mobile.expenses.PotGuest.pot
            else -> null
        }
        else -> null
    }

    /**
     * What a READ_WRITE guest's upload at a folder of the link creates (the web's level upload: an application's own level
     * action, Depot's `guestFileUploadJob`): one job per source, each its own node created as the guest under [parent].
     */
    fun guestLevelUploads(
        access: me.raddatz.veyq.mobile.sharing.GuestAccess, session: me.raddatz.veyq.mobile.sharing.GuestSession,
        parent: me.raddatz.veyq.mobile.session.OpenNode, sources: List<me.raddatz.veyq.mobile.blob.UploadSource>,
    ): List<me.raddatz.veyq.mobile.blob.UploadQueue.Job> = sources.map { me.raddatz.veyq.mobile.depot.DepotGuest.LevelUpload(access, session, parent, it) }

    private const val FOLDER = "FOLDER"
    private const val FILE = "FILE"
    private const val DROPBOX = "DEPOT_DROPBOX"
    private const val SHARE_BOOKMARK = "SHARE_BOOKMARK"
    private const val MEMORY = "MEMORY"
    private const val CHAPTER = "CHAPTER"
}
