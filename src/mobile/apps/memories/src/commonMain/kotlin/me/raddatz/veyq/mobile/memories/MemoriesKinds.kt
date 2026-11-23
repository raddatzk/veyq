package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.tree.KindFacts
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.ApplicationKinds
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.export.SubtreeImport
import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.HeaderAction as H
import me.raddatz.veyq.mobile.navigation.ShareAnswer
import me.raddatz.veyq.mobile.offline.PinKinds
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.search.ApplicationSearch
import me.raddatz.veyq.mobile.search.DocumentKind
import me.raddatz.veyq.mobile.search.KindSearch
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.ShareWords
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.rotation.ScopeRekeyParticipant
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.words.Msg

/**
 * Memories' part of the platform's registry ([AppKinds], the web's `features/memories/register.ts`): a Memory and a
 * chapter — their re-key (`MemoryRekey`, ADR 0149 decision 2), the item marks' part of a scope rotation and of a Master Key
 * rotation (`ItemMarksRotation`), their download, copy and import (`MemoryExport`, `MemoryCopy`, `MemoryImport`), what a
 * pin keeps (`MemoryMedia.pinned`, the banner's `Banner.pinned`), their search with its facets and documents
 * (`MemoriesSearch`), their answer to shared files (`MemoryShare`) and their guest doors (`MemoriesGuest`).
 */
@HiddenFromObjC
object MemoriesKinds : ApplicationKinds {
    private const val MEMORY = MemoryCreate.MEMORY
    private const val CHAPTER = MemoryCreate.CHAPTER
    private const val FOLDER = "FOLDER"

    override val application = ApplicationId.MEMORIES
    override val kinds = listOf(MEMORY, CHAPTER)
    override val word = Msg.APP_MEMORIES
    override val objectKinds = listOf(MEMORY)

    override fun tabWord(key: String, objectKind: String?): Msg? = when (key) {
        "memories.all" -> Msg.TAB_MEMORIES_ALL
        "memories.map" -> Msg.TAB_MEMORIES_MAP
        else -> null
    }

    @PlatformApi
    @HiddenFromObjC
    override fun queuedSender(q: Queued): QueuedSender? = when (q) {
        Queued.MEMORY_CREATE, Queued.MEDIA_ADD, Queued.MEDIA_CAPTURE_TIME, Queued.MEDIA_TRASH, Queued.MEDIA_MARK -> QueuedMemories.sender(q)
        else -> null
    }

    /** A Memory and a chapter fold as containers behind Memories' route slug; a Memory at the top or in a folder, a chapter in a Memory. */
    override val kindFacts = listOf(
        KindFacts.Kind(MEMORY, "memories", KindFacts.NAME, root = true, parents = listOf(FOLDER)),
        KindFacts.Kind(CHAPTER, "memories", KindFacts.NAME, root = false, parents = listOf(MEMORY)),
    )

    override fun rekey(kind: String): NodeRekey.ContentRekey? = if (kind in kinds) MemoryRekey.content else null

    override val scopeRekeyParticipants: List<ScopeRekeyParticipant> = listOf(ItemMarksRotation.participant)

    // every one of the account's own item marks the server lists, whatever scope, re-tagged (the web's
    // `retagItemMarksUnderNewMasterKey`)
    override suspend fun masterKeyRotated(account: VeyqAccount, oldMk: ByteArray, newMk: ByteArray) =
        ItemMarksRotation.retag(account, oldMk, newMk)

    override fun exports(deletedName: String): NodeExport.Kinds = NodeExport.Kinds(
        exports = { it in kinds },
        content = { tenant, node -> if (node.kind in kinds) MemoryExport.content(tenant, node) else null },
    )

    override fun copies(deletedName: String, uuid: () -> String): SubtreeCopy.Kinds = SubtreeCopy.Kinds(
        copies = { it in kinds },
        duplicate = { tenant, node, scopeId, parentId, name -> MemoryCopy.duplicate(tenant, node, scopeId, parentId, name) },
    )

    override fun imports(deletedName: String, uuid: () -> String, deriver: Derivation.Deriver?, now: () -> Long): SubtreeImport.Kinds {
        val memory = MemoryImport.hook(MEMORY, deriver, now)
        val chapter = MemoryImport.hook(CHAPTER, deriver, now)
        return SubtreeImport.Kinds { kind ->
            when (kind) {
                MEMORY -> memory
                CHAPTER -> chapter
                else -> null
            }
        }
    }

    /** A Memory's and a chapter's noun with its article (the web's `nounWithArticle`, `memory.aMemory`, `memory.aChapter`). */
    override fun noun(kind: String): Msg? = if (kind in kinds) MemoryCreate.noun(kind) else null

    override fun headerOrder(kind: String): List<H>? =
        if (kind in kinds) listOf(H.FAVORITE, H.UPLOAD, H.SHARE, H.MUSIC, H.DOWNLOAD, H.BANNER, H.MOVE, H.COPY) else null

    override fun header(kind: String): AppKinds.Header? = when (kind) {
        MEMORY -> AppKinds.Header(true, true, true, Msg.MEMORY_DELETE_MEMORY, Msg.MEMORY_DELETE_CONFIRM)
        CHAPTER -> AppKinds.Header(true, true, true, Msg.MEMORY_DELETE_CHAPTER, Msg.MEMORY_DELETE_CONFIRM)
        else -> null
    }

    // ADR 0149 decision 3: a Memory and a chapter copy and download
    override fun doors(kind: String, facts: AppKinds.Facts): AppKinds.Doors? =
        if (kind in kinds) AppKinds.Doors(coverOffered = true, copies = true, downloads = true) else null

    override fun lossesOfCopy(kind: String): List<Msg>? = if (kind in kinds) listOf(Msg.COPY_LOSS_MEMORY) else null

    @PlatformApi
    @HiddenFromObjC
    override val kept = MemoriesKept

    override val pins: PinKinds = object : PinKinds {
        override fun pinnable(kind: String): Boolean = kind in kinds
        override suspend fun blobs(tenant: TenantSession, node: OpenNode) = MemoryMedia.pinned(tenant, node)

        // a Memory's and a chapter's banner: only their sealed meta names it (ADR 0144 decision 7)
        override fun named(tenant: TenantSession, node: OpenNode) = me.raddatz.veyq.mobile.memories.banner.Banner.pinned(tenant, node)
    }

    private val documents = listOf(
        DocumentKind(MemoriesSearch.PHOTO, Msg.SEARCH_KIND_PHOTO, MemoriesSearch.PHOTO_ICON),
        DocumentKind(MemoriesSearch.VIDEO, Msg.SEARCH_KIND_VIDEO, MemoriesSearch.VIDEO_ICON),
    )

    override fun search(kind: String): KindSearch? = when (kind) {
        MEMORY -> KindSearch(Msg.SEARCH_KIND_MEMORY, "images", MemoriesSearch::facets, documents)
        CHAPTER -> KindSearch(Msg.SEARCH_KIND_CHAPTER, "images", MemoriesSearch::facets, documents)
        else -> null
    }

    override val spaceSearch: ApplicationSearch = MemoriesSearch.search

    // the same dialog as core's, its sentences naming the node shared (Memories' `share.*`)
    override fun shareWords(kind: String): ShareWords? = when (kind) {
        MEMORY -> ShareWords(
            Msg.SHARE_MEMORY_TITLE, Msg.SHARE_MEMORY_INTRO, Msg.SHARE_MEMORY_ROLE_CONTRIBUTOR,
            Msg.SHARE_MEMORY_ROLE_VIEWER_HINT, Msg.SHARE_MEMORY_ROLE_CONTRIBUTOR_HINT, Msg.SHARE_MEMORY_DELETE_CONFIRM,
            Msg.SHARE_MEMORY_ROLE_UPLOADER, Msg.SHARE_MEMORY_ROLE_UPLOADER_HINT,
        )
        CHAPTER -> ShareWords(
            Msg.SHARE_CHAPTER_TITLE, Msg.SHARE_CHAPTER_INTRO, Msg.SHARE_MEMORY_ROLE_CONTRIBUTOR,
            Msg.SHARE_CHAPTER_ROLE_VIEWER_HINT, Msg.SHARE_MEMORY_ROLE_CONTRIBUTOR_HINT, Msg.SHARE_CHAPTER_DELETE_CONFIRM,
            Msg.SHARE_MEMORY_ROLE_UPLOADER, Msg.SHARE_CHAPTER_ROLE_UPLOADER_HINT,
        )
        else -> null
    }

    override fun share(kind: String): ShareAnswer? = if (kind in kinds) MemoryShare.answer else null

    // a guest moves a Memory, never a chapter (the web's `memories/rights.ts`)
    override fun guestMoves(kind: String): Boolean = kind == MEMORY

    override fun guestDoor(kind: String, role: String): GuestDoor? = when {
        kind !in kinds -> null
        role == GuestAccess.WRITE_ONLY -> MemoriesGuest.uploader
        role == GuestAccess.READ || role == GuestAccess.READ_WRITE -> MemoriesGuest.gallery
        else -> null
    }
}
