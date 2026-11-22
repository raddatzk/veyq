package me.raddatz.veyq.mobile

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.tree.KindFacts
import me.raddatz.veyq.mobile.navigation.HeaderAction as H
import me.raddatz.veyq.mobile.session.NodeRekey

/**
 * Every node kind this build knows, as core's writes need it: the platform's one registry of kinds (ADR 0154 decision 3;
 * the web's registry, filled by each application's `register.ts`), the one place core's session meets an application.
 * Core's own kinds (a folder, a kept link) are answered here; every other answer is an application's part
 * ([ApplicationKinds]), which the composition installs once ([install]) before any session opens. Core's session never
 * names a kind; the apps hand it these.
 */
object AppKinds {
    @kotlin.concurrent.Volatile
    private var installed: List<ApplicationKinds>? = null

    /**
     * The applications' parts, in the order installed. A read before [install] fails: never a silent default, since an
     * unregistered kind would write a node the app cannot name.
     */
    internal val applications: List<ApplicationKinds>
        get() = installed ?: error("AppKinds read before the applications were installed (composition's AppComposition.install)")

    /**
     * Installs the applications' parts (the composition's `AppComposition.install`, from each client's start path): one per
     * [me.raddatz.veyq.mobile.navigation.ApplicationId], no kind owned twice. The same parts again are no change (an
     * Android activity recreated, a second bridge instance, a test class after another); different ones fail.
     */
        @HiddenFromObjC
    fun install(parts: List<ApplicationKinds>) {
        installed?.let { held ->
            check(held == parts) { "AppKinds installed twice with different applications: ${held.map { it.application }} then ${parts.map { it.application }}" }
            return
        }
        val ids = parts.map { it.application }
        check(ids.toSet() == me.raddatz.veyq.mobile.navigation.ApplicationId.entries.toSet() && ids.size == ids.toSet().size) {
            "AppKinds needs exactly one part per ApplicationId, got $ids"
        }
        val kinds = parts.flatMap { it.kinds }
        check(kinds.size == kinds.toSet().size && kinds.none { it in CORE }) { "a node kind owned twice: $kinds" }
        check(parts.all { p -> p.kindFacts.map { it.kind }.sorted() == p.kinds.sorted() }) { "an application's kind facts are not its kinds" }
        held = KindFacts((CORE_FACTS + parts.flatMap { it.kindFacts }).sortedBy { it.kind })
        installed = parts
    }

    /** Each application's part of the offline store, in the order installed. */
    internal val kept: List<me.raddatz.veyq.mobile.offline.ApplicationKept> get() = applications.mapNotNull { it.kept }

    private val CORE = setOf("FOLDER", "SHARE_BOOKMARK")

    /** Core's own kinds' facts: a folder and a kept link fold as containers, at the root or in a folder, under no route. */
    private val CORE_FACTS = CORE.map { KindFacts.Kind(it, null, KindFacts.NAME, root = true, parents = listOf(FOLDER)) }

    @kotlin.concurrent.Volatile
    private var held: KindFacts? = null

    /**
     * Every kind's facts as the platform's tree rules read them (`tree/KindFacts`: route slug, folding, placement), core's
     * and every application's, by kind — the table the web builds from its own registry, both held to
     * `contracts/platform/tree/v1-vectors.json`'s `kinds`.
     */
    val facts: KindFacts get() = held ?: error("AppKinds read before the applications were installed (composition's AppComposition.install)")
    /**
     * Each write that may be queued offline and the sender that takes it through its online path (ADR 0153 decision 3,
     * [me.raddatz.veyq.mobile.offline.Queued]): core's node writes here; an application's rows are its own senders.
     */
    @PlatformApi
    @HiddenFromObjC
    fun queuedSender(q: me.raddatz.veyq.mobile.offline.Queued): me.raddatz.veyq.mobile.offline.QueuedSender = when (q) {
        me.raddatz.veyq.mobile.offline.Queued.FOLDER_CREATE,
        me.raddatz.veyq.mobile.offline.Queued.NODE_RENAME,
        me.raddatz.veyq.mobile.offline.Queued.NODE_TRASH,
        -> me.raddatz.veyq.mobile.offline.QueuedNodes.sender(q)
        else -> applications.firstNotNullOfOrNull { it.queuedSender(q) } ?: error("no application sends a queued $q")
    }

    /**
     * The input a kind folds a node's name to for its slug token (the web's `registry.tokenInputFor`: the kind's own slug
     * rule — a file's stricter `fileNameSlug`, else `slugify` — behind its application's route slug where it claimed one,
     * pinned by `v1.json#name_folding`); null for a kind this build does not know, whose names it then does not write.
     */
    fun tokenInput(kind: String, name: String): String? = facts.takeIf { it.knows(kind) }?.tokenInput(kind, name)

    /**
     * How the app re-keys a node of a kind (ADR 0114): core's kinds and Depot's with nothing of their own under the node's
     * key (their blobs are core's, the re-key moves them), a Pot its revisions' keys (`PotRekey`), a Memory and a chapter
     * the banner's wraps in their meta, their media's sealed tiers and their bucket names (`MemoryRekey`, ADR 0149 decision 2).
     */
    val rekeys = NodeRekey.Kinds { kind ->
        when (kind) {
            FOLDER, SHARE_BOOKMARK -> NodeRekey.ContentRekey.NONE
            else -> applications.firstNotNullOfOrNull { it.rekey(kind) }
        }
    }

    /** What a tenant session re-keys and folds with (`TenantSession.rekeyRules`). */
    val rules = NodeRekey.Rules(rekeys, ::tokenInput)

    /**
     * Each application's part of a scope rotation's scope-level move (the web's `registry.scopeRekeyParticipants()`, ADR
     * 0115 decision 2): Memories' item marks' value-key wraps (`ItemMarksRotation.participant`). Core's `ScopeRotation`
     * stages them after its own, under the same re-key id.
     */
    val scopeRekeyParticipants: List<me.raddatz.veyq.mobile.session.rotation.ScopeRekeyParticipant>
        get() = applications.flatMap { it.scopeRekeyParticipants }

    /**
     * The account's Master Key replaced (an account rotation's `afterCommit`, the web's lifecycle `onMasterKeyRotated`):
     * each application's material derived from the Master Key carried from [oldMk] to [newMk] — Memories re-tags every one
     * of the account's own item marks the server lists, whatever scope (`ItemMarksRotation.retag`, as the web's
     * `retagItemMarksUnderNewMasterKey`). Best effort: nothing here fails the rotation, and neither key is kept or zeroed
     * (the caller owns both).
     */
    suspend fun masterKeyRotated(account: me.raddatz.veyq.mobile.session.VeyqAccount, oldMk: ByteArray, newMk: ByteArray) {
        for (part in applications) {
            try {
                part.masterKeyRotated(account, oldMk, newMk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // a mark whose tag the new key does not verify reads as someone else's set (ADR 0068 §9) until its next toggle
            }
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
        val parts = applications.mapNotNull { it.exports(deletedName) }
        return me.raddatz.veyq.mobile.export.NodeExport.Kinds(
            exports = { kind -> parts.any { it.exports(kind) } },
            content = { tenant, node -> parts.firstOrNull { it.exports(node.kind) }?.content?.invoke(tenant, node) },
        )
    }

    /**
     * Whether a node of [kind] downloads as its own content rather than the web's ZIP (a Depot file: the file itself, under
     * its own name, ADR 0136's Consequences); every other kind downloads the ZIP of it with everything under it.
     */
    fun downloadsItself(kind: String): Boolean = applications.any { it.downloadsItself(kind) }

    /**
     * Node [ref]'s own download into [sink] where its kind has one ([downloadsItself]): true once whole, false where it has
     * nothing to download or did not finish; null for a kind that downloads as the ZIP.
     */
    @Throws(Exception::class)
    suspend fun ownDownload(tenant: me.raddatz.veyq.mobile.session.TenantSession, ref: me.raddatz.veyq.mobile.navigation.NodeAt, sink: (ByteArray) -> Unit): Boolean? =
        applications.firstOrNull { it.downloadsItself(ref.kind) }?.ownDownload(tenant, ref, sink)

    /** The name node [ref]'s download is saved as: a file's own (`DepotFiles.downloadName`), else the ZIP's. */
    @Throws(Exception::class)
    suspend fun downloadName(tenant: me.raddatz.veyq.mobile.session.TenantSession, ref: me.raddatz.veyq.mobile.navigation.NodeAt): String =
        applications.firstOrNull { it.downloadsItself(ref.kind) }?.downloadName(tenant, ref)
            ?: me.raddatz.veyq.mobile.export.NodeExport.fileName(ref.name)

    /**
     * How the build's kinds copy (the web's `importContent` for a copy, `SubtreeCopy`): a Pot by Expenses' copy (`PotCopy`,
     * its erased people named [deletedName]; it names itself), a Depot file read here and uploaded again under a fresh key
     * (`DepotExport.duplicate`), a drop box as a plain folder (the web's `importsAsFolder`), a Memory and a chapter by
     * Memories' copy (`MemoryCopy`: its media and their tiers read by frame, never decoded); every other kind but a folder is
     * left out and said. One per run: Depot's files are listed once.
     */
    fun copies(deletedName: String, uuid: () -> String): me.raddatz.veyq.mobile.export.SubtreeCopy.Kinds {
        val parts = applications.mapNotNull { it.copies(deletedName, uuid) }
        return me.raddatz.veyq.mobile.export.SubtreeCopy.Kinds(
            copies = { kind -> parts.any { it.copies(kind) } },
            asFolder = { kind -> parts.any { it.asFolder(kind) } },
            duplicate = { tenant, node, scopeId, parentId, name ->
                parts.firstOrNull { it.copies(node.kind) }?.duplicate?.invoke(tenant, node, scopeId, parentId, name)
                    ?: me.raddatz.veyq.mobile.export.SubtreeCopy.Made(null)
            },
        )
    }

    /**
     * How the build's kinds import a download (ADR 0152 decision 3; the web's `importContent`, `importsAsFolder`,
     * `validateContent`, `contentSchema`, `SubtreeImport`): a Depot file uploaded from its checked payload and a drop box as a
     * plain folder (`DepotImport`), a Pot by Expenses' import (`PotImport`, an erased person named [deletedName], its ids
     * minted by [uuid]), a Memory and a chapter by Memories' (`MemoryImport`: each medium's tiers derived by the platform's
     * [deriver], as an upload's; [now] bounds a video's capture time); a kept link and every kind this build does not know
     * have none, and are left out with what is under them (`unknown_application`), as on the web.
     */
    fun imports(
        deletedName: String, uuid: () -> String, deriver: me.raddatz.veyq.mobile.media.Derivation.Deriver?, now: () -> Long,
    ): me.raddatz.veyq.mobile.export.SubtreeImport.Kinds {
        val parts = applications.mapNotNull { it.imports(deletedName, uuid, deriver, now) }
        return me.raddatz.veyq.mobile.export.SubtreeImport.Kinds { kind -> parts.firstNotNullOfOrNull { it.hook(kind) } }
    }

    /**
     * Where a node of a kind may be placed (each kind's `placement`, the web's `registry.placementAllows` mirroring the
     * backend's `NodeKindRules`): a folder, a Pot, a Memory, a Depot file and drop box and a kept link at the space's top or
     * in a folder, a chapter in a Memory; a kind this build does not know nowhere. A hint: the server decides.
     */
    val placement = me.raddatz.veyq.mobile.session.NodeMove.PlacementAllows { kind, parent -> facts.placementAllows(kind, parent) }

    /**
     * A kind's header priority (the web's `header.order` of its descriptor, `NodeHeader.plan`): the actions listed first, in
     * this order. A kind not listed has none: core's merge order alone. `HeaderOrderIsTheWebsTest` reads each kind's
     * order from the web's own registration (`registry.ts`, each application's `register.ts`, `core/bookmark/kind.ts`).
     */
    fun headerOrder(kind: String): List<H> = when (kind) {
        FOLDER -> listOf(H.FAVORITE, H.SHARE, H.DOWNLOAD, H.MOVE, H.COPY)
        SHARE_BOOKMARK -> listOf(H.OPEN_OWN_PAGE, H.FAVORITE, H.MOVE)
        else -> applications.firstNotNullOfOrNull { it.headerOrder(kind) } ?: emptyList()
    }

    /** The application whose object a node of [kind] is; null for core's kinds and a chapter. */
    fun applicationOf(kind: String): me.raddatz.veyq.mobile.navigation.ApplicationId? =
        applications.firstOrNull { kind in it.objectKinds }?.application

    /** The noun a node of [kind] is named with in a refusal ("a Pot"), where its application has one. */
    fun noun(kind: String): me.raddatz.veyq.mobile.words.Msg? = applications.firstNotNullOfOrNull { it.noun(kind) }

    /** An application's name. */
    fun applicationWord(app: me.raddatz.veyq.mobile.navigation.ApplicationId): me.raddatz.veyq.mobile.words.Msg =
        applications.first { it.application == app }.word

    /** A bar tab's name; Depot's one tab names the object open ([objectKind]): a file or a drop box. */
    fun tabWord(tab: me.raddatz.veyq.mobile.navigation.BarTab, objectKind: String?): me.raddatz.veyq.mobile.words.Msg =
        applications.firstNotNullOfOrNull { it.tabWord(tab.key, objectKind) } ?: me.raddatz.veyq.mobile.words.Msg.NOT_BUILT

    /**
     * What "New" creates at a level (the web's menu); each app wires each to its own dialog or picker. An entry of a plan
     * (ADR 0154 decision 2: `LevelPlan.creates`, `ShellModel.drawerCreates`): every platform draws each one it is handed.
     */
    @me.raddatz.veyq.mobile.navigation.Plan
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
        // a kept link is never shared on (ADR 0120 decision 7) and takes no cover; its delete says what it is
        SHARE_BOOKMARK -> Header(false, false, true, me.raddatz.veyq.mobile.words.Msg.BOOKMARK_DELETE, me.raddatz.veyq.mobile.words.Msg.BOOKMARK_DELETE_CONFIRM)
        else -> applications.firstNotNullOfOrNull { it.header(kind) }
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
        FOLDER -> Doors(coverOffered = true, copies = true, downloads = true)
        else -> applications.firstNotNullOfOrNull { it.doors(kind, facts) } ?: Doors(coverOffered = false, copies = false, downloads = false)
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
            else -> applications.firstNotNullOfOrNull { it.lossesOfCopy(kind) } ?: emptyList()
        }
    }.distinct()

    /**
     * Whether a node of [kind] copies by its open model rather than with everything under it (`SubtreeCopy`): a Pot by the
     * Pot open on its page (`PotModel.duplicate`: its session read, its write right known — the web's `canCopy`); every
     * other kind (a folder, a drop box, a file) through `SubtreeCopy`.
     */
    fun copiesByOpenModel(kind: String): Boolean = applications.any { it.copiesByOpenModel(kind) }

    /**
     * What a pin keeps of each kind (ADR 0140 decision 9, `OfflinePins`): a Memory and a chapter their media's tiles and
     * previews and a video's original (`MemoryMedia.pinned`, never a photo's original), a Depot file its content
     * (`DepotFiles.pinned`), a folder nothing of its own — the walk below it reaches what it holds; a Memory's and a
     * chapter's banner by the ids their meta names (`Banner.pinned`, ADR 0144 decision 7), a Pot its whole ledger and
     * its receipts (`PotPins.pinned`, ADR 0153 decision 6) — every node's cover is core's. A kind this build does not know
     * is not kept.
     */
    val pins: me.raddatz.veyq.mobile.offline.PinKinds = object : me.raddatz.veyq.mobile.offline.PinKinds {
        private fun of(kind: String) = applications.firstNotNullOfOrNull { a -> a.pins?.takeIf { it.pinnable(kind) } }

        override fun pinnable(kind: String): Boolean = kind == FOLDER || of(kind) != null

        override suspend fun blobs(tenant: me.raddatz.veyq.mobile.session.TenantSession, node: me.raddatz.veyq.mobile.session.OpenNode) =
            of(node.kind)?.blobs(tenant, node) ?: emptyList()

        override fun named(tenant: me.raddatz.veyq.mobile.session.TenantSession, node: me.raddatz.veyq.mobile.session.OpenNode) =
            of(node.kind)?.named(tenant, node) ?: emptyList()
    }

    /**
     * What each kind and application adds to the search (ADR 0146 decision 4; the web's `KindDescriptor.search` and
     * `ApplicationRegistrar.search`): every kind its word and its icon — a folder and a kept link core's, a file and a drop
     * box Depot's, a Pot Expenses', a Memory and a chapter Memories' with their facets (a date range, a place, the
     * participants, from the meta core opens) and the photo and video documents of Memories' search over a whole space.
     * The one place an application's part reaches core's `search/`.
     */
    val search: me.raddatz.veyq.mobile.search.SearchKinds = object : me.raddatz.veyq.mobile.search.SearchKinds {
        private val byKind by lazy {
            linkedMapOf(
                FOLDER to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_FOLDER, "folder"),
                SHARE_BOOKMARK to me.raddatz.veyq.mobile.search.KindSearch(me.raddatz.veyq.mobile.words.Msg.SEARCH_KIND_BOOKMARK, "bookmark"),
            ).apply { for (a in this@AppKinds.applications) for (k in a.kinds) a.search(k)?.let { put(k, it) } }
        }

        override fun kind(kind: String) = byKind[kind]
        override val kinds: List<String> get() = byKind.keys.toList()
        override val applications: List<Pair<String, me.raddatz.veyq.mobile.search.ApplicationSearch>>
            get() = this@AppKinds.applications.mapNotNull { a -> a.spaceSearch?.let { a.application.id to it } }
    }

    /**
     * The answer of [kind] to files shared to veyq (ADR 0138 decision 2): each application's own — a Pot's new expense
     * (`PotShare`), a Memory's and a chapter's upload queue (`MemoryShare`), a folder's — a space's top asks as one — Depot's
     * upload queue (`FolderShare`); null for a kind that takes nothing.
     */
    fun share(kind: String): me.raddatz.veyq.mobile.navigation.ShareAnswer? = applications.firstNotNullOfOrNull { it.share(kind) }

    /** The words of a node of [kind]'s share sheet, where its application has its own; null: core's folder words. */
    fun shareWords(kind: String): me.raddatz.veyq.mobile.session.ShareWords? = applications.firstNotNullOfOrNull { it.shareWords(kind) }

    /**
     * The answer of [kind] to a link's guest of [role] (ADR 0150 decision 5): each application's own door in its package —
     * a folder core's level, a Depot file its page with a guest's viewer and a drop box's WRITE_ONLY link its drop page, a
     * Memory and a chapter their gallery with a guest's access and a WRITE_ONLY link their uploader, a Pot its page over the
     * link's reads; null for a kind or role without one (the guest view says "not shown here").
     */
    fun guestDoor(kind: String, role: String): me.raddatz.veyq.mobile.sharing.GuestDoor? = when {
        kind == FOLDER && (role == me.raddatz.veyq.mobile.sharing.GuestAccess.READ || role == me.raddatz.veyq.mobile.sharing.GuestAccess.READ_WRITE) ->
            me.raddatz.veyq.mobile.sharing.GuestLevel.door
        else -> applications.firstNotNullOfOrNull { it.guestDoor(kind, role) }
    }

    /**
     * What a READ_WRITE guest's upload at a folder of the link creates (the web's level upload: an application's own level
     * action, Depot's `guestFileUploadJob`): one job per source, each its own node created as the guest under [parent].
     */
    fun guestLevelUploads(
        access: me.raddatz.veyq.mobile.sharing.GuestAccess, session: me.raddatz.veyq.mobile.sharing.GuestSession,
        parent: me.raddatz.veyq.mobile.session.OpenNode, sources: List<me.raddatz.veyq.mobile.blob.UploadSource>,
    ): List<me.raddatz.veyq.mobile.blob.UploadQueue.Job> =
        sources.mapNotNull { source -> applications.firstNotNullOfOrNull { it.guestLevelUpload(access, session, parent, source) } }

    private const val FOLDER = "FOLDER"
    private const val SHARE_BOOKMARK = "SHARE_BOOKMARK"
}
