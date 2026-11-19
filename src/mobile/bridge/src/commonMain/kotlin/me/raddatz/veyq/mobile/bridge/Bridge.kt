@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.map.TileRecipient
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.expenses.Currencies
import me.raddatz.veyq.mobile.expenses.EntryEditor
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.PotCreateSheet
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.expenses.PotSettings
import me.raddatz.veyq.mobile.expenses.RevisionFormat
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel
import me.raddatz.veyq.mobile.navigation.TrashSheet
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.navigation.ShareSheet
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.CoverFrame
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.blob.pathName
import me.raddatz.veyq.mobile.words.Notices
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.ShellWords
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceLink
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.raddatz.veyq.mobile.session.addressChange
import me.raddatz.veyq.mobile.session.erasure
import me.raddatz.veyq.mobile.session.passwordChange
import me.raddatz.veyq.mobile.session.rotation.keyRotation

/**
 * The models one process holds, and what a tenant opened becomes: its shell and, per Pot opened, a Pot model. [A] is the
 * account and [T] an opened tenant, as in [SessionModel] — the real ones in [live], a test's own in a test.
 */
internal class Composition<A : Connection, T : Any>(
    val session: SessionModel<A, T>,
    /** What this device hides, which the switcher's tenants leave out ([ShellShown.servers]). */
    val hidden: Hidden,
    /**
     * The files the host names by their paths (ADR 0137 decision 3); a test's composition that writes none has
     * [DeviceFiles.None].
     */
    val files: DeviceFiles = DeviceFiles.None,
    /** How a scan's frame is read: the core's decoder; a test plays one it holds. */
    val decode: suspend (me.raddatz.veyq.mobile.qr.QrFrame) -> String? = { me.raddatz.veyq.mobile.qr.QrDecoder.decode(it) },
    /**
     * How a kit file the host picked is drawn ([Call.SessionSignInKit]): over the host's requests ([HostPictures]) in the
     * library; a test plays them, or has none.
     */
    val pictures: (path: String) -> me.raddatz.veyq.mobile.account.KitPictures = { NoPictures },
    /**
     * An account's Settings page (core's `AccountSettings`) over the session that holds it: `AccountSettings.of` in the
     * library; a test plays one over a door of its own, or has none (`Settings.Open` is then refused for the connection).
     */
    val settings: (session: SessionModel<A, T>, account: A, separator: Char) -> AccountSettings<A, T>? = { _, _, _ -> null },
    /**
     * How a Memories upload's tiers are made (`MediaUpload.Deriver`): asked of the host in the library ([HostTiers]); a test
     * plays the host, or has none (every original goes up alone).
     */
    val tiers: me.raddatz.veyq.mobile.memories.MediaUpload.Deriver = NoTiers,
    /**
     * How a picked banner is decoded for its previews and its upload (`BannerWrite.Pictures`): asked of the host in the
     * library ([HostBannerPictures], its upload tiers' request with the cover's spec); a test plays the host, or has none
     * (every pick refused as unreadable).
     */
    val bannerPictures: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures = NoBannerPictures,
    /**
     * The trusted device's link over a signed-in account (core's `DeviceLink.showForNewDevice`, `scanSignInCode` with
     * [decode]): [role] is one of those two. The real account's in the library; a test plays a granter over a relay of
     * its own, or has none (the trusted `Link.*` calls are then refused for the connection).
     */
    val trustedLink: (account: A, role: DeviceLink.Role, decode: suspend (me.raddatz.veyq.mobile.qr.QrFrame) -> String?) -> DeviceLink? = { _, _, _ -> null },
    /**
     * The host's half of the music (ADR 0142 decisions 2, 3, 4 and 7): its decks, its system controls, its measure of a
     * picked track and its words for an untitled one, over its requests ([HostAudio]) in the library; a test plays the host,
     * or has none — then no player is made and no page plays music.
     */
    val audio: HostAudio? = null,
    /** The app's words' language (`Config.language`): the geocoder's `accept-language` (ADR 0143 decision 2). */
    val language: String = "en",
    /**
     * The words the search matches and names in the host's language (ADR 0146 decision 7: a kind's word, a month's name, a
     * settings section's title and words), asked of the host ([HostWords]); a test plays its own.
     */
    val words: me.raddatz.veyq.mobile.search.SearchWords = me.raddatz.veyq.mobile.search.SearchWords { it.key },
    /**
     * A WebP the host's platform cannot decode, as a PNG ([Call.PictureDecode]): libwebp's decoder in the library
     * (`WebpPicture.png`); none on the JVM, where every such call is refused.
     */
    val webp: (ByteArray) -> ByteArray? = { null },
    /**
     * The account's flows over a signed-in account (ADR 0148 decision 11): the real account's in the library; a test plays
     * its own, or has none (each `*.Open` of a flow is then refused for the connection). [open] is the connection's tenant
     * session where it is the one open (null otherwise): the rotation names its status lines by its spaces (inbox #979) and
     * its *Links to renew* by its nodes (inbox #977).
     */
    val accountFlows: (account: A, tenant: TenantRef?, open: me.raddatz.veyq.mobile.session.TenantSession?) -> AccountFlows? = { _, _, _ -> null },
    /**
     * One's own profile picture cropped and drawn square by the host ([HostRequest.PictureSquare]): RGBA of `edge` × `edge`,
     * or null where the host could not; a test plays one, or has none.
     */
    val squarePicture: (path: String, pose: me.raddatz.veyq.mobile.session.CoverFrame.Pose, edge: Int) -> ByteArray? = { _, _, _ -> null },
    /**
     * A received link opened as its guest (ADR 0150 decisions 2–4): core's `GuestView` over the platform's client, the
     * device store and [received]'s guest sessions (the host's credential store), the guest's uploads on [queue], and a
     * kept link's [password] handed in memory (null: none, or a pasted link). The real one in the library; a test plays a
     * link's instance, or has none (`Received.Open` then opens nothing, nor does a bookmark).
     */
    val received: (
        link: me.raddatz.veyq.mobile.sharing.ReceivedLink.ShareLink, queue: me.raddatz.veyq.mobile.blob.UploadQueue, password: String?,
        /** The player a guest's Memory plays its music with (#1034): the open tenant's, where one plays. */
        music: me.raddatz.veyq.mobile.memories.GalleryModel.Music?,
    ) -> me.raddatz.veyq.mobile.sharing.GuestView? = { _, _, _, _ -> null },
    /** The models over a tenant just opened; null where there are none (a test's tenant that is no `TenantSession`). */
    val tenant: (T) -> Tenant?,
)

/** A kept link's page the host opened ([Call.BookmarkOpen]); [inPlace] the received link shown in it, by its id. */
internal class HeldBookmark(val page: me.raddatz.veyq.mobile.sharing.BookmarkPage) {
    val open = MutableStateFlow(true)
    val inPlace = MutableStateFlow<Long?>(null)
}

/** A received link's screen the host opened ([Call.ReceivedOpen]); the ids its pages' models were registered under. */
internal class HeldReceived(val screen: me.raddatz.veyq.mobile.sharing.GuestScreen) {
    val open = MutableStateFlow(true)
    val page = MutableStateFlow<ReceivedPage?>(null)

    /** A WRITE_ONLY page's sent files followed into [page] while it shows (cancelled when the page changes or closes). */
    var sending: kotlinx.coroutines.Job? = null

    /** The core's page [page] was drawn from: one page is registered once, whichever of a call or a reload showed it. */
    var drawn: me.raddatz.veyq.mobile.sharing.GuestPage? = null

    /** Follows the view's page (a reload after a READ_WRITE guest's write shows a new one by itself, #1034); cancelled as it closes. */
    var follow: kotlinx.coroutines.Job? = null
}

/** The account's flows the bridge opens over one account (ADR 0148 decisions 6–8): each a core model, made on `*.Open`. */
internal class AccountFlows(
    val passwordChange: () -> me.raddatz.veyq.mobile.session.PasswordChange,
    val addressChange: () -> me.raddatz.veyq.mobile.session.AddressChange,
    val keyRotation: () -> me.raddatz.veyq.mobile.session.rotation.KeyRotation,
    val erasure: () -> me.raddatz.veyq.mobile.session.AccountErasure,
)

/** One of the account's flows the host opened over the connection [connection]; [open] turns false as it closes. */
internal class HeldFlow<M : Any>(val model: M, val connection: String, private val closing: (M) -> Unit = {}) {
    val open = MutableStateFlow(true)

    fun close() {
        open.value = false
        closing(model)
    }
}

/** What a [ScanKind.SAFETY] scan found: a safety code's text, which the bridge compares and never sends (found as `veyqid`). */
internal class SafetyCode(val text: String)

/**
 * What a tenant opened offers the bridge: its shell, a Pot model per Pot opened (both null for a test's tenant, as they
 * take only a `TenantSession`), the signed-in member's user id ([me]) and the members' pictures the core holds
 * ([pictures], `AvatarsModel.state`), read when [loadPictures] runs (`AvatarsModel.load`: when is the core's rule).
 */
internal class Tenant(
    val shell: ShellModel?,
    val pot: (() -> PotModel)?,
    val me: String?,
    val pictures: StateFlow<Map<String, ByteArray>>,
    val loadPictures: suspend () -> Unit,
    /** The pictures' round (`AvatarsModel.held`): moves with each sync that changed them, a picture replaced under its id among it. */
    val picturesRound: Flow<Int> = flowOf(0),
    /** The tenant a received link is kept in (*Keep*, ADR 0150 decision 6); null for a test's tenant. */
    val session: me.raddatz.veyq.mobile.session.TenantSession? = null,
    /**
     * A node's header over this tenant (`ShellModel.header`), whose `closeOpen` the bridge hands in (it closes the open
     * Pots of a node gone to the trash); a test's tenant plays one over a door of its own.
     */
    val header: ((node: NodeAt, facts: AppKinds.Facts?, closeOpen: (NodeAt) -> Unit) -> NodeHeaderModel)? = shell?.let { s ->
        { node, facts, closeOpen -> s.header(node, facts, closeOpen) {} }
    },
    /** A space's trash sheet (`ShellModel.trashSheet`). */
    val trash: ((scopeId: String) -> TrashSheet)? = shell?.let { s -> { scopeId -> s.trashSheet(scopeId) {} } },
    /** A new folder where the shell is (`ShellModel.createFolderHere`): why not, or null. */
    val createFolder: (suspend (name: String) -> Notice?)? = shell?.let { s -> { name -> s.createFolderHere(name) } },
    /** A node's move or copy sheet (`ShellModel.moveSheet`), a Pot's copy by the open Pot model handed in. */
    val move: ((node: NodeAt, copying: Boolean, pot: PotModel?, deletedName: String) -> MoveSheet)? = shell?.let { s ->
        { node, copying, pot, deletedName -> s.moveSheet(node, copying, pot, deletedName) {} }
    },
    /** A node's share sheet (`ShellModel.shareSheet`). */
    val share: ((node: NodeAt) -> ShareSheet)? = shell?.let { s -> { node -> s.shareSheet(node) {} } },
    /** The share of a space's top level, "Add files only" (`ShellModel.topLevelShareSheet`). */
    val topLevelShare: ((scopeId: String) -> ShareSheet)? = shell?.let { s -> { scopeId -> s.topLevelShareSheet(scopeId) {} } },
    /** A drop box's page (`ShellModel.dropBoxPage`): its guests' folders read anew on each revision of the shell's lists. */
    val dropBox: ((node: NodeAt) -> me.raddatz.veyq.mobile.depot.DropBoxPage)? = shell?.let { s -> { node -> s.dropBoxPage(node) } },
    /** A node's report sheet (`ShellModel.reportSheet`). */
    val report: ((node: NodeAt) -> ReportSheet)? = shell?.let { s -> { node -> s.reportSheet(node) } },
    /**
     * "New pot" where the shell is (`PotModel.createSheet` over a Pot model of its own, [language] the host's): null where
     * the tenant has no shell's lists yet. A test's tenant plays one over a door of its own.
     */
    val createPot: ((language: String) -> PotCreateSheet?)? = if (shell != null && pot != null) { language -> pot().createSheet(shell, language) } else null,
    /** A node with everything under it into a sink (`ShellModel.download`): what it left out, or null. */
    val download: (suspend (node: NodeAt, deletedName: String, sink: (ByteArray) -> Unit) -> List<NodeExport.Skipped>?)? = shell?.let { s ->
        { node, deletedName, sink -> s.download(node, deletedName, Clock.System.now().toEpochMilliseconds(), sink) }
    },
    /** A file's page (`ShellModel.fileModel`; the desktops hand no copy to another app, so it offers no "Send a copy"). */
    val file: ((node: NodeAt) -> me.raddatz.veyq.mobile.depot.FileModel)? = shell?.let { s -> { node -> s.fileModel(node, shares = false) } },
    /**
     * The uploads over the app's one [queue] (`ShellModel.depotUploads`), [changed] after each write (the shell's lists and
     * the open file pages read anew); a test's tenant plays one over a door of its own.
     */
    val uploads: ((queue: me.raddatz.veyq.mobile.blob.UploadQueue, changed: () -> Unit) -> me.raddatz.veyq.mobile.depot.DepotUploads)? =
        shell?.let { s -> { queue, changed -> s.depotUploads(queue, changed) } },
    /**
     * Memories' uploads over the app's one [queue] (`ShellModel.mediaUploads`): each picked medium's tiers made by [deriver],
     * [changed] at a batch's end. A desktop reads each picked file in place at its path, so a file the queue is done with
     * needs nothing done. A test's tenant plays one over a door of its own.
     */
    val mediaUploads: ((
        queue: me.raddatz.veyq.mobile.blob.UploadQueue, deriver: me.raddatz.veyq.mobile.memories.MediaUpload.Deriver, changed: () -> Unit,
    ) -> me.raddatz.veyq.mobile.memories.MediaUploads)? =
        shell?.let { s -> { queue, deriver, changed -> s.mediaUploads(queue, deriver, left = {}, changed = changed) } },
    /**
     * A Memory's or a chapter's page (`ShellModel.galleryModel`) over the tenant's Memories [uploads], playing its music with
     * [music] (the account's player; null: none).
     */
    val gallery: ((
        node: NodeAt, uploads: me.raddatz.veyq.mobile.memories.MediaUploads, music: me.raddatz.veyq.mobile.memories.GalleryModel.Music?,
    ) -> me.raddatz.veyq.mobile.memories.GalleryModel)? =
        shell?.let { s -> { node, uploads, music -> s.galleryModel(node, uploads, music) } },
    /** A page's Contributions view (`ShellModel.contributions`, ADR 0149 decision 6); a test's tenant plays one over its own door. */
    val contributions: ((me.raddatz.veyq.mobile.memories.GalleryModel) -> me.raddatz.veyq.mobile.memories.Contributions)? =
        shell?.let { s -> { g -> s.contributions(g) } },
    /**
     * The account's music player (`ShellModel.musicPlayer`, ADR 0142 decision 1) over the host's decks and system controls;
     * one per tenant opened, stopped when it closes. A test's tenant plays one over its own randomness.
     */
    val musicPlayer: ((
        newDeck: (me.raddatz.veyq.mobile.audio.Deck.Events) -> me.raddatz.veyq.mobile.audio.Deck, controls: me.raddatz.veyq.mobile.audio.SystemControls?,
    ) -> me.raddatz.veyq.mobile.audio.MusicPlayer)? = shell?.let { s -> { newDeck, controls -> s.musicPlayer(newDeck, controls) } },
    /**
     * A page's Music view (`ShellModel.musicModel`, ADR 0142 decision 8) over the tenant's Memories [uploads], a picked
     * track's length read by the host's [measure]. A desktop reads each picked file in place, so one not taken needs
     * nothing done. A test's tenant plays one over a door of its own.
     */
    val musicModel: ((
        gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uploads: me.raddatz.veyq.mobile.memories.MediaUploads,
        measure: me.raddatz.veyq.mobile.memories.music.MusicUpload.Measure,
    ) -> me.raddatz.veyq.mobile.memories.music.MusicModel)? =
        shell?.let { s -> { gallery, uploads, measure -> s.musicModel(gallery, uploads, measure, left = {}) } },
    /** A Memory made where New makes it (`ShellModel.createMemory`), *I was there* ticked or not: the node made, or why not. */
    val createMemory: (suspend (scopeId: String, parentId: String?, name: String, wasThere: Boolean, autoPlace: Boolean) -> me.raddatz.veyq.mobile.memories.MemoryCreate.Made)? =
        shell?.let { s -> { scopeId, parentId, name, wasThere, autoPlace -> s.createMemory(scopeId, parentId, name, wasThere, autoPlace) } },
    /** Whether New Memory in a scope offers *I was there* (`ShellModel.offersWasThere`). */
    val offersWasThere: ((scopeId: String) -> Boolean)? = shell?.let { s -> { scopeId -> s.offersWasThere(scopeId) } },
    /** Whether New Memory offers *Determine location automatically* (`ShellModel.offersAutoPlace`, uc.memory.place A1). */
    val offersAutoPlace: (() -> Boolean)? = shell?.let { s -> { s.offersAutoPlace() } },
    /**
     * The search over this tenant (`ShellModel.searchModel`, ADR 0146): one per field, over the host's [words]; a desktop's
     * settings page offers no appearance section (the phones' alone does).
     */
    val search: ((words: me.raddatz.veyq.mobile.search.SearchWords) -> me.raddatz.veyq.mobile.search.SearchModel)? =
        shell?.let { s -> { words -> s.searchModel(words, appearance = false) } },
    /** A person's frame (`ShellModel.profile`): named by the tenant's directory as read, with the sections of the applications on. */
    val profile: ((userId: String) -> me.raddatz.veyq.mobile.navigation.PersonProfile)? = shell?.let { s -> { userId -> s.profile(userId) } },
    /** Memories' section of a person's page (`ShellModel.personMemories`) over the tenant's participants index. */
    val personMemories: ((userId: String) -> me.raddatz.veyq.mobile.memories.people.PersonMemories)? =
        shell?.let { s -> { userId -> s.personMemories(userId) } },
    /** A list row's cover (`ShellModel.rowCover`): its frame and crop, its picture only where the host holds another frame. */
    val rowCover: (suspend (nodeId: String, held: String?) -> me.raddatz.veyq.mobile.session.NodeCover.Row?)? =
        shell?.let { s -> { nodeId, held -> s.rowCover(nodeId, held) } },
    /**
     * This tenant's map services on this device (`ShellModel.mapServices`, ADR 0143 decision 2): the tile host's map (a
     * desktop draws the core's raster map), place names in [language]; one per tenant opened, closed when it closes. A
     * test's tenant plays one over a tile server of its own, or has none (no map; the editors' name field alone).
     */
    val mapServices: ((language: () -> String) -> me.raddatz.veyq.mobile.map.MapServices)? =
        shell?.let { s -> { language -> s.mapServices(TileRecipient.TILE_HOST, language) } },
    /** A page's date editor (`ShellModel.dateEditor`): null where it is not offered. */
    val dateEditor: ((gallery: me.raddatz.veyq.mobile.memories.GalleryModel) -> me.raddatz.veyq.mobile.memories.place.DateEditor?)? =
        shell?.let { s -> { g -> s.dateEditor(g) } },
    /** A page's place editor (`ShellModel.placeEditor`, over [mapServices]' place names): null where it is not offered. */
    val placeEditor: ((gallery: me.raddatz.veyq.mobile.memories.GalleryModel) -> me.raddatz.veyq.mobile.memories.place.PlaceEditor?)? =
        shell?.let { s -> { g -> s.placeEditor(g) } },
    /**
     * The Memories bar's Map tab (`ShellModel.memoriesMap`) over the application's All list as the shell holds it now (ADR
     * 0143 decision 6: the Map tab's set is the All tab's) — its Favorites, Recent and the rest, each object listed once
     * there (ADR 0122 decision 5), so `all` alone would leave out every Memory used lately.
     */
    val memoriesMap: (suspend () -> me.raddatz.veyq.mobile.memories.place.MemoriesMap)? = shell?.let { s ->
        {
            val list = s.state.value?.lists?.applicationList(ApplicationId.MEMORIES)
            val listed = list?.let { it.favorites + it.recent + it.all }.orEmpty().distinctBy { it.scopeId to it.nodeId }
            s.memoriesMap(listed.map { NodeRef(it.scopeId, it.nodeId, null, it.kind, it.name) })
        }
    },
    /** Every Memory's and chapter's date by node (`ShellModel.memoryDates`): a row's second line. */
    val memoryDates: (suspend () -> Map<String, me.raddatz.veyq.mobile.memories.place.MemoryDate>)? = shell?.let { s -> { s.memoryDates() } },
    /**
     * A page's Map view (`ShellModel.trackList`) over the tenant's Memories [uploads]. A desktop reads each picked file in
     * place, so one not taken needs nothing done. A test's tenant plays one over a door of its own.
     */
    val trackList: ((
        gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uploads: me.raddatz.veyq.mobile.memories.MediaUploads,
    ) -> me.raddatz.veyq.mobile.memories.tracks.TrackList)? = shell?.let { s -> { g, uploads -> s.trackList(g, uploads, left = {}) } },
    /** One's own profile in this tenant (core's `ProfileModel`, `TenantSession.profile`); null for a test's tenant without one. */
    val ownProfile: (() -> me.raddatz.veyq.mobile.session.ProfileModel)? = null,
    /** The safety numbers of this tenant (core's `PersonTrust`, `TenantSession.personTrust`); null for a test's tenant without one. */
    val trust: me.raddatz.veyq.mobile.session.PersonTrust? = null,
    /** The tenant's spaces as it opened them (`TenantSession.spaces`): the names a rotation's status lines are drawn by. */
    val spaces: suspend () -> List<me.raddatz.veyq.mobile.navigation.SpaceRef> = { emptyList() },
)

/**
 * A participants picker the host opened ([Call.ParticipantsOpen]) on gallery [gallery]; [open] turns false as it closes, so a
 * watch standing on it delivers null once.
 */
internal class HeldParticipants(val gallery: Long, val model: me.raddatz.veyq.mobile.memories.ParticipantsPicker) {
    val open = MutableStateFlow(true)
}

/** A capture-time editor the host opened ([Call.CaptureTimeOpen]) in lightbox [lightbox] of gallery [gallery]. */
internal class HeldCaptureTime(val gallery: Long, val lightbox: Long, val model: me.raddatz.veyq.mobile.memories.CaptureTimeEditor) {
    val open = MutableStateFlow(true)
}

/**
 * A banner dialog the host opened ([Call.BannerOpen]) on gallery [gallery]: the file it read last ([source], closed when
 * another is picked or the dialog goes), the pick's number ([pick]) and the save running ([job], which [Call.BannerCancel]
 * cancels — the old banner then stays, `BannerEditor`'s rule).
 */
internal class HeldBanner(val gallery: Long, val model: me.raddatz.veyq.mobile.memories.banner.BannerEditor) {
    val open = MutableStateFlow(true)
    val pick = MutableStateFlow(0L)
    val source = AtomicReference<me.raddatz.veyq.mobile.blob.UploadSource?>(null)
    val job = AtomicReference<Job?>(null)

    /** The dialog goes: its save stopped, its file let go. */
    fun close() {
        open.value = false
        job.exchange(null)?.cancel()
        source.exchange(null)?.close()
    }
}

/**
 * A person's page the host opened ([Call.PersonOpen]): core's frame and Memories' section where it is among the
 * application's sections; [open] turns false as it closes.
 */
/** A search the host opened ([Call.SearchOpen]); [open] turns false as its tenant closes, so its watch delivers null. */
internal class HeldSearch(val model: me.raddatz.veyq.mobile.search.SearchModel) {
    val open = MutableStateFlow(true)
}

internal class HeldPerson(
    val profile: me.raddatz.veyq.mobile.navigation.PersonProfile,
    val memories: me.raddatz.veyq.mobile.memories.people.PersonMemories?,
) {
    val open = MutableStateFlow(true)
}

/** A date editor the host opened ([Call.DateOpen]) on gallery [gallery]. */
internal class HeldDate(val gallery: Long, val model: me.raddatz.veyq.mobile.memories.place.DateEditor)

/** A place editor the host opened ([Call.PlaceOpen]) on gallery [gallery], its map's id (null: no map services). */
internal class HeldPlace(val gallery: Long, val page: me.raddatz.veyq.mobile.memories.GalleryModel, val model: me.raddatz.veyq.mobile.memories.place.PlaceEditor, val map: Long?) {
    val open = MutableStateFlow(true)
}

/** A Map view the host opened ([Call.TracksOpen]) on gallery [gallery], its map's id (null: no map services). */
internal class HeldTracks(val gallery: Long, val model: me.raddatz.veyq.mobile.memories.tracks.TrackList, val map: Long?)

/** A Music view the host opened ([Call.MusicOpen]) over gallery [gallery]. */
internal class HeldMusic(val gallery: Long, val model: me.raddatz.veyq.mobile.memories.music.MusicModel)

/** A page's Contributions view ([Call.BucketsOpen]), held with its gallery: it closes with that gallery and its tenant. */
internal class HeldBuckets(val gallery: Long, val model: me.raddatz.veyq.mobile.memories.Contributions)

/** A lightbox the host opened ([Call.LightboxOpen]) over gallery [gallery], its [page]. */
internal class HeldLightbox(val gallery: Long, val page: me.raddatz.veyq.mobile.memories.GalleryModel, val model: me.raddatz.veyq.mobile.memories.Lightbox)

/**
 * A header the host opened, with the most buttons its bar holds, and the file page whose own actions join its plan
 * ([Call.HeaderFacts]' `file`: a FILE's Replace, worded by the page).
 */
internal class HeldHeader(val model: NodeHeaderModel, val maxButtons: Int) {
    val file = MutableStateFlow<Long?>(null)

    /** The Memory's or chapter's page whose own actions join the plan ([Call.HeaderFacts]' `gallery`: its upload). */
    val gallery = MutableStateFlow<Long?>(null)
}

/** A drop box's page the host opened, and the header its last delete trashed through (its undo, [Call.DropBoxUndo]). */
internal class HeldDropBox(val page: me.raddatz.veyq.mobile.depot.DropBoxPage) {
    @kotlin.concurrent.Volatile var trashed: NodeHeaderModel? = null
}

/** An entry editor the host opened on Pot [pot] ([Call.EditorOpen]), typed in with [separator]. */
internal class HeldEditor(val pot: Long, val model: EntryEditor, val separator: Char)

/** The settings of Pot [pot] the host opened ([Call.PotSettingsOpen]). */
internal class HeldSettings(val pot: Long, val model: PotSettings)

/**
 * An account's Settings the host opened ([Call.SettingsOpen]) for the connection [connection]; [open] turns false as it
 * closes, so a watch standing on it delivers null once.
 */
internal class HeldAccount<A : Connection, T : Any>(val model: AccountSettings<A, T>, val connection: String) {
    val open = MutableStateFlow(true)

    fun close() {
        open.value = false
    }
}

/**
 * A device link the host opened (`Link.*` of a role); [trusted] for the trusted device's two (they close with the tenant),
 * [open] false once the host closed it, so a watch standing on it delivers null once.
 */
internal class HeldLink(val model: DeviceLink, val trusted: Boolean) {
    val open = MutableStateFlow(true)
}

/** "New pot" the host opened ([Call.PotCreateOpen]) and the name it sent last ([Call.PotCreateName]), never echoed. */
internal class HeldCreate(val sheet: PotCreateSheet) {
    val name = MutableStateFlow("")
}

/**
 * A file on the device the host named by its path, as its save dialog gave it (ADR 0137 decision 3): the core opens and
 * writes it, and nothing of its content crosses the bridge. Natively core's `FileSink`; a JVM test plays one.
 */
internal interface DeviceFiles {
    /**
     * Writes the file at [path] with what [produce] hands its sink; it stays only when [produce] answers true, and one
     * that throws or answers false leaves nothing at [path]. Throws when [path] does not open.
     */
    suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean

    /** The whole file at [path] (a cover the host encoded); throws when it does not open or holds more than [most] bytes. */
    suspend fun read(path: String, most: Int): ByteArray

    /**
     * The file at [path] as an upload's source (core's `PickedPaths.source`): read at offsets a frame at a time, never whole;
     * null where it is no file that reads.
     */
    fun source(path: String): me.raddatz.veyq.mobile.blob.UploadSource?

    /** What a walk of the directory at [path] meets (core's `PickedPaths.walk`); null where it is no directory that lists. */
    fun walk(path: String): List<me.raddatz.veyq.mobile.depot.FolderUpload.Found>?

    /** No file is written: a test's composition that has none to offer. */
    object None : DeviceFiles {
        override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) =
            throw UnsupportedOperationException("this composition writes no files")
        override suspend fun read(path: String, most: Int): ByteArray = throw UnsupportedOperationException("this composition reads no files")
        override fun source(path: String): me.raddatz.veyq.mobile.blob.UploadSource? = null
        override fun walk(path: String): List<me.raddatz.veyq.mobile.depot.FolderUpload.Found>? = null
    }

    companion object {
        /** The most a cover file the host encoded may hold (a 2048-pixel JPEG is a few MB at most). */
        const val COVER_MOST = 32 shl 20
    }
}

/** The tenant open now: its models, and the account and tenant the session opened them for. */
private class Open<A : Connection>(val models: Tenant, val account: A, val tenant: TenantRef)

/**
 * The dispatch (ADR 0123's Consequences, the bridge): each [Call] is one line onto a model's own function, each
 * [Watched] one model's state flow, and the registry of what is open — the tenant's models, the Pots by id, the watches
 * by id. Nothing here decides anything the person sees: a rule found here is a defect to move into the core (ADR 0136
 * decision 1).
 *
 * Every answer and update is delivered on a core thread ([Dispatchers.Default]); [compose] runs there too, so a host
 * request it makes (the store's first read) never waits on the thread that started the core.
 */
internal class Bridge<A : Connection, T : Any>(compose: () -> Composition<A, T>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parts = scope.async { compose() }
    private val tenant = MutableStateFlow<Open<A>?>(null)
    private val ids = AtomicLong(0)
    private val pots = Registry<PotModel>()
    private val readers = Registry<RangeReader>()
    private val watches = Registry<Job>()
    private val headers = Registry<HeldHeader>()
    private val trashes = Registry<TrashSheet>()
    private val moves = Registry<MoveSheet>()
    private val shares = Registry<ShareSheet>()
    private val dropBoxes = Registry<HeldDropBox>()
    private val reports = Registry<ReportSheet>()
    private val editors = Registry<HeldEditor>()
    private val settings = Registry<HeldSettings>()
    private val creates = Registry<HeldCreate>()

    /** The accounts' Settings pages the host opened ([Call.SettingsOpen]): closed with the tenant. */
    private val accountPages = Registry<HeldAccount<A, T>>()
    private val filePages = Registry<me.raddatz.veyq.mobile.depot.FileModel>()
    /** Each reader a file's page opened ([Call.FileReader]), by the reader's id: the page's id, so it closes with it. */
    private val pageReaders = Registry<Long>()
    private val saves = Registry<me.raddatz.veyq.mobile.depot.FileSave>()

    /** The QR scans the host opened ([Call.ScanOpen]): no tenant's, each until [Call.ScanClose] or [shutdown]. */
    private val scans = Registry<me.raddatz.veyq.mobile.qr.QrScan<*>>()

    /** What a link's scan, by its scan id, is found as ([ScanShown.found]): its direction's scheme. */
    private val linkScans = Registry<String>()

    /**
     * The device links the host opened (`Link.*` of a role), each until [Call.LinkClose]; the trusted device's also until
     * the tenant closes, the new device's (no tenant yet) until [shutdown].
     */
    private val links = Registry<HeldLink>()

    /** The received links the host opened ([Call.ReceivedOpen]), each until left or closed; not a tenant's (no sign-in needed). */
    private val received = Registry<HeldReceived>()

    /** The kept links' pages the host opened ([Call.BookmarkOpen]), each until closed or its tenant closes. */
    private val bookmarks = Registry<HeldBookmark>()

    /** The app's one upload queue (the phones' `AppModel.uploads`): emptied when the tenant closes. */
    private val queue = me.raddatz.veyq.mobile.blob.UploadQueue()

    /** The open tenant's uploads over [queue] ([Watched.Uploads]); null while none is open. */
    private val uploads = MutableStateFlow<me.raddatz.veyq.mobile.depot.DepotUploads?>(null)

    /** The open tenant's Memories uploads over the same [queue] (the tray stays [uploads]'); null while none is open. */
    private val mediaUploads = MutableStateFlow<me.raddatz.veyq.mobile.memories.MediaUploads?>(null)

    /** The Memories' and chapters' pages the host opened ([Call.GalleryOpen]), and their lightboxes: closed with the tenant. */
    private val galleries = Registry<me.raddatz.veyq.mobile.memories.GalleryModel>()
    private val lightboxes = Registry<HeldLightbox>()

    /**
     * Each reader a lightbox opened ([Call.MediumReader]), by the reader's id: the lightbox's id. The lightbox's model closes
     * its reader (paged away, stopped, closed): a read of one it no longer holds is refused and the id dropped.
     */
    private val lightboxReaders = Registry<Long>()

    /**
     * The open tenant's music player (ADR 0142 decision 1; null while none is open, or the composition has no decks), its
     * decks by id (the host's [Call.AudioEnded] and [Call.AudioFailed] find them) and the pages' Music views: each the
     * tenant's, gone when it closes.
     */
    private val player = MutableStateFlow<me.raddatz.veyq.mobile.audio.MusicPlayer?>(null)
    private val decks = Registry<HostDeck>()
    private val musics = Registry<HeldMusic>()
    private val buckets = Registry<HeldBuckets>()

    /** The account's view choice turned ([Call.ViewSet]): the shell's state shows it anew. */
    private val viewTurned = MutableStateFlow(0L)

    /** A deck's reader held for the host as any reader is ([openReader]), only while a tenant is open. */
    private val deckReaders = object : HeldReaders {
        override fun hold(reader: RangeReader): Long? = (openReader(reader, null) as? Answer.Reader)?.reader
        override fun drop(id: Long) {
            readers.remove(id)
        }
    }

    /**
     * The open tenant's map services (ADR 0143 decision 2; null while none is open, or the tenant has none), the maps the
     * host opened, and the pages' date and place editors and Map views: each the tenant's, gone when it closes — the
     * services closed (their tiles dropped, the geocoder's queue cleared), every map's window closed.
     */
    private val mapServices = MutableStateFlow<me.raddatz.veyq.mobile.map.MapServices?>(null)
    private val maps = Registry<HeldMap>()
    private val dates = Registry<HeldDate>()
    private val places = Registry<HeldPlace>()
    private val trackLists = Registry<HeldTracks>()

    /**
     * The pages' curation dialogs (ADR 0144 decision 8) — participants pickers, capture-time editors, banner dialogs — and
     * the readers a video banner opened ([Call.BannerPlay], by the reader's id: its gallery's), each closed with its page and
     * its tenant; and the person's pages, closed with the tenant (the participants index with it, `TenantSession.close`).
     */
    private val participantPickers = Registry<HeldParticipants>()
    private val captureTimes = Registry<HeldCaptureTime>()
    private val banners = Registry<HeldBanner>()
    private val bannerReaders = Registry<Long>()
    private val persons = Registry<HeldPerson>()

    // the account's flows (ADR 0148 decision 11): each closed with the tenant and at a sign-out
    private val profiles = Registry<HeldFlow<me.raddatz.veyq.mobile.session.ProfileModel>>()
    private val passwordChanges = Registry<HeldFlow<me.raddatz.veyq.mobile.session.PasswordChange>>()
    private val addressChanges = Registry<HeldFlow<me.raddatz.veyq.mobile.session.AddressChange>>()
    private val keyRotations = Registry<HeldFlow<me.raddatz.veyq.mobile.session.rotation.KeyRotation>>()
    private val erasures = Registry<HeldFlow<me.raddatz.veyq.mobile.session.AccountErasure>>()
    private val kits = Registry<HeldFlow<me.raddatz.veyq.mobile.account.KitStep>>()

    /** The searches the host opened (ADR 0146 decision 13), the tenant's: each disposed when it closes. */
    private val searches = Registry<HeldSearch>()

    /** "Manage servers" while it is open, null while closed; its spaces read by [managing]'s job. */
    private val manage = MutableStateFlow<List<ManagedShown>?>(null)
    private val managing = AtomicReference<Job?>(null)

    /** Turned by each tenant hidden or shown, so the shell's watch reads the switcher's tenants anew. */
    private val hiddenTurned = MutableStateFlow(0L)

    /**
     * One call, answered once through [answer] on a core thread — a request that is no [Call] too. The payload beside
     * the JSON is the bytes of an [Answer.Bytes] (never null for one, empty included) and null for every other answer.
     * [inbound] is what the host handed beside the call's JSON (a [Call.ScanFrame]'s plane, a [Call.PictureDecode]'s WebP), already the core's own copy;
     * every other call ignores it.
     */
    fun call(request: String, inbound: ByteArray? = null, answer: (json: String, payload: ByteArray?) -> Unit) {
        val call = runCatching { wire.decodeFromString(Call.serializer(), request) }.getOrNull()
        // An editor's edit is applied here, on the host's thread, in the order the host called: launched, the edits of two
        // keystrokes could land the wrong way round, and the field would end on the first.
        val edited = when (call) {
            is Call.EditorChange -> edit(call)
            is Call.EditorDropReceipt -> editors[call.editor]?.model?.dropReceipt(call.receipt).done(Answer.Why.NO_SUCH_EDITOR)
            is Call.PotCreateName -> named(call)
            // a link's label and password are typed too: a Save sent after the last keystroke finds it applied
            is Call.ShareLabel -> shares[call.share]?.label(call.label).done(Answer.Why.NO_SUCH_SHARE)
            is Call.SharePassword -> shares[call.share]?.password(call.password).done(Answer.Why.NO_SUCH_SHARE)
            // a map's views come at gesture pace, a place's name and a date's fields as typed and picked: in the host's order
            is Call.MapView, is Call.PlaceTyped, is Call.DateChoose, is Call.DateFrom, is Call.DateTo,
            is Call.ParticipantsSearch, is Call.ParticipantsToggle,
            // the search's text as typed: the latest keystroke is the one the field ends on
            is Call.SearchText -> ordered(call)
            else -> null
        }
        scope.launch {
            var payload: ByteArray? = null
            val a = try {
                when {
                    call == null -> Answer.Refused(Answer.Why.NOT_A_CALL)
                    edited != null -> edited
                    else -> dispatch(call, inbound) { payload = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Answer.Failed(e::class.simpleName ?: "Exception")
            }
            // the bytes ride only beside the two answers that say so: Bytes (always, empty for none) and a RowCover that sent its picture
            answer(wire.encodeToString(Answer.serializer(), a), when {
                a is Answer.Bytes -> payload ?: ByteArray(0)
                a is Answer.RowCover && a.sent -> payload
                else -> null
            })
        }
    }

    /** The maps' and the editors' calls applied on the host's thread, in its order. */
    private fun ordered(call: Call): Answer = when (call) {
        is Call.MapView -> maps[call.map]?.view(call.x, call.y, call.zoom, call.width, call.height).done(Answer.Why.NO_SUCH_MAP)
        is Call.PlaceTyped -> places[call.place]?.model?.typeName(call.name).done(Answer.Why.NO_SUCH_PLACE)
        is Call.DateChoose -> dates[call.date]?.model?.mode(me.raddatz.veyq.mobile.memories.place.MemoryDate.Mode.valueOf(call.mode.name)).done(Answer.Why.NO_SUCH_DATE)
        is Call.DateFrom -> dates[call.date]?.model?.from(call.day).done(Answer.Why.NO_SUCH_DATE)
        is Call.DateTo -> dates[call.date]?.model?.to(call.day).done(Answer.Why.NO_SUCH_DATE)
        is Call.ParticipantsSearch -> participantPickers[call.participants]?.model?.search(call.query).done(Answer.Why.NO_SUCH_PARTICIPANTS)
        is Call.ParticipantsToggle -> participantPickers[call.participants]?.let { taken(it.model.toggle(call.userId)) } ?: noParticipants
        is Call.SearchText -> searches[call.search]?.model?.text(call.text).done(Answer.Why.NO_SUCH_SEARCH)
        else -> Answer.Refused(Answer.Why.NOT_A_CALL)
    }

    /** [Call.EditorChange] on its editor: done, or no such editor. */
    private fun edit(c: Call.EditorChange): Answer = editors[c.editor]?.model?.change(c.change).done(Answer.Why.NO_SUCH_EDITOR)

    /** [Call.PotCreateName] held: done, or no such sheet. */
    private fun named(c: Call.PotCreateName): Answer = creates[c.create]?.name?.let { it.value = c.name }.done(Answer.Why.NO_SUCH_CREATE)

    /**
     * One line per call; a call whose answer is [Answer.Bytes] (or a [Answer.RowCover] that sent its picture) hands its bytes
     * to [payload] first, and a call that carries
     * bytes in reads them from [inbound].
     */
    private suspend fun dispatch(call: Call, inbound: ByteArray?, payload: (ByteArray) -> Unit): Answer {
        val parts = parts.await()
        val session = parts.session
        val shell = tenant.value?.models?.shell
        return when (call) {
            Call.SessionStart -> opened(session.start())
            is Call.SessionSignIn -> opened(session.signIn(call.server, call.email, call.password, call.secretKey)).also { managed(parts) }
            is Call.SessionStaySignedIn -> (session.stay(call.level)?.answer() ?: Answer.Refused(Answer.Why.NO_OFFER)).also { managed(parts) }
            Call.SessionNotNow -> if (session.notNow()) Answer.Done else Answer.Refused(Answer.Why.NO_OFFER)
            is Call.SessionUnlock -> opened(session.unlock(call.password))
            Call.SessionOtherAccount -> Answer.Done.also { session.otherAccount() }
            Call.SessionAddServer -> Answer.Done.also { session.addServer() }
            Call.SessionCancelAdd -> Answer.Done.also { session.cancelAdd() }
            is Call.SessionSignInKit -> {
                // the core reads the file by its path, never more than a kit file may hold; one that does not read (or
                // is past the cap) is read as no kit, and nothing is asked of the host for it
                val bytes = runCatching { parts.files.read(call.path, me.raddatz.veyq.mobile.account.KitImport.FILE_CAP) }.getOrNull()
                val read = if (bytes == null) session.readKit(ByteArray(0), NoPictures, call.server)
                else session.readKit(bytes, parts.pictures(call.path), call.server)
                if (read) Answer.Done else Answer.Refused(Answer.Why.NO_KIT)
            }
            Call.SessionSignInKitClear -> Answer.Done.also { session.clearKit() }
            is Call.SessionForgetDevice -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.forgetDevice(account)
                managed(parts)
                Answer.Done
            }
            Call.ManageOpen -> {
                manage.value = shownManaged(parts)
                managing.exchange(scope.launch { session.loadSpaces { managed(parts) } })?.cancel()
                Answer.Done
            }
            Call.ManageClose -> {
                managing.exchange(null)?.cancel()
                manage.value = null
                Answer.Done
            }
            is Call.ManageHideTenant -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                if (account.tenants.none { it.tenantId == call.tenantId }) return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                parts.hidden.setTenantHidden(account.key, call.tenantId, call.hidden)
                hiddenTurned.value++ // the switcher's tenants are read beside the shell's state, which did not change
                managed(parts)
                Answer.Done
            }
            is Call.ManageStaySignedIn -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.stay(account, call.level).answer().also { managed(parts) }
            }
            is Call.ManagePasswordTrust -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.trustWithPassword(account, call.password).answer().also { managed(parts) }
            }
            is Call.ManageHideSpace -> {
                parts.hidden.setSpaceHidden(call.scopeId, call.hidden)
                shell?.rebuild()
                managed(parts)
                Answer.Done
            }
            is Call.SessionSwitchTo -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val to = account.tenants.firstOrNull { it.tenantId == call.tenantId } ?: return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                opened(session.switchTo(account, to)).also { managed(parts) }
            }
            is Call.SessionSignOut -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val out = session.signOut(account)
                if (out.wasCurrent) closeTenant()
                opened(out.next).also { managed(parts) }
            }
            is Call.SettingsOpen -> {
                if (tenant.value == null) return noTenant
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val model = parts.settings(session, account, call.separator.firstOrNull() ?: '.') ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val id = ids.incrementAndFetch()
                accountPages.put(id, HeldAccount(model, account.key))
                scope.launch { model.refresh() }
                Answer.SettingsOpened(id)
            }
            is Call.SettingsClose -> accountPages.remove(call.settings)?.close().done(Answer.Why.NO_SUCH_ACCOUNT_PAGE)
            is Call.SettingsRefresh -> onPage(call.settings) { it.refresh() }
            is Call.SettingsRename -> onPage(call.settings) { it.rename(call.label) }
            is Call.SettingsSignOut -> {
                val held = accountPages[call.settings] ?: return noPage
                held.model.signOut(call.family)?.let { out -> signedOutHere(parts, call.settings, out) } ?: Answer.Done
            }
            is Call.SettingsSignOutOthers -> onPage(call.settings) { it.signOutOthers() }
            is Call.SettingsSignOutAndRemoveKey -> onPage(call.settings) { it.signOutAndRemoveKey(call.family) }
            is Call.SettingsRemoveSavedKey -> onPage(call.settings) { it.removeSavedKey(call.device) }
            is Call.SettingsRemoveOtherSavedKeys -> onPage(call.settings) { it.removeEveryOtherSavedKey() }
            is Call.SettingsStaySignedIn -> accountPages[call.settings]?.model?.stay(call.level)?.answer()?.also { managed(parts) } ?: noPage
            is Call.SettingsPasswordTrust -> accountPages[call.settings]?.model?.trustWithPassword(call.password)?.answer()?.also { managed(parts) } ?: noPage
            is Call.SettingsStopTrusting -> onPage(call.settings) { it.stopTrusting() }.also { managed(parts) }
            is Call.SettingsOfflineLimit -> onPage(call.settings) { it.setOfflineLimit(call.amount, call.unit) }
            is Call.SettingsOfflineLimitOff -> onPage(call.settings) { it.turnOfflineLimitOff() }
            is Call.SettingsClearCache -> onPage(call.settings) { it.clearOfflineCache() }
            is Call.SettingsOfflineRemove -> onPage(call.settings) { it.unpinOffline(call.node) }
            is Call.SettingsAskRemoveAll -> onPage(call.settings) { it.askRemoveAllOffline() }
            is Call.SettingsRemoveAll -> onPage(call.settings) { it.removeAllOffline() }
            is Call.SettingsCancelRemoveAll -> onPage(call.settings) { it.cancelRemoveAllOffline() }
            is Call.SettingsSignOutHere -> {
                val held = accountPages[call.settings] ?: return noPage
                signedOutHere(parts, call.settings, held.model.signOutHere())
            }
            is Call.ShellChoose -> shell?.choose(call.application).done()
            is Call.ShellSwitchSpace -> shell?.switchSpace(call.scopeId).done()
            is Call.ShellEnter -> shell?.enter(call.folder).done()
            is Call.ShellFilter -> shell?.filter(call.application).done()
            Call.ShellBack -> shell?.goBack().done()
            is Call.ShellSelect -> shell?.select(call.tab).done()
            is Call.ShellOpen -> shell?.open(call.ref).done()
            is Call.ShellOpenAtTop -> shell?.openAtTop(call.ref).done()
            is Call.ShellOpenPerson -> shell?.openPerson(call.userId).done()
            is Call.ShellOpenBookmark -> shell?.openBookmark(call.node).done()
            is Call.ShellSearch -> shell?.search(call.on).done()
            // a person's refresh (F5): the members too, a picture replaced since among them
            Call.ShellRefresh -> shell?.refresh(people = true).done()
            Call.ShellOfflineGoneSeen -> shell?.goneSeen().done()
            Call.HomeGroups -> shell?.current?.let { Answer.Groups(it.lists.groups(it.shell)) } ?: noTenant
            Call.SearchOpen -> tenant.value?.models?.search?.let { make ->
                val id = ids.incrementAndFetch()
                searches.put(id, HeldSearch(make(parts.words)))
                Answer.SearchOpened(id)
            } ?: noTenant
            is Call.SearchFocus -> searches[call.search]?.model?.focus().done(Answer.Why.NO_SUCH_SEARCH)
            is Call.SearchChoose -> searches[call.search]?.model?.choose(call.index).done(Answer.Why.NO_SUCH_SEARCH)
            is Call.SearchRemoveChip -> searches[call.search]?.model?.removeChip(call.index).done(Answer.Why.NO_SUCH_SEARCH)
            is Call.SearchRemoveLastChip -> searches[call.search]?.model?.removeLastChip().done(Answer.Why.NO_SUCH_SEARCH)
            is Call.SearchMore -> searches[call.search]?.model?.more().done(Answer.Why.NO_SUCH_SEARCH)
            is Call.SearchOpenResult -> searches[call.search]?.model?.open(call.index).done(Answer.Why.NO_SUCH_SEARCH)
            is Call.SearchClose -> searches[call.search]?.model?.close().done(Answer.Why.NO_SUCH_SEARCH)
            is Call.PotOpen -> tenant.value?.models?.pot?.let { make ->
                val id = ids.incrementAndFetch()
                val pot = make()
                pots.put(id, pot)
                scope.launch { pot.open(call.ref) }
                Answer.PotOpened(id)
            } ?: noTenant
            is Call.PotRefresh -> pots[call.pot]?.refresh().done(Answer.Why.NO_SUCH_POT)
            is Call.PotRecord -> pots[call.pot]?.let { pot -> Answer.Said(if (pot.record(call.rows)) null else pot.refusal()) } ?: noPot
            is Call.PotRestore -> pots[call.pot]?.let { pot -> Answer.Said(if (pot.restore(call.entry)) null else pot.refusal()) } ?: noPot
            is Call.PotHistory -> pots[call.pot]?.let { pot ->
                val view = pot.current?.view ?: return noPot
                val rows = view.history(call.entry).takeIf { it.isNotEmpty() } ?: return Answer.Refused(Answer.Why.NO_SUCH_ENTRY)
                Answer.EntryHistory(
                    rows.map { HistoryShown(view.historyLine(it), it.writer, it.at) },
                    view.deletedEntries.any { it.id == call.entry }, pot.restorable(call.entry), view.purgeAfter(call.entry),
                )
            } ?: noPot
            is Call.PotClose -> pots.remove(call.pot)?.close().also {
                editors.removeIf { it.pot == call.pot }.forEach { it.model.close() }
                settings.removeIf { it.pot == call.pot }
            }.done(Answer.Why.NO_SUCH_POT)
            is Call.PotSettingsOpen -> pots[call.pot]?.settings()?.let { model ->
                val id = ids.incrementAndFetch()
                settings.put(id, HeldSettings(call.pot, model))
                Answer.PotSettingsOpened(id)
            } ?: noPot
            is Call.PotSettingsClose -> settings.remove(call.settings)?.model?.clear().done(Answer.Why.NO_SUCH_SETTINGS)
            is Call.PotSettingsCurrency -> stored(call.settings) { it.setBaseCurrency(call.code) }
            is Call.PotSettingsAddCategory -> stored(call.settings) { it.addCategory(call.name) }
            is Call.PotSettingsRenameCategory -> stored(call.settings) { it.renameCategory(call.category, call.name) }
            is Call.PotSettingsAddPerson -> stored(call.settings) { it.addPerson(call.name) }
            is Call.PotSettingsAddMember -> stored(call.settings) { it.addMember(call.userId) }
            is Call.PotSettingsRename -> stored(call.settings) { it.rename(call.person, call.name) }
            is Call.PotSettingsToggle -> stored(call.settings) { it.toggleActive(call.person) }
            is Call.PotSettingsAskErase -> settings[call.settings]?.model?.askErase(call.person).done(Answer.Why.NO_SUCH_SETTINGS)
            is Call.PotSettingsErase -> stored(call.settings) { it.erase() }
            is Call.PotSettingsCancelAsk -> settings[call.settings]?.model?.cancelAsk().done(Answer.Why.NO_SUCH_SETTINGS)
            is Call.PotCreateOpen -> tenant.value?.models?.createPot?.let { make ->
                val sheet = make(call.language) ?: return noTenant
                val id = ids.incrementAndFetch()
                creates.put(id, HeldCreate(sheet))
                Answer.PotCreateOpened(id)
            } ?: noTenant
            is Call.PotCreateClose -> creates.remove(call.create)?.sheet?.close().done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateName -> named(call)
            is Call.PotCreateSpace -> creates[call.create]?.sheet?.space(call.scopeId).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateCurrency -> creates[call.create]?.sheet?.currency(call.code).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateAddTyped -> creates[call.create]?.let { Answer.Stored(it.sheet.addTyped(call.name), null) } ?: noCreate
            is Call.PotCreateAddMember -> creates[call.create]?.sheet?.addMember(call.userId).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateRemove -> creates[call.create]?.sheet?.remove(call.index).done(Answer.Why.NO_SUCH_CREATE)
            is Call.PotCreateCreate -> creates[call.create]?.let { c ->
                val created = c.sheet.create(c.name.value, call.typed)
                Answer.Stored(created, c.sheet.current.notice)
            } ?: noCreate
            is Call.EditorOpen -> pots[call.pot]?.let { pot ->
                val separator = call.separator.firstOrNull() ?: '.'
                val model = pot.editor(call.entry, separator) ?: return Answer.Refused(Answer.Why.NO_SUCH_ENTRY)
                val id = ids.incrementAndFetch()
                editors.put(id, HeldEditor(call.pot, model, separator))
                Answer.EditorOpened(id)
            } ?: noPot
            is Call.EditorChange -> edit(call)
            is Call.EditorSave -> editors[call.editor]?.model?.save()?.let { Answer.EditorSaved(it.done, it.notice) } ?: noEditor
            is Call.EditorAskDelete -> editors[call.editor]?.model?.askDelete().done(Answer.Why.NO_SUCH_EDITOR)
            is Call.EditorDelete -> editors[call.editor]?.model?.let { e -> e.delete().let { Answer.EditorSaved(it, if (it) null else e.current.notice) } } ?: noEditor
            is Call.EditorCancelAsk -> editors[call.editor]?.model?.cancelAsk().done(Answer.Why.NO_SUCH_EDITOR)
            is Call.EditorClose -> editors.remove(call.editor)?.model?.close().done(Answer.Why.NO_SUCH_EDITOR)
            is Call.EditorAddReceipts -> editors[call.editor]?.model?.let { e ->
                // each file read by the core at the path given, never more than a receipt may hold
                val picked = call.paths.map { path ->
                    me.raddatz.veyq.mobile.expenses.Receipts.picked(path, runCatching { parts.files.read(path, me.raddatz.veyq.mobile.expenses.Receipts.MAX_BYTES.toInt()) }.getOrNull())
                }
                e.addReceipts(picked)
                Answer.Done
            } ?: noEditor
            is Call.EditorDropReceipt -> editors[call.editor]?.model?.dropReceipt(call.receipt).done(Answer.Why.NO_SUCH_EDITOR)
            is Call.ReceiptOpen -> pots[call.pot]?.let { pot ->
                val bytes = pot.openReceipt(call.receipt) ?: return Answer.Refused(Answer.Why.NO_RECEIPT)
                payload(bytes)
                Answer.Bytes(me.raddatz.veyq.mobile.expenses.Receipts.mediaTypeOf(bytes))
            } ?: noPot
            is Call.ReceiptPages -> Answer.ReceiptPages(me.raddatz.veyq.mobile.expenses.Receipts.pagesShown(call.total), me.raddatz.veyq.mobile.expenses.Receipts.pagesCut(call.total))
            is Call.ReceiptSave -> pots[call.pot]?.let { pot ->
                val bytes = pot.openReceipt(call.receipt) ?: return Answer.Refused(Answer.Why.NO_RECEIPT)
                val written = runCatching { parts.files.write(call.path) { sink -> sink(bytes); true } }.getOrDefault(false)
                if (written) Answer.Done else Answer.Refused(Answer.Why.NOT_WRITTEN)
            } ?: noPot
            is Call.PotDownload -> pots[call.pot]?.let { pot ->
                val written = parts.files.write(call.path) { sink -> pot.download(call.deletedName, sink) != null }
                if (written) Answer.Done else Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_POT)
            is Call.PotArchiveName -> pots[call.pot]?.let { pot ->
                pot.archiveName()?.let(Answer::Name) ?: Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_POT)
            is Call.AvatarPicture -> tenant.value?.models?.let { t ->
                val picture = t.pictures.value[call.userId] ?: return Answer.Refused(Answer.Why.NO_PICTURE)
                payload(picture)
                Answer.Bytes(Sniff.image(picture)?.mime)
            } ?: noTenant
            Call.PictureDecode -> {
                val png = inbound?.let(parts.webp) ?: return Answer.Refused(Answer.Why.NOT_A_PICTURE)
                payload(png)
                Answer.Bytes("image/png")
            }
            is Call.ReaderRead -> readers[call.reader]?.let { r ->
                if (!stillHeld(call.reader, r)) return Answer.Refused(Answer.Why.NO_SUCH_READER)
                payload(r.read(call.position, call.length))
                Answer.Bytes(null)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_READER)
            is Call.ReaderClose -> readers.remove(call.reader)?.let {
                pageReaders.remove(call.reader)
                lightboxReaders.remove(call.reader)
                bannerReaders.remove(call.reader)
                Answer.Done
            } ?: Answer.Refused(Answer.Why.NO_SUCH_READER)
            is Call.FileOpen -> tenant.value?.models?.file?.let { make ->
                val id = ids.incrementAndFetch()
                filePages.put(id, make(call.node))
                Answer.FileOpened(id)
            } ?: noTenant
            is Call.FileBytes -> filePages[call.file]?.let { f ->
                val shown = f.current.shown
                val bytes = f.bytes(shown) ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes)
                Answer.Bytes(f.mediaType(shown))
            } ?: noFile
            is Call.FilePages -> Answer.ReceiptPages(me.raddatz.veyq.mobile.depot.FileView.pagesShown(call.total), me.raddatz.veyq.mobile.depot.FileView.pagesCut(call.total))
            is Call.FileClose -> filePages.remove(call.file)?.also { closeReadersOf(call.file) }?.close().done(Answer.Why.NO_SUCH_FILE)
            is Call.FileReader -> filePages[call.file]?.let { f ->
                val medium = f.current.shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Media
                    ?: return Answer.Refused(Answer.Why.NO_MEDIUM)
                openReader(medium.reader, medium.mediaType).also { if (it is Answer.Reader) pageReaders.put(it.reader, call.file) }
            } ?: noFile
            is Call.FileSave -> filePages[call.file]?.let { f ->
                val save = f.save { produce -> parts.files.write(call.path, produce) } ?: return Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
                val id = ids.incrementAndFetch()
                saves.put(id, save)
                Answer.Saving(id)
            } ?: noFile
            is Call.SaveCancel -> saves[call.save]?.let { if (it.cancel()) Answer.Done else Answer.Refused(Answer.Why.SAVE_ENDED) }
                ?: Answer.Refused(Answer.Why.NO_SUCH_SAVE)
            is Call.FileChooseReplace -> filePages[call.file]?.let { if (it.chooseReplace()) Answer.Done else Answer.Refused(Answer.Why.NO_REPLACE) } ?: noFile
            is Call.UploadFiles -> uploads.value?.let { u ->
                // each path opened by the core and read at offsets: nothing of a file crosses, none is held whole; a path that
                // does not read is said by name, never dropped unsaid
                val sources = call.paths.map { it to parts.files.source(it) }
                val notRead = sources.filter { it.second == null }.map { pathName(it.first) }
                Answer.Queued(u.upload(call.scopeId, call.parentId, call.label, sources.mapNotNull { it.second }), Notices.notRead(notRead))
            } ?: noTenant
            is Call.UploadFolder -> uploads.value?.let { u ->
                val found = parts.files.walk(call.path) ?: return Answer.Said(Notices.notRead(listOf(pathName(call.path))))
                Answer.Queued(u.uploadFolder(call.scopeId, call.parentId, null, call.path, found))
            } ?: noTenant
            is Call.UploadReplace -> uploads.value?.let { u ->
                val page = filePages[call.file] ?: return noFile
                val source = parts.files.source(call.path) ?: return Answer.Said(Notices.notRead(listOf(pathName(call.path))))
                if (u.replace(page, source)) Answer.Queued(1) else Answer.Refused(Answer.Why.NOT_CHOSEN)
            } ?: noTenant
            is Call.UploadCancel -> uploads.value?.cancel(call.item).done()
            is Call.UploadRetry -> uploads.value?.retry(call.item).done()
            Call.UploadCancelAll -> uploads.value?.cancelAll().done()
            is Call.UploadAcknowledge -> uploads.value?.acknowledge(call.batch).done()
            is Call.UploadTakeOffer -> uploads.value?.let { u ->
                val said = u.current.saying.firstOrNull { it.id == call.said } ?: return Answer.Refused(Answer.Why.NO_SUCH_SAID)
                u.take(said)
                Answer.Done
            } ?: noTenant
            is Call.UploadSeen -> uploads.value?.seen(call.said).done()
            is Call.HeaderOpen -> tenant.value?.models?.header?.let { make ->
                val id = ids.incrementAndFetch()
                headers.put(id, HeldHeader(make(call.node, call.facts, ::closeOpen), call.maxButtons))
                Answer.HeaderOpened(id)
            } ?: noTenant
            is Call.HeaderFacts -> headers[call.header]?.let { h ->
                h.file.value = call.file
                h.gallery.value = call.gallery
                h.model.update(call.facts)
                Answer.Done
            } ?: noHeader
            is Call.HeaderClose -> headers.remove(call.header)?.let { Answer.Done } ?: Answer.Refused(Answer.Why.NO_SUCH_HEADER)
            is Call.HeaderPin -> headers[call.header]?.let { Answer.Said(it.model.toggleFavorite()) } ?: noHeader
            is Call.HeaderOffline -> headers[call.header]?.let { Answer.Said(it.model.toggleOffline()) } ?: noHeader
            is Call.HeaderOfflineConfirm -> headers[call.header]?.let { Answer.Said(it.model.confirmOffline()) } ?: noHeader
            is Call.HeaderOfflineCancel -> headers[call.header]?.let { it.model.cancelOffline(); Answer.Done } ?: noHeader
            is Call.HeaderTrash -> headers[call.header]?.let { h -> h.model.trash().let { Answer.Trashed(it, h.model.current.undo != null) } } ?: noHeader
            is Call.HeaderUndo -> headers[call.header]?.let { Answer.Said(it.model.undoTrash()) } ?: noHeader
            is Call.HeaderRename -> headers[call.header]?.let { it.model.rename(call.name).let { r -> Answer.Renamed(r.problem, r.notice) } } ?: noHeader
            is Call.HeaderCoverPicture -> headers[call.header]?.let { h ->
                val picture = h.model.coverPicture() ?: return Answer.Refused(Answer.Why.NO_COVER)
                payload(picture)
                Answer.Bytes(Sniff.image(picture)?.mime)
            } ?: noHeader
            is Call.HeaderCoverSave -> headers[call.header]?.let { h ->
                val jpeg = call.path?.let { path ->
                    runCatching { parts.files.read(path, DeviceFiles.COVER_MOST) }.getOrNull() ?: return Answer.Said(Notice(Msg.COVER_FAILED))
                }
                Answer.Said(h.model.saveCover(jpeg, call.crop))
            } ?: noHeader
            is Call.HeaderCoverRemove -> headers[call.header]?.let { Answer.Said(it.model.removeCover()) } ?: noHeader
            is Call.CoverPose -> {
                val frame = CoverFrame(call.imageWidth, call.imageHeight, call.frameWidth, call.frameHeight)
                val start = call.at?.let(frame::clamp) ?: call.crop?.let(frame::pose) ?: frame.centred()
                val pose = frame.zoom(frame.pan(start, call.panX, call.panY), call.zoom)
                val scale = frame.baseScale * pose.zoom
                Answer.CoverPosed(pose, call.imageWidth * scale, call.imageHeight * scale, frame.crop(pose))
            }
            is Call.CoverCut -> CoverFrame.pixels(call.crop, call.width, call.height).let { Answer.CoverCut(it[0], it[1], it[2], it[3]) }
            is Call.CoverFit -> NodeCover.encodedSize(call.width, call.height).let { Answer.CoverFit(it[0], it[1], NodeCover.QUALITY, NodeCover.QUALITY_PERCENT) }
            is Call.ReportOpen -> tenant.value?.models?.report?.let { make ->
                val id = ids.incrementAndFetch()
                reports.put(id, make(call.node))
                Answer.ReportOpened(id)
            } ?: noTenant
            is Call.ReportClose -> reports.remove(call.report)?.let { Answer.Done } ?: noReport
            is Call.ReportWhole -> reports[call.report]?.whole(call.whole).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportCategory -> reports[call.report]?.category(call.category).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportReason -> reports[call.report]?.reason(call.reason).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportGoodFaith -> reports[call.report]?.goodFaith(call.on).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportContact -> reports[call.report]?.contact(call.contact).done(Answer.Why.NO_SUCH_REPORT)
            is Call.ReportSend -> reports[call.report]?.let { Answer.Said(it.send().problem) } ?: noReport
            is Call.HeaderDownload -> headers[call.header]?.let { h ->
                val download = tenant.value?.models?.download ?: return noTenant
                var leftOut: List<NodeExport.Skipped>? = null
                runCatching { parts.files.write(call.path) { sink -> download(h.model.node, call.deletedName, sink).also { leftOut = it } != null } }
                Answer.Downloaded(leftOut != null, Notices.downloaded(h.model.node.name, leftOut), Notices.downloadLeftOut(leftOut))
            } ?: noHeader
            is Call.MoveOpen -> tenant.value?.models?.move?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.node, call.copy, pots.values().firstOrNull { it.current?.ref?.nodeId == call.node.nodeId }, call.deletedName)
                moves.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.MoveOpened(id)
            } ?: noTenant
            is Call.MoveClose -> moves.remove(call.move)?.let { Answer.Done } ?: noMove
            is Call.MoveGo -> moves[call.move]?.go(call.at).done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveUp -> moves[call.move]?.up().done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveHere -> moves[call.move]?.let { sent(it.choose()) } ?: noMove
            is Call.MoveConfirm -> moves[call.move]?.let { sent(it.confirm()) } ?: noMove
            is Call.MoveCancelConfirm -> moves[call.move]?.cancelConfirm().done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveUndo -> moves[call.move]?.let { Answer.Said(it.undo()) } ?: noMove
            is Call.ShellCreateFolder -> tenant.value?.models?.createFolder?.let { Answer.Said(it(call.name)) } ?: noTenant
            is Call.TrashOpen -> tenant.value?.models?.trash?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.scopeId)
                trashes.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.TrashOpened(id)
            } ?: noTenant
            is Call.TrashClose -> trashes.remove(call.trash)?.let { Answer.Done } ?: noTrash
            is Call.TrashRestore -> onRow(call.trash, call.row) { sheet, row -> sheet.restore(row) }
            is Call.TrashRetry -> onRow(call.trash, call.row) { sheet, row -> sheet.retry(row) }
            is Call.TrashPurge -> onRow(call.trash, call.row) { sheet, row -> sheet.purge(row) }
            is Call.TrashType -> trashes[call.trash]?.type(call.name).done(Answer.Why.NO_SUCH_TRASH)
            is Call.TrashCancelRename -> trashes[call.trash]?.cancelRename().done(Answer.Why.NO_SUCH_TRASH)
            is Call.ShareOpen -> tenant.value?.models?.share?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.node)
                shares.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.ShareOpened(id)
            } ?: noTenant
            is Call.ShareClose -> shares.remove(call.share)?.let { Answer.Done } ?: noShare
            is Call.ShareOpenTopLevel -> tenant.value?.models?.topLevelShare?.let { make ->
                val id = ids.incrementAndFetch()
                shares.put(id, make(call.scopeId))
                Answer.ShareOpened(id)
            } ?: noTenant
            is Call.DropBoxOpen -> tenant.value?.models?.dropBox?.let { make ->
                val id = ids.incrementAndFetch()
                dropBoxes.put(id, HeldDropBox(make(call.node)))
                Answer.DropBoxOpened(id)
            } ?: noTenant
            is Call.DropBoxClose -> dropBoxes.remove(call.box)?.page?.close().done(Answer.Why.NO_SUCH_DROPBOX)
            is Call.DropBoxMoveOut -> dropBoxes[call.box]?.let { held ->
                val folder = held.page.current.folders?.firstOrNull { it.node.id == call.folder } ?: return noFolder
                val make = tenant.value?.models?.move ?: return noTenant
                val id = ids.incrementAndFetch()
                val sheet = make(held.page.at(folder), false, null, "")
                moves.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.MoveOpened(id)
            } ?: noDropBox
            is Call.DropBoxAskDelete -> dropBoxes[call.box]?.let { held ->
                val folder = held.page.current.folders?.firstOrNull { it.node.id == call.folder } ?: return noFolder
                held.page.askDelete(folder)
                Answer.Done
            } ?: noDropBox
            is Call.DropBoxCancelAsk -> dropBoxes[call.box]?.page?.cancelAsk().done(Answer.Why.NO_SUCH_DROPBOX)
            is Call.DropBoxDelete -> dropBoxes[call.box]?.let { held ->
                val folder = held.page.current.folders?.firstOrNull { it.node.id == call.folder } ?: return noFolder
                // the core hands the node only where its delete was asked; the header trashes it with a guest folder's facts
                val node = held.page.delete(folder) ?: return Answer.Refused(Answer.Why.NOT_ASKED)
                val make = tenant.value?.models?.header ?: return noTenant
                val header = make(node, held.page.folderFacts, ::closeOpen)
                held.trashed = header
                header.trash().let { Answer.Trashed(it, header.current.undo != null) }
            } ?: noDropBox
            is Call.DropBoxUndo -> dropBoxes[call.box]?.let { Answer.Said(it.trashed?.undoTrash()) } ?: noDropBox
            is Call.ShareAdd -> shares[call.share]?.addLink().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareEdit -> shares[call.share]?.edit(call.link).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareRole -> shares[call.share]?.role(call.role).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareLabel -> shares[call.share]?.label(call.label).done(Answer.Why.NO_SUCH_SHARE)
            is Call.SharePassword -> shares[call.share]?.password(call.password).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareLockOn -> shares[call.share]?.passwordOn(call.on).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareEndOn -> shares[call.share]?.endOn(call.on).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareEndDay -> shares[call.share]?.endDay(call.day).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareCancelForm -> shares[call.share]?.cancelForm().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareSave -> shares[call.share]?.let { Answer.Said(it.save().problem) } ?: noShare
            is Call.ShareFreshSeen -> shares[call.share]?.freshSeen().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareAskRenew -> shares[call.share]?.askRenew(call.link).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareAskDelete -> shares[call.share]?.askDelete(call.link).done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareCancelAsk -> shares[call.share]?.cancelAsk().done(Answer.Why.NO_SUCH_SHARE)
            is Call.ShareRenew -> shares[call.share]?.let { Answer.Said(it.renew().notice) } ?: noShare
            is Call.ShareDelete -> shares[call.share]?.let { Answer.Said(it.delete().notice) } ?: noShare
            is Call.ShareSwitchOff -> shares[call.share]?.let { Answer.Said(it.switchOff(call.link).notice) } ?: noShare
            is Call.ShareReactivate -> shares[call.share]?.let { Answer.Said(it.reactivate(call.link).notice) } ?: noShare
            is Call.ScanOpen -> {
                val id = ids.incrementAndFetch()
                scans.put(id, when (call.kind) {
                    ScanKind.SCHEME -> me.raddatz.veyq.mobile.qr.QrScan(accepts = me.raddatz.veyq.mobile.qr.QrSchemes::of, decode = parts.decode)
                    // the sign-in's own scan (the phones' too): its accepts reads the kit and hands it to the sign-in
                    // (`takeKit`), so the kit is taken as the code is read, watched or not, and never leaves the core
                    ScanKind.KIT -> session.scanKit(call.server, parts.decode)
                    // a safety code: only `veyqid:` is taken, held here for `Trust.CompareScanned`, found as its scheme
                    // a flow's Secret Key: the kit goes to that flow's intake (`KeyIntake.scan`), never across the bridge
                    ScanKind.FLOW_KIT -> (call.link?.let { flowIntake(it) } ?: return noFlow).scan(decode = parts.decode)
                    ScanKind.SAFETY -> me.raddatz.veyq.mobile.qr.QrScan(
                        accepts = { text -> SafetyCode(text).takeIf { me.raddatz.veyq.mobile.qr.QrSchemes.of(text) == "veyqid" } },
                        decode = parts.decode,
                    )
                    // the link's own scan: what it reads goes into the link (`DeviceLink.scan`), the found scheme its direction's
                    ScanKind.LINK -> {
                        val link = call.link?.let { links[it] }?.model ?: return noLink
                        val scan = link.scan ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                        val marker = if (link.role == DeviceLink.Role.SCAN_FROM_TRUSTED) me.raddatz.veyq.mobile.crypto.PairingMarker.ADD_DEVICE
                        else me.raddatz.veyq.mobile.crypto.PairingMarker.SIGN_IN
                        linkScans.put(id, marker.prefix.removeSuffix(":"))
                        scan
                    }
                })
                Answer.ScanOpened(id)
            }
            is Call.ScanCamera -> scans[call.scan]?.camera(call.camera).done(Answer.Why.NO_SUCH_SCAN)
            is Call.ScanFrame -> scans[call.scan]?.let { s ->
                val plane = inbound ?: return Answer.Refused(Answer.Why.NOT_A_FRAME)
                // asked before the plane is fitted: a frame the scan would drop costs no scaling
                if (!s.ready) return Answer.FrameTaken(false)
                val frame = runCatching { me.raddatz.veyq.mobile.qr.QrFrames.fit(call.width, call.height, call.stride, plane) }
                    .getOrElse { return Answer.Refused(Answer.Why.NOT_A_FRAME) }
                Answer.FrameTaken(s.offer(frame))
            } ?: noScan
            // a link's scan is the link's: dropped here, closed with the link (`Link.Close`)
            is Call.ScanClose -> scans.remove(call.scan)?.let { s -> if (linkScans.remove(call.scan) == null) s.close() }.done(Answer.Why.NO_SUCH_SCAN)
            is Call.LinkShowForNewDevice -> trustedLink(parts, call.connection, DeviceLink.Role.SHOW_FOR_NEW_DEVICE)
            is Call.LinkScanSignInCode -> trustedLink(parts, call.connection, DeviceLink.Role.SCAN_SIGN_IN_CODE)
            is Call.LinkScanFromTrusted -> newDeviceLink(parts, session.scanFromTrusted(call.server, parts.decode))
            is Call.LinkShowForSignIn -> newDeviceLink(parts, session.showForSignIn(call.server))
            is Call.LinkCode -> links[call.link]?.let { drawnCode(it.model.matrix(), call.px, payload) } ?: noLink
            is Call.LinkConfirm -> links[call.link]?.let { taken(it.model.confirm(call.label)) } ?: noLink
            is Call.LinkEnterCode -> links[call.link]?.let { taken(it.model.enterCode(call.code)) } ?: noLink
            is Call.LinkClose -> links.remove(call.link)?.let { closeLink(it); Answer.Done } ?: noLink
            is Call.ReceivedOpen, is Call.ReceivedPassword, is Call.ReceivedAcceptTerms, is Call.ReceivedAcceptNote, is Call.ReceivedOpenNode, is Call.ReceivedBack, is Call.ReceivedStay, is Call.ReceivedLeave, is Call.ReceivedClose, is Call.ReceivedName, is Call.ReceivedForgetGuest, is Call.ReceivedKeep, is Call.ReceivedUpload, is Call.ReceivedReplace, is Call.ReceivedNewFolder, is Call.ReceivedReportNode, is Call.ReceivedReport, is Call.ReceivedKeepPlaces, is Call.ReceivedReportShown -> receivedCall(parts, call)
            is Call.BookmarkOpen -> openBookmark(parts, call.node)
            is Call.BookmarkOwnPage -> bookmarks[call.bookmark]?.let { b ->
                heldReceived(b.page.openOwnPage(tenant.value?.models?.session))
            } ?: noBookmark
            is Call.BookmarkReportShared -> bookmarks[call.bookmark]?.let { b -> heldReceived(b.page.reportShared()) } ?: noBookmark
            is Call.BookmarkClose -> bookmarks.remove(call.bookmark)?.let { b -> closeBookmark(b); Answer.Done } ?: noBookmark
            is Call.BookmarkCheck -> {
                val r = me.raddatz.veyq.mobile.sharing.ReceivedLink.parse(call.text)
                val problem = (r as? me.raddatz.veyq.mobile.sharing.ReceivedLink.Parsed.Refused)?.problem
                Answer.BookmarkChecked(
                    problem?.let(me.raddatz.veyq.mobile.sharing.BookmarkCreate::wordOf),
                    (r as? me.raddatz.veyq.mobile.sharing.ReceivedLink.Parsed.Ok)?.link?.host,
                    groupSpace(call.scopeId),
                )
            }
            is Call.BookmarkCreate -> tenant.value?.models?.session?.let { t ->
                val dialog = me.raddatz.veyq.mobile.sharing.BookmarkCreate(
                    me.raddatz.veyq.mobile.sharing.BookmarkCreate.into(t, call.scopeId, call.parentId), groupSpace(call.scopeId),
                )
                dialog.link(call.text)
                dialog.password(call.password)
                dialog.name(call.name)
                if (!dialog.state.value.canSave) return Answer.Refused(Answer.Why.NOT_OFFERED)
                if (!dialog.save()) return Answer.Refused(Answer.Why.NOT_TAKEN)
                val made = dialog.state.value
                Answer.Created(NodeAt(call.scopeId, made.created!!, me.raddatz.veyq.mobile.AppKinds.BOOKMARK_KIND, made.nameShown), null)
            } ?: noTenant
            is Call.ShellCreateMemory -> tenant.value?.models?.createMemory?.let { make ->
                make(call.scopeId, call.parentId, call.name, call.wasThere, call.autoPlace).let { Answer.Created(it.node, it.notice) }
            } ?: noTenant
            is Call.ShellRowCover -> tenant.value?.models?.rowCover?.let { read ->
                val row = read(call.nodeId, call.held) ?: return Answer.Refused(Answer.Why.NO_COVER)
                row.bytes?.let { b -> payload(b) }
                Answer.RowCover(row.frame, row.crop, row.bytes != null, row.bytes?.let { Sniff.image(it)?.mime })
            } ?: noTenant
            is Call.GalleryOpen -> tenant.value?.models?.let { t ->
                val make = t.gallery ?: return noTenant
                val uploads = mediaUploads.value ?: return noTenant
                if (call.node.kind != me.raddatz.veyq.mobile.memories.GalleryModel.MEMORY &&
                    call.node.kind != me.raddatz.veyq.mobile.memories.GalleryModel.CHAPTER
                ) return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                val audio = parts.audio
                val music = player.value?.let { p -> audio?.let { a -> me.raddatz.veyq.mobile.memories.GalleryModel.Music(p, a::untitled) } }
                galleries.put(id, make(call.node, uploads, music))
                Answer.GalleryOpened(id)
            } ?: noTenant
            is Call.GalleryClose -> galleries.remove(call.gallery)?.let { g ->
                closeLightboxesOf(call.gallery)
                closeFactsOf(call.gallery)
                closeCurationOf(call.gallery)
                musics.removeIf { it.gallery == call.gallery }.forEach { it.model.close() }
                buckets.removeIf { it.gallery == call.gallery }
                g.close()
                Answer.Done
            } ?: noGallery
            is Call.GalleryReload -> galleries[call.gallery]?.reload().done(Answer.Why.NO_SUCH_GALLERY)
            is Call.GalleryTile -> galleries[call.gallery]?.let { g ->
                val bytes = g.tile(call.mediaId) ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes)
                Answer.Bytes(Sniff.image(bytes)?.mime)
            } ?: noGallery
            is Call.GalleryChapterCover -> galleries[call.gallery]?.let { g ->
                val bytes = g.chapterCover(call.nodeId) ?: return Answer.Refused(Answer.Why.NO_COVER)
                payload(bytes)
                Answer.Bytes(Sniff.image(bytes)?.mime)
            } ?: noGallery
            is Call.GalleryLayout -> galleries[call.gallery]?.let { g ->
                val now = g.state.value
                // the rows the filter shows (ADR 0149 decision 5), as `GalleryShown.rows` names them: the host builds a tile from those
                val laid = me.raddatz.veyq.mobile.memories.GalleryModel.layout(now.shown.orEmpty(), now.uploading, call.width)
                val shown = laid.window(call.top, call.viewHeight).map { laid.rows[it] }
                Answer.GridLayout(shown.map { r -> GridRow(r.index, r.top, r.height, r.tiles.map { GridTile(it.id, it.left, it.width) }) }, laid.height, laid.rows.size)
            } ?: noGallery
            is Call.GalleryTrash -> galleries[call.gallery]?.let { g -> trashed(g, g.trash(call.mediaId)) } ?: noGallery
            is Call.GalleryUndo -> galleries[call.gallery]?.let { Answer.Said(it.undo()) } ?: noGallery
            is Call.GalleryPurge -> galleries[call.gallery]?.let { Answer.Said(it.purge()) } ?: noGallery
            is Call.GalleryCancelAsk -> galleries[call.gallery]?.cancelAsk().done(Answer.Why.NO_SUCH_GALLERY)
            is Call.GallerySeen -> galleries[call.gallery]?.seen().done(Answer.Why.NO_SUCH_GALLERY)
            is Call.GallerySearchOpenedSeen -> galleries[call.gallery]?.searchOpenedSeen().done(Answer.Why.NO_SUCH_GALLERY)
            is Call.GalleryCreateChapter -> galleries[call.gallery]?.let { g -> g.createChapter(call.name).let { Answer.Created(it.node, it.notice) } }
                ?: noGallery
            is Call.MemoryUpload -> galleries[call.gallery]?.let { g ->
                if (!g.rights.upload) return Answer.Refused(Answer.Why.NOT_OFFERED)
                // each path opened by the core and read at offsets, named by its path for the host's tiers: nothing of a
                // file crosses, none is held whole
                val sources = call.paths.mapNotNull { path -> parts.files.source(path)?.let { PathSource(path, it) } }
                val queued = g.upload(sources)
                Answer.Queued(queued, me.raddatz.veyq.mobile.memories.MediaUploads.notTaken(call.paths.size - queued))
            } ?: noGallery
            is Call.LightboxOpen -> galleries[call.gallery]?.let { g ->
                // one lightbox a gallery: the one open before is closed by the gallery as this one opens
                closeLightboxesOf(call.gallery)
                val id = ids.incrementAndFetch()
                lightboxes.put(id, HeldLightbox(call.gallery, g, g.lightbox(call.index)))
                Answer.LightboxOpened(id)
            } ?: noGallery
            is Call.LightboxClose -> lightboxes.remove(call.lightbox)?.let { held ->
                dropReadersOf(call.lightbox)
                captureTimes.removeIf { it.lightbox == call.lightbox }.forEach { it.open.value = false }
                maps.removeIf { it.lightbox == call.lightbox }.forEach { it.close() }
                held.model.close()
                Answer.Done
            } ?: noLightbox
            is Call.LightboxGo -> lightboxes[call.lightbox]?.model?.go(call.index).done(Answer.Why.NO_SUCH_LIGHTBOX)
            is Call.LightboxPicture -> lightboxes[call.lightbox]?.let { held ->
                val bytes = held.model.picture(call.mediaId) ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes)
                Answer.Bytes(Sniff.image(bytes)?.mime)
            } ?: noLightbox
            is Call.LightboxSlideshow -> lightboxes[call.lightbox]?.model?.slideshow(call.on).done(Answer.Why.NO_SUCH_LIGHTBOX)
            is Call.LightboxDetails -> lightboxes[call.lightbox]?.let { held ->
                val d = held.model.details(call.mediaId) ?: return Answer.Refused(Answer.Why.NO_MEDIUM)
                Answer.MediumDetails(
                    d.rows.map(::detailRow), d.uploader?.let(me.raddatz.veyq.mobile.memories.MediaDetails::uploaderWords), d.uploaderAt, map = d.map != null,
                    profile = d.uploader?.profile,
                )
            } ?: noLightbox
            is Call.LightboxTrash -> lightboxes[call.lightbox]?.let { held -> trashed(held.page, held.model.trash()) } ?: noLightbox
            is Call.LightboxMark -> lightboxes[call.lightbox]?.let { held ->
                if (!held.model.canMark) return Answer.Refused(Answer.Why.NOT_OFFERED)
                Answer.Said(held.model.toggleMark())
            } ?: noLightbox
            is Call.LightboxReport -> lightboxes[call.lightbox]?.let { held ->
                val sheet = held.model.reportSheet() ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                reports.put(id, sheet)
                Answer.ReportOpened(id)
            } ?: noLightbox
            is Call.MarksToggle -> galleries[call.gallery]?.let { g ->
                if (!g.current.marking || !g.current.marksLoaded) return Answer.Refused(Answer.Why.NOT_OFFERED)
                Answer.Said(g.toggleMark(call.mediaId))
            } ?: noGallery
            is Call.MarksSeen -> galleries[call.gallery]?.markSeen().done(Answer.Why.NO_SUCH_GALLERY)
            is Call.FilterText -> galleries[call.gallery]?.let { g -> g.filter(g.current.filter.copy(text = call.text)); Answer.Done } ?: noGallery
            is Call.FilterChip -> galleries[call.gallery]?.let { g ->
                val (chip, rest) = suggestions(g).firstOrNull { me.raddatz.veyq.mobile.memories.GalleryFilter.key(it.first) == call.chip }
                    ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                g.filter(g.current.filter.let { it.copy(chips = it.chips + chip, text = rest) })
                Answer.Done
            } ?: noGallery
            is Call.FilterRemove -> galleries[call.gallery]?.let { g ->
                g.filter(g.current.filter.let { f -> f.copy(chips = f.chips.filter { me.raddatz.veyq.mobile.memories.GalleryFilter.key(it) != call.chip }) })
                Answer.Done
            } ?: noGallery
            is Call.FilterMode -> galleries[call.gallery]?.let { g ->
                if (!g.marksModeOffered) return Answer.Refused(Answer.Why.NOT_OFFERED)
                g.filter(g.current.filter.copy(mode = me.raddatz.veyq.mobile.memories.GalleryFilter.Mode.valueOf(call.mode.name)))
                Answer.Done
            } ?: noGallery
            is Call.FilterClear -> galleries[call.gallery]?.let { g -> g.filter(me.raddatz.veyq.mobile.memories.GalleryFilter.State()); Answer.Done } ?: noGallery
            is Call.BucketsOpen -> galleries[call.gallery]?.let { g ->
                val make = tenant.value?.models?.contributions ?: return noTenant
                if (!g.current.rights.buckets || !me.raddatz.veyq.mobile.memories.Contributions.OFFERED) return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                val model = make(g)
                buckets.put(id, HeldBuckets(call.gallery, model))
                model.read()
                Answer.BucketsOpened(id)
            } ?: noGallery
            is Call.BucketsPick -> buckets[call.buckets]?.model?.pick(call.bucketId).done(Answer.Why.NO_SUCH_BUCKETS)
            is Call.BucketsCreate -> buckets[call.buckets]?.let { Answer.BucketsDone(it.model.create(call.name)) } ?: noBuckets
            is Call.BucketsRename -> buckets[call.buckets]?.let { Answer.BucketsDone(it.model.rename(call.name)) } ?: noBuckets
            is Call.BucketsDelete -> buckets[call.buckets]?.let { Answer.BucketsDone(it.model.delete()) } ?: noBuckets
            is Call.BucketsMove -> buckets[call.buckets]?.let { Answer.BucketsDone(it.model.move(call.mediaIds, call.target)) } ?: noBuckets
            is Call.BucketsSeen -> buckets[call.buckets]?.model?.seen().done(Answer.Why.NO_SUCH_BUCKETS)
            is Call.BucketsLightbox -> buckets[call.buckets]?.let { held ->
                val page = galleries[held.gallery] ?: return noGallery
                // one lightbox a gallery, as `Lightbox.Open`'s: the one open before is closed as this one opens
                closeLightboxesOf(held.gallery)
                val box = held.model.lightbox(call.mediaId) ?: return Answer.Refused(Answer.Why.NO_MEDIUM)
                val id = ids.incrementAndFetch()
                lightboxes.put(id, HeldLightbox(held.gallery, page, box))
                Answer.LightboxOpened(id)
            } ?: noBuckets
            is Call.BucketsClose -> buckets.remove(call.buckets)?.let { Answer.Done } ?: noBuckets
            is Call.ViewSet -> tenant.value?.models?.shell?.let { s ->
                s.chooseView(me.raddatz.veyq.mobile.navigation.ViewMode.valueOf(call.mode.name))
                viewTurned.value++
                Answer.Done
            } ?: noTenant
            is Call.ViewTiles -> {
                val frames = me.raddatz.veyq.mobile.navigation.TileGrid.frames(call.width, call.count, call.labelHeight)
                Answer.Tiles(me.raddatz.veyq.mobile.navigation.TileGrid.columns(call.width), frames.map { TileFrame(it.x, it.y, it.size) })
            }
            is Call.LightboxStop -> lightboxes[call.lightbox]?.model?.stop().done(Answer.Why.NO_SUCH_LIGHTBOX)
            is Call.MediumReader -> lightboxes[call.lightbox]?.let { held ->
                val reader = held.model.play() ?: return Answer.Refused(Answer.Why.NO_MEDIUM)
                openReader(reader, held.model.current.row?.type).also { if (it is Answer.Reader) lightboxReaders.put(it.reader, call.lightbox) }
            } ?: noLightbox
            is Call.MediumSave -> lightboxes[call.lightbox]?.let { held ->
                val save = held.model.save { produce -> parts.files.write(call.path, produce) }
                    ?: return Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
                val id = ids.incrementAndFetch()
                saves.put(id, save)
                Answer.Saving(id)
            } ?: noLightbox
            is Call.GalleryMusic -> galleries[call.gallery]?.let { g ->
                if (g.music == null) return Answer.Refused(Answer.Why.NOT_OFFERED)
                g.toggleMusic()
                Answer.Done
            } ?: noGallery
            is Call.LightboxVideo -> lightboxes[call.lightbox]?.model?.videoPlaying(call.playing).done(Answer.Why.NO_SUCH_LIGHTBOX)
            Call.PlayerPause -> player.value?.pause().done(Answer.Why.NO_PLAYER)
            Call.PlayerResume -> player.value?.resume().done(Answer.Why.NO_PLAYER)
            Call.PlayerNext -> player.value?.next().done(Answer.Why.NO_PLAYER)
            Call.PlayerPrevious -> player.value?.previous().done(Answer.Why.NO_PLAYER)
            is Call.PlayerSeek -> player.value?.seek(call.positionMs).done(Answer.Why.NO_PLAYER)
            Call.PlayerStop -> player.value?.stop().done(Answer.Why.NO_PLAYER)
            Call.PlayerOpenNode -> player.value?.current?.nowPlaying?.route?.let { route -> shell?.open(route) }.done(Answer.Why.NO_PLAYER)
            is Call.MusicOpen -> galleries[call.gallery]?.let { g ->
                val make = tenant.value?.models?.musicModel ?: return noTenant
                val uploads = mediaUploads.value ?: return noTenant
                val audio = parts.audio
                if (g.music == null || audio == null) return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                musics.put(id, HeldMusic(call.gallery, make(g, uploads, audio.measure)))
                Answer.MusicOpened(id)
            } ?: noGallery
            is Call.MusicClose -> musics.remove(call.music)?.model?.close().done(Answer.Why.NO_SUCH_MUSIC)
            is Call.MusicPlay -> musics[call.music]?.model?.play(call.mediaId).done(Answer.Why.NO_SUCH_MUSIC)
            is Call.MusicRename -> musics[call.music]?.let { Answer.Said(it.model.rename(call.mediaId, call.name)) } ?: noMusic
            is Call.MusicRemove -> musics[call.music]?.let { held -> trashed(held.model.gallery, held.model.remove(call.mediaId)) } ?: noMusic
            is Call.MusicDownload -> musics[call.music]?.let { held ->
                val save = held.model.save(call.mediaId) { produce -> parts.files.write(call.path, produce) }
                    ?: return Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
                val id = ids.incrementAndFetch()
                saves.put(id, save)
                Answer.Saving(id)
            } ?: noMusic
            is Call.MusicReport -> musics[call.music]?.let { held ->
                val sheet = held.model.reportSheet(call.mediaId) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                reports.put(id, sheet)
                Answer.ReportOpened(id)
            } ?: noMusic
            is Call.MusicAdd -> musics[call.music]?.let { held ->
                // each path opened by the core and read at offsets, named by its path for the host's measure of its length
                val sources = call.paths.mapNotNull { path -> parts.files.source(path)?.let { PathSource(path, it) } }
                Answer.MusicAdded(held.model.add(sources))
            } ?: noMusic
            is Call.MusicSeen -> musics[call.music]?.model?.seen().done(Answer.Why.NO_SUCH_MUSIC)
            is Call.AudioEnded -> decks[call.deck]?.ended(call.load).done(Answer.Why.NO_SUCH_DECK)
            is Call.AudioFailed -> decks[call.deck]?.failed(call.load).done(Answer.Why.NO_SUCH_DECK)
            // these are applied on the host's thread, in its order ([call])
            is Call.MapView, is Call.PlaceTyped, is Call.DateChoose, is Call.DateFrom, is Call.DateTo,
            is Call.ParticipantsSearch, is Call.ParticipantsToggle, is Call.SearchText -> ordered(call)
            Call.MapOpenMemories -> tenant.value?.models?.let { t ->
                val services = mapServices.value ?: return noMapServices
                val pins = t.memoriesMap?.invoke() ?: return noMapServices
                val opening = { w: Double, h: Double -> pins.framing(w, h) ?: world(w, h) }
                Answer.MapOpened(held(HeldMap(services, opening, flowOf(MapOverlays()), pins = pins)))
            } ?: noTenant
            is Call.MapTile -> maps[call.map]?.let { held ->
                val bytes = held.tile(call.tile) ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes)
                Answer.Bytes(Sniff.image(bytes)?.mime)
            } ?: noMap
            is Call.MapTap -> maps[call.map]?.let { held ->
                val pins = held.pins ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val view = held.frame.value?.view ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val cluster = pins.clusters(view.zoom).firstOrNull { it.members.first().nodeId == call.nodeId }
                    ?: return Answer.Refused(Answer.Why.NO_SUCH_ROW)
                when (val tap = pins.tap(cluster, view)) {
                    is me.raddatz.veyq.mobile.memories.place.MemoriesMap.Tap.Open -> Answer.MapTapped(HeldMap.pin(tap.pin), emptyList())
                    is me.raddatz.veyq.mobile.memories.place.MemoriesMap.Tap.List -> Answer.MapTapped(null, tap.pins.map(HeldMap::pin))
                    is me.raddatz.veyq.mobile.memories.place.MemoriesMap.Tap.Frame -> Answer.MapTapped(null, emptyList()).also { held.moveTo(tap.view) }
                }
            } ?: noMap
            is Call.MapClose -> maps.remove(call.map)?.close().done(Answer.Why.NO_SUCH_MAP)
            Call.MapTurnOn -> mapServices.value?.let { services ->
                services.turnOn()
                // a place editor shown without its map gets it where the name field's offer stood
                places.values().forEach { it.model.mapsTurnedOn() }
            }.done(Answer.Why.NO_MAP_SERVICES)
            Call.MapTurnOff -> mapServices.value?.turnOff().done(Answer.Why.NO_MAP_SERVICES)
            Call.MemoriesDates -> tenant.value?.models?.memoryDates?.let { read ->
                Answer.MemoryDates(read().map { (nodeId, date) -> NodeDate(nodeId, dateShown(date)) })
            } ?: noTenant
            is Call.LightboxMap -> lightboxes[call.lightbox]?.let { held ->
                val services = mapServices.value ?: return noMapServices
                val photo = held.model.details(call.mediaId)?.map ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val opening = { w: Double, h: Double -> Viewport(photo.at, photo.zoom, w, h) }
                Answer.MapOpened(held(HeldMap(services, opening, flowOf(MapOverlays(photo = photo.at, direction = photo.directionTrue)), lightbox = call.lightbox)))
            } ?: noLightbox
            is Call.LightboxPlace -> lightboxes[call.lightbox]?.let { Answer.PlaceResolved(it.model.resolvePlace(call.mediaId)) } ?: noLightbox
            is Call.DateOpen -> galleries[call.gallery]?.let { g ->
                val make = tenant.value?.models?.dateEditor ?: return noTenant
                val editor = make(g) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                dates.put(id, HeldDate(call.gallery, editor))
                Answer.DateOpened(id)
            } ?: noGallery
            is Call.DateSave -> dates[call.date]?.let { held -> held.model.save()?.let(::factsSaved) ?: Answer.Refused(Answer.Why.NOT_TAKEN) } ?: noDate
            is Call.DateClose -> dates.remove(call.date)?.let { Answer.Done } ?: noDate
            is Call.PlaceOpen -> galleries[call.gallery]?.let { g ->
                val make = tenant.value?.models?.placeEditor ?: return noTenant
                val editor = make(g) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val map = mapServices.value?.let { services ->
                    held(HeldMap(services, { w, h -> editor.initialView(w, h) }, flowOf(MapOverlays(dots = editor.points)), gallery = call.gallery, place = editor))
                }
                val id = ids.incrementAndFetch()
                places.put(id, HeldPlace(call.gallery, g, editor, map))
                editor.open()
                Answer.PlaceOpened(id, map)
            } ?: noGallery
            is Call.PlaceLevel -> places[call.place]?.let { held ->
                val level = me.raddatz.veyq.mobile.map.GeoLevel.Key.of(call.level) ?: return Answer.Refused(Answer.Why.NOT_TAKEN)
                held.model.pickLevel(level)
                Answer.Done
            } ?: noPlace
            is Call.PlaceToggleName -> places[call.place]?.model?.toggleNameMode().done(Answer.Why.NO_SUCH_PLACE)
            is Call.PlaceFollow -> places[call.place]?.let { held ->
                val medoid = held.model.medoid ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                held.model.followAgain()
                // the platform recentres its map on the medoid (`PlaceEditor.followAgain`)
                held.map?.let { maps[it] }?.let { m -> m.frame.value?.view?.let { v -> m.moveTo(v.copy(center = medoid)) } }
                Answer.Done
            } ?: noPlace
            is Call.PlaceSave -> places[call.place]?.let { held -> held.model.save()?.let(::factsSaved) ?: Answer.Refused(Answer.Why.NOT_TAKEN) } ?: noPlace
            is Call.PlaceRemove -> places[call.place]?.let { held -> factsSaved(held.model.remove()) } ?: noPlace
            is Call.PlaceClose -> places.remove(call.place)?.let { closePlace(it); Answer.Done } ?: noPlace
            is Call.TracksOpen -> galleries[call.gallery]?.let { g ->
                val make = tenant.value?.models?.trackList ?: return noTenant
                val uploads = mediaUploads.value ?: return noTenant
                val list = make(g, uploads)
                val map = mapServices.value?.let { services ->
                    val overlays = combine(list.state, g.state) { t, page -> MapOverlays(tracks = trackLines(t), dots = page.gpsPoints) }
                    // no view while a track is still read: the map opens on its framing, never on the world first (#945)
                    val opening = { w: Double, h: Double ->
                        me.raddatz.veyq.mobile.memories.place.MemoryLocation.opening(list.current.framed(g.current.gpsPoints), w, h)
                    }
                    held(HeldMap(services, opening, overlays, gallery = call.gallery))
                }
                val id = ids.incrementAndFetch()
                trackLists.put(id, HeldTracks(call.gallery, list, map))
                // the tracks are read only while the view shows (ADR 0143 decision 8)
                list.active(true)
                Answer.TracksOpened(id, map)
            } ?: noGallery
            is Call.TracksClose -> trackLists.remove(call.tracks)?.let { closeTracks(it); Answer.Done } ?: noTracks
            is Call.TracksShow -> trackLists[call.tracks]?.model?.setShown(call.mediaId, call.shown).done(Answer.Why.NO_SUCH_TRACKS)
            is Call.TracksSelect -> trackLists[call.tracks]?.let { held ->
                held.model.select(call.mediaId)
                // a selected track is framed (the web's `fitBounds` on it)
                val lines = held.model.current.entries.orEmpty().firstOrNull { it.mediaId == call.mediaId }?.lines?.flatten().orEmpty()
                held.map?.let { maps[it] }?.let { m ->
                    m.frame.value?.view?.let { v -> Viewport.fit(lines, v.width, v.height)?.let(m::moveTo) }
                }
                Answer.Done
            } ?: noTracks
            is Call.TracksAdd -> trackLists[call.tracks]?.let { held ->
                // each path opened by the core and read at offsets; a path that is no file that reads is left out
                val sources = call.paths.mapNotNull { path -> parts.files.source(path) }
                Answer.TracksAdded(held.model.add(sources))
            } ?: noTracks
            is Call.TracksConfirm -> trackLists[call.tracks]?.let { held ->
                Answer.Queued(held.model.confirm(call.labels.map { me.raddatz.veyq.mobile.memories.music.TrackLabel.Label(it.name, it.activity) }))
            } ?: noTracks
            is Call.TracksCancel -> trackLists[call.tracks]?.model?.cancel().done(Answer.Why.NO_SUCH_TRACKS)
            is Call.TracksEdit -> trackLists[call.tracks]?.let { held ->
                Answer.Said(held.model.edit(call.mediaId, me.raddatz.veyq.mobile.memories.music.TrackLabel.Label(call.label.name, call.label.activity)))
            } ?: noTracks
            is Call.TracksRemove -> trackLists[call.tracks]?.let { held -> trashed(held.model.gallery, held.model.remove(call.mediaId)) } ?: noTracks
            is Call.TracksDownload -> trackLists[call.tracks]?.let { held ->
                val save = held.model.save(call.mediaId) { produce -> parts.files.write(call.path, produce) }
                    ?: return Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
                val id = ids.incrementAndFetch()
                saves.put(id, save)
                Answer.Saving(id)
            } ?: noTracks
            is Call.TracksReport -> trackLists[call.tracks]?.let { held ->
                val sheet = held.model.reportSheet(call.mediaId) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                reports.put(id, sheet)
                Answer.ReportOpened(id)
            } ?: noTracks
            is Call.TracksSeen -> trackLists[call.tracks]?.model?.seen().done(Answer.Why.NO_SUCH_TRACKS)
            // --- curating a Memory and a person's page (ADR 0144 decision 8) ---
            is Call.ParticipantsOpen -> galleries[call.gallery]?.let { g ->
                val picker = g.participantsPicker() ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                participantPickers.put(id, HeldParticipants(call.gallery, picker))
                scope.launch { picker.load() }
                Answer.ParticipantsOpened(id)
            } ?: noGallery
            is Call.ParticipantsLoad -> participantPickers[call.participants]?.let { it.model.load(); Answer.Done } ?: noParticipants
            is Call.ParticipantsSave -> participantPickers[call.participants]?.let { held ->
                when (held.model.save()) {
                    null -> Answer.Refused(Answer.Why.NOT_TAKEN)
                    me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Written, me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Unchanged ->
                        Answer.FactsSaved(FactsSave.SAVED)
                    else -> Answer.FactsSaved(FactsSave.FAILED)
                }
            } ?: noParticipants
            is Call.ParticipantsClose -> participantPickers.remove(call.participants)?.let { it.open.value = false; Answer.Done } ?: noParticipants
            is Call.CaptureTimeOpen -> lightboxes[call.lightbox]?.let { held ->
                val editor = held.model.captureTimeEditor(call.mediaId) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                captureTimes.put(id, HeldCaptureTime(held.gallery, call.lightbox, editor))
                Answer.CaptureTimeOpened(id)
            } ?: noLightbox
            is Call.CaptureTimeSet -> captureTimes[call.captureTime]?.let { held -> captureSaved(held.model.set(call.epochMs)) } ?: noCaptureTime
            is Call.CaptureTimeReset -> captureTimes[call.captureTime]?.let { held ->
                held.model.reset()?.let(::captureSaved) ?: Answer.Refused(Answer.Why.NOT_TAKEN)
            } ?: noCaptureTime
            is Call.CaptureTimeClose -> captureTimes.remove(call.captureTime)?.let { it.open.value = false; Answer.Done } ?: noCaptureTime
            is Call.BannerPicture -> galleries[call.gallery]?.let { g ->
                val bytes = g.bannerPicture() ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes)
                Answer.Bytes(Sniff.image(bytes)?.mime)
            } ?: noGallery
            is Call.BannerPlay -> galleries[call.gallery]?.let { g ->
                val reader = g.bannerReader() ?: return Answer.Refused(Answer.Why.NO_MEDIUM)
                openReader(reader, g.current.banner?.type).also { if (it is Answer.Reader) bannerReaders.put(it.reader, call.gallery) }
            } ?: noGallery
            is Call.BannerFrame -> {
                val f = me.raddatz.veyq.mobile.memories.banner.BannerFrame.of(call.columnWidth, call.viewportHeight)
                val surround = me.raddatz.veyq.mobile.memories.banner.BannerFrame.surround(call.pictureWidth, call.pictureHeight)
                Answer.BannerFramed(f.width, f.height, f.wide, surround[0], surround[1])
            }
            is Call.BannerOpen -> galleries[call.gallery]?.let { g ->
                val editor = g.bannerEditor(parts.bannerPictures) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = ids.incrementAndFetch()
                banners.put(id, HeldBanner(call.gallery, editor))
                Answer.BannerOpened(id)
            } ?: noGallery
            is Call.BannerPick -> banners[call.banner]?.let { held ->
                if (held.model.current.saving || held.job.load() != null) return Answer.Refused(Answer.Why.NOT_TAKEN)
                // the core opens the path and reads it where it lies (ADR 0137 decision 3); the path is never echoed
                val source = parts.files.source(call.path)?.let { PathSource(call.path, it) } ?: return Answer.Refused(Answer.Why.NO_SUCH_PATH)
                held.source.exchange(source)?.close()
                held.model.pick(source)
                held.pick.value += 1
                Answer.Done
            } ?: noBanner
            is Call.BannerPreview -> banners[call.banner]?.let { held ->
                val bytes = held.model.current.picked?.picture ?: return Answer.Refused(Answer.Why.NO_BYTES)
                payload(bytes.copyOf())
                Answer.Bytes(Sniff.image(bytes)?.mime)
            } ?: noBanner
            is Call.BannerSave -> banners[call.banner]?.let { held ->
                if (!held.model.current.canSave) return Answer.Refused(Answer.Why.NOT_TAKEN)
                bannerRun(held) { held.model.save() }
            } ?: noBanner
            is Call.BannerCancel -> banners[call.banner]?.let { held -> held.job.load()?.cancel().done(Answer.Why.NOT_TAKEN) } ?: noBanner
            is Call.BannerRemove -> banners[call.banner]?.let { held ->
                if (!held.model.current.canRemove) return Answer.Refused(Answer.Why.NOT_TAKEN)
                bannerRun(held) { held.model.remove() }
            } ?: noBanner
            is Call.BannerClose -> banners.remove(call.banner)?.let { it.close(); Answer.Done } ?: noBanner
            is Call.GalleryTrashBannerPicture -> galleries[call.gallery]?.let { g -> trashed(g, g.trashBannerPicture(call.removeBanner)) } ?: noGallery
            is Call.ShellOffersWasThere -> tenant.value?.models?.let { m ->
                val wasThere = m.offersWasThere ?: return noTenant
                Answer.WasThere(wasThere(call.scopeId), m.offersAutoPlace?.invoke() == true)
            } ?: noTenant
            is Call.PersonOpen -> tenant.value?.models?.let { t ->
                val profile = t.profile?.invoke(call.userId) ?: return noTenant
                val memories = if (ApplicationId.MEMORIES in profile.sections) t.personMemories?.invoke(call.userId) else null
                val id = ids.incrementAndFetch()
                persons.put(id, HeldPerson(profile, memories))
                memories?.let { m -> scope.launch { m.load() } }
                Answer.PersonOpened(id)
            } ?: noTenant
            is Call.PersonView -> persons[call.person]?.let { held ->
                held.memories?.view(me.raddatz.veyq.mobile.memories.people.PersonMemories.View.valueOf(call.view.name)).done(Answer.Why.NOT_OFFERED)
            } ?: noPerson
            is Call.PersonRetry -> persons[call.person]?.let { held -> held.memories?.load().done(Answer.Why.NOT_OFFERED) } ?: noPerson
            is Call.PersonMap -> persons[call.person]?.let { held ->
                val services = mapServices.value ?: return noMapServices
                val section = held.memories ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val pins = section.current.map
                val opening = { w: Double, h: Double -> pins.framing(w, h) ?: world(w, h) }
                Answer.MapOpened(held(HeldMap(services, opening, flowOf(MapOverlays()), pins = pins, person = call.person)))
            } ?: noPerson
            is Call.PersonClose -> persons.remove(call.person)?.let { closePerson(call.person, it); Answer.Done } ?: noPerson
            is Call.SettingsRenameFamily, Call.ProfileOpen, is Call.ProfileUsername, is Call.ProfileFirstName, is Call.ProfileLastName,
            is Call.ProfileConsent, is Call.ProfilePicture, is Call.ProfileRemovePicture, is Call.ProfileDiscardPicture,
            is Call.ProfilePictureBytes, is Call.ProfileSave, is Call.ProfileClose, Call.TrustMine, is Call.TrustMineCode,
            is Call.TrustPerson, is Call.TrustCompareDigits, is Call.TrustCompareScanned, is Call.PasswordChangeOpen,
            is Call.PasswordChangeRun, is Call.PasswordChangeClose, is Call.AddressChangeOpen, is Call.AddressChangeRequest,
            is Call.AddressChangeKit, is Call.AddressChangeClose, is Call.KitSave, is Call.KitConfirm, is Call.KitClose,
            is Call.KeyRotationOpen, is Call.KeyRotationChooseNewPassword, is Call.KeyRotationProve, is Call.KeyRotationRun, is Call.KeyRotationRefreshLinks,
            is Call.KeyRotationClose, is Call.PasswordChangeCheck, is Call.PasswordChangeEdited, is Call.PasswordChangeUseAnyway,
            is Call.KeyRotationCheck, is Call.KeyRotationEdited, is Call.KeyRotationUseAnyway, is Call.KitRead, is Call.KitClear,
            is Call.ErasureOpen, is Call.ErasureChoose, is Call.ErasureRun, is Call.ErasureClose,
            -> accountFlow(call, parts, payload)
        }
    }

    private val noProfile = Answer.Refused(Answer.Why.NO_SUCH_FLOW)

    /** The Secret Key control of flow [id] — a password change, an address change or a rotation (ids are unique across them). */
    private fun flowIntake(id: Long): me.raddatz.veyq.mobile.account.KeyIntake? =
        passwordChanges[id]?.model?.intake ?: addressChanges[id]?.model?.intake ?: keyRotations[id]?.model?.intake
    private val noFlow = Answer.Refused(Answer.Why.NO_SUCH_FLOW)

    /**
     * The account's flows (ADR 0148 decision 11): each `*.Open` makes the core's model over the account the connection
     * names (or the tenant open now), held by id; each other call is one line onto that model. The typed secrets arrive
     * here and go straight into the model, which zeroes what it was handed; nothing of them comes back.
     */
    private suspend fun accountFlow(call: Call, parts: Composition<A, T>, payload: (ByteArray) -> Unit): Answer {
        val session = parts.session
        fun flows(connection: String): AccountFlows? {
            val account = session.accounts.firstOrNull { it.key == connection } ?: return null
            val open = tenant.value?.takeIf { it.account.key == connection }
            return parts.accountFlows(account, open?.tenant, open?.models?.session)
        }
        fun <M : Any> held(registry: Registry<HeldFlow<M>>, flow: HeldFlow<M>): Long = ids.incrementAndFetch().also { registry.put(it, flow) }
        return when (call) {
            is Call.SettingsRenameFamily -> onPage(call.settings) { it.renameFamily(call.family, call.label) }
            Call.ProfileOpen -> {
                val open = tenant.value ?: return noTenant
                val model = open.models.ownProfile?.invoke() ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                val id = held(profiles, HeldFlow(model, open.account.key) { it.close() })
                scope.launch { model.load() }
                Answer.ProfileOpened(id)
            }
            is Call.ProfileUsername -> profiles[call.profile]?.model?.setUsername(call.value).done(Answer.Why.NO_SUCH_FLOW)
            is Call.ProfileFirstName -> profiles[call.profile]?.model?.setFirstName(call.value).done(Answer.Why.NO_SUCH_FLOW)
            is Call.ProfileLastName -> profiles[call.profile]?.model?.setLastName(call.value).done(Answer.Why.NO_SUCH_FLOW)
            is Call.ProfileConsent -> profiles[call.profile]?.model?.setRequireGroupAddConsent(call.on).done(Answer.Why.NO_SUCH_FLOW)
            is Call.ProfilePicture -> profiles[call.profile]?.model?.let { model ->
                val edge = me.raddatz.veyq.mobile.session.ProfileModel.EDGE
                val rgba = parts.squarePicture(call.path, call.pose, edge)?.takeIf { it.size == edge * edge * 4 }
                    ?: return Answer.Refused(Answer.Why.NOT_A_PICTURE)
                try {
                    model.choosePicture(rgba)
                } finally {
                    rgba.fill(0)
                }
                Answer.Done
            } ?: noProfile
            is Call.ProfileRemovePicture -> profiles[call.profile]?.model?.removePicture().done(Answer.Why.NO_SUCH_FLOW)
            is Call.ProfileDiscardPicture -> profiles[call.profile]?.model?.discardPicture().done(Answer.Why.NO_SUCH_FLOW)
            is Call.ProfilePictureBytes -> profiles[call.profile]?.model?.let { model ->
                val picture = model.picture() ?: return Answer.Refused(Answer.Why.NO_PICTURE)
                payload(picture)
                Answer.Bytes(Sniff.image(picture)?.mime)
            } ?: noProfile
            is Call.ProfileSave -> profiles[call.profile]?.model?.let { it.save(); Answer.Done } ?: noProfile
            is Call.ProfileClose -> profiles.remove(call.profile)?.close().done(Answer.Why.NO_SUCH_FLOW)
            Call.TrustMine -> tenant.value?.models?.trust?.let { t ->
                val mine = t.mine() ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                Answer.SafetyNumber(mine.number, mine.chainCode)
            } ?: noTenant
            is Call.TrustMineCode -> tenant.value?.models?.trust?.let { t ->
                val mine = t.mine() ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
                drawnCode(mine.payload, call.px, payload)
            } ?: noTenant
            is Call.TrustPerson -> tenant.value?.models?.trust?.let { t ->
                val shown = t.person(call.userId)
                Answer.PersonTrust(shown, shown?.status)
            } ?: noTenant
            is Call.TrustCompareDigits -> tenant.value?.models?.trust?.let { t -> Answer.TrustOutcome(t.compareDigits(call.userId, call.match)) } ?: noTenant
            is Call.TrustCompareScanned -> tenant.value?.models?.trust?.let { t ->
                val found = (scans[call.scan]?.current as? me.raddatz.veyq.mobile.qr.QrScan.State.Found<*>)?.value as? SafetyCode
                    ?: return Answer.Refused(Answer.Why.NO_SUCH_SCAN)
                Answer.TrustOutcome(t.compareScan(call.userId, found.text))
            } ?: noTenant
            is Call.PasswordChangeOpen -> {
                val f = flows(call.connection) ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val model = f.passwordChange()
                val id = held(passwordChanges, HeldFlow(model, call.connection) { it.close() })
                scope.launch { model.breach.load() } // the range service's address, read when the flow opens
                Answer.FlowOpened(id)
            }
            is Call.PasswordChangeCheck -> passwordChanges[call.flow]?.model?.check(call.newPassword).done(Answer.Why.NO_SUCH_FLOW)
            is Call.PasswordChangeEdited -> passwordChanges[call.flow]?.model?.edited().done(Answer.Why.NO_SUCH_FLOW)
            is Call.PasswordChangeUseAnyway -> passwordChanges[call.flow]?.model?.useAnyway(call.on).done(Answer.Why.NO_SUCH_FLOW)
            is Call.KeyRotationCheck -> keyRotations[call.flow]?.model?.check(call.newPassword).done(Answer.Why.NO_SUCH_FLOW)
            is Call.KeyRotationEdited -> keyRotations[call.flow]?.model?.edited().done(Answer.Why.NO_SUCH_FLOW)
            is Call.KeyRotationUseAnyway -> keyRotations[call.flow]?.model?.useAnyway(call.on).done(Answer.Why.NO_SUCH_FLOW)
            is Call.KitRead -> {
                val intake = flowIntake(call.flow) ?: return noFlow
                // as `Session.SignInKit`: read by its path, never past a kit file's cap; one that does not read is no kit
                val bytes = runCatching { parts.files.read(call.path, me.raddatz.veyq.mobile.account.KitImport.FILE_CAP) }.getOrNull()
                val read = if (bytes == null) intake.read(ByteArray(0), NoPictures) else intake.read(bytes, parts.pictures(call.path))
                if (read != null) Answer.Done else Answer.Refused(Answer.Why.NO_KIT)
            }
            is Call.KitClear -> flowIntake(call.flow)?.clear().done(Answer.Why.NO_SUCH_FLOW)
            is Call.PasswordChangeRun -> passwordChanges[call.flow]?.let { held ->
                held.model.run(call.currentPassword, call.newPassword, call.confirmPassword, call.secretKey)
                // the change took this device's saved key: the account's Settings pages read anew, so their trust row
                // offers the levels and the password trust instead of the trust that is gone (inbox #1009)
                if (held.model.current.done) accountPages.values().filter { it.connection == held.connection }.forEach { it.model.refresh() }
                managed(parts)
                Answer.Done
            } ?: noFlow
            is Call.PasswordChangeClose -> passwordChanges.remove(call.flow)?.close().done(Answer.Why.NO_SUCH_FLOW)
            is Call.AddressChangeOpen -> {
                val f = flows(call.connection) ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                Answer.FlowOpened(held(addressChanges, HeldFlow(f.addressChange(), call.connection) { it.close() }))
            }
            is Call.AddressChangeRequest -> addressChanges[call.flow]?.model?.let {
                it.request(call.newEmail, call.password, call.secretKey)
                Answer.Done
            } ?: noFlow
            is Call.AddressChangeKit -> addressChanges[call.flow]?.let { held ->
                val step = held.model.kit(call.password, call.secretKey) ?: return Answer.Done
                Answer.KitOpened(held(kits, HeldFlow(step, held.connection) { it.close() }))
            } ?: noFlow
            is Call.AddressChangeClose -> addressChanges.remove(call.flow)?.close().done(Answer.Why.NO_SUCH_FLOW)
            // the kit's bytes are the core's alone: written at the path the host's dialog gave, never over the bridge
            is Call.KitSave -> kits[call.kit]?.model?.let { step ->
                if (step.save(call.path, call.language, call.createdEpochDay)) Answer.Done else Answer.Refused(Answer.Why.NOT_WRITTEN)
            } ?: noFlow
            is Call.KitConfirm -> kits[call.kit]?.model?.confirm(call.checked).done(Answer.Why.NO_SUCH_FLOW)
            is Call.KitClose -> kits.remove(call.kit)?.close().done(Answer.Why.NO_SUCH_FLOW)
            is Call.KeyRotationOpen -> {
                val f = flows(call.connection) ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val model = f.keyRotation()
                val id = held(keyRotations, HeldFlow(model, call.connection) { it.close() })
                scope.launch { model.open() }
                Answer.FlowOpened(id)
            }
            is Call.KeyRotationChooseNewPassword -> keyRotations[call.flow]?.model?.setNewPassword(call.on).done(Answer.Why.NO_SUCH_FLOW)
            is Call.KeyRotationProve -> keyRotations[call.flow]?.let { held ->
                held.model.prove(call.password, call.secretKey, call.newPassword, call.confirmPassword)
                // the kit step is the rotation's: it closes with the rotation, never on its own `Kit.Close`
                val step = held.model.kit ?: return Answer.Done
                Answer.KitOpened(held(kits, HeldFlow(step, held.connection)))
            } ?: noFlow
            is Call.KeyRotationRun -> keyRotations[call.flow]?.let { held ->
                held.model.run()
                // the account holds the new keys: the tenant open on it is opened anew (its keys were the old identity's).
                // The done rotation outlives that reopen (inbox #984): it holds no secret any more, and its last step — each
                // space's new lock, the links to renew — stays on the host's screen until `KeyRotation.Close` or a sign-out.
                if (held.model.current.done) {
                    val open = tenant.value
                    if (open != null && open.account.key == held.connection) {
                        keyRotations.remove(call.flow)
                        try {
                            return opened(session.switchTo(open.account, open.tenant)).also { managed(parts) }
                        } finally {
                            keyRotations.put(call.flow, held)
                        }
                    }
                }
                Answer.Done
            } ?: noFlow
            is Call.KeyRotationRefreshLinks -> keyRotations[call.flow]?.model?.refreshLinks().done(Answer.Why.NO_SUCH_FLOW)
            is Call.KeyRotationClose -> keyRotations.remove(call.flow)?.close().done(Answer.Why.NO_SUCH_FLOW)
            is Call.ErasureOpen -> {
                val f = flows(call.connection) ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val model = f.erasure()
                val id = held(erasures, HeldFlow(model, call.connection))
                scope.launch { model.load() }
                Answer.FlowOpened(id)
            }
            is Call.ErasureChoose -> erasures[call.flow]?.model?.choose(call.tenantId, call.newOwner).done(Answer.Why.NO_SUCH_FLOW)
            is Call.ErasureRun -> erasures[call.flow]?.let { held ->
                held.model.erase(call.password)
                if (!held.model.current.erased) return Answer.Done
                // the account is erased and forgotten by the device: it leaves the session as a sign-out does
                val account = session.accounts.firstOrNull { it.key == held.connection } ?: return Answer.Done
                val out = session.signOut(account)
                if (out.wasCurrent) closeTenant()
                opened(out.next).also { managed(parts) }
            } ?: noFlow
            is Call.ErasureClose -> erasures.remove(call.flow)?.close().done(Answer.Why.NO_SUCH_FLOW)
            else -> Answer.Refused(Answer.Why.NOT_A_CALL)
        }
    }

    /** What a capture-time editor's save came to, as the host is told it. */
    private fun captureSaved(out: me.raddatz.veyq.mobile.memories.CaptureTime.Outcome): Answer = Answer.FactsSaved(
        when (out) {
            is me.raddatz.veyq.mobile.memories.CaptureTime.Outcome.Done -> FactsSave.SAVED
            is me.raddatz.veyq.mobile.memories.CaptureTime.Outcome.Conflict -> FactsSave.REOPENED
            else -> FactsSave.FAILED
        },
    )

    /**
     * A banner's save or removal run as the dialog's one job ([HeldBanner.job], so [Call.BannerCancel] stops it) and
     * answered when it ended: what it came to, or cancelled — the old banner as it was.
     */
    private suspend fun bannerRun(held: HeldBanner, op: suspend () -> me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome?): Answer {
        val job = scope.async { op() }
        if (!held.job.compareAndSet(null, job)) {
            job.cancel()
            return Answer.Refused(Answer.Why.NOT_TAKEN)
        }
        job.join()
        held.job.compareAndSet(job, null)
        if (job.isCancelled) return Answer.BannerSaved(BannerSave.CANCELLED)
        return Answer.BannerSaved(
            when (job.getCompleted()) {
                me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Done -> BannerSave.DONE
                me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Stale -> BannerSave.STALE
                me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Busy -> BannerSave.BUSY
                else -> BannerSave.FAILED
            },
        )
    }

    /** Gallery [gallery]'s curation dialogs and its banner's readers go with it. */
    private fun closeCurationOf(gallery: Long) {
        participantPickers.removeIf { it.gallery == gallery }.forEach { it.open.value = false }
        captureTimes.removeIf { it.gallery == gallery }.forEach { it.open.value = false }
        banners.removeIf { it.gallery == gallery }.forEach { it.close() }
        bannerReaders.keys().forEach { reader -> if (bannerReaders[reader] == gallery) { bannerReaders.remove(reader); readers.remove(reader) } }
    }

    /** A person's page goes, its section's map closed. */
    private fun closePerson(id: Long, held: HeldPerson) {
        held.open.value = false
        maps.removeIf { it.person == id }.forEach { it.close() }
    }

    /** [map] held under a new id, for [Watched.Map] and the `Map.*` calls. */
    private fun held(map: HeldMap): Long = ids.incrementAndFetch().also { maps.put(it, map) }

    /** The world view of a map of [width] × [height] (`MemoryLocation`'s): where a map with nothing to frame opens. */
    private fun world(width: Double, height: Double) =
        Viewport(me.raddatz.veyq.mobile.memories.place.MemoryLocation.WORLD_CENTER, me.raddatz.veyq.mobile.memories.place.MemoryLocation.WORLD_ZOOM, width, height)

    /** A place editor goes, its pending lookup dropped, its map closed. */
    private fun closePlace(held: HeldPlace) {
        held.open.value = false
        held.model.close()
        held.map?.let { maps.remove(it) }?.close()
    }

    /** A Map view goes, its parsed tracks let go, its map closed. */
    private fun closeTracks(held: HeldTracks) {
        held.model.close()
        held.map?.let { maps.remove(it) }?.close()
    }

    /** Gallery [gallery]'s editors, Map view and maps go with it. */
    private fun closeFactsOf(gallery: Long) {
        dates.removeIf { it.gallery == gallery }
        places.removeIf { it.gallery == gallery }.forEach(::closePlace)
        trackLists.removeIf { it.gallery == gallery }.forEach(::closeTracks)
        maps.removeIf { it.gallery == gallery }.forEach { it.close() }
    }

    /** What an editor's save came to, as the host is told it. */
    private fun factsSaved(r: me.raddatz.veyq.mobile.memories.place.EditorSave): Answer = Answer.FactsSaved(
        when (r) {
            me.raddatz.veyq.mobile.memories.place.EditorSave.Saved -> FactsSave.SAVED
            is me.raddatz.veyq.mobile.memories.place.EditorSave.Reopen -> FactsSave.REOPENED
            me.raddatz.veyq.mobile.memories.place.EditorSave.Failed -> FactsSave.FAILED
        },
    )

    /** A Memory's date as the host says it. */
    private fun dateShown(d: me.raddatz.veyq.mobile.memories.place.MemoryDate) = MemoryDateShown(DateMode.valueOf(d.mode.name), d.from, d.to)

    /** The Map view's tracks drawn now, as lines in normalised space (`TrackList.State.drawn`). */
    private fun trackLines(s: me.raddatz.veyq.mobile.memories.tracks.TrackList.State) = s.drawn.map { e ->
        TrackLineShown(e.mediaId, e.color, e.dash, s.selected == e.mediaId, e.lines.map { seg -> seg.map(HeldMap::point) })
    }

    /** A medium's delete as its answer: a trash with whether its Undo is offered now, or nothing said (a purge asked). */
    private fun trashed(g: me.raddatz.veyq.mobile.memories.GalleryModel, notice: Notice?): Answer =
        if (notice == null) Answer.Said(null) else Answer.Trashed(notice, g.current.undo != null)

    /** One row of a medium's details, as `MediaDetails.shown` says it shows. */
    private fun detailRow(row: me.raddatz.veyq.mobile.memories.MediaDetails.Row): DetailRow {
        val label = me.raddatz.veyq.mobile.memories.MediaDetails.label(row.field)
        return when (val shown = me.raddatz.veyq.mobile.memories.MediaDetails.shown(row)) {
            is me.raddatz.veyq.mobile.memories.MediaDetails.Shown.Words -> DetailRow(label, shown.notice, null, null, null)
            is me.raddatz.veyq.mobile.memories.MediaDetails.Shown.Size -> DetailRow(label, null, shown.bytes, null, null)
            is me.raddatz.veyq.mobile.memories.MediaDetails.Shown.Moment -> DetailRow(label, null, null, shown.epochMs, null)
            is me.raddatz.veyq.mobile.memories.MediaDetails.Shown.Plain -> DetailRow(label, null, null, null, shown.text)
        }
    }

    /**
     * Whether reader [id] ([r]) is still its holder's: a lightbox's model closes the reader it held (paged away, stopped,
     * closed), and then its id goes too. A reader no lightbox opened is the host's until it closes it.
     */
    private fun stillHeld(id: Long, r: RangeReader): Boolean {
        val lightbox = lightboxReaders[id] ?: return true
        if (lightboxes[lightbox]?.model?.current?.reader === r) return true
        lightboxReaders.remove(id)
        readers.remove(id)
        return false
    }

    /** The readers lightbox [lightbox] opened go from the registry (its model closes them). */
    private fun dropReadersOf(lightbox: Long) {
        lightboxReaders.keys().forEach { reader -> if (lightboxReaders[reader] == lightbox) { lightboxReaders.remove(reader); readers.remove(reader) } }
    }

    /** Gallery [gallery]'s lightboxes close and go from the registry, with the readers they opened. */
    private fun closeLightboxesOf(gallery: Long) {
        lightboxes.removeIf { it.gallery == gallery }.forEach { it.model.close() }
        maps.removeIf { m -> m.lightbox != null && lightboxes[m.lightbox] == null }.forEach { it.close() }
        lightboxReaders.keys().forEach { reader ->
            val l = lightboxReaders[reader] ?: return@forEach
            if (lightboxes[l] == null) { lightboxReaders.remove(reader); readers.remove(reader) }
        }
    }

    /** What a scan's [ScanShown.found] says of what it settled on: a scheme as it is, a kit (where its server came from) as its scheme alone. */
    private fun foundAs(value: Any): String = when (value) {
        is me.raddatz.veyq.mobile.account.KitImport.ServerFrom -> "veyqkit"
        is SafetyCode -> "veyqid"
        else -> value as String
    }

    /**
     * A code drawn by the core for a host to show (the QR CR's D2, ADR 0137 decision 1): [text] encoded (`QrEncoder`, level
     * M, its quiet zone) and written as a 1-bit greyscale PNG (`QrPng`) at the largest whole scale that keeps its side
     * within [px] (at least 1, at most `QrPng.MAX_SIDE`), handed to [payload] and answered `Bytes(image/png)`. The door of
     * every call that answers a code as a picture — a device link's `Link.Code` (its CR) the first —, so the text a code
     * holds never crosses the bridge. Refused `NO_BYTES` for a text no code holds.
     */
    internal fun drawnCode(text: String, px: Int, payload: (ByteArray) -> Unit): Answer =
        drawnCode(runCatching { me.raddatz.veyq.mobile.qr.QrEncoder.encode(text) }.getOrNull(), px, payload)

    /** [drawnCode] of a code the core already encoded ([matrix]; a device link's, whose text stays in the link); refused `NO_BYTES` for none. */
    private fun drawnCode(matrix: me.raddatz.veyq.mobile.qr.QrMatrix?, px: Int, payload: (ByteArray) -> Unit): Answer {
        if (matrix == null) return Answer.Refused(Answer.Why.NO_BYTES)
        // version 40's side (185 modules) fits MAX_SIDE at scale 1, so a scale of at least 1 always writes
        val scale = maxOf(1, minOf(px, me.raddatz.veyq.mobile.qr.QrPng.MAX_SIDE) / matrix.side)
        payload(me.raddatz.veyq.mobile.qr.QrPng.write(matrix, scale))
        return Answer.Bytes("image/png")
    }

    /**
     * Holds [reader] for the host until [Call.ReaderClose] or the tenant closes, answered as [Answer.Reader] (ADR 0137
     * decision 2): the door of every call that opens a blob for reading in ranges — a Depot file's medium
     * ([Call.FileReader]), a Memory's medium ([Call.MediumReader]) — and of the test's reader. Refused while no tenant is open.
     */
    internal fun openReader(reader: RangeReader, mediaType: String?): Answer {
        if (tenant.value == null) return noTenant
        val id = ids.incrementAndFetch()
        readers.put(id, reader)
        return Answer.Reader(id, reader.size, mediaType)
    }

    /** Closes every reader page [file] opened: a reader's lifetime is its page's (ADR 0137's Consequences). */
    private fun closeReadersOf(file: Long) {
        pageReaders.keys().forEach { reader -> if (pageReaders[reader] == file) { pageReaders.remove(reader); readers.remove(reader) } }
    }

    private val noTenant = Answer.Refused(Answer.Why.NO_TENANT_OPEN)
    private val noHeader = Answer.Refused(Answer.Why.NO_SUCH_HEADER)
    private val noPot = Answer.Refused(Answer.Why.NO_SUCH_POT)
    private val noTrash = Answer.Refused(Answer.Why.NO_SUCH_TRASH)
    private val noMove = Answer.Refused(Answer.Why.NO_SUCH_MOVE)
    private val noShare = Answer.Refused(Answer.Why.NO_SUCH_SHARE)
    private val noReport = Answer.Refused(Answer.Why.NO_SUCH_REPORT)
    private val noEditor = Answer.Refused(Answer.Why.NO_SUCH_EDITOR)
    private val noCreate = Answer.Refused(Answer.Why.NO_SUCH_CREATE)
    private val noFile = Answer.Refused(Answer.Why.NO_SUCH_FILE)
    private val noDropBox = Answer.Refused(Answer.Why.NO_SUCH_DROPBOX)
    private val noFolder = Answer.Refused(Answer.Why.NO_SUCH_FOLDER)
    private val noScan = Answer.Refused(Answer.Why.NO_SUCH_SCAN)
    private val noLink = Answer.Refused(Answer.Why.NO_SUCH_LINK)
    private val noMusic = Answer.Refused(Answer.Why.NO_SUCH_MUSIC)
    private val noBuckets = Answer.Refused(Answer.Why.NO_SUCH_BUCKETS)

    /** A gallery's filter suggestions for its text, the kinds' words in the host's language. */
    private suspend fun suggestions(g: me.raddatz.veyq.mobile.memories.GalleryModel) =
        g.suggestions(parts.await().words.say(Msg.SEARCH_KIND_PHOTO), parts.await().words.say(Msg.SEARCH_KIND_VIDEO))
    private val noMap = Answer.Refused(Answer.Why.NO_SUCH_MAP)
    private val noMapServices = Answer.Refused(Answer.Why.NO_MAP_SERVICES)
    private val noDate = Answer.Refused(Answer.Why.NO_SUCH_DATE)
    private val noPlace = Answer.Refused(Answer.Why.NO_SUCH_PLACE)
    private val noTracks = Answer.Refused(Answer.Why.NO_SUCH_TRACKS)
    private val noParticipants = Answer.Refused(Answer.Why.NO_SUCH_PARTICIPANTS)
    private val noCaptureTime = Answer.Refused(Answer.Why.NO_SUCH_CAPTURE_TIME)
    private val noBanner = Answer.Refused(Answer.Why.NO_SUCH_BANNER)
    private val noPerson = Answer.Refused(Answer.Why.NO_SUCH_PERSON)

    private fun taken(taken: Boolean): Answer = if (taken) Answer.Done else Answer.Refused(Answer.Why.NOT_TAKEN)

    private val noReceived = Answer.Refused(Answer.Why.NO_SUCH_RECEIVED)
    private val noBookmark = Answer.Refused(Answer.Why.NO_SUCH_BOOKMARK)

    /** The player a guest's Memory plays its music with: the open tenant's, where one plays (the members' galleries' own). */
    private fun music(parts: Composition<A, T>): me.raddatz.veyq.mobile.memories.GalleryModel.Music? =
        player.value?.let { p -> parts.audio?.let { a -> me.raddatz.veyq.mobile.memories.GalleryModel.Music(p, a::untitled) } }

    /** Whether space [scopeId] of the open tenant is a group space's (its bookmark dialog says who else opens the link). */
    private suspend fun groupSpace(scopeId: String): Boolean =
        tenant.value?.models?.session?.let { t -> runCatching { t.spaces() }.getOrNull()?.firstOrNull { it.scopeId == scopeId }?.kind != "PERSONAL" } ?: false

    /**
     * Kept link [node]'s page (ADR 0150 decision 6): its meta opened under the open tenant; a share shown in place is held
     * as a received link of its own, whose id the page's update names.
     */
    private suspend fun openBookmark(parts: Composition<A, T>, node: NodeAt): Answer {
        val t = tenant.value?.models?.session ?: return noTenant
        // a composition without guest views (a test's) opens no link: the page then reads as its meta alone
        val page = me.raddatz.veyq.mobile.sharing.BookmarkPage.of(t, node) { link, password ->
            parts.received(link, queue, password, music(parts)) ?: error("no guest views in this composition")
        }
        val held = HeldBookmark(page)
        val id = ids.incrementAndFetch()
        bookmarks.put(id, held)
        runCatching { page.load() }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }
        (page.state.value as? me.raddatz.veyq.mobile.sharing.BookmarkPage.State.InPlace)?.let { inPlace ->
            val shown = HeldReceived(inPlace.screen)
            val rid = ids.incrementAndFetch()
            received.put(rid, shown)
            pageOf(shown)
            held.inPlace.value = rid
        }
        return Answer.BookmarkOpened(id)
    }

    /** A bookmark's link opened as its own screen ([screen], null where its meta did not read): held, loaded, answered with its id. */
    private suspend fun heldReceived(screen: me.raddatz.veyq.mobile.sharing.GuestScreen?): Answer {
        val s = screen ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
        val held = HeldReceived(s)
        val id = ids.incrementAndFetch()
        received.put(id, held)
        s.load()
        pageOf(held)
        return Answer.ReceivedOpened(id)
    }

    /** A bookmark's page closed: its in-place link let go with it. */
    private fun closeBookmark(b: HeldBookmark) {
        b.inPlace.value?.let { rid -> received.remove(rid)?.let(::closeReceived) }
        b.page.close()
        b.open.value = false
    }

    /** A received link's calls (`Received.*`, ADR 0150 decisions 2–6), apart from [dispatch] to keep its method within the JVM's size.
     */
    private suspend fun receivedCall(parts: Composition<A, T>, call: Call): Answer = when (call) {
        is Call.ReceivedOpen -> openReceived(parts, call.text)
        is Call.ReceivedPassword -> received[call.received]?.let { h -> h.screen.view.gates.submitPassword(call.password); enterReceived(h) } ?: noReceived
        is Call.ReceivedAcceptTerms -> received[call.received]?.let { h -> h.screen.view.gates.acceptTerms(); enterReceived(h) } ?: noReceived
        is Call.ReceivedAcceptNote -> received[call.received]?.let { h -> h.screen.view.gates.acceptNote(); enterReceived(h) } ?: noReceived
        is Call.ReceivedOpenNode -> received[call.received]?.let { h -> h.screen.view.open(call.node); pageOf(h); Answer.Done } ?: noReceived
        is Call.ReceivedBack -> received[call.received]?.let { h -> h.screen.back(); pageOf(h); Answer.Done } ?: noReceived
        is Call.ReceivedStay -> received[call.received]?.let { h -> h.screen.stay(); Answer.Done } ?: noReceived
        is Call.ReceivedLeave, is Call.ReceivedClose -> {
            val id = (call as? Call.ReceivedLeave)?.received ?: (call as Call.ReceivedClose).received
            received.remove(id)?.let { h -> closeReceived(h); Answer.Done } ?: noReceived
        }
        is Call.ReceivedName -> received[call.received]?.let { h ->
            val access = h.screen.view.access ?: return noReceived
            val named = runCatching {
                if (access.role == me.raddatz.veyq.mobile.sharing.GuestAccess.WRITE_ONLY) access.nameUploader(call.name.trim()) else access.nameGuest(call.name.trim())
            }.isSuccess
            taken(named)
        } ?: noReceived
        is Call.ReceivedForgetGuest -> received[call.received]?.let { h -> h.screen.view.gates.forgetGuest(); Answer.Done } ?: noReceived
        is Call.ReceivedKeep -> received[call.received]?.let { h ->
            val space = h.screen.keepPlaces().firstOrNull { it.scopeId == call.scope } ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
            val dialog = h.screen.keep(space) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
            taken(dialog.save())
        } ?: noReceived
        is Call.ReceivedUpload -> received[call.received]?.let { h ->
            val sources = call.paths.mapNotNull { parts.files.source(it) }
            if (sources.isEmpty()) return Answer.Refused(Answer.Why.NO_SUCH_PATH)
            val sent = when (val page = h.screen.view.page.value) {
                is me.raddatz.veyq.mobile.sharing.GuestPage.DropBox -> page.model.upload(sources)
                is me.raddatz.veyq.mobile.sharing.GuestPage.Uploader -> page.model.upload(sources)
                // a READ_WRITE guest's files into the folder on screen (#1034), each its own file
                is me.raddatz.veyq.mobile.sharing.GuestPage.Level -> page.model.upload(sources)
                else -> 0
            }
            if (sent == 0) sources.forEach { it.close() }
            taken(sent > 0)
        } ?: noReceived
        is Call.ReceivedReplace -> received[call.received]?.let { h ->
            val page = h.screen.view.page.value as? me.raddatz.veyq.mobile.sharing.GuestPage.File ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
            val access = h.screen.view.access ?: return noReceived
            if (access.role != me.raddatz.veyq.mobile.sharing.GuestAccess.READ_WRITE || access.session.value == null) return Answer.Refused(Answer.Why.NOT_OFFERED)
            val source = parts.files.source(call.path) ?: return Answer.Said(Notices.notRead(listOf(pathName(call.path))))
            if (access.replace(page.model, source)) Answer.Queued(1) else Answer.Refused(Answer.Why.NOT_CHOSEN).also { source.close() }
        } ?: noReceived
        is Call.ReceivedNewFolder -> received[call.received]?.let { h ->
            val level = h.screen.view.page.value as? me.raddatz.veyq.mobile.sharing.GuestPage.Level ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
            if (!level.model.writes) return Answer.Refused(Answer.Why.NOT_OFFERED)
            Answer.Said(level.model.createFolder(call.name))
        } ?: noReceived
        is Call.ReceivedReportNode -> received[call.received]?.let { h ->
            val sheet = me.raddatz.veyq.mobile.sharing.GuestReport.nodeSheet(h.screen.view, call.node) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
            val id = ids.incrementAndFetch()
            reports.put(id, sheet)
            Answer.ReportOpened(id)
        } ?: noReceived
        is Call.ReceivedReport -> received[call.received]?.let { h ->
            val sheet = h.screen.reportSheet() ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
            val id = ids.incrementAndFetch()
            reports.put(id, sheet)
            Answer.ReportOpened(id)
        } ?: noReceived
        is Call.ReceivedKeepPlaces -> received[call.received]?.let { h ->
            Answer.KeepPlaces(h.screen.keepPlaces().map { ShellShown.ShownSpace(it, ShellWords.space(it)) })
        } ?: noReceived
        is Call.ReceivedReportShown -> received[call.received]?.let { h -> h.screen.reportShown(); Answer.Done } ?: noReceived
        else -> Answer.Refused(Answer.Why.NOT_A_CALL)
    }

    /**
     * *Open a link* (ADR 0150 decisions 4, 8): the pasted text parsed by the core; a link opened as its guest — signed in or
     * not; *Keep* offered with the open tenant — and loaded at once; a text that is none answered with why. The text, which
     * holds the key, goes no further than the core's parse and the link's model.
     */
    private suspend fun openReceived(parts: Composition<A, T>, text: String): Answer {
        val r = me.raddatz.veyq.mobile.sharing.ReceivedLink.open(text)
        if (r is me.raddatz.veyq.mobile.sharing.ReceivedLink.Parsed.Refused) {
            val word = me.raddatz.veyq.mobile.sharing.BookmarkCreate.wordOf(r.problem) ?: Msg.LINK_PROBLEM_NOT_A_URL
            return Answer.ReceivedProblem(word, browser = r.problem == me.raddatz.veyq.mobile.sharing.ReceivedLink.Problem.MODERATION)
        }
        val ok = r as me.raddatz.veyq.mobile.sharing.ReceivedLink.Parsed.Ok
        val view = parts.received(ok.link, queue, null, music(parts)) ?: return Answer.Refused(Answer.Why.NOT_OFFERED)
        val held = HeldReceived(me.raddatz.veyq.mobile.sharing.GuestScreen(view, tenant.value?.models?.session, ok.report))
        val id = ids.incrementAndFetch()
        received.put(id, held)
        held.screen.load()
        pageOf(held)
        return Answer.ReceivedOpened(id)
    }

    /** After a gate: the content where the gates passed. */
    private suspend fun enterReceived(h: HeldReceived): Answer {
        h.screen.enter()
        pageOf(h)
        return Answer.Done
    }

    /**
     * The page the link shows now, as the host draws it: a level's rows; a file's, a gallery's or a Pot's model registered
     * under the existing calls' registries, so `File.*`, `Gallery.*` and `Pot.*` drive it as they drive a member's.
     */
    private suspend fun pageOf(h: HeldReceived) {
        val page = h.screen.view.page.value
        if (page != null && page === h.drawn) return
        h.drawn = page
        // the view's page followed from here on: a reload after a guest's write draws the level anew by itself
        if (h.follow == null) h.follow = scope.launch { h.screen.view.page.collect { if (it !== h.drawn) pageOf(h) } }
        h.sending?.cancel()
        h.sending = null
        if (page == null) return run { h.page.value = null }
        val node = page.node
        fun shown(kind: ReceivedPageKind, rows: List<ReceivedRow> = emptyList(), file: Long? = null, gallery: Long? = null, pot: Long? = null, sent: List<String> = emptyList()) =
            ReceivedPage(kind, node.name, node.kind, rows, file, gallery, pot, sent)
        h.page.value = when (page) {
            is me.raddatz.veyq.mobile.sharing.GuestPage.Level -> ReceivedPage(
                ReceivedPageKind.LEVEL, node.name, node.kind, page.model.rows.map { ReceivedRow(it.nodeId, it.kind, it.name, it.identity) },
                null, null, null, emptyList(), writes = page.model.writes,
            )
            is me.raddatz.veyq.mobile.sharing.GuestPage.File -> shown(ReceivedPageKind.FILE, file = ids.incrementAndFetch().also { filePages.put(it, page.model) })
            is me.raddatz.veyq.mobile.sharing.GuestPage.Gallery -> shown(ReceivedPageKind.GALLERY, gallery = ids.incrementAndFetch().also { galleries.put(it, page.model) })
            is me.raddatz.veyq.mobile.sharing.GuestPage.Pot -> {
                val id = ids.incrementAndFetch()
                pots.put(id, page.model)
                h.screen.view.access?.tree?.node(node.nodeId)?.asObject()?.let { ref -> scope.launch { page.model.open(ref) } }
                shown(ReceivedPageKind.POT, pot = id)
            }
            // what a WRITE_ONLY guest sent shows as it settles: the page follows the model's list while it stands
            is me.raddatz.veyq.mobile.sharing.GuestPage.DropBox -> shown(ReceivedPageKind.DROP_BOX, sent = page.model.sent.value).also { first ->
                h.sending = scope.launch { page.model.sent.collect { sent -> h.page.value = ReceivedPage(first.kind, first.name, first.nodeKind, first.rows, null, null, null, sent) } }
            }
            is me.raddatz.veyq.mobile.sharing.GuestPage.Uploader -> shown(ReceivedPageKind.UPLOADER, sent = page.model.sent.value.map { it.name }).also { first ->
                h.sending = scope.launch { page.model.sent.collect { sent -> h.page.value = ReceivedPage(first.kind, first.name, first.nodeKind, first.rows, null, null, null, sent.map { it.name }) } }
            }
            is me.raddatz.veyq.mobile.sharing.GuestPage.NotShown -> shown(ReceivedPageKind.NOT_SHOWN)
        }
    }

    /** A received link let go: its key zeroed, its page's models dropped from the registries. */
    private fun closeReceived(h: HeldReceived) {
        h.follow?.cancel()
        h.sending?.cancel()
        h.page.value?.let { p ->
            p.file?.let { filePages.remove(it)?.close() }
            p.gallery?.let { galleries.remove(it) }
            p.pot?.let { pots.remove(it) }
        }
        h.screen.leave()
        h.open.value = false
    }

    /** What the host is shown of a kept link's page: where it stands and its link's host — never the link, its password or key. */
    private fun shownBookmark(state: me.raddatz.veyq.mobile.sharing.BookmarkPage.State, inPlace: Long?): BookmarkShown = when (state) {
        me.raddatz.veyq.mobile.sharing.BookmarkPage.State.Loading -> BookmarkShown(BookmarkState.LOADING, "", null)
        me.raddatz.veyq.mobile.sharing.BookmarkPage.State.Unreadable -> BookmarkShown(BookmarkState.UNREADABLE, "", null)
        // in place once the link's screen is held (its id named); until then the page still loads
        is me.raddatz.veyq.mobile.sharing.BookmarkPage.State.InPlace ->
            if (inPlace == null) BookmarkShown(BookmarkState.LOADING, state.host, null) else BookmarkShown(BookmarkState.IN_PLACE, state.host, inPlace)
        is me.raddatz.veyq.mobile.sharing.BookmarkPage.State.OnItsOwn -> BookmarkShown(BookmarkState.ON_ITS_OWN, state.host, null)
    }

    /** What the host is shown of a received link: never the link, its key or anything sealed. */
    private fun shownReceived(h: HeldReceived, gate: me.raddatz.veyq.mobile.sharing.ShareGates.State, busy: Boolean, guest: me.raddatz.veyq.mobile.sharing.GuestSession?, s: me.raddatz.veyq.mobile.sharing.GuestScreen.State, page: ReceivedPage?): ReceivedShown {
        val ready = gate as? me.raddatz.veyq.mobile.sharing.ShareGates.State.Ready
        val terms = gate as? me.raddatz.veyq.mobile.sharing.ShareGates.State.Terms
        return ReceivedShown(
            host = h.screen.host,
            gate = when (gate) {
                me.raddatz.veyq.mobile.sharing.ShareGates.State.Loading -> ReceivedGate.LOADING
                is me.raddatz.veyq.mobile.sharing.ShareGates.State.Refused -> ReceivedGate.REFUSED
                is me.raddatz.veyq.mobile.sharing.ShareGates.State.Locked -> ReceivedGate.LOCKED
                is me.raddatz.veyq.mobile.sharing.ShareGates.State.Terms -> ReceivedGate.TERMS
                is me.raddatz.veyq.mobile.sharing.ShareGates.State.Note -> ReceivedGate.NOTE
                is me.raddatz.veyq.mobile.sharing.ShareGates.State.Ready -> ReceivedGate.READY
                is me.raddatz.veyq.mobile.sharing.ShareGates.State.Unavailable -> ReceivedGate.UNAVAILABLE
            },
            refusal = (gate as? me.raddatz.veyq.mobile.sharing.ShareGates.State.Refused)?.refusal?.let { ReceivedRefusal.valueOf(it.name) },
            askedAgain = (gate as? me.raddatz.veyq.mobile.sharing.ShareGates.State.Locked)?.wrong == true,
            busy = busy,
            terms = terms?.markdown.orEmpty(),
            operatorSection = terms?.operatorSection.orEmpty(),
            record = terms?.record.orEmpty(),
            note = (gate as? me.raddatz.veyq.mobile.sharing.ShareGates.State.Note)?.markdown ?: ready?.note.orEmpty(),
            rootName = ready?.name.orEmpty(),
            role = ready?.role.orEmpty(),
            page = page,
            atRoot = h.screen.view.atRoot,
            guestName = guest?.displayName,
            leaving = s.leaving,
            left = s.left,
            keepOffered = h.screen.keepOffered,
            reportAsked = s.reporting,
            recordDays = h.screen.view.access?.guestWriteRecordDays,
            privacy = h.screen.view.access?.let { "${it.origin}/privacy#guest-write-record" },
        )
    }

    /** The trusted device's link over the connection [connection] while a tenant is open: it closes with the tenant. */
    private fun trustedLink(parts: Composition<A, T>, connection: String, role: DeviceLink.Role): Answer {
        if (tenant.value == null) return noTenant
        val account = parts.session.accounts.firstOrNull { it.key == connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
        val link = parts.trustedLink(account, role, parts.decode) ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
        val id = ids.incrementAndFetch()
        links.put(id, HeldLink(link, trusted = true))
        return Answer.LinkOpened(id)
    }

    /**
     * The new device's link from the sign-in ([link] null: no sign-in shown). Once its tail signed in — also after a
     * `Link.Close` meanwhile (core's [DeviceLink.signedIn]) —, the session it signed in is the open tenant, as a sign-in
     * call's is ([opened]).
     */
    private fun newDeviceLink(parts: Composition<A, T>, link: DeviceLink?): Answer {
        link ?: return Answer.Refused(Answer.Why.NO_SIGN_IN)
        val id = ids.incrementAndFetch()
        links.put(id, HeldLink(link, trusted = false))
        scope.launch {
            if (link.signedIn()) parts.session.signedTo?.let { opened(it) }.also { managed(parts) }
        }
        return Answer.LinkOpened(id)
    }

    /**
     * A link the host is done with: its watch delivers null, its scans leave the registry, and it ends — a new device's
     * whose grant is being spent still signs in (the core runs that tail to its end whatever a close says, ADR 0136's
     * Consequences), and [newDeviceLink] opens its tenant on [DeviceLink.signedIn], not on the state's DONE.
     */
    private fun closeLink(held: HeldLink) {
        held.open.value = false
        held.model.scan?.let { s -> scans.keys().forEach { k -> if (scans[k] === s) { scans.remove(k); linkScans.remove(k) } } }
        held.model.close()
    }
    private val noPage = Answer.Refused(Answer.Why.NO_SUCH_ACCOUNT_PAGE)
    private val noGallery = Answer.Refused(Answer.Why.NO_SUCH_GALLERY)
    private val noLightbox = Answer.Refused(Answer.Why.NO_SUCH_LIGHTBOX)

    /** [act] on the Settings page [id], answered once it ended. */
    private suspend fun onPage(id: Long, act: suspend (AccountSettings<A, T>) -> Unit): Answer {
        val held = accountPages[id] ?: return noPage
        act(held.model)
        return Answer.Done
    }

    /**
     * The account of Settings page [id] signed out on this device ([out], `SessionModel.signOut`): the page closes, and
     * the way in follows as `Session.SignOut`'s does.
     */
    private suspend fun signedOutHere(parts: Composition<A, T>, id: Long, out: SessionModel.SignedOut<A, T>): Answer {
        accountPages.remove(id)?.close()
        if (out.wasCurrent) closeTenant()
        return opened(out.next).also { managed(parts) }
    }

    /** A settings change on [id]'s model, answered once its write ended with whether it was stored and why not. */
    private suspend fun stored(id: Long, change: suspend (PotSettings) -> Boolean): Answer {
        val model = settings[id]?.model ?: return Answer.Refused(Answer.Why.NO_SUCH_SETTINGS)
        val stored = change(model)
        return Answer.Stored(stored, model.current.notice.takeIf { !stored })
    }

    /** A move sheet's state as a write's answer ([Answer.MoveSent]). */
    private fun sent(s: MoveSheet.State) = Answer.MoveSent(s.done, s.undo != null, s.confirming, s.problem)

    /** [act] on the row of trash sheet [trash] listed as [rowId] (`TrashSheet.row`). */
    private suspend fun onRow(trash: Long, rowId: String, act: suspend (TrashSheet, me.raddatz.veyq.mobile.session.NodeTrash.Trashed) -> Unit): Answer {
        val sheet = trashes[trash] ?: return noTrash
        val row = sheet.row(rowId) ?: return Answer.Refused(Answer.Why.NO_SUCH_ROW)
        act(sheet, row)
        return Answer.Done
    }

    /** The open Pots of a node gone to the trash close (a header's `closeOpen`). */
    private fun closeOpen(ref: NodeAt) {
        pots.values().filter { it.current?.ref?.nodeId == ref.nodeId }.forEach { it.close() }
    }

    /** "Manage servers" as the core has it now, when it is open (after a call that changed the servers or their trust). */
    private fun managed(parts: Composition<A, T>) {
        if (manage.value != null) manage.value = shownManaged(parts)
    }

    /** `SessionModel.managed`, each field the core's own ([ManagedShown]). */
    private fun shownManaged(parts: Composition<A, T>): List<ManagedShown> = parts.session.managed().map { m ->
        ManagedShown(m.account.key, m.label, m.trusted, m.level, m.levels, m.tenants.map { t ->
            ManagedShown.Tenant(t.tenant, t.hidden, t.current, t.shown, t.switchable, t.subtitle, t.spaces?.map { s ->
                ManagedShown.Space(s.space, ShellWords.space(s.space), s.hidden, s.canHide)
            })
        })
    }

    private fun Unit?.done(why: Answer.Why = Answer.Why.NO_TENANT_OPEN): Answer = if (this == null) Answer.Refused(why) else Answer.Done

    /** A change of trust's outcome as the call's answer. */
    private fun TrustChange.answer(): Answer = when (this) {
        TrustChange.DONE -> Answer.Done
        TrustChange.CANCELLED -> Answer.Refused(Answer.Why.TRUST_CANCELLED)
        TrustChange.WRONG_PASSWORD -> Answer.Refused(Answer.Why.WRONG_PASSWORD)
        TrustChange.FAILED -> Answer.Refused(Answer.Why.TRUST_FAILED)
    }

    /**
     * A tenant the session opened becomes the open one: its shell read and shown, its pictures read in the background
     * (the letters show meanwhile), every Pot and reader of the one before closed.
     */
    private suspend fun opened(o: SessionModel.Opened<A, T>?): Answer {
        if (o == null) return Answer.Done
        val t = parts.await().tenant(o.session) ?: return Answer.Done
        closeTenant()
        tenant.value = Open(t, o.account, o.tenant)
        // a write of the uploads reads the shell's lists anew and every file page open (a replaced file shows its new content)
        uploads.value = t.uploads?.invoke(queue) {
            scope.launch {
                runCatching { t.shell?.refresh() }
                filePages.values().forEach { it.reload() }
            }
        }
        // Memories' batches settle the core's way (`MediaUploads`): a gallery reads when its medium is in, the lists at the end
        mediaUploads.value = t.mediaUploads?.invoke(queue, parts.await().tiers) { scope.launch { runCatching { t.shell?.refresh() } } }
        // the account's one player over the host's two decks, each held by id for the host's events (ADR 0142 decision 1)
        player.value = parts.await().audio?.let { audio ->
            t.musicPlayer?.invoke({ events -> audio.deck(ids.incrementAndFetch(), deckReaders, events).also { decks.put(it.id, it) } }, audio.controls)
        }
        // the tenant's map services on this device (ADR 0143 decision 2): the shell's place names come from them from now on
        val language = parts.await().language
        mapServices.value = t.mapServices?.invoke { language }
        scope.launch { runCatching { t.loadPictures() } }
        t.shell?.load(openLastUsed = true)
        return Answer.Done
    }

    private fun closeTenant() {
        // the tree and what was derived from it (a person's index, ADR 0144 decision 12) dropped now, not when collected
        tenant.value?.models?.shell?.tenant?.close()
        tenant.value = null
        // a kept link's page is the tenant's: it closes with it, its in-place link let go
        bookmarks.removeAll().forEach(::closeBookmark)
        // the uploads are the tenant's: what still waits or runs is cancelled (the phones' `uploads.clear()` on a switch)
        uploads.value?.close()
        uploads.value = null
        mediaUploads.value?.close()
        mediaUploads.value = null
        queue.clear()
        // the music is the tenant's: its views close, and the player stops — every track it opened released, its decks
        // cleared, the system's controls cleared (ADR 0142 decision 1, as the web's shell unmount stops it)
        musics.removeAll().forEach { it.model.close() }
        buckets.removeAll()
        player.value?.stop()
        player.value = null
        decks.removeAll()
        // the maps are the tenant's (ADR 0143 decision 4): every map's window closed, the editors and Map views gone, and
        // the services closed — the tiles dropped, what is in flight cancelled, the geocoder's queue cleared
        maps.removeAll().forEach { it.close() }
        dates.removeAll()
        places.removeAll().forEach(::closePlace)
        trackLists.removeAll().forEach(::closeTracks)
        mapServices.value?.close()
        mapServices.value = null
        // the curation's dialogs are their pages' (a banner's save running cancelled, its file let go), the person's pages
        // the tenant's (ADR 0144 decision 8): each watch standing on one delivers null
        participantPickers.removeAll().forEach { it.open.value = false }
        captureTimes.removeAll().forEach { it.open.value = false }
        banners.removeAll().forEach { it.close() }
        bannerReaders.removeAll()
        persons.removeAll().forEach { it.open.value = false }
        // the searches are the tenant's (ADR 0146 decision 5): their media documents dropped, nothing revalidated any more
        searches.removeAll().forEach {
            it.open.value = false
            it.model.dispose()
        }
        // a page closes its lightbox, and the lightbox its reader
        galleries.removeAll().forEach { it.close() }
        lightboxes.removeAll().forEach { it.model.close() }
        lightboxReaders.removeAll()
        pots.removeAll().forEach { it.close() }
        editors.removeAll().forEach { it.model.close() }
        settings.removeAll()
        creates.removeAll().forEach { it.sheet.close() }
        filePages.removeAll().forEach { it.close() }
        // a save stops with its tenant: its partial file removed by the sink as its job is cancelled
        saves.removeAll().forEach { save -> scope.launch { save.cancel() } }
        readers.removeAll()
        pageReaders.removeAll()
        headers.removeAll()
        trashes.removeAll()
        moves.removeAll()
        shares.removeAll()
        dropBoxes.removeAll().forEach { it.page.close() }
        reports.removeAll()
        accountPages.removeAll().forEach { it.close() }
        // the account's flows (ADR 0148 decision 11): every model dropped, its secrets zeroed, each watch delivering null
        profiles.removeAll().forEach { it.close() }
        kits.removeAll().forEach { it.close() }
        passwordChanges.removeAll().forEach { it.close() }
        addressChanges.removeAll().forEach { it.close() }
        keyRotations.removeAll().forEach { it.close() }
        erasures.removeAll().forEach { it.close() }
        // the trusted device's links are the tenant's; a new device's (opened before any tenant) stays
        links.keys().forEach { id -> if (links[id]?.trusted == true) links.remove(id)?.let(::closeLink) }
    }

    /**
     * Observes what [request] names: [update] gets the state now and each change after, on a core thread, until
     * [unwatch]. Returns the watch's id, or 0 for a request that is no [Watched] (nothing is delivered then).
     */
    fun watch(request: String, update: (String) -> Unit): Long {
        val watched = runCatching { wire.decodeFromString(Watched.serializer(), request) }.getOrNull() ?: return 0
        val id = ids.incrementAndFetch()
        val job = scope.launch {
            updates(watched).collect { update(wire.encodeToString(Update.serializer(), it)) }
        }
        watches.put(id, job)
        return id
    }

    private suspend fun updates(w: Watched): Flow<Update> = when (w) {
        Watched.Session -> parts.await().session.gate.map { Update.Session(it) }
        Watched.Manage -> manage.map { Update.Manage(it) }
        Watched.Shell -> {
            val parts = parts.await()
            tenant.flatMapLatest { open ->
                val m = open?.models
                m?.shell?.state?.let { state ->
                    combine(state, m.pictures, m.picturesRound, hiddenTurned, viewTurned) { s, pictures, round, _, _ -> Update.Shell(s, s?.let { shown(parts, open, it, pictures.keys, round) }) }
                } ?: flowOf(Update.Shell(null))
            }
        }
        // a file page's own actions join its header's plan: read anew as the page's state changes
        is Watched.Header -> headers[w.header]?.let { h ->
            val page = h.file.flatMapLatest { f -> f?.let { filePages[it]?.state } ?: flowOf(null) }
            val gallery = h.gallery.flatMapLatest { g -> g?.let { galleries[it]?.state } ?: flowOf(null) }
            combine(h.model.state, page, gallery) { _, _, _ -> Update.Header(w.header, shownHeader(h)) }
        } ?: flowOf(Update.Header(w.header, null))
        is Watched.Gallery -> galleries[w.gallery]?.let { g -> g.state.map { Update.Gallery(w.gallery, shownGallery(g, it)) } }
            ?: flowOf(Update.Gallery(w.gallery, null))
        // the poll runs inside this watch's own job: a host's dropped watch cancels it, and it ends with its page
        is Watched.GalleryLive -> flow { galleries[w.gallery]?.live() }
        // the section's follow runs inside this watch's own job, as a gallery's poll: it ends with the watch or the page
        is Watched.SettingsStorageLive -> flow { accountPages[w.settings]?.model?.followStorage() }
        // the heart's state is the gallery's marks (ADR 0149 decision 4): a toggle moves them, not the lightbox's own state
        is Watched.Lightbox -> lightboxes[w.lightbox]?.let { held ->
            combine(held.model.state, held.page.state.map { it.marks to it.marksLoaded }.distinctUntilChanged()) { s, _ -> Update.Lightbox(w.lightbox, shownLightbox(held, s)) }
        }
            ?: flowOf(Update.Lightbox(w.lightbox, null))
        Watched.Player -> player.flatMapLatest { p ->
            p?.state?.map { Update.Player(PlayerShown(it.nowPlaying?.let(NowPlayingShown::of), it.notice?.let { n -> playerNotice(n) })) }
                ?: flowOf(Update.Player(null))
        }
        is Watched.Music -> musics[w.music]?.let { held -> held.model.state.map { Update.Music(w.music, shownMusic(held, it)) } }
            ?: flowOf(Update.Music(w.music, null))
        is Watched.Buckets -> buckets[w.buckets]?.let { held ->
            val page = galleries[held.gallery]
            // the gallery's state too: a bucket's rows carry its badges and tiles as the page's do (#1001)
            if (page == null) held.model.state.map { Update.Buckets(w.buckets, shownBuckets(null, it)) }
            else kotlinx.coroutines.flow.combine(held.model.state, page.state) { s, _ -> Update.Buckets(w.buckets, shownBuckets(page, s)) }
        }
            ?: flowOf(Update.Buckets(w.buckets, null))
        is Watched.Map -> maps[w.map]?.let { held -> held.shown().map { Update.Map(w.map, it) } } ?: flowOf(Update.Map(w.map, null))
        Watched.MapServices -> mapServices.flatMapLatest { services ->
            services?.state?.map { s ->
                val given = services.consentGivenFor
                Update.MapServices(MapServicesShown(HeldMap.mapState(s), given?.tiles, given?.places))
            } ?: flowOf(Update.MapServices(null))
        }
        is Watched.Date -> dates[w.date]?.let { held ->
            held.model.state.map { s ->
                Update.Date(w.date, DateShown(DateMode.valueOf(s.mode.name), s.from, s.to, s.rangeInvalid, s.canSave, dateShown(held.model.autoRange)))
            }
        } ?: flowOf(Update.Date(w.date, null))
        is Watched.Place -> places[w.place]?.let { held ->
            combine(held.model.state, held.page.state, held.open) { s, page, open -> Update.Place(w.place, if (open) shownPlace(held, s, page) else null) }
        } ?: flowOf(Update.Place(w.place, null))
        is Watched.Tracks -> trackLists[w.tracks]?.let { held -> held.model.state.map { Update.Tracks(w.tracks, shownTracks(held, it)) } }
            ?: flowOf(Update.Tracks(w.tracks, null))
        is Watched.Participants -> participantPickers[w.participants]?.let { held ->
            combine(held.model.state, held.open) { s, open -> Update.Participants(w.participants, if (open) shownParticipants(s) else null) }
        } ?: flowOf(Update.Participants(w.participants, null))
        is Watched.CaptureTime -> captureTimes[w.captureTime]?.let { held ->
            combine(held.model.state, held.open) { s, open ->
                Update.CaptureTime(w.captureTime, if (open) CaptureTimeShown(s.atMs, s.corrected, s.originalMs, s.saving, s.said?.let { CurationSaid.valueOf(it.name) }, s.saved) else null)
            }
        } ?: flowOf(Update.CaptureTime(w.captureTime, null))
        is Watched.Banner -> banners[w.banner]?.let { held ->
            combine(held.model.state, held.pick, held.open) { s, pick, open -> Update.Banner(w.banner, if (open) shownBanner(s, pick) else null) }
        } ?: flowOf(Update.Banner(w.banner, null))
        is Watched.Profile -> profiles[w.profile]?.let { h -> combine(h.model.state, h.open) { s, open -> Update.Profile(w.profile, s.takeIf { open }) } }
            ?: flowOf(Update.Profile(w.profile, null))
        is Watched.PasswordChange -> passwordChanges[w.flow]?.let { h ->
            combine(h.model.state, h.model.intake.state, h.model.breach.state, h.open) { s, k, b, open ->
                Update.PasswordChange(w.flow, s.takeIf { open }, k.takeIf { open }, b.takeIf { open })
            }
        }
            ?: flowOf(Update.PasswordChange(w.flow, null))
        is Watched.AddressChange -> addressChanges[w.flow]?.let { h ->
            combine(h.model.state, h.model.intake.state, h.open) { s, k, open -> Update.AddressChange(w.flow, s.takeIf { open }, k.takeIf { open }) }
        }
            ?: flowOf(Update.AddressChange(w.flow, null))
        is Watched.KeyRotation -> keyRotations[w.flow]?.let { h ->
            combine(h.model.state, h.model.intake.state, h.model.breach.state, h.open) { s, k, b, open ->
                Update.KeyRotation(w.flow, s.takeIf { open }, k.takeIf { open }, b.takeIf { open })
            }
        }
            ?: flowOf(Update.KeyRotation(w.flow, null))
        is Watched.Erasure -> erasures[w.flow]?.let { h -> combine(h.model.state, h.open) { s, open -> Update.Erasure(w.flow, s.takeIf { open }) } }
            ?: flowOf(Update.Erasure(w.flow, null))
        is Watched.Kit -> kits[w.kit]?.let { h -> combine(h.model.state, h.open) { s, open -> Update.Kit(w.kit, s.takeIf { open }) } }
            ?: flowOf(Update.Kit(w.kit, null))
        is Watched.Person -> persons[w.person]?.let { held ->
            val pictures = tenant.value?.models?.pictures ?: flowOf(emptyMap())
            val section = held.memories?.state ?: flowOf(null)
            combine(section, pictures, held.open) { m, p, open -> Update.Person(w.person, if (open) shownPerson(held, m, held.profile.userId in p) else null) }
        } ?: flowOf(Update.Person(w.person, null))
        is Watched.Search -> searches[w.search]?.let { held ->
            combine(held.model.state, held.open) { s, open -> Update.Search(w.search, if (open) s else null) }
        } ?: flowOf(Update.Search(w.search, null))
        is Watched.Trash -> trashes[w.trash]?.let { t -> t.state.map { Update.Trash(w.trash, shownTrash(t, it)) } }
            ?: flowOf(Update.Trash(w.trash, null))
        is Watched.Move -> moves[w.move]?.let { m -> m.state.map { Update.Move(w.move, shownMove(m, it)) } }
            ?: flowOf(Update.Move(w.move, null))
        is Watched.Share -> shares[w.share]?.let { sh -> sh.state.map { Update.Share(w.share, shownShare(sh, it)) } }
            ?: flowOf(Update.Share(w.share, null))
        is Watched.Report -> reports[w.report]?.let { r -> r.state.map { Update.Report(w.report, shownReport(r, it)) } }
            ?: flowOf(Update.Report(w.report, null))
        // the poll runs inside this watch's own job: a host's dropped watch cancels it, and it ends with its Pot
        is Watched.PotLive -> flow { pots[w.pot]?.live() }
        is Watched.Editor -> editors[w.editor]?.let { e -> e.model.state.map { Update.Editor(w.editor, shownEditor(e, it)) } }
            ?: flowOf(Update.Editor(w.editor, null))
        is Watched.PotSettings -> settings[w.settings]?.let { held ->
            val pot = pots[held.pot]?.state ?: flowOf(null)
            combine(held.model.state, pot) { s, p -> Update.PotSettings(w.settings, p?.view?.let { shownSettings(s, it, p.saving) }) }
        } ?: flowOf(Update.PotSettings(w.settings, null))
        is Watched.PotCreate -> creates[w.create]?.let { held ->
            combine(held.sheet.state, held.name) { s, name -> Update.PotCreate(w.create, shownCreate(held.sheet, s, name)) }
        } ?: flowOf(Update.PotCreate(w.create, null))
        is Watched.File -> filePages[w.file]?.let { f -> f.state.map { Update.File(w.file, shownFile(f, it)) } }
            ?: flowOf(Update.File(w.file, null))
        is Watched.Save -> saves[w.save]?.let { sv -> sv.state.map { Update.Save(w.save, SaveShown(sv.name, it.written, it.total, it.fraction, it.end, it.notice)) } }
            ?: flowOf(Update.Save(w.save, null))
        is Watched.DropBox -> dropBoxes[w.box]?.let { h -> h.page.state.map { Update.DropBox(w.box, shownDropBox(h.page, it)) } }
            ?: flowOf(Update.DropBox(w.box, null))
        // the page's poll runs inside this watch's own job, as a gallery's: it ends with the watch or the page
        is Watched.DropBoxLive -> flow { dropBoxes[w.box]?.page?.live() }
        is Watched.Scan -> scans[w.scan]?.let { s -> s.state.map { Update.Scan(w.scan, shownScan(it, linkScans[w.scan])) } }
            ?: flowOf(Update.Scan(w.scan, null))
        is Watched.Received -> received[w.received]?.let { h ->
            val gates = h.screen.view.gates
            combine(gates.state, gates.busy, gates.guest, h.screen.state, h.page) { gate, busy, guest, s, page -> gate to listOf(busy, guest, s, page) }
                .combine(h.open) { (gate, rest), open ->
                    @Suppress("UNCHECKED_CAST")
                    Update.Received(
                        w.received,
                        if (open) shownReceived(
                            h, gate, rest[0] as Boolean, rest[1] as me.raddatz.veyq.mobile.sharing.GuestSession?,
                            rest[2] as me.raddatz.veyq.mobile.sharing.GuestScreen.State, rest[3] as ReceivedPage?,
                        ) else null,
                    )
                }
        } ?: flowOf(Update.Received(w.received, null))
        is Watched.Bookmark -> bookmarks[w.bookmark]?.let { b ->
            combine(b.page.state, b.inPlace, b.open) { state, inPlace, open ->
                Update.Bookmark(w.bookmark, if (open) shownBookmark(state, inPlace) else null)
            }
        } ?: flowOf(Update.Bookmark(w.bookmark, null))
        is Watched.Link -> links[w.link]?.let { held ->
            combine(held.model.state, held.open) { s, open -> Update.Link(w.link, if (open) shownLink(s) else null) }
        } ?: flowOf(Update.Link(w.link, null))
        is Watched.Settings -> accountPages[w.settings]?.let { held ->
            combine(held.model.state, held.open) { s, open -> Update.Settings(w.settings, if (open) shownAccount(s) else null) }
        } ?: flowOf(Update.Settings(w.settings, null))
        Watched.Uploads -> uploads.flatMapLatest { u -> u?.state?.map { Update.Uploads(shownUploads(it)) } ?: flowOf(Update.Uploads(null)) }
        is Watched.Pot -> {
            val pictures = tenant.value?.models?.pictures ?: flowOf(emptyMap())
            (pots[w.pot]?.state ?: flowOf(null)).combine(pictures) { s, p -> Update.Pot(w.pot, s, if (s == null) emptyList() else p.keys.sorted()) }
        }
    }

    /**
     * What the shell shows beside [s] ([ShellShown]; ADR 0123's Consequences, the bridge): each field one core
     * function's answer.
     */
    private fun shown(parts: Composition<A, T>, open: Open<A>, s: ShellModel.State, pictures: Set<String>, round: Int): ShellShown {
        val shell = s.shell
        val lists = s.lists
        val space = lists.space(shell)
        return ShellShown(
            tenant = open.tenant,
            server = open.account.label,
            connection = open.account.key,
            servers = parts.session.accounts.map { c ->
                ShellShown.ShownServer(c.key, c.label, parts.hidden.visibleTenants(c.key, c.tenants, if (c == open.account) open.tenant else null))
            },
            space = space,
            spaceName = ShellWords.space(space),
            switcherSubtitle = ShellWords.switcherSubtitle(space, open.tenant.name, open.account.label),
            spaces = lists.spaces.map { ShellShown.ShownSpace(it, ShellWords.space(it)) },
            applicationWords = ApplicationId.entries.map { ShellShown.ShownApplication(it, AppKinds.applicationWord(it)) },
            groups = lists.groups(shell),
            list = shell.application?.let(lists::applicationList),
            tabs = shell.barTabs.map { ShellShown.ShownTab(it, AppKinds.tabWord(it, shell.openObject?.kind)) },
            selectedTab = shell.selectedTab,
            canGoBack = shell.canGoBack,
            isHome = shell.isHome,
            drawerOpensAtStart = shell.drawerOpensAtStart,
            creates = AppKinds.createsWhere(shell.isHome, bookmarks = true).map { ShellShown.ShownCreate(it, it.word) },
            canCreateHere = open.models.shell?.canCreateHere == true,
            topLevelShare = open.models.shell?.offersTopLevelShare(shell) == true,
            headed = shell.bookmark ?: shell.openObject?.at ?: shell.space?.let(shell::folderAt),
            me = open.models.me,
            pictures = pictures.sorted(),
            picturesRound = round,
            offlineBanner = s.banner,
            view = open.models.shell?.levelView?.let { ViewModeShown.valueOf(it.name) } ?: ViewModeShown.valueOf(me.raddatz.veyq.mobile.navigation.ViewMode.FIRST.name),
        )
    }

    /** A Contributions view as the host draws it ([BucketsShown]), each field the model's own answer. */
    private fun shownBuckets(g: me.raddatz.veyq.mobile.memories.GalleryModel?, s: me.raddatz.veyq.mobile.memories.Contributions.State) = BucketsShown(
        rows = s.rows.map { BucketRowShown(it.bucketId, it.label, it.depth, it.mediaCount, it.isPrimary, it.own) },
        selected = s.selected, media = g?.let { page -> s.media.map { galleryRow(page, it) } }.orEmpty(), createsSub = s.createsSub, renames = s.renames,
        moveTargets = s.moveTargets.map { it.bucketId }, loading = s.loading, failed = s.failed, refused = s.refused,
    )

    /** An entry editor as the host draws it ([EditorShown]), each field one core function's answer. */
    private fun shownEditor(e: HeldEditor, s: EntryEditor.State): EditorShown {
        val f = s.form
        val view = pots[e.pot]?.current?.view
        val ids = s.people.map { it.id }
        fun split(split: EntryForm.FormSplit, part: Long) = EditorSplit(
            split.mode, split.expanded, EntryForm.collapsedWords(split, s.people),
            s.people.map { p -> EntryForm.typedIn(split, p.id).let { EditorSplitPerson(p.id, p.name, it != null, it.orEmpty()) } },
            EntryForm.splitHint(split, part, f.currency, f.minorUnits, e.separator),
        )
        fun money(amount: Long) = me.raddatz.veyq.mobile.words.Piece.Money(amount, view?.currency, view?.minorUnits ?: 2)
        val categories = RevisionFormat.DEFAULT_CATEGORIES + view?.ownCategories.orEmpty().map { it.first }
        return EditorShown(
            isNew = s.isNew, editable = s.editable, invalid = s.invalid,
            kind = f.kind, title = f.title, date = f.date, category = f.category,
            categories = view?.let { v -> categories.map { EditorChoice(it, v.categoryLabel(it)) } }.orEmpty(),
            currency = f.currency, currencies = Currencies.known, base = view?.currency.orEmpty(), rate = f.rate, rateFill = s.rate,
            amount = f.amount, from = f.from, to = f.to,
            people = s.people.map { EditorPerson(it.id, it.name) },
            payers = f.payers.map { EditorPayer(it.participant, it.amount) },
            canRemovePayer = f.canRemovePayer, canAddPayer = f.nextPayer(ids) != null,
            total = me.raddatz.veyq.mobile.words.Piece.Money(f.payersTotal, f.currency, f.minorUnits),
            items = f.items.map { EditorItem(it.label, it.amount, split(it.split, f.itemAmount(it))) },
            rest = if (f.kind != EntryForm.Kind.TRANSFER && f.showsRest) split(f.rest, f.restLeft) else null,
            restIsWhole = f.items.isEmpty(),
            preview = s.preview.map { EditorShare(it.name, it.given.takeIf { g -> g != 0L }?.let(::money), money(it.share)) },
            refusal = s.refusal?.let { EditorRefusal(Notice(Notices.entryProblem(it.problem)), it.at, it.item) },
            saving = s.saving, notice = s.notice, asking = s.asking, done = s.done,
            receipts = f.receipts.map { id ->
                val r = view?.receipt(id)
                EditorReceipt(id, me.raddatz.veyq.mobile.expenses.Receipts.downloadName(r?.name, r?.type), held = false, opens = r != null && !r.blocked)
            } + s.pending.map { EditorReceipt(it.id, it.name, held = true, opens = false) },
            canAddReceipt = s.canAddReceipt, refusedPick = s.refusedPick,
        )
    }

    /** An account's Settings as the host draws them ([SettingsShown]), each field the core's state. */
    private fun shownAccount(s: AccountSettings.State) = SettingsShown(
        email = s.email,
        emailConfirmed = s.emailConfirmed,
        device = s.device.label,
        trusted = s.device.trust.method,
        level = s.device.trust.level,
        levels = s.device.trust.levels,
        families = s.families?.map { SettingsFamily(it.id, it.createdAt, it.lastUsedAt, it.current, it.label, it.removesKey) },
        keptDevices = s.savedKeyDevices.map { SettingsKeptDevice(it.deviceId, it.label) },
        otherSavedKeys = s.otherSavedKeys,
        hasOthers = s.hasOthers,
        addDevice = s.addDevice,
        working = s.working,
        refusal = s.refusal,
        registerRefusal = s.registerRefusal,
        storage = s.storage,
        storageUnavailable = s.storageUnavailable,
        askingRemoveAll = s.askingRemoveAll,
        limitUnits = me.raddatz.veyq.mobile.offline.LimitUnit.entries.map { SettingsLimitUnit(it, it.word) },
    )

    /** A Pot's settings as the host draws them ([PotSettingsShown]), each field one core function's answer. */
    private fun shownSettings(s: PotSettings.State, view: me.raddatz.veyq.mobile.expenses.PotView, saving: Boolean) = PotSettingsShown(
        ready = view.ready, canWrite = view.canWrite, saving = saving, notice = s.notice,
        currency = view.currency, currencies = Currencies.known,
        currencyEditable = view.currencyEditable, currencyFixed = view.currencyFixed, canChangeSettings = view.canChangeSettings,
        categoriesShown = view.categoriesShown, canAddCategory = view.canAddCategory,
        categories = view.ownCategories.map { EditorChoice(it.first, view.categoryLabel(it.first)) },
        people = view.personEntries.map { me.raddatz.veyq.mobile.expenses.PotView.ShownPerson(it, view.personFacts(it)) },
        deletedPeople = view.deletedPeople, settleFirst = view.settleFirst, metaShown = view.metaShown,
        members = view.addableMembers.map { PotMember(it.userId, it.name) },
        asking = s.asking?.let { ErasingPerson(it.personId, it.name, it.named) },
    )

    /** "New pot" as the host draws it ([PotCreateShown]), each field one core function's answer. */
    private fun shownCreate(sheet: PotCreateSheet, s: PotCreateSheet.State, name: String) = PotCreateShown(
        spaces = s.spaces.map { ShellShown.ShownSpace(it, ShellWords.space(it)) }, space = s.scopeId,
        currency = s.currency, currencies = Currencies.known,
        people = s.people.map { CreatePerson(it.name, it.userId != null) },
        offered = s.offered.map { PotMember(it.userId, it.displayName) },
        canCreate = sheet.canCreate(name), saving = s.saving, notice = s.notice, done = s.done,
    )

    /** A header as the host draws it ([HeaderShown]), each field the model's own answer — a file page's own actions its. */
    private fun shownHeader(h: HeldHeader): HeaderShown {
        val m = h.model
        val s = m.current
        val page = h.file.value?.let { filePages[it] }?.current?.header
        val gallery = h.gallery.value?.let { galleries[it] }
        fun word(id: String) = page?.label(id) ?: gallery?.label(id) ?: m.label(id, hasCover = s.cover != null)
        fun actions(ids: List<String>) = ids.map { HeaderShown.PlanAction(it, word(it)) }
        val own = page?.own ?: gallery?.headerActions.orEmpty()
        return HeaderShown(
            node = m.node,
            pinned = s.pinned,
            plan = m.plan(h.maxButtons, own)?.let { p -> HeaderShown.Plan(actions(p.buttons), actions(p.menu), actions(p.last)) },
            deleteQuestion = m.deleteQuestion(),
            undo = s.undo,
            gone = s.gone,
            archiveName = s.downloadName ?: NodeExport.fileName(m.node.name),
            cover = s.cover,
            offline = s.offline,
        )
    }

    /** A Memory's or a chapter's page as the host draws it ([GalleryShown]), each field the model's own answer. */
    private suspend fun shownGallery(g: me.raddatz.veyq.mobile.memories.GalleryModel, s: me.raddatz.veyq.mobile.memories.GalleryModel.State) = GalleryShown(
        node = g.node,
        // the rows the filter shows (ADR 0149 decision 5): the grid's and the lightbox's
        rows = s.shown?.map { galleryRow(g, it) },
        filter = if (!s.rights.filterFacts) null else FilterShown(
            offered = true, mode = MarkMode.valueOf(s.filter.mode.name), modeOffered = g.marksModeOffered,
            chips = s.filter.chips.map { ChipShown(me.raddatz.veyq.mobile.memories.GalleryFilter.key(it), chipLabel(it)) },
            text = s.filter.text,
            suggestions = if (s.filter.text.isBlank()) emptyList() else suggestions(g).map { (c, rest) -> ChipShown(me.raddatz.veyq.mobile.memories.GalleryFilter.key(c), chipLabel(c), rest) },
            shown = s.shown?.size ?: 0, total = s.count,
        ),
        contributions = s.rights.buckets && me.raddatz.veyq.mobile.memories.Contributions.OFFERED && tenant.value?.models?.contributions != null,
        markSaid = s.markSaid,
        marking = s.marking && s.marksLoaded,
        failed = s.failed,
        failure = s.failure,
        count = Notice(Msg.MEMORY_ITEMS, s.count),
        chapters = s.chapters.map { NodeAt(it.scopeId, it.nodeId, it.kind, it.name) },
        canUpload = s.rights.upload,
        canSave = s.rights.download,
        creates = AppKinds.Create.entries.filter { it.name in g.creates }.map { ShellShown.ShownCreate(it, it.word) },
        undo = s.undo,
        purging = s.purging,
        purgeQuestion = g.purgeQuestion.takeIf { s.purging != null },
        said = s.said,
        offline = s.offline,
        musicView = me.raddatz.veyq.mobile.memories.music.MusicModel.shown(s),
        musicHere = s.musicHere,
        date = s.facts?.let { dateShown(s.date) },
        place = s.facts?.placeName,
        placePending = s.facts?.location?.pending == true,
        editsFacts = s.editsFacts,
        tracks = s.tracks.size,
        people = s.people?.let { line ->
            PeopleShown(line.people.map(::participant), line.avatars.map { it.userId }, line.more, line.named, line.edits, line.add)
        },
        banner = s.banner?.let { b -> BannerShown(BannerMedium.valueOf(b.medium.name), b.key, b.type) },
        editsBanner = s.editsBanner,
        bannerQuestion = s.bannerPicture,
        uploading = s.uploading.map { t ->
            UploadTileShown(
                t.tileId, t.isVideo, t.progress, t.preparing, t.failed,
                Notice(if (t.failed) Msg.MEMORY_UPLOAD_TILE_FAILED else if (t.preparing) Msg.MEMORY_UPLOAD_TILE_PREPARING else Msg.MEMORY_UPLOAD_TILE, t.name),
            )
        },
        searchOpened = s.searchOpened,
    )

    /** One participant of the people line, as `Participants.Entry` says it. */
    private fun participant(e: me.raddatz.veyq.mobile.memories.Participants.Entry) = ParticipantShown(e.userId, e.name, e.former, e.opensProfile)

    /** The participants picker as the host draws it ([ParticipantsShown]), each field the picker's own answer. */
    private fun shownParticipants(s: me.raddatz.veyq.mobile.memories.ParticipantsPicker.State) = ParticipantsShown(
        members = s.members?.let { s.visible.map { p -> MemberShown(p.userId, p.displayName, s.ticked(p.userId), s.togglable(p.userId)) } },
        former = s.former.map { id -> MemberShown(id, null, s.ticked(id), s.togglable(id)) },
        failed = s.failed,
        query = s.query,
        full = s.full,
        noMatches = s.noMatches,
        nobody = s.nobody,
        canSave = s.canSave,
        saving = s.saving,
        said = when (s.said) {
            null, me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Written, me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Unchanged -> null
            is me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Conflict -> CurationSaid.CONFLICT
            me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Unreadable -> CurationSaid.UNREADABLE
            is me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Refused -> CurationSaid.FAILED
        },
        saved = s.saved,
    )

    /** The banner dialog as the host draws it ([BannerEditorShown]), each field the dialog's own answer; [pick] the pick's number. */
    private fun shownBanner(s: me.raddatz.veyq.mobile.memories.banner.BannerEditor.State, pick: Long) = BannerEditorShown(
        hasBanner = s.hasBanner,
        picking = s.picking,
        picked = s.picked?.let { BannerPickedShown(BannerMedium.valueOf(it.medium.name), it.type, pick) },
        saving = s.saving,
        progress = s.progress,
        said = s.said?.let { BannerSaid.valueOf(it.name) },
        done = s.done,
        canSave = s.canSave,
        canRemove = s.canRemove,
    )

    /** A person's page as the host draws it ([PersonShown]): core's frame, and Memories' section as its state says it. */
    private fun shownPerson(held: HeldPerson, m: me.raddatz.veyq.mobile.memories.people.PersonMemories.State?, picture: Boolean): PersonShown {
        val p = held.profile
        return PersonShown(
            userId = p.userId,
            name = p.name,
            former = p.former,
            picture = picture,
            username = p.handle,
            sections = p.sections,
            memories = m?.let { s ->
                PersonMemoriesShown(
                    loading = s.loading, failed = s.failed, empty = s.empty, partial = s.partial, offersViews = s.offersViews,
                    view = MemoriesView.valueOf(s.view.name),
                    rows = s.rows.map { r -> PersonRowShown(r.ref, r.date?.let(::dateShown), r.placeName, r.group, r.group?.let(ShellWords::space)) },
                    located = s.map.pins.size,
                )
            },
        )
    }

    /** A place editor as the host draws it ([PlaceShown]), each field the editor's own answer. */
    private fun shownPlace(
        held: HeldPlace, s: me.raddatz.veyq.mobile.memories.place.PlaceEditor.State, page: me.raddatz.veyq.mobile.memories.GalleryModel.State,
    ) = PlaceShown(
        withMap = s.withMap,
        mode = PlaceMode.valueOf(s.derivedMode.name),
        name = s.name,
        naming = PlaceNaming.valueOf(s.nameSource.name),
        levels = s.levels.map { LevelShown(it.key.token, it.name) },
        chosenLevel = s.chosenLevel?.token,
        levelPicker = s.showLevelPicker,
        canSave = s.canSave,
        canFollow = held.model.medoid != null,
        canRemove = page.facts?.location != null,
    )

    /** A Map view as the host draws it ([TracksShown]), each field the list's own answer. */
    private fun shownTracks(held: HeldTracks, s: me.raddatz.veyq.mobile.memories.tracks.TrackList.State) = TracksShown(
        node = held.model.gallery.node,
        tracks = s.entries?.map { e ->
            TrackShown(
                mediaId = e.mediaId, name = e.name, number = e.number, fileTrackName = e.fileTrackName, ownName = e.ownName,
                activity = e.activity, dash = e.dash, fileName = e.fileName, color = e.color, status = TrackStatus.valueOf(e.status.name),
                facts = e.facts.map { f ->
                    when (f) {
                        is me.raddatz.veyq.mobile.memories.tracks.TrackFacts.Fact.Distance -> TrackFactShown(f.value, f.kilometres, null)
                        is me.raddatz.veyq.mobile.memories.tracks.TrackFacts.Fact.Words -> TrackFactShown(null, false, f.notice)
                    }
                },
                shown = e.mediaId !in s.hidden, selected = s.selected == e.mediaId, blocked = e.blocked, canRemove = held.model.removes(e.mediaId),
            )
        },
        activities = me.raddatz.veyq.mobile.memories.music.TrackLabel.ACTIVITIES,
        canAdd = s.rights.add,
        canEdit = s.rights.edit,
        canDownload = s.rights.download,
        canReport = s.rights.report,
        offline = s.offline,
        checking = s.checking,
        asking = s.asking.map { AskedTrackShown(it.fileName, it.fileTrackName) },
        said = s.said,
    )

    /** Why the last queue ended by itself, in words ([PlayerShown.notice]). */
    private fun playerNotice(n: me.raddatz.veyq.mobile.audio.PlayerNotice): Notice = when (n) {
        me.raddatz.veyq.mobile.audio.PlayerNotice.UNPLAYABLE -> Notice(Msg.MUSIC_NOTHING_PLAYS)
    }

    /** A Music view as the host draws it ([MusicShown]), each field the model's own answer. */
    private fun shownMusic(held: HeldMusic, s: me.raddatz.veyq.mobile.memories.music.MusicModel.State) = MusicShown(
        node = held.model.gallery.node,
        tracks = s.tracks?.map { t ->
            MusicTrackShown(
                mediaId = t.mediaId, title = t.title, untitled = if (t.title == null) Notice(Msg.MUSIC_UNTITLED, t.number) else null,
                ownName = t.track.ownName, fileName = t.track.fileName, length = t.length, playing = t.playing,
                unplayable = t.unplayable, blocked = t.blocked, canRemove = held.model.removes(t.mediaId),
            )
        },
        canAdd = s.rights.add,
        canRename = s.rights.rename,
        canDownload = s.rights.download,
        canReport = s.rights.report,
        offline = s.offline,
        checking = s.checking,
        said = s.said,
    )

    /** One medium as a gallery's row and a lightbox's page show it ([GalleryRow]), with its mark's badge. */
    private fun galleryRow(g: me.raddatz.veyq.mobile.memories.GalleryModel, r: me.raddatz.veyq.mobile.memories.GalleryModel.Row): GalleryRow {
        val mark = g.current.marks[r.mediaId]
        val personal = g.current.marks.values.any { it.count == null }
        return GalleryRow(
            r.mediaId, r.name, r.type, r.isVideo, r.blocked, r.tile, r.picture, r.capturedAt, r.width, r.height, r.said, g.deletes(r.mediaId),
            marks = mark?.count ?: if (personal) null else 0, mine = mark?.mine == true,
        )
    }

    /** A filter chip as it reads: a year by its number, a day, a place or a kind by its label. */
    private fun chipLabel(c: me.raddatz.veyq.mobile.memories.GalleryFilter.Chip): String = when (c) {
        is me.raddatz.veyq.mobile.memories.GalleryFilter.Chip.Day -> c.label
        is me.raddatz.veyq.mobile.memories.GalleryFilter.Chip.Search -> when (val k = c.chip) {
            is me.raddatz.veyq.mobile.search.SearchChip.Year -> k.year.toString()
            is me.raddatz.veyq.mobile.search.SearchChip.Place -> k.label
            is me.raddatz.veyq.mobile.search.SearchChip.Kind -> k.label
            else -> me.raddatz.veyq.mobile.search.Query.chipKey(k)
        }
    }

    /** A lightbox as the host draws it ([LightboxShown]), each field the model's own answer; none once it closed. */
    private fun shownLightbox(held: HeldLightbox, s: me.raddatz.veyq.mobile.memories.Lightbox.State): LightboxShown? {
        if (s.closed) return null
        val row = s.row
        return LightboxShown(
            index = s.index,
            count = s.count,
            position = row?.let { Notice(Msg.MEMORY_ITEM_POSITION, s.index + 1, s.count) },
            row = row?.let { galleryRow(held.page, it) },
            playing = s.playing,
            slideshow = s.slideshow?.let { SlideshowState.valueOf(it.name) },
            downloadName = held.model.downloadName()?.takeIf { held.page.rights.download },
            canTrash = row != null && held.page.deletes(row.mediaId),
            music = s.music?.let(NowPlayingShown::of),
            canMark = held.model.canMark,
            marked = row?.let { held.page.current.marks[it.mediaId]?.mine } == true,
            // a photo's or a video's report (ADR 0149 decision 6), not a blocked one's; the server may still take none
            canReport = row != null && !row.blocked && held.page.current.offline == null,
        )
    }

    /** The uploads as the host draws them ([UploadsShown]), each field the model's or a core function's answer. */
    private fun shownUploads(s: me.raddatz.veyq.mobile.depot.DepotUploads.State): UploadsShown {
        val q = s.queue
        return UploadsShown(
            summary = Notices.uploadSummary(q),
            active = q.active,
            progress = q.progress,
            preparing = s.preparing?.let { Notice(Msg.UPLOAD_PREPARING_FOLDER, it) },
            batches = q.batches.map { b ->
                UploadsShown.Batch(b.id, b.label, b.finished, b.items.map { i ->
                    UploadsShown.Item(
                        i.id, i.name, i.sizeBytes, i.status, i.progress, Notices.uploadStatus(i),
                        failed = i.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED, canCancel = i.active, canRetry = i.retryable,
                    )
                })
            },
            said = s.said?.let { UploadsShown.Said(it.id, it.notices, it.action) },
        )
    }

    /** A file's page as the host draws it ([FileShown]), each field the model's own answer. */
    private fun shownFile(f: me.raddatz.veyq.mobile.depot.FileModel, s: me.raddatz.veyq.mobile.depot.FileModel.State): FileShown {
        val shown = s.shown
        val view = when (shown) {
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Loading -> FileShown.View.LOADING
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Failed -> FileShown.View.FAILED
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.NotUploaded -> FileShown.View.NOT_UPLOADED
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Message -> FileShown.View.MESSAGE
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Image -> FileShown.View.IMAGE
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Text -> FileShown.View.TEXT
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Pdf -> FileShown.View.PDF
            is me.raddatz.veyq.mobile.depot.FileModel.Shown.Media -> FileShown.View.MEDIA
        }
        return FileShown(
            node = f.node, view = view, words = me.raddatz.veyq.mobile.depot.FileModel.words(shown), mediaType = f.mediaType(shown),
            edge = (shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Image)?.edge,
            text = (shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Text)?.text, truncated = me.raddatz.veyq.mobile.depot.FileModel.cut(shown),
            size = (shown as? me.raddatz.veyq.mobile.depot.FileModel.Shown.Media)?.size,
            revision = s.revision, facts = s.facts, uploader = s.uploader,
            headerFacts = s.header?.facts, downloadName = s.header?.downloadName,
        )
    }

    /** A report sheet as the host draws it ([ReportShown]), each field the sheet's own answer. */
    private fun shownReport(r: ReportSheet, s: ReportSheet.State): ReportShown = ReportShown(
        node = r.node, categories = r.categories.map { ReportShown.ShownCategory(it.token, it.word) }, whole = s.whole,
        scopeHint = s.scopeHint, category = s.category, note = s.note, reasonMax = NodeReport.REASON_MAX,
        contactMax = NodeReport.CONTACT_MAX, goodFaith = s.goodFaith, canSend = s.canSend, sending = s.sending, problem = s.problem,
        sent = s.sent != null, reference = s.sent?.reference, receivedAt = s.sent?.receivedAt, privacyUrl = r.privacyUrl,
        offersName = r.offersName,
    )

    /** A move or copy sheet as the host draws it ([MoveShown]), each field the sheet's own answer. */
    private fun shownMove(m: MoveSheet, s: MoveSheet.State): MoveShown = MoveShown(
        node = m.node, copying = m.copying, title = m.title, hint = m.hint, submit = m.submit, loaded = s.loaded,
        trail = s.trail.map { MoveShown.MoveCrumb(it.nodeId, it.name) },
        places = s.places.map { MoveShown.MovePlace(it.node.nodeId, it.node.kind, it.node.name, it.blocked?.let(NodeMove::placeWord)) },
        empty = m.empty.takeIf { s.loaded && s.places.isEmpty() },
        here = m.hereWord(s), problem = s.problem, noRoom = s.noRoom, losses = s.losses.map { Notice(it) }, leftOut = s.leftOut,
        busy = s.busy, canChoose = s.canChoose, confirming = s.confirming, confirmQuestion = m.confirmQuestion, done = s.done, undo = s.undo,
    )

    /** A share sheet as the host draws it ([ShareShown]), each field the sheet's own answer. */
    /** A drop box's page as the host draws it ([DropBoxShown]), each field the page's or the words' own answer. */
    private fun shownDropBox(page: me.raddatz.veyq.mobile.depot.DropBoxPage, s: me.raddatz.veyq.mobile.depot.DropBoxPage.State): DropBoxShown {
        val asked = s.deleting
        return DropBoxShown(
            node = page.node,
            folders = s.folders?.map { f ->
                DropBoxShown.ShownGuestFolder(
                    f.node.id, f.label, Notice(Msg.DROPBOX_GUEST_FILES, f.files.size), Notice(Msg.DROPBOX_MOVE_FOLDER, f.label),
                    Notice(Msg.DROPBOX_DELETE_FOLDER, f.label), f.files.mapNotNull { it.asObject() },
                )
            },
            failed = s.failed, empty = Msg.DROPBOX_EMPTY, hint = Msg.DROPBOX_HINT, deleting = asked?.node?.id,
            deleteTitle = asked?.let { Notice(Msg.DROPBOX_DELETE_FOLDER, it.label) },
            deleteQuestion = asked?.let { Notice(Msg.DROPBOX_DELETE_FOLDER_CONFIRM, it.label) },
            deleteAccept = Msg.DROPBOX_DELETE_FOLDER_ACCEPT,
        )
    }

    private fun shownShare(sh: ShareSheet, s: ShareSheet.State): ShareShown = ShareShown(
        node = sh.node, atTopLevel = sh.atTopLevel, title = sh.title, intro = sh.words.intro, canMint = sh.canMint,
        links = s.links?.let {
            sh.rows(s).map { r ->
                val l = r.link
                ShareShown.ShownLink(
                    l.shareId, r.title, r.badges.map { Notice(it) }, l.createdAt, l.expiresAt, l.url.takeIf { r.canCopy }, r.canCopy, r.canEdit,
                    r.canRenew, r.canSwitchOff, r.canReactivate, r.canDelete,
                )
            }
        },
        failed = s.failed, busy = s.busy, notice = s.notice,
        form = s.form?.let { f ->
            ShareShown.ShownForm(
                f.roles.map { ShareShown.ShownForm.ShownRole(it, sh.words.roleWord(it)) }, f.role, sh.words.hint(f.role), f.namesIt,
                f.nameLabel, f.label,
                ShareShown.ShownForm.ShownLock(f.withPassword, f.passwordOptional, f.hadPassword, f.passwordTooShort, sh.passwordHint),
                ShareShown.ShownForm.ShownEnd(f.withEnd, f.endOptional, f.endDay, f.today, f.lastDay, sh.endHint),
                f.canSave,
            )
        },
        editing = s.editing, submit = sh.submit(s), saving = s.saving, problem = s.problem, freshAddress = s.fresh,
        renewing = s.renewing, deleting = s.deleting, renewQuestion = Msg.SHARE_RENEW_CONFIRM, deleteQuestion = sh.words.deleteConfirm,
    )

    /** A trash sheet as the host draws it ([TrashShown]), each field the sheet's own answer. */
    private fun shownTrash(sheet: TrashSheet, s: TrashSheet.State): TrashShown = TrashShown(
        rows = s.rows?.map { TrashShown.Row(it.id, it.kind, it.name, it.deletedAt, it.purgeAfter, sheet.canRestore(it), s.retryable(it)) },
        failed = s.failed, busy = s.busy, errorRow = s.errorRow, error = s.error, renaming = s.renaming, retryName = s.retryName,
    )

    /** A link's state as the host draws it: the core's, field for field, under the wire's own enum names. */
    private fun shownLink(s: DeviceLink.Shown): LinkShown = LinkShown(
        LinkRole.valueOf(s.role.name), LinkStep.valueOf(s.step.name), s.number, s.code, s.label,
        s.refusal?.let { LinkRefusal.valueOf(it.name) }, s.hint?.let { LinkHint.valueOf(it.name) },
    )

    private fun shownScan(s: me.raddatz.veyq.mobile.qr.QrScan.State<*>, linkFound: String? = null): ScanShown = when (s) {
        me.raddatz.veyq.mobile.qr.QrScan.State.Starting -> ScanShown(ScanState.STARTING, null)
        me.raddatz.veyq.mobile.qr.QrScan.State.NoCamera -> ScanShown(ScanState.NO_CAMERA, null)
        me.raddatz.veyq.mobile.qr.QrScan.State.Denied -> ScanShown(ScanState.DENIED, null)
        me.raddatz.veyq.mobile.qr.QrScan.State.Scanning -> ScanShown(ScanState.SCANNING, null)
        is me.raddatz.veyq.mobile.qr.QrScan.State.Found -> ScanShown(ScanState.FOUND, linkFound ?: foundAs(s.value!!))
        me.raddatz.veyq.mobile.qr.QrScan.State.Closed -> ScanShown(ScanState.CLOSED, null)
    }

    fun unwatch(id: Long) {
        watches.remove(id)?.cancel()
    }

    /** Ends everything: the watches, the calls under way, the models. */
    fun shutdown() {
        scans.removeAll().forEach { it.close() }
        links.removeAll().forEach { it.model.close() }
        bookmarks.removeAll().forEach { runCatching { it.page.close() } }
        received.removeAll().forEach { runCatching { it.screen.leave() } }
        scope.cancel()
    }

    companion object {
        /** The bridge over the real accounts: [primitives] the platform's suite, [client] its HTTP client, [store] the host's device store. */
        fun live(
            primitives: Primitives,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            config: Config,
            store: DeviceStore,
            /** The device's keys over [store]: the platform's (`platformDeviceKeys`), a test's own on the JVM. */
            keys: (DeviceStore) -> DeviceKeys,
            files: DeviceFiles,
            /** A kit file's drawings by its path: the host's ([HostPictures]). */
            pictures: (path: String) -> me.raddatz.veyq.mobile.account.KitPictures = { NoPictures },
            /** A Memories upload's tiers: the host's ([HostTiers]). */
            tiers: me.raddatz.veyq.mobile.memories.MediaUpload.Deriver = NoTiers,
            /** The music's decks, system controls, measure and words: the host's ([HostAudio]); none: no player. */
            audio: HostAudio? = null,
            /** A picked banner's decoding: the host's ([HostBannerPictures]). */
            bannerPictures: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures = NoBannerPictures,
            /** A WebP as a PNG ([Call.PictureDecode]): the core's libwebp (`WebpPicture.png`). */
            webp: (ByteArray) -> ByteArray? = { null },
            /** One's own picture cropped square by the host ([HostSquarePicture]). */
            squarePicture: (path: String, pose: me.raddatz.veyq.mobile.session.CoverFrame.Pose, edge: Int) -> ByteArray? = { _, _, _ -> null },
            /** One's own picture's WebP encoder: the core's libwebp (`LibwebpEncoder`); none on the JVM. */
            profileEncoder: me.raddatz.veyq.mobile.media.WebpEncoder? = me.raddatz.veyq.mobile.media.coreWebpEncoder,
            /** The search's words in the host's language: the host's ([HostWords]). */
            words: me.raddatz.veyq.mobile.search.SearchWords = me.raddatz.veyq.mobile.search.SearchWords { it.key },
            /** The gallery filter's day chips' calendar (inbox #989): the desktop's zone in the library ([LocalCalendar]); UTC on the JVM. */
            calendar: me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar = me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar.UTC,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
            /**
             * Where a received link's guest sessions are kept (ADR 0150 decision 3): the host's credential store — the Secret
             * Service, the credential locker (cr-app-received-links phases 9, 10); in memory until a host hands its own.
             */
            guestSessions: me.raddatz.veyq.mobile.sharing.GuestSessions = me.raddatz.veyq.mobile.sharing.MemoryGuestSessions(),
        ): Bridge<VeyqAccount, TenantSession> = Bridge {
            val recent = RecentUses(
                persist = { keys -> store.write(RECENT, keys.joinToString("\n") { it.encode() }) },
                initial = store.read(RECENT)?.lines()?.mapNotNull(UseKey::decode).orEmpty(),
            )
            val hidden = Hidden(
                persist = { ids -> store.write(HIDDEN, ids.joinToString("\n")) },
                initial = store.read(HIDDEN)?.lines()?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            )
            // every host of the bridge is a desktop (Linux, Windows): a store starts with the desktop's limit (ADR 0140 decision 8)
            val device = ThisDevice(
                store, keys(store), config.device, primitives,
                offlineRoot = config.offlineRoot, offlineLimit = me.raddatz.veyq.mobile.offline.AccountOffline.DESKTOP_LIMIT,
            )
            Composition(
                SessionModel.accounts(primitives, device, client, hidden, recent), hidden, files, pictures = pictures,
                settings = { session, account, separator -> AccountSettings.of(session, account, separator) }, tiers = tiers, audio = audio,
                bannerPictures = bannerPictures, language = config.language, webp = webp, words = words,
                trustedLink = { account, role, decode ->
                    when (role) {
                        DeviceLink.Role.SHOW_FOR_NEW_DEVICE -> DeviceLink.showForNewDevice(account)
                        DeviceLink.Role.SCAN_SIGN_IN_CODE -> DeviceLink.scanSignInCode(account, decode)
                        else -> null
                    }
                },
                accountFlows = { account, on, open ->
                    AccountFlows(
                        { account.passwordChange() }, { account.addressChange(on ?: account.defaultTenant) },
                        { account.keyRotation(open = open) }, { account.erasure() },
                    )
                },
                squarePicture = squarePicture,
                received = { link, queue, password, music ->
                    me.raddatz.veyq.mobile.sharing.GuestView.open(
                        primitives, link, client, store, guestSessions, { config.language }, handedPassword = password, queue = queue,
                        deriver = tiers, now = now, music = music,
                    )
                },
            ) { t ->
                val shell = ShellModel(t, hidden, recent, now)
                Tenant(
                    shell, { PotModel(t, now) }, t.account.userId, t.pictures.state, t.pictures::load, t.pictures.held.map { it.round },
                    ownProfile = { t.profile(profileEncoder) }, trust = t.personTrust, spaces = { t.spaces() }, session = t,
                    gallery = { node, uploads, music -> shell.galleryModel(node, uploads, music, calendar) },
                )
            }
        }

        /** The device store's names for what the bridge itself keeps there (the others are the core's). */
        const val RECENT = "veyq.recent"
        const val HIDDEN = "veyq.hidden"
    }
}
