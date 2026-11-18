package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.audio.MusicPlayer
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.memories.music.MusicQueue
import me.raddatz.veyq.mobile.memories.music.MusicTrack
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeader
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A Memory's or a chapter's page (Memories' `MemoryDetail.vue` with `MediaGrid.vue`, ADR 0136 decision 2): the chapters a
 * Memory holds, then its media as [Row]s in the grid's order ([MemoryMedia.order]), each tile the grid's tier
 * ([MemoryMedia.Item.gridBlob]) read when the platform asks for it ([tile]) — the core caches no bytes; decoding and the
 * decoded picture's cache are the platform's (ADR 0136 decision 1).
 *
 * **When it reads anew is the core's:** on each revision of the shell's lists ([Door.revisions]), and when a medium this
 * device uploaded into this node is in ([Door.uploaded], [MediaUploads]); nothing else re-reads it. A lightbox opened on it
 * ([lightbox]) follows each read. The details' uploaders are read once per gallery for every owner key it shows. While a
 * screen shows the page it runs [live] (the web's poll, `mediaRevisionPolling.ts`: core's [LivePoll] at the item lists'
 * cadence, a conditional read that reads the gallery anew only when the node's media moved, and one of the scope's tree
 * that reads the shell's lists — and so this page — anew only when the tree moved: a peer's change of the node's meta,
 * [Door.treeMoved]), ended when the page closes.
 *
 * **One page for a member and a link's guest** (share view = member view, inbox #401): what it offers is
 * [MemoryRights.of] who looks ([State.rights]) — an upload, a delete, a chapter made —, never a view of its own. A medium
 * deleted goes the way the rights say: a member's to the trash, out of the rows at once with its Undo offered for
 * [ShellModel.TRASH_UNDO_MS] ([trash], [undo]: restore is reached only from that Undo, as the web's); a guest's own one is a
 * purge, asked first and without Undo ([askPurge], [purge]).
 *
 * **Offline** (ADR 0140 decisions 5 and 7) the page shows the listing the offline store kept, [State.offline] saying as
 * of when; a tile it did not keep reads as none. It is read-only then: [State.rights] offers no write, and every write
 * is refused with one notice ([Msg.OFFLINE_READ_ONLY]). Back online it reads anew.
 *
 * **Its music** (ADR 0142 decisions 4–6 and 13, with [music]: the account's one player): the node's audio media are its
 * music, never rows — [State.music], listed and numbered as its Music view lists them ([MusicQueue.tracks]) —; the header's
 * music action plays or stops them ([MUSIC], [toggleMusic]), a track's delete is a medium's ([trash]), and a video the
 * lightbox plays interrupts what plays ([Lightbox.videoPlaying]).
 *
 * Each app draws [state] (or [watch]); [close] when the page goes (a lightbox on it closes with it).
 */
class GalleryModel(
    val node: NodeAt,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Where an original's save runs ([Lightbox.save]): not the page's own [scope], so a save outlives its page. */
    private val saves: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Who looks: the apps open a gallery as a member today; a link's guest is a source, never another view. */
    access: MemoryRights.Access = MemoryRights.Access.Member(),
    /** The account's music player and how an untitled track is named; null: the page plays no music. */
    val music: Music? = null,
    /** The tenant's search (ADR 0146 decision 8): what the page contributes and the request it takes; null: none. */
    private val search: Search? = null,
    /** A clock in milliseconds for the live poll's catch-up gap (only its differences count). */
    private val now: () -> Long = monotonicMs(),
) {
    /**
     * The tenant's search as a page meets it (ADR 0146 decision 8; the web's `contribution.ts` and `openRequest.ts`): the
     * page contributes its media as documents at no fetch of their own ([contributions]), and takes a request the search
     * left to open one of them in the lightbox ([requests]).
     */
    class Search(val requests: me.raddatz.veyq.mobile.search.OpenRequests, val contributions: me.raddatz.veyq.mobile.search.SearchContributions)

    /** What the page plays its music with (ADR 0142 decision 1): the account's one [player]; [untitled] names a track by its number. */
    class Music(val player: MusicPlayer, val untitled: (Int) -> String)

    /** What the page reads ([me.raddatz.veyq.mobile.navigation.ShellModel.galleryModel] over a tenant, a test over its own). */
    interface Door {
        /** The node's gallery as listed now (`MemoryMedia.list`); null or a throw where it did not read. */
        suspend fun gallery(): MemoryMedia.Gallery?

        /** Every node of the node's space this device opens (a Memory's chapters are among them). */
        suspend fun scopeNodes(): List<NodeRef>

        /** [blob] of [item], whole (a tile, a lightbox's picture); null where it does not read. */
        suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray?

        /** [item]'s original read at any offset (a video played); null where it does not open. */
        fun reader(item: MemoryMedia.Item): RangeReader?

        /** [item]'s original handed to [sink] a frame at a time; false where it did not open. */
        suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean

        /** [item]'s heavy EXIF tier; null where none was sealed. */
        suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif?

        /** Who added each of [items], by owner key (`MediaDetails.uploaders`). */
        suspend fun uploaders(items: List<MemoryMedia.Item>): Map<String, MediaDetails.Uploader>

        /** The picked [sources] queued into this node ([MediaUploads.upload]); how many were. */
        fun upload(sources: List<UploadSource>): Int

        /** Each revision of the shell's lists (its first value: the lists as they are when the page opens). */
        val revisions: Flow<Any?>

        /** The node ids a medium this device uploaded went into, each as it is in ([MediaUploads.uploaded]). */
        val uploaded: Flow<String>

        /**
         * Whether the node's media moved since the listing [since] (the conditional read, `listNodeMedia` with its revision
         * as `If-None-Match`: a `304` is no); a throw where the read failed. Absent: never (no live poll).
         */
        suspend fun moved(since: MemoryMedia.Gallery): Boolean = false

        /**
         * Whether the node's scope tree moved since it was read (a conditional `listNodes`, ADR 0081: a `304` is no) — the
         * node's meta among it: participants, date, place, banner —, the shell's lists read anew where it did, which this page
         * follows through [revisions] (inbox #953; the web's open node follows the same signal, `useLevelLive.ts`). A throw
         * where the read failed. Absent: never.
         */
        suspend fun treeMoved(): Boolean = false

        /** Medium [mediaId] to the node's trash (a member's delete). Absent: refused. */
        suspend fun trash(mediaId: String): Removal = Removal.FAILED

        /** Medium [mediaId] back from the trash (a member's Undo). Absent: refused. */
        suspend fun restore(mediaId: String): Removal = Removal.FAILED

        /** Whether [mediaId] is one this device sent under the guest's link identity (a guest's own set). Absent: none. */
        fun isOwn(mediaId: String): Boolean = false

        /** A guest's own medium [mediaId] purged for good; whether it went. Absent: refused. */
        suspend fun purge(mediaId: String): Boolean = false

        /** A chapter [name] made in this Memory. Absent: refused. */
        suspend fun createChapter(name: String): MemoryCreate.Made = MemoryCreate.Made(null, MemoryCreate.refused(null))

        /** Node [nodeId]'s cover picture as stored (core's `NodeCover`), or null where it has none. Absent: none. */
        suspend fun cover(nodeId: String): ByteArray? = null

        /** Whether the account reaches its server: false while it is offline. Absent: always. */
        val online: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)

        /** The node's date and place as its tree holds them now (`MemoryFacts.of`); null where it does not read. Absent: none. */
        suspend fun facts(): me.raddatz.veyq.mobile.memories.place.MemoryFacts.Facts? = null

        /**
         * The settle over the node's [items] (`MemoryFacts.reconcile`: the `auto` range, then the `auto` place and its name);
         * whether it wrote. Absent: nothing.
         */
        suspend fun settle(items: List<MemoryMedia.Item>): Boolean = false

        /**
         * Photo [item]'s place name resolved once and sealed where [mayWrite] and no [slideshow] (`PhotoPlace.resolve`); the
         * name sealed, or null. Absent: none.
         */
        suspend fun placeName(item: MemoryMedia.Item, mayWrite: Boolean, slideshow: Boolean): String? = null

        /**
         * What this device is sending into the node now, in the order queued (`MediaUploads.tiles`): the grid's placeholder
         * tiles (ADR 0145 decision 3). Absent: none.
         */
        val uploading: Flow<List<UploadTile>> get() = kotlinx.coroutines.flow.flowOf(emptyList())

        /** The node is in a personal scope, which has no participants (ADR 0144 decision 1). Absent: not. */
        val personal: Boolean get() = false

        /** The scope's current members (`TenantSession.scopeMembers`); null where they did not read. Absent: none read. */
        suspend fun members(): List<me.raddatz.veyq.mobile.session.Person>? = null

        /** The node's participants set to [wanted], the editor having opened with [openedWith] (`Participants.set`). Absent: refused. */
        suspend fun setParticipants(wanted: List<String>, openedWith: List<String>): me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome =
            me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Refused(-1, null)

        /** Medium [item]'s capture time corrected to [overrideMs], or reset for null (`CaptureTime`). Absent: refused. */
        suspend fun captureTime(item: MemoryMedia.Item, overrideMs: Long?): CaptureTime.Outcome = CaptureTime.Outcome.Failed("refused")

        /** The picture the node's banner draws — an image, a video's poster — as stored (`Banner.picture`); null for none. Absent: none. */
        suspend fun bannerPicture(): ByteArray? = null

        /** The node's video banner read at any offset (`Banner.reader`); null for none. Absent: none. */
        suspend fun bannerReader(): me.raddatz.veyq.mobile.blob.RangeReader? = null

        /**
         * [picked] made the node's banner, the dialog having opened on [openedWith], an equal medium of [items] shared
         * (`BannerWrite.set`). Absent: refused.
         */
        suspend fun setBanner(
            openedWith: me.raddatz.veyq.mobile.memories.banner.Banner.Slots, picked: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Picked,
            items: List<MemoryMedia.Item>, progress: (Double) -> Unit,
        ): me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome = me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Refused("refused")

        /** The node's banner removed, the dialog having opened on [openedWith] (`BannerWrite.remove`). Absent: refused. */
        suspend fun removeBanner(openedWith: me.raddatz.veyq.mobile.memories.banner.Banner.Slots): me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome =
            me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Refused("refused")
    }

    /** What a trash or a restore of a medium came to (`deleteMedia`, `restoreMedia`). */
    enum class Removal {
        DONE,
        /** Already gone from this node, or no access (`404`). */
        GONE,
        /** The space is being deleted (`409`). */
        SCOPE_DELETING,
        /** The operator blocked it: deleted, never restored (`451`). */
        BLOCKED,
        FAILED,
    }

    /** One medium as the page shows it. */
    class Row internal constructor(internal val item: MemoryMedia.Item) {
        val mediaId: String get() = item.mediaId
        /** The original's sealed name (a claim). */
        val name: String? get() = item.name
        /** The original's sealed type (a claim; a player is handed it). */
        val type: String? get() = item.type
        val isVideo: Boolean get() = item.isVideo
        /** The operator blocked it: no bytes are served, the lightbox says [said]. */
        val blocked: Boolean get() = item.blocked
        /** The blob the grid draws ([tile] reads it; a platform's cache keys by it); null: the grid shows an icon. */
        val tile: String? get() = item.gridBlob?.blobId?.takeIf { !item.blocked }
        /** The blob the lightbox shows ([Lightbox.picture] reads it); null for a video without a poster and a blocked one. */
        val picture: String? get() = item.lightboxBlob.blobId.takeIf { item.hasPicture && !item.blocked }
        /** The effective capture time (epoch ms), the platform formats it; null where none is known. */
        val capturedAt: Long? get() = item.meta?.effectiveCapturedAt
        val width: Int? get() = item.meta?.width
        val height: Int? get() = item.meta?.height
        /** Its tile's aspect in the justified grid ([MediaGridLayout.displayAspect] of its sealed dimensions). */
        val aspect: Double get() = MediaGridLayout.displayAspect(width, height)
        /** What the lightbox says in its picture's place: a blocked medium's words; null otherwise. */
        val said: Msg? get() = if (item.blocked) Msg.MEMORY_BLOCKED else null
    }

    data class State(
        /** The media in the grid's order; null while the first read runs. */
        val rows: List<Row>? = null,
        /** A Memory's chapters (none for a chapter). */
        val chapters: List<NodeRef> = emptyList(),
        /** The gallery did not read (the last read stays shown); [failure] says why. */
        val failed: Boolean = false,
        /** What the page offers whoever looks ([MemoryRights.of]); an action shows only where it says yes. */
        val rights: MemoryRights.Rights = MemoryRights.NONE,
        /** While a trashed medium's Undo is offered, what it says ("Moved to trash"); null once its window passed. */
        val undo: Notice? = null,
        /** A guest's own medium whose purge is asked ([purge] does it, [cancelAsk] does not). */
        val purging: String? = null,
        /** What the last delete or Undo left to say (a restore refused, a purge failed); null once seen ([seen]). */
        val said: Notice? = null,
        /** Shown from the offline store, read-only, its content as of when (ADR 0140 decision 5); null online. */
        val offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null,
        /** The node's music: its audio media, sorted and numbered as its Music view lists them (a blocked one included). */
        val music: List<MusicTrack> = emptyList(),
        /** This node's music is the player's queue now (playing or paused): the header offers its stop. */
        val musicHere: Boolean = false,
        /** The node's GPX tracks (its Map view's, ADR 0143 D8; a blocked one included), as listed. */
        val tracks: List<MemoryMedia.Item> = emptyList(),
        /** The node's date and place as sealed (the header's facts line, ADR 0143 D5); null before they read. */
        val facts: me.raddatz.veyq.mobile.memories.place.MemoryFacts.Facts? = null,
        /**
         * The header's people line (ADR 0144 decision 1): where the participants are shown — a member's, on a Memory, not
         * in a personal scope, offline too (ADR 0144 decision 7) — and somebody is listed or whoever looks may add someone; null otherwise.
         */
        val people: Participants.Line? = null,
        /**
         * A member's delete of the banner's picture asked first (ADR 0144 decision 5, A2): the medium whose original's
         * digest is the banner's; [trashBannerPicture] answers, [cancelAsk] declines.
         */
        val bannerPicture: String? = null,
        /**
         * The photos and videos this device is sending into the node, as placeholder tiles before [rows] (ADR 0145
         * decision 3): one whose medium [rows] lists gave way to it.
         */
        val uploading: List<UploadTile> = emptyList(),
        /**
         * A medium the search asked this page to open (ADR 0146 decision 8): its lightbox is open on it ([openLightbox]); the
         * platform shows it and says [searchOpenedSeen].
         */
        val searchOpened: String? = null,
    ) {
        /** The banner above the header (ADR 0144 decision 4) as sealed: what it shows; null for none (no frame drawn). */
        val banner: me.raddatz.veyq.mobile.memories.banner.Banner.Shown? get() = me.raddatz.veyq.mobile.memories.banner.Banner.shown(facts?.banner)

        /** Whether the banner's dialog is offered: the right, online (nothing is edited offline, ADR 0140 decision 7). */
        val editsBanner: Boolean get() = rights.setBanner && offline == null

        /** The `auto` range the node's media give now (the date editor's preview, and the header's before one is sealed). */
        val autoRange: me.raddatz.veyq.mobile.memories.place.MemoryDate
            get() = me.raddatz.veyq.mobile.memories.place.MemoryDate.autoRange(rows.orEmpty().map { it.item })

        /** The date the header shows: a manual one as sealed, else the sealed `auto` range, else the live one. */
        val date: me.raddatz.veyq.mobile.memories.place.MemoryDate
            get() = me.raddatz.veyq.mobile.memories.place.MemoryDate.effective(facts?.date, autoRange)

        /** Whether the date and the place may be edited here (their editors, ADR 0143 D5): the meta writable by whoever looks, online. */
        val editsFacts: Boolean get() = rights.editMeta && offline == null

        /** Every photo's GPS point (the place editor's dots and the Map view's). */
        val gpsPoints: List<me.raddatz.veyq.mobile.map.LatLng>
            get() = me.raddatz.veyq.mobile.memories.place.MemoryLocation.gpsPoints(rows.orEmpty().map { it.item })

        val count: Int get() = rows?.size ?: 0

        /** Whether the node has music that plays: a track the operator did not block. */
        val hasMusic: Boolean get() = music.any { !it.blocked }

        /**
         * What the page says where the gallery did not read ([failed]): offline, that the store does not hold it (a node
         * not kept offline, or its listing cleared) — not a failed load; online, that the load failed. Null where it read.
         */
        val failure: Msg? get() = when {
            !failed -> null
            offline != null -> Msg.OFFLINE_NOT_KEPT
            else -> Msg.MEMORY_GALLERY_FAILED
        }
    }

    /** What whoever looks may do here online; offline the page offers none of its writes ([State.rights]). */
    private val granted = MemoryRights.of(access, node.kind)

    private val _state = MutableStateFlow(State(rights = granted))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /** The page's rights (who looks, on this node). */
    val rights: MemoryRights.Rights get() = current.rights

    /**
     * The page's own header actions, joining core's where the kind's order puts them (`AppKinds.headerOrder`): adding
     * photos and videos where the rights let whoever looks upload.
     */
    val headerActions: List<NodeHeader.Action> get() = listOfNotNull(
        NodeHeader.Action(UPLOAD).takeIf { rights.upload },
        // offline too: a pinned node's music plays (ADR 0142 decision 12)
        NodeHeader.Action(MUSIC).takeIf { music != null && (current.hasMusic || current.musicHere) },
    )

    /** What the page's own New makes here ([MemoryCreate.offered] on this node, where the rights allow): a chapter on a Memory. */
    val creates: List<String> get() = MemoryCreate.offered(node.kind).filter { it == MemoryCreate.CHAPTER && rights.createChapter }

    /** An own header action's words ([headerActions]: adding photos and videos, the music's play or stop); null for another id. */
    fun label(id: String): Msg? = when (id) {
        UPLOAD -> Msg.MEMORY_ADD
        MUSIC -> if (current.musicHere) Msg.MUSIC_STOP else Msg.MUSIC_PLAY
        else -> null
    }

    // ---- the music ----

    /** The header's music action: this node's music stopped where it is the player's queue, else started. */
    fun toggleMusic() {
        val m = music ?: return
        if (current.musicHere) m.player.stop() else playMusic()
    }

    /**
     * The node's music played (ADR 0135 decision 6): [startId] first, then the rest shuffled; null shuffled from the start.
     * A snapshot of the tracks listed now (one added later plays from the next start); nothing where none plays.
     */
    fun playMusic(startId: String? = null) {
        val m = music ?: return
        val tracks = current.music
        if (tracks.none { !it.blocked }) return
        m.player.play(MusicQueue.queue(node, tracks, m.untitled, { item -> door.reader(item)?.let { MusicQueue.source(item, it) } }, startId))
    }

    /** The player's queue key of this node's music. */
    val musicKey: String get() = MusicQueue.key(node.scopeId, node.nodeId)

    /** A music track of the node, by its medium id. */
    internal fun track(mediaId: String): MusicTrack? = current.music.firstOrNull { it.mediaId == mediaId }

    /**
     * Whether medium [mediaId] offers its delete to whoever looks ([trash]): every one a member's (to the trash), a guest's
     * own one only (a purge); none where the rights delete nothing or it is not shown.
     */
    fun deletes(mediaId: String): Boolean = known(mediaId) && when (rights.deleteMedia) {
        MemoryRights.DeleteMedia.ANY -> true
        MemoryRights.DeleteMedia.OWN -> door.isOwn(mediaId)
        MemoryRights.DeleteMedia.NONE -> false
    }

    /**
     * Chapter [nodeId]'s cover picture as stored, for its row (the platform decodes it), where it is one of this Memory's
     * chapters ([State.chapters]); null where it is not, has no cover or did not read.
     */
    suspend fun chapterCover(nodeId: String): ByteArray? {
        if (current.chapters.none { it.nodeId == nodeId }) return null
        return runCatching { door.cover(nodeId) }.getOrNull()
    }

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    private val reading = Mutex()

    /** The scope's current members as last read (the people line's names, the picker's candidates); null: not read. */
    private var members: List<me.raddatz.veyq.mobile.session.Person>? = null

    /** What the door last said is on its way here (the placeholders before the listing drops those in). */
    private val queuedNow = MutableStateFlow<List<UploadTile>>(emptyList())
    private val queued: List<UploadTile> get() = queuedNow.value

    /** When the last listing the server answered was read (epoch ms, [now]'s own clock is no wall clock). */
    private var listedAt: Long? = null

    /** [this] offline as of [at] (read-only), or online again (null). */
    private fun State.offline(at: me.raddatz.veyq.mobile.session.OfflineAsOf?): State =
        copy(offline = at, rights = if (at == null) granted else readOnly(granted))

    /** The online rights less every write: what the page offers offline (ADR 0140 decision 7). */
    private fun readOnly(r: MemoryRights.Rights) = r.copy(
        upload = false, deleteMedia = MemoryRights.DeleteMedia.NONE, createMemory = false, createChapter = false, editMeta = false,
    )

    /** The notice every write offline is refused with. */
    private val readOnlyNotice get() = Notice(Msg.OFFLINE_READ_ONLY)

    /** The gallery read anew (a pull to refresh). */
    fun reload() {
        scope.launch { read() }
    }

    /** Every medium id the rows list (a placeholder whose medium is among them gives way). */
    private val State.listedIds: Set<String> get() = rows.orEmpty().mapTo(HashSet()) { it.mediaId }

    /** The last listing that read: what the live poll asks whether the media moved since. */
    private var listed: MemoryMedia.Gallery? = null

    /** Media trashed here whose listing may still name them (a read racing the trash): kept out of the rows. */
    private val pending = HashSet<String>()

    private suspend fun read() = reading.withLock {
        val read = runCatching { door.gallery() }.getOrNull()
        val chapters = if (node.kind == MEMORY) {
            runCatching { MediaDetails.chapters(node.kind, node.nodeId, door.scopeNodes()) }.getOrNull()
        } else {
            emptyList()
        }
        val facts = runCatching { door.facts() }.getOrNull()
        if (read != null) {
            listed = read
            if (read.offline == null) listedAt = wallClock()
            // a trashed one the listing no longer names is settled
            pending.retainAll(read.items.map { it.mediaId }.toSet())
        }
        _state.update { prior ->
            val s = if (read != null) prior.offline(read.offline) else prior
            val withFacts = (if (chapters != null) s.copy(chapters = chapters) else s).let { if (facts != null) it.copy(facts = facts) else it }
            if (read == null) withFacts.copy(failed = true)
            // a photo view's media only: a track and a music track are another view's (GalleryMedium, ADR 0135 decision 2)
            else withFacts.copy(
                rows = read.items.filter { it.mediaId !in pending && GalleryMedium.shows(it.type, it.name) }.map(::Row),
                music = MusicQueue.tracks(read.items.filter { it.mediaId !in pending }),
                tracks = read.items.filter { it.mediaId !in pending && it.isTrack },
                failed = false,
            ).let { it.copy(uploading = UploadTile.shown(queued, it.listedIds)) }
        }
        if (read != null) searched(settled = true)
        // the Memory opening is a settle point (the self-heal): once, on its first listing the server answered
        if (read != null && read.offline == null && !openedSettle) {
            openedSettle = true
            scope.launch { settle() }
        }
    }

    // ---- the search (ADR 0146 decision 8) ----

    /**
     * After a read: the page's media contributed to the search (its photos and videos, a blocked one left out), and a request
     * the search left for one of them taken — the lightbox opened on it; one this page cannot answer once [settled] dropped.
     */
    private fun searched(settled: Boolean) {
        val s = search ?: return
        val rows = current.rows ?: return
        s.contributions.contribute(
            me.raddatz.veyq.mobile.search.SearchContributions.Contribution(node.scopeId, node.nodeId, rows.filter { !it.blocked }.mapNotNull { row ->
                val i = row.item
                MemoriesSearch.mediumDocument(i.mediaId, node.nodeId, i.metaRevision, i.original.blobId, i.meta, i.type, i.name) { "" }?.let {
                    // unnamed: the search names it by its word
                    me.raddatz.veyq.mobile.search.ContributedDocument(it.kind, it.id, it.revision, it.name, it.facets, it.icon)
                }
            }),
        )
        takeRequest(settled)
    }

    private fun takeRequest(settled: Boolean) {
        val s = search ?: return
        val rows = current.rows ?: return
        val id = s.requests.take(node.scopeId, node.nodeId, { want -> rows.any { it.mediaId == want && !it.blocked } }, settled) ?: return
        lightboxOn(id)
        _state.update { it.copy(searchOpened = id) }
    }

    /** The platform showed the lightbox [State.searchOpened] asked for. */
    fun searchOpenedSeen() = _state.update { it.copy(searchOpened = null) }

    // ---- the settle (ADR 0143 D5): the `auto` range and place kept current ----

    private var openedSettle = false
    private val settling = Mutex()

    /**
     * The settle at the page's settle points — its opening and a medium's trash (an upload batch settles in [MediaUploads]):
     * the `auto` range and the `auto` place re-derived from the media listed now and re-sealed only where they changed,
     * where whoever looks may edit the meta and online; one at a time. The facts line reads anew when it wrote.
     */
    private suspend fun settle() = settling.withLock {
        if (!current.rights.editMeta || current.offline != null) return@withLock
        val items = listed?.items?.filter { it.mediaId !in pending } ?: return@withLock
        if (runCatching { door.settle(items) }.getOrDefault(false)) {
            runCatching { door.facts() }.getOrNull()?.let { f -> _state.update { it.copy(facts = f) } }
        }
    }

    // ---- the participants (ADR 0144 decision 1) ----

    /**
     * Whether the participants are shown here: a member's right on a Memory, not a personal scope — offline too, named from
     * the kept member list (ADR 0144 decision 7); offline the rights edit nothing, so the line offers no edit.
     */
    private val showsParticipants: Boolean get() = current.rights.participants && !door.personal

    /** The people line drawn anew: the members read once where somebody is to be named. */
    private suspend fun people() {
        if (!showsParticipants) return _state.update { it.copy(people = null) }
        val ids = current.facts?.participants.orEmpty()
        if (members == null && ids.isNotEmpty()) members = runCatching { door.members() }.getOrNull()
        _state.update { it.copy(people = Participants.line(ids, members, it.rights.editMeta)) }
    }

    /**
     * The participants picker over the scope's current members, where they are shown and whoever looks may edit the
     * node's meta (online); null otherwise. Its save has the header's line follow.
     */
    fun participantsPicker(): ParticipantsPicker? {
        if (!showsParticipants || !current.rights.editMeta) return null
        return ParticipantsPicker(
            current.facts?.participants.orEmpty(),
            members = { door.members()?.also { members = it } },
        ) { wanted, openedWith -> door.setParticipants(wanted, openedWith).also { factsChanged() } }
    }

    // ---- the banner (ADR 0144 decisions 4 and 5) ----

    /** The banner's picture (an image, a video's poster) as stored; null for none or one that does not read. */
    suspend fun bannerPicture(): ByteArray? = if (current.banner == null) null else runCatching { door.bannerPicture() }.getOrNull()

    /** The video banner's bytes for its player (a tap plays it); null for an image or none. */
    suspend fun bannerReader(): me.raddatz.veyq.mobile.blob.RangeReader? =
        if (current.banner?.medium != me.raddatz.veyq.mobile.memories.banner.Banner.Medium.VIDEO) null else runCatching { door.bannerReader() }.getOrNull()

    /**
     * The banner's dialog over the platform's [pictures], where whoever looks may set the banner and online; null otherwise.
     * It opens on the banner as the page holds it (a peer's change meanwhile is its stale), shares an equal gallery medium's
     * object, and its save or removal has the page's banner follow.
     */
    fun bannerEditor(pictures: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures): me.raddatz.veyq.mobile.memories.banner.BannerEditor? {
        if (!current.editsBanner) return null
        var openedWith = current.facts?.bannerSlots ?: me.raddatz.veyq.mobile.memories.banner.Banner.Slots.NONE
        // a peer's change said (stale): the page and the dialog follow the peer's banner, so Save again writes over it, as
        // the web's dialog does (its save takes the page's node, which the conflict updated)
        suspend fun follow(out: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome) {
            if (out != me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Stale) return factsChanged()
            val f = runCatching { door.facts() }.getOrNull() ?: return
            _state.update { it.copy(facts = f) }
            openedWith = f.bannerSlots ?: me.raddatz.veyq.mobile.memories.banner.Banner.Slots.NONE
        }
        return me.raddatz.veyq.mobile.memories.banner.BannerEditor(
            openedWith.blobId != null, pictures,
            write = { picked, progress -> door.setBanner(openedWith, picked, listed?.items.orEmpty(), progress).also { follow(it) } },
            removeBanner = { door.removeBanner(openedWith).also { follow(it) } },
        )
    }

    // ---- a medium's capture time (ADR 0144 decision 3) ----

    /**
     * Medium [mediaId]'s capture-time editor, where whoever looks may write its meta and online; null otherwise (or where
     * the medium is not in the gallery). Each save has the gallery read anew, so it re-orders by the effective time.
     */
    fun captureTimeEditor(mediaId: String): CaptureTimeEditor? {
        if (!current.rights.editMeta || current.offline != null) return null
        val item = row(mediaId)?.item ?: return null
        return CaptureTimeEditor(item.meta?.capturedAt, item.meta?.capturedAtOverride) { overrideMs ->
            runCatching { door.captureTime(item, overrideMs) }.getOrElse { CaptureTime.Outcome.Failed("failed") }.also { reload() }
        }
    }

    /** The node's facts read anew (an editor saved): the header's line follows. */
    fun factsChanged() {
        scope.launch { runCatching { door.facts() }.getOrNull()?.let { f -> _state.update { it.copy(facts = f) } } }
    }

    // ---- the live poll ----

    private val closed = MutableStateFlow(false)

    /**
     * The page's live poll (the web's `mediaRevisionPolling.ts`, core's [LivePoll] at [LivePoll.ITEMS]) for as long as the
     * caller runs it — a screen while it shows the page, so nothing is asked in the background: each tick asks whether the
     * node's media moved since the last listing ([Door.moved], a conditional read) and reads the gallery anew only when they
     * did. It ends when the page closes.
     */
    suspend fun live() {
        if (closed.value) return
        val poll = LivePoll(now = now)
        coroutineScope {
            val ticking = launch {
                poll.run {
                    val since = listed ?: return@run LivePoll.Tick.SKIP
                    if (door.moved(since)) read()
                    // a peer's change of the node's meta: the lists' new revision reads this page anew (inbox #953)
                    door.treeMoved()
                    LivePoll.Tick.DONE
                }
            }
            closed.first { it }
            ticking.cancel()
        }
    }

    /** [live] in the page's own scope, for a platform that cannot run a suspend loop: stopped with [Watch.stop] or the page's close. */
    fun startLive(): Watch = Watch(scope.launch { live() })

    private fun row(mediaId: String): Row? = current.rows?.firstOrNull { it.mediaId == mediaId }

    /** Whether [mediaId] is shown here: a row of the grid or a track of the music. */
    private fun known(mediaId: String): Boolean = row(mediaId) != null || track(mediaId) != null || gpx(mediaId) != null

    /** A GPX track of the node, by its medium id. */
    private fun gpx(mediaId: String): MemoryMedia.Item? = current.tracks.firstOrNull { it.mediaId == mediaId }

    /** GPX track [mediaId]'s original, whole (the Map view parses it on the device); null where it is none or did not read. */
    internal suspend fun trackOriginal(mediaId: String): ByteArray? {
        val item = gpx(mediaId)?.takeIf { !it.blocked } ?: return null
        return runCatching { door.read(item, item.original) }.getOrNull()
    }

    /** The grid's tier of medium [mediaId], whole, for the platform to decode; null where it has none or it did not read. */
    suspend fun tile(mediaId: String): ByteArray? {
        val row = row(mediaId) ?: return null
        val blob = row.item.gridBlob?.takeIf { row.tile != null } ?: return null
        return runCatching { door.read(row.item, blob) }.getOrNull()
    }

    /** The lightbox's tier of medium [mediaId] (its preview, else the original); null where it shows none. */
    internal suspend fun picture(mediaId: String): ByteArray? {
        val row = row(mediaId)?.takeIf { it.picture != null } ?: return null
        return runCatching { door.read(row.item, row.item.lightboxBlob) }.getOrNull()
    }

    /**
     * The picked [sources] added to this Memory or chapter (the app's one upload queue), where the rights let whoever looks
     * upload; how many were queued.
     */
    fun upload(sources: List<UploadSource>): Int = if (rights.upload && current.offline == null) door.upload(sources) else 0

    // ---- a chapter made ----

    /**
     * A chapter [name] made in this Memory, where the rights allow it ([creates]); the node made (the page opens it), or why
     * not. The gallery's chapters are read again with the lists.
     */
    suspend fun createChapter(name: String): MemoryCreate.Made {
        if (current.offline != null) return MemoryCreate.Made(null, readOnlyNotice)
        if (MemoryCreate.CHAPTER !in creates) return MemoryCreate.Made(null, Notice(Msg.NODE_ILLEGAL_STRUCTURE))
        if (name.isBlank()) return MemoryCreate.Made(null, Notice(Msg.FOLDER_NAME_EMPTY))
        return runCatching { door.createChapter(name.trim()) }.getOrElse { MemoryCreate.Made(null, MemoryCreate.refused(null)) }
    }

    // ---- a medium deleted: a member's trash with its Undo, a guest's own purge asked first ----

    /** A trashed medium's Undo while it is offered; [trashed] completes with what its trash came to. */
    private class Undoing(val mediaId: String, val name: Any) {
        val trashed = kotlinx.coroutines.CompletableDeferred<Removal>()
    }

    private var undoing: Undoing? = null
    private var undoWindow: Job? = null

    /** The name a refusal says a medium by: its original's sealed name, else its place in the grid (`Item {n}`). */
    private fun nameOf(mediaId: String): Any {
        track(mediaId)?.let { t -> return t.title ?: Notice(Msg.MUSIC_UNTITLED, t.number) }
        gpx(mediaId)?.let { g -> return g.meta?.trackName ?: g.name ?: Notice(Msg.MEMORY_UNNAMED, 1) }
        val rows = current.rows.orEmpty()
        val at = rows.indexOfFirst { it.mediaId == mediaId }
        return rows.getOrNull(at)?.name?.takeIf { it.isNotBlank() } ?: Notice(Msg.MEMORY_UNNAMED, if (at >= 0) at + 1 else 1)
    }

    /**
     * Medium [mediaId] deleted the way the rights say: a member's to the trash — out of the rows at once, its Undo offered
     * for [ShellModel.TRASH_UNDO_MS] ([State.undo]); a trash that did not go drops the Undo and reads the gallery anew (the
     * web's) —; a guest's own one asked first ([State.purging], then [purge]). What to say at once, or null.
     */
    suspend fun trash(mediaId: String): Notice? {
        if (!known(mediaId)) return null
        if (current.offline != null) return readOnlyNotice
        return when (rights.deleteMedia) {
            MemoryRights.DeleteMedia.ANY -> if (asksBanner(mediaId)) {
                _state.update { it.copy(bannerPicture = mediaId) }
                null
            } else trashAny(mediaId)
            MemoryRights.DeleteMedia.OWN -> {
                askPurge(mediaId)
                null
            }
            MemoryRights.DeleteMedia.NONE -> null
        }
    }

    /** Whether trashing [mediaId] asks about the banner first: whoever looks may set it, and its original is the banner's file. */
    private fun asksBanner(mediaId: String): Boolean {
        if (!rights.setBanner) return false
        val item = row(mediaId)?.item ?: track(mediaId)?.let { t -> listed?.items?.firstOrNull { it.mediaId == t.mediaId } } ?: gpx(mediaId)
        return item != null && me.raddatz.veyq.mobile.memories.banner.StoredMatch.isBannerPicture(current.facts?.banner, item)
    }

    /**
     * The banner-picture question answered (A2): the medium trashed as any member's is (with its Undo), the banner kept —
     * or, with [removeBanner], removed too (one `removeMemoryBanner`). Nothing where no question is asked.
     */
    suspend fun trashBannerPicture(removeBanner: Boolean): Notice? {
        val mediaId = current.bannerPicture ?: return null
        _state.update { it.copy(bannerPicture = null) }
        if (current.offline != null) return readOnlyNotice
        val said = trashAny(mediaId)
        if (removeBanner) {
            val out = runCatching { door.removeBanner(current.facts?.bannerSlots ?: me.raddatz.veyq.mobile.memories.banner.Banner.Slots.NONE) }
                .getOrElse { me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Refused(null) }
            factsChanged()
            if (out != me.raddatz.veyq.mobile.memories.banner.BannerWrite.Outcome.Done) return Notice(Msg.MEMORY_REFUSED_FAILED)
        }
        return said
    }

    private suspend fun trashAny(mediaId: String): Notice {
        val name = nameOf(mediaId) // before the removal: a refused restore names it
        pending += mediaId
        _state.update { s -> s.copy(rows = s.rows?.filter { it.mediaId != mediaId }, music = s.music.filter { it.mediaId != mediaId }, tracks = s.tracks.filter { it.mediaId != mediaId }) }
        val said = Notice(Msg.MEMORY_MOVED_TO_TRASH)
        val u = Undoing(mediaId, name)
        offerUndo(u, said)
        val done = runCatching { door.trash(mediaId) }.getOrDefault(Removal.FAILED)
        u.trashed.complete(done)
        if (done != Removal.DONE) {
            if (undoing?.mediaId == mediaId) endUndo()
            pending -= mediaId
            read()
        } else scope.launch { settle() } // a medium's trash is a settle point
        return said
    }

    private fun offerUndo(u: Undoing, said: Notice) {
        undoWindow?.cancel()
        undoing = u
        _state.update { it.copy(undo = said) }
        undoWindow = scope.launch {
            delay(ShellModel.TRASH_UNDO_MS)
            endUndo()
        }
    }

    private fun endUndo() {
        undoing = null
        _state.update { it.copy(undo = null) }
    }

    /**
     * The Undo of the last trash while it is offered: the medium restored and the gallery read anew (it comes back at its
     * place). Why not — named with its reason, as the web's —, or null; nothing once its window passed. An Undo pressed
     * while the trash is still on its way waits for it: a restore sent first would find nothing to restore, and the trash
     * landing after it would take the medium for good (measured on Android, 2026-11-15).
     */
    suspend fun undo(): Notice? {
        val u = undoing ?: return null
        if (current.offline != null) return readOnlyNotice
        undoWindow?.cancel()
        endUndo()
        // a trash that did not go has read the gallery anew already: nothing to restore
        if (u.trashed.await() != Removal.DONE) return null
        pending -= u.mediaId
        val r = runCatching { door.restore(u.mediaId) }.getOrDefault(Removal.FAILED)
        read()
        if (r == Removal.DONE) return null
        val reason = when (r) {
            Removal.GONE -> Msg.MEMORY_REFUSED_GONE
            Removal.SCOPE_DELETING -> Msg.MEMORY_REFUSED_SCOPE_DELETING
            Removal.BLOCKED -> Msg.MEMORY_REFUSED_BLOCKED
            else -> Msg.MEMORY_REFUSED_FAILED
        }
        val said = Notice(Msg.MEMORY_RESTORE_REFUSED, u.name, Notice(reason))
        _state.update { it.copy(said = said) }
        return said
    }

    /** "Delete" on a guest's own medium [mediaId]: asked first (it is purged for good, no Undo). Not for another's. */
    fun askPurge(mediaId: String) {
        if (current.offline != null || rights.deleteMedia != MemoryRights.DeleteMedia.OWN || !known(mediaId) || !door.isOwn(mediaId)) return
        _state.update { it.copy(purging = mediaId) }
    }

    /** The purge's question, while one is asked: the confirmation's words. */
    val purgeQuestion: Notice get() = Notice(Msg.MEMORY_DELETE_OWN_CONFIRM)

    /** The question declined: nothing is purged. */
    fun cancelAsk() = _state.update { it.copy(purging = null, bannerPicture = null) }

    /**
     * The purge asked, answered: the guest's own medium purged for good, out of the rows; why not ([State.said]: the tile
     * stays, to try again), or null. Nothing where no purge was asked.
     */
    suspend fun purge(): Notice? {
        val mediaId = current.purging ?: return null
        _state.update { it.copy(purging = null) }
        if (current.offline != null) return readOnlyNotice
        if (rights.deleteMedia != MemoryRights.DeleteMedia.OWN || !door.isOwn(mediaId)) return null
        val gone = runCatching { door.purge(mediaId) }.getOrDefault(false)
        if (!gone) {
            val said = Notice(Msg.MEMORY_DELETE_OWN_FAILED)
            _state.update { it.copy(said = said) }
            return said
        }
        _state.update { s -> s.copy(rows = s.rows?.filter { it.mediaId != mediaId }, music = s.music.filter { it.mediaId != mediaId }, tracks = s.tracks.filter { it.mediaId != mediaId }) }
        return null
    }

    /** What [State.said] said was seen: it goes. */
    fun seen() = _state.update { it.copy(said = null) }

    // ---- the details: the EXIF tier once per medium, the uploaders once per gallery ----

    private val exifs = HashMap<String, MediaDetails.Exif?>()
    private val uploaders = HashMap<String, MediaDetails.Uploader>()
    private val asked = HashSet<String>()
    private val details = Mutex()

    /**
     * Medium [mediaId]'s details: its rows with its EXIF tier (read once, when first shown) and who added it — the
     * uploaders read once for every owner key the gallery shows (one request, not one per medium), a key not read yet
     * (a medium that came since) asked with the next. Null where the medium is not in the gallery.
     */
    internal suspend fun details(mediaId: String): Lightbox.Details? = details.withLock {
        val row = row(mediaId) ?: return@withLock null
        val item = row.item
        val exif = if (mediaId in exifs) exifs[mediaId] else runCatching { door.exif(item) }.getOrNull().also { exifs[mediaId] = it }
        val key = item.ownerKey
        if (key != null && key !in asked) {
            val all = current.rows.orEmpty().map { it.item }.filter { it.ownerKey != null && it.ownerKey !in asked }
            val read = runCatching { door.uploaders(all) }.getOrNull()
            if (read != null) {
                uploaders.putAll(read)
                // a member the space's list did not read is left out of the answer: asked again with the next
                asked.addAll(read.keys)
            }
        }
        val rows = MediaDetails.rows(item, exif)
        Lightbox.Details(mediaId, rows, key?.let { uploaders[it] }, MediaDetails.uploaderAt(rows), me.raddatz.veyq.mobile.memories.place.PhotoPlace.mapOf(item, exif))
    }

    /** Music track [mediaId]'s original saved through [into] as the lightbox saves a medium's ([Lightbox.save]); null where it is not one or may not be. */
    internal fun saveTrack(mediaId: String, into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? {
        gpx(mediaId)?.let { g -> return save(Row(g), current.tracks.indexOf(g), into) }
        val t = track(mediaId) ?: return null
        return save(Row(t.item), t.number - 1, into)
    }

    /**
     * Photo [mediaId]'s place name resolved once and sealed where it has a coordinate and no name, the meta may be written
     * here, the device's consent holds and no [slideshow] runs ([me.raddatz.veyq.mobile.memories.place.PhotoPlace]); the
     * gallery reads anew when it was. The name, or null.
     */
    internal suspend fun placeName(mediaId: String, slideshow: Boolean): String? {
        val item = row(mediaId)?.item ?: return null
        val name = runCatching { door.placeName(item, current.rights.editMeta && current.offline == null, slideshow) }.getOrNull() ?: return null
        reload()
        return name
    }

    internal fun reader(row: Row): RangeReader? = if (rights.download && row.isVideo && !row.blocked) door.reader(row.item) else null

    internal fun save(row: Row, index: Int, into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? {
        if (row.blocked || !rights.download) return null
        val item = row.item
        val name = MediaDetails.downloadName(item, node.name, index)
        return FileSave(name, item.original.size, saves) { progress ->
            into { sink ->
                var written = 0L
                door.stream(item) { frame ->
                    sink(frame)
                    written += frame.size
                    progress(written)
                }
            }
        }
    }

    // ---- the lightbox ----

    private var open: Lightbox? = null

    /**
     * The lightbox open over this page, until it closes: a view drawn anew over the same gallery shows it again — an
     * Android activity a rotation recreated (#934) —, its medium, slideshow and reader as they were.
     */
    val openLightbox: Lightbox? get() = open?.takeIf { !it.current.closed }

    /** The lightbox opened at row [index] (one at a time: an open one closes). */
    fun lightbox(index: Int): Lightbox {
        open?.close()
        return Lightbox(this, index, scope).also { open = it }
    }

    /**
     * The lightbox opened on medium [mediaId] (one at a time: an open one closes) — a page opened anew for it, a window of
     * its own on a Mac, before its first read: it shows that medium once the rows hold it, else the first.
     */
    fun lightboxOn(mediaId: String): Lightbox {
        open?.close()
        val at = current.rows?.indexOfFirst { it.mediaId == mediaId } ?: -1
        return Lightbox(this, maxOf(at, 0), scope, wanted = mediaId.takeIf { at < 0 }).also { open = it }
    }

    /** The page went: nothing more is read or watched; its lightbox closes (its reader with it). */
    fun close() {
        search?.contributions?.withdraw(node.scopeId, node.nodeId)
        open?.close()
        open = null
        closed.value = true
        undoWindow?.cancel()
        scope.cancel()
    }

    /*
     * The collectors start last: they run on other threads at once, and Kotlin sets the fields in declaration order — one
     * declared below an `init` that a collector reaches is still null then (`pending`, `settling`: a read NPE'd), or reset
     * after the collector set it (`openedSettle`). A field the collectors use may sit anywhere above this block.
     */
    init {
        scope.launch { door.revisions.collect { read() } }
        music?.let { m ->
            scope.launch {
                m.player.state.collect { p -> _state.update { it.copy(musicHere = p.nowPlaying?.key == musicKey) } }
            }
        }
        scope.launch { door.uploaded.collect { if (it == node.nodeId) read() } }
        scope.launch {
            door.uploading.collect { tiles ->
                queuedNow.value = tiles
                _state.update { it.copy(uploading = UploadTile.shown(tiles, it.listedIds)) }
            }
        }
        // the people line follows the participants sealed, the rights and going offline or online
        scope.launch {
            state.map { Triple(it.facts?.participants, it.rights, it.offline) }.distinctUntilChanged().collect { people() }
        }
        // a request the search leaves while this page shows (its own photo chosen) is taken at once
        search?.let { s -> scope.launch { s.requests.request.collect { if (it != null) reading.withLock { takeRequest(settled = false) } } } }
        scope.launch {
            door.online.collect { on ->
                if (!on) _state.update { it.offline(it.offline ?: me.raddatz.veyq.mobile.session.OfflineAsOf(listedAt)) }
                else if (current.offline != null) read()
            }
        }
    }

    companion object {
        const val MEMORY = "MEMORY"
        const val CHAPTER = "CHAPTER"

        /** The page's own header action: photos and videos added ([upload]). */
        const val UPLOAD = "upload"

        /** The page's own header action: its music played or stopped ([toggleMusic], ADR 0142 decision 6). */
        const val MUSIC = "music"

        /**
         * Memory or chapter [node] as the page an app opens (Memories' object): a chapter is no object of a list
         * (`applicationOf` names no application for it, so `NodeRef.asObject` gives none), yet a chapter row and a chapter
         * just made open it. Null for any other kind.
         */
        fun page(node: NodeAt): me.raddatz.veyq.mobile.navigation.ObjectRef? =
            if (node.kind == MEMORY || node.kind == CHAPTER) {
                me.raddatz.veyq.mobile.navigation.ObjectRef(node.scopeId, node.nodeId, node.kind, me.raddatz.veyq.mobile.navigation.ApplicationId.MEMORIES, node.name)
            } else {
                null
            }

        /** [page] of a listed node (a Memory's chapter row). */
        fun rowPage(row: me.raddatz.veyq.mobile.navigation.NodeRef): me.raddatz.veyq.mobile.navigation.ObjectRef? =
            page(NodeAt(row.scopeId, row.nodeId, row.kind, row.name))

        /**
         * [rows] laid out as the justified grid [width] wide (the web's `MediaGrid.vue`, [MediaGridLayout]): each tile's frame
         * in the platform's unit, every row justified to the width but the last, the row height aiming at
         * [MediaGridLayout.targetHeight] of the width (a phone two to four tiles a row). A platform lays [State.rows] out
         * with it whenever the rows or its width change, and draws only the rows near its viewport.
         */
        fun layout(rows: List<Row>, width: Double): MediaGridLayout.Layout = layout(rows, emptyList(), width)

        /**
         * [layout] with [uploading]'s placeholders first (ADR 0145 decision 3), each square, under its [UploadTile.tileId]: a
         * platform draws a tile whose id [UploadTile.idOf] names as the placeholder of that upload.
         */
        fun layout(rows: List<Row>, uploading: List<UploadTile>, width: Double): MediaGridLayout.Layout =
            MediaGridLayout.justify(
                uploading.map { MediaGridLayout.Source(it.tileId, PLACEHOLDER_ASPECT) } + rows.map { MediaGridLayout.Source(it.mediaId, it.aspect) },
                width, MediaGridLayout.targetHeight(width),
            )

        /** A placeholder's aspect: square, its picture's size unknown until its medium lists. */
        private const val PLACEHOLDER_ASPECT = 1.0

        /** The wall clock (epoch ms): what an offline page says its content is as of. */
        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun wallClock(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

        private fun monotonicMs(): () -> Long {
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

/**
 * The lightbox over a [GalleryModel] (the web's `MediaLightbox.vue`): the current medium by index over the gallery's rows,
 * kept by its id when the gallery reads anew — a removed one gives way to the one now at its place (its neighbour) —, its
 * picture read when asked ([picture]), a video's original opened for the player ([play]) and **closed by the model** when
 * the lightbox pages away, stops, closes or its gallery does; the details ([details]); the original saved ([save]); the
 * medium deleted as its gallery deletes ([trash]).
 *
 * **The slideshow is the web's** (`MediaLightbox.vue`, [SLIDESHOW_MS]): the model runs its clock, no view keeps a timer.
 * Started where a next medium exists, it steps on every interval through the media in the grid's order — a video shows its
 * poster for one interval and is never played by it —, ends ON the last medium, and pauses on any [go] or [play] (the
 * web pauses on any key, pointer or wheel; a platform whose view is touched pauses it with [slideshow] off).
 * Zoom is the platform's; the model knows nothing of it.
 *
 * **The music** (ADR 0142 decisions 5 and 13): while the account's player holds a queue, [State.music] is what the compact
 * controls draw ([musicControls] their commands; closing the lightbox stops nothing, and the slideshow's pause leaves the
 * music alone). A video it plays interrupts the music: the platform's player reports it playing ([videoPlaying] true) and
 * paused or ended (false); paging to another medium, [stop] and [close] end the interruption too, and the music plays on
 * where nothing a listener did came between (the player's rule).
 */
class Lightbox internal constructor(
    private val gallery: GalleryModel,
    start: Int,
    parent: CoroutineScope,
    /** The medium to show once the rows first hold any ([GalleryModel.lightboxOn] before the gallery's first read). */
    private var wanted: String? = null,
) {
    /** A medium's details as the lightbox shows them: its rows, who added it, where that row stands ([MediaDetails.uploaderAt]). */
    class Details(
        val mediaId: String, val rows: List<MediaDetails.Row>, val uploader: MediaDetails.Uploader?, val uploaderAt: Int,
        /** The photo's small map (ADR 0143 D7): its point at zoom 15 and its direction; null without GPS. */
        val map: me.raddatz.veyq.mobile.memories.place.PhotoPlace.PhotoMap? = null,
    )

    data class State(
        /** The gallery's rows, paged through. */
        val rows: List<GalleryModel.Row>,
        /** The current row's index; -1 where the gallery holds none. */
        val index: Int,
        /** The current video's original, open while it plays; null otherwise. */
        val reader: RangeReader? = null,
        /** The last save of an original started here (it outlives the lightbox). */
        val save: FileSave? = null,
        /** The slideshow: running, paused, ended on the last medium; null before it first started. */
        val slideshow: Slideshow? = null,
        val closed: Boolean = false,
        /** What the account's player plays (any node's queue), for the compact controls; null: they are not shown. */
        val music: me.raddatz.veyq.mobile.audio.NowPlaying? = null,
    ) {
        val count: Int get() = rows.size
        val row: GalleryModel.Row? get() = rows.getOrNull(index)
        val playing: Boolean get() = reader != null
    }

    /** The Memory or chapter whose gallery it pages (a window of its own is titled by it and reopened by its ids). */
    val node: NodeAt get() = gallery.node

    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>
    val current: State get() = _state.value

    // the gallery's own dispatcher, a child of its scope: it goes when the gallery does
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    init {
        val rows = gallery.current.rows.orEmpty()
        _state = MutableStateFlow(State(rows, if (rows.isEmpty()) -1 else start.coerceIn(0, rows.size - 1)))
        state = _state.asStateFlow()
        scope.launch { gallery.state.collect { s -> s.rows?.let(::follow) } }
        gallery.music?.let { m -> scope.launch { m.player.state.collect { p -> _state.update { it.copy(music = p.nowPlaying) } } } }
    }

    /** The compact controls' commands (previous, play/pause, next): the account's player; null where the page plays no music. */
    val musicControls: me.raddatz.veyq.mobile.audio.PlayerCommands? get() = gallery.music?.player

    private var interruption: MusicPlayer.Interruption? = null

    /**
     * The video the lightbox shows started ([playing] true; a muted one too) or paused or ended (false), as the platform's
     * player reports it: the music is interrupted while it plays, and plays again after (ADR 0142 decision 13).
     */
    fun videoPlaying(playing: Boolean) {
        if (!playing) return endInterruption()
        if (current.closed || interruption != null) return
        interruption = gallery.music?.player?.interrupt()
    }

    private fun endInterruption() {
        interruption?.end()
        interruption = null
    }

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Where the slideshow stands (the web's `slideshowState`, which a screen reader is told). */
    enum class Slideshow { PLAYING, PAUSED, ENDED }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** The gallery read anew: the current medium kept by its id, else the one now at its place. */
    private fun follow(rows: List<GalleryModel.Row>) {
        var stale: RangeReader? = null
        val want = wanted
        _state.update { s ->
            if (s.closed || s.rows === rows) return@update s
            // the first rows of a lightbox opened on a medium before they were read: that medium's place
            val id = s.row?.mediaId ?: want
            val kept = rows.indexOfFirst { it.mediaId == id }
            val index = when {
                rows.isEmpty() -> -1
                kept >= 0 -> kept
                else -> s.index.coerceIn(0, rows.size - 1)
            }
            val same = kept >= 0
            if (!same) stale = s.reader
            s.copy(rows = rows, index = index, reader = if (same) s.reader else null)
        }
        if (rows.isNotEmpty()) wanted = null
        if (stale != null) endInterruption()
        stale?.close()
    }

    /** Paged to row [index] by the person: the slideshow pauses; a video playing on the page left is stopped, its reader closed. */
    fun go(index: Int) {
        pause()
        step(index)
    }

    private fun step(index: Int) {
        var stale: RangeReader? = null
        var moved = false
        _state.update { s ->
            if (s.closed || index !in s.rows.indices || index == s.index) return@update s
            stale = s.reader
            moved = true
            s.copy(index = index, reader = null)
        }
        // another medium: a video that played on the one left no longer does
        if (moved) endInterruption()
        stale?.close()
    }

    // ---- the slideshow ----

    private var ticking: Job? = null

    /**
     * The slideshow started ([on]) where a next medium exists — a video playing stops first — or paused. It steps every
     * [SLIDESHOW_MS] and ends on the last medium.
     */
    fun slideshow(on: Boolean) {
        if (!on) return pause()
        val s = current
        if (s.closed || s.slideshow == Slideshow.PLAYING || s.index >= s.count - 1) return
        stop()
        _state.update { it.copy(slideshow = Slideshow.PLAYING) }
        ticking = scope.launch {
            while (true) {
                delay(SLIDESHOW_MS)
                val now = current
                if (now.index >= now.count - 1) return@launch end()
                step(now.index + 1)
                // it stops ON the last medium
                if (current.index >= current.count - 1) return@launch end()
            }
        }
    }

    private fun pause() {
        if (current.slideshow != Slideshow.PLAYING) return
        ticking?.cancel()
        ticking = null
        _state.update { it.copy(slideshow = Slideshow.PAUSED) }
    }

    private fun end() {
        ticking = null
        _state.update { it.copy(slideshow = Slideshow.ENDED) }
    }

    /** Medium [mediaId]'s picture in the lightbox (its preview, else its original), whole; null where it shows none. */
    suspend fun picture(mediaId: String): ByteArray? = gallery.picture(mediaId)

    /**
     * The current medium's original opened for the player, where it is a video not blocked (null otherwise); held as
     * [State.reader] until the lightbox pages away, [stop]s, closes or its gallery does.
     */
    fun play(): RangeReader? {
        val s = current
        if (s.closed) return null
        pause()
        s.reader?.let { return it }
        val row = s.row ?: return null
        val reader = gallery.reader(row) ?: return null
        var kept = false
        _state.update { now ->
            if (now.closed || now.row?.mediaId != row.mediaId || now.reader != null) now
            else now.copy(reader = reader).also { kept = true }
        }
        if (!kept) {
            reader.close()
            return current.reader
        }
        return reader
    }

    /** The player went (its view left): the reader closes. */
    fun stop() {
        var stale: RangeReader? = null
        _state.update { s -> stale = s.reader; s.copy(reader = null) }
        endInterruption()
        stale?.close()
    }

    /** Medium [mediaId]'s details ([GalleryModel]'s reads: the EXIF tier once per medium, the uploaders once per gallery). */
    suspend fun details(mediaId: String): Details? = gallery.details(mediaId)

    /** Medium [mediaId]'s capture-time editor in its details ([GalleryModel.captureTimeEditor]); null where not offered. */
    fun captureTimeEditor(mediaId: String): CaptureTimeEditor? = gallery.captureTimeEditor(mediaId)

    /**
     * Medium [mediaId]'s place name resolved once, when its details show (ADR 0143 D7) — never while the slideshow runs;
     * the name sealed, or null.
     */
    suspend fun resolvePlace(mediaId: String): String? = gallery.placeName(mediaId, slideshow = current.slideshow == Slideshow.PLAYING)

    /** What the current medium's original is saved as (`MediaDetails.downloadName`), what a save dialog proposes; null for none. */
    fun downloadName(): String? {
        val s = current
        val row = s.row?.takeIf { !it.blocked } ?: return null
        return MediaDetails.downloadName(row.item, gallery.node.name, s.index)
    }

    /**
     * The current medium's original saved through [into] (as `FileModel.save`: the platform opens where the person chose
     * and hands its sink — natively core's `FileSink`, which removes a copy that did not finish — and answers whether it
     * kept it), under [downloadName], a frame at a time; it outlives the lightbox. Null for a blocked medium.
     */
    fun save(into: suspend (produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) -> Boolean): FileSave? {
        val s = current
        val row = s.row ?: return null
        val save = gallery.save(row, s.index, into) ?: return null
        _state.update { it.copy(save = save) }
        return save
    }

    /**
     * The current medium deleted as its gallery deletes it ([GalleryModel.trash]: a member's to the trash with its Undo, a
     * guest's own one asked first); the lightbox then shows the one now at its place. What to say at once, or null.
     */
    suspend fun trash(): Notice? {
        val row = current.row ?: return null
        pause()
        return gallery.trash(row.mediaId)
    }

    /** The lightbox went: its reader closes, nothing more is followed. */
    fun close() {
        var stale: RangeReader? = null
        ticking?.cancel()
        _state.update { s -> stale = s.reader; s.copy(reader = null, closed = true, slideshow = s.slideshow?.let { if (it == Slideshow.PLAYING) Slideshow.PAUSED else it }) }
        endInterruption()
        stale?.close()
        scope.cancel()
    }

    companion object {
        /** The slideshow's step (the web's `SLIDESHOW_INTERVAL_MS`, `MediaLightbox.vue`; `LightboxSlideshowPinTest` holds it there). */
        const val SLIDESHOW_MS = 5_000L
    }
}
