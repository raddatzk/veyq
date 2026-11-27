package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.ViewMode
import me.raddatz.veyq.mobile.offline.QueuedShown
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

// Memories' pages over the platform's shell (ADR 0156, inbox #1215): each a model the shell hands a Memory's or a chapter's
// page, its uploads, its editors, its music and tracks, a person's Memories — extensions of [ShellModel], so every client
// calls them where it did (Swift: methods of a category on `ShellModel`; Kotlin: imported from this package). They read
// the shell through the members it opens to its applications (`@PlatformApi`).

/**
 * Where this shell's place names come from: its map services once the platform built them (`ShellModel.mapServices`),
 * asked in the app's language — a settle's `auto` place and a photo's name. Null before: no name is looked up then (a
 * settle still re-medoids).
 */
val ShellModel.placeNames: me.raddatz.veyq.mobile.memories.place.PlaceNames?
    get() = builtMaps?.let { me.raddatz.veyq.mobile.memories.place.PlaceNames(it, mapsLanguage) }

/** Memory or chapter [nodeId] of space [scopeId] among [nodes]; null where it is not among them or is another kind. */
fun ShellModel.Companion.galleryAt(nodes: List<NodeRef>, scopeId: String, nodeId: String): NodeAt? =
    nodes.firstOrNull { it.scopeId == scopeId && it.nodeId == nodeId }
        ?.takeIf { it.kind == GalleryModel.MEMORY || it.kind == GalleryModel.CHAPTER }
        ?.let { NodeAt(it.scopeId, it.nodeId, it.kind, it.name) }

/** The media added offline into [nodeId], as its gallery's placeholder tiles marked *Waiting to send* (failed: refused). */
private fun ShellModel.queuedTiles(nodeId: String): kotlinx.coroutines.flow.Flow<List<me.raddatz.veyq.mobile.memories.UploadTile>> {
    val box = tenant.account.outbox ?: return kotlinx.coroutines.flow.flowOf(emptyList())
    return box.changes.map {
        box.writes().filter { it.op == me.raddatz.veyq.mobile.offline.Queued.MEDIA_ADD.name && it.node == nodeId }.map { w ->
            val name = (w.intent["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val type = (w.intent["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val status = if (w.state == me.raddatz.veyq.mobile.offline.QueuedWrite.State.WAITING) me.raddatz.veyq.mobile.blob.UploadQueue.Status.QUEUED
            else me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED
            me.raddatz.veyq.mobile.memories.UploadTile(w.id, name, me.raddatz.veyq.mobile.memories.MediaUpload.claimsVideo(type, name), status, 0.0, w.id, waiting = true)
        }
    }
}

/**
 * What the outbox holds for gallery [nodeId]: the media whose trash waits, the marks and the capture times set offline,
 * and the chapters made in it offline, each with what it says (ADR 0153 decision 5, #1040).
 */
private suspend fun ShellModel.queuedInGallery(nodeId: String): me.raddatz.veyq.mobile.memories.GalleryModel.QueuedHere {
    val all = tenant.account.outbox?.writes().orEmpty().filter { it.tenant == tenantId }
    val writes = all.filter { it.node == nodeId && it.state == me.raddatz.veyq.mobile.offline.QueuedWrite.State.WAITING }
    fun field(w: me.raddatz.veyq.mobile.offline.QueuedWrite, k: String) = (w.intent[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return me.raddatz.veyq.mobile.memories.GalleryModel.QueuedHere(
        trashed = writes.filter { it.op == me.raddatz.veyq.mobile.offline.Queued.MEDIA_TRASH.name }.mapNotNull { field(it, "mediaId") }.toSet(),
        marks = writes.filter { it.op == me.raddatz.veyq.mobile.offline.Queued.MEDIA_MARK.name }.mapNotNull { w -> field(w, "mediaId")?.let { it to (field(w, "marked") == "true") } }.toMap(),
        times = writes.filter { it.op == me.raddatz.veyq.mobile.offline.Queued.MEDIA_CAPTURE_TIME.name }.associate { w ->
            me.raddatz.veyq.mobile.offline.QueuedNodes.decode(me.raddatz.veyq.mobile.memories.QueuedMemories.TimeIntent.serializer(), w).let { it.mediaId to it.overrideMs }
        },
        chapters = queuedMade(all).filter { it.first.parentId == nodeId }.associate { (n, w) -> n.nodeId to QueuedShown.said(w) },
    )
}

/**
 * Memories' section of person [userId]'s profile (ADR 0144 decisions 12–13): over the tenant's participants index, a
 * row or a pin opening its Memory with its use recorded ([open]).
 */
fun ShellModel.personMemories(userId: String): me.raddatz.veyq.mobile.memories.people.PersonMemories =
    me.raddatz.veyq.mobile.memories.people.PersonMemories(userId, object : me.raddatz.veyq.mobile.memories.people.PersonMemories.Door {
        override val chosen get() = me.raddatz.veyq.mobile.memories.people.PersonMemories.View.valueOf(viewModes.of(tenant.account.userId, ViewMode.PERSON).name)
        override fun chose(view: me.raddatz.veyq.mobile.memories.people.PersonMemories.View) = viewModes.set(tenant.account.userId, ViewMode.valueOf(view.name))
        override suspend fun index() = me.raddatz.veyq.mobile.memories.people.ParticipantIndex.of(tenant)
        override fun open(ref: ObjectRef) = this@personMemories.open(ref)
    })

/**
 * Memories' uploads of this tenant over the app's [queue] ([me.raddatz.veyq.mobile.memories.MediaUploads]): each picked
 * medium a job whose tiers the platform's [deriver] makes, a gallery told when its medium is in, [changed] at a batch's
 * end (the app reads its lists anew), [left] once the queue is done with a picked file.
 */
fun ShellModel.mediaUploads(
    queue: me.raddatz.veyq.mobile.blob.UploadQueue, deriver: me.raddatz.veyq.mobile.media.Derivation.Deriver,
    left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit, changed: () -> Unit,
): me.raddatz.veyq.mobile.memories.MediaUploads =
    me.raddatz.veyq.mobile.memories.MediaUploads(queue, object : me.raddatz.veyq.mobile.memories.MediaUploads.Door {
        override fun job(scopeId: String, nodeId: String, source: me.raddatz.veyq.mobile.blob.UploadSource) =
            me.raddatz.veyq.mobile.memories.MediaUpload.Job(tenant, scopeId, nodeId, source, deriver, now)
        override fun musicJob(scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken) =
            me.raddatz.veyq.mobile.memories.music.MusicUpload.Job(tenant, scopeId, nodeId, track)
        override fun trackJob(
            scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Check.Taken,
            label: me.raddatz.veyq.mobile.memories.music.TrackLabel.Label,
        ) = me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Job(tenant, scopeId, nodeId, track, label)
        // the batch's settle (ADR 0143 D5): where this member may edit the node's meta, online
        override suspend fun settled(scopeId: String, nodeId: String) {
            if (!online) return
            val node = tenant.node(scopeId, nodeId) ?: return
            val at = NodeAt(scopeId, nodeId, node.kind, node.name)
            if (!memoryRights(at).editMeta) return
            val items = me.raddatz.veyq.mobile.memories.MemoryMedia.list(tenant, scopeId, nodeId)?.takeIf { it.offline == null }?.items ?: return
            me.raddatz.veyq.mobile.memories.place.MemoryFacts.reconcile(tenant, scopeId, nodeId, items, placeNames)
        }
        override fun changed() = changed()
        override fun left(source: me.raddatz.veyq.mobile.blob.UploadSource) = left(source)
        override suspend fun queue(scopeId: String, nodeId: String, sources: List<me.raddatz.veyq.mobile.blob.UploadSource>): Int {
            if (!queues(me.raddatz.veyq.mobile.offline.Queued.MEDIA_ADD)) return 0
            return me.raddatz.veyq.mobile.memories.QueuedMemories.media(tenant.account, tenantId, scopeId, nodeId, sources, deriver, now, left).size
        }
    })

/**
 * A Memory or a chapter ([kind], `MemoryCreate.offered` where the shell is) named [name] made in [scopeId] under
 * [parentId] (null: the space's top): the node made, to open, or why not; the lists are read anew. [wasThere]: the
 * maker listed as a Memory's participant from its birth, where [offersWasThere] (ADR 0144 decision 2); [autoPlace]: its
 * place armed to come from its photos, where [offersAutoPlace] (uc.memory.place A1).
 */
suspend fun ShellModel.createMemoryNode(
    scopeId: String, parentId: String?, kind: String, name: String, wasThere: Boolean = false, autoPlace: Boolean = false,
): me.raddatz.veyq.mobile.memories.MemoryCreate.Made {
    if (queues(me.raddatz.veyq.mobile.offline.Queued.MEMORY_CREATE)) {
        // offline it waits to be sent: nothing to open yet, it shows where it was made ([queuedAt])
        if (name.isBlank()) return me.raddatz.veyq.mobile.memories.MemoryCreate.Made(null, Notice(Msg.FOLDER_NAME_EMPTY))
        val queued = me.raddatz.veyq.mobile.memories.QueuedMemories.create(tenant.account, tenantId, scopeId, parentId, kind, name, wallClock(), wasThere, autoPlace && offersAutoPlace())
        return if (queued != null) me.raddatz.veyq.mobile.memories.MemoryCreate.Made(null, Notice(Msg.OFFLINE_WAITING), queued = true)
        else me.raddatz.veyq.mobile.memories.MemoryCreate.Made(null, noRoom)
    }
    if (!online) return me.raddatz.veyq.mobile.memories.MemoryCreate.Made(null, readOnly)
    val armed = autoPlace && offersAutoPlace()
    val made = me.raddatz.veyq.mobile.memories.MemoryCreate.create(tenant, scopeId, parentId, kind, name, wasThere, armed)
    refresh()
    return made
}

/** A Memory [name] made in [scopeId] under [parentId] (null: the space's top); the node made, to open, or why not. */
suspend fun ShellModel.createMemory(scopeId: String, parentId: String?, name: String): me.raddatz.veyq.mobile.memories.MemoryCreate.Made =
    createMemory(scopeId, parentId, name, wasThere = false)

/**
 * [createMemory] with *I was there* ticked or not ([wasThere], ADR 0144 decision 2). An overload, not a default: Swift
 * sees no Kotlin default, and the Apple app calls the three-argument form.
 */
suspend fun ShellModel.createMemory(scopeId: String, parentId: String?, name: String, wasThere: Boolean): me.raddatz.veyq.mobile.memories.MemoryCreate.Made =
    createMemoryNode(scopeId, parentId, me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, name, wasThere)

/** [createMemory] with *Determine location automatically* ticked or not ([autoPlace], uc.memory.place A1); Swift's form, as above. */
suspend fun ShellModel.createMemory(scopeId: String, parentId: String?, name: String, wasThere: Boolean, autoPlace: Boolean): me.raddatz.veyq.mobile.memories.MemoryCreate.Made =
    createMemoryNode(scopeId, parentId, me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, name, wasThere, autoPlace)

/**
 * Whether New Memory offers *Determine location automatically*, unticked (uc.memory.place A1): where this tenant's map services are
 * shown on this device ([mapServices] built, offered and consented) — the web's `mapsAvailable`.
 */
fun ShellModel.offersAutoPlace(): Boolean = me.raddatz.veyq.mobile.memories.MemoryCreate.offersAutoPlace(
    me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, placeNames?.maps?.available == true,
)

/** Whether New Memory in [scopeId] offers *I was there*, ticked (ADR 0144 decision 2: a tenant or group scope). */
fun ShellModel.offersWasThere(scopeId: String): Boolean =
    me.raddatz.veyq.mobile.memories.MemoryCreate.offersWasThere(me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, tenant.scopeKind(scopeId))

/**
 * Memory or chapter [nodeId] of space [scopeId] as the lists hold it now — what a page opened by its ids alone opens (a
 * Mac's lightbox window, whose restorable value carries ids, never a name) —; null where it is not listed or is no
 * Memory or chapter.
 */
fun ShellModel.galleryAt(scopeId: String, nodeId: String): NodeAt? = ShellModel.galleryAt(current?.lists?.nodes.orEmpty(), scopeId, nodeId)

/** A Memory's or a chapter's Contributions view over this tenant (`Contributions.of`, ADR 0149 decision 6). */
fun ShellModel.contributions(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.Contributions =
    me.raddatz.veyq.mobile.memories.Contributions.of(tenant, gallery)

/**
 * Memory or chapter [ref]'s page over this tenant ([me.raddatz.veyq.mobile.memories.GalleryModel]), opened as a member:
 * its media read anew on every revision of these lists, when a medium [uploads] sent into it is in and when its live
 * poll finds them moved; what it adds goes through [uploads]; a medium trashed and restored, a chapter made.
 */
fun ShellModel.galleryModel(
    ref: NodeAt, uploads: me.raddatz.veyq.mobile.memories.MediaUploads, music: me.raddatz.veyq.mobile.audio.PageMusic? = null,
    calendar: me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar = me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar.UTC,
): me.raddatz.veyq.mobile.memories.GalleryModel =
    me.raddatz.veyq.mobile.memories.GalleryModel(ref, object : me.raddatz.veyq.mobile.memories.GalleryModel.Door {
        override suspend fun gallery() =
            // made offline and not sent yet: nothing listed, nothing asked of the server — what waits to go into it shows
            if (madeOffline(ref.nodeId)) me.raddatz.veyq.mobile.memories.MemoryMedia.Gallery(emptyList(), 0, 0, offlineNow() ?: me.raddatz.veyq.mobile.session.OfflineAsOf(null))
            else me.raddatz.veyq.mobile.memories.MemoryMedia.list(tenant, ref.scopeId, ref.nodeId)
        override suspend fun scopeNodes() = scopeNodesWithQueued(ref.scopeId)
        override fun shownOffline(at: me.raddatz.veyq.mobile.session.OfflineAsOf?) = tenant.pagesOffline.shown(ref.nodeId, at)
        override suspend fun read(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, blob: me.raddatz.veyq.mobile.memories.MemoryMedia.Blob) =
            me.raddatz.veyq.mobile.memories.MemoryMedia.read(tenant, item, blob)
        override fun reader(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item) =
            me.raddatz.veyq.mobile.memories.MemoryMedia.reader(tenant, item, item.original)
        override suspend fun stream(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) =
            me.raddatz.veyq.mobile.memories.MemoryMedia.stream(tenant, item, item.original, sink = sink)
        override suspend fun exif(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item) = me.raddatz.veyq.mobile.memories.MediaDetails.exif(tenant, item)
        override suspend fun uploaders(items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>) =
            me.raddatz.veyq.mobile.memories.MediaDetails.uploaders(tenant, ref.scopeId, items)
        override fun upload(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>) = uploads.upload(ref.scopeId, ref.nodeId, ref.name, sources)
        override fun upload(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>, filed: suspend (mediaId: String) -> Unit) =
            uploads.upload(ref.scopeId, ref.nodeId, ref.name, sources, filed)
        // each read of the lists is a new one (a rebuild after a refresh); a move of the shell alone keeps them
        override val revisions = state.map { it?.lists }.distinctUntilChanged { a, b -> a === b }
        override val uploaded = uploads.uploaded
        override val uploading = kotlinx.coroutines.flow.combine(uploads.tiles(ref.nodeId), queuedTiles(ref.nodeId)) { a, b -> b + a }
        override val queues get() = tenant.account.queues(me.raddatz.veyq.mobile.offline.Queued.MEDIA_ADD)
        override suspend fun queueMedia(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>) = uploads.queue(ref.scopeId, ref.nodeId, sources)
        override suspend fun queueMark(mediaId: String, marked: Boolean) =
            queues(me.raddatz.veyq.mobile.offline.Queued.MEDIA_MARK) &&
                me.raddatz.veyq.mobile.memories.QueuedMemories.mark(tenant.account, tenantId, ref.scopeId, ref.nodeId, mediaId, marked, wallClock())
        override suspend fun queuedHere() = this@galleryModel.queuedInGallery(ref.nodeId)
        override suspend fun moved(since: me.raddatz.veyq.mobile.memories.MemoryMedia.Gallery) =
            me.raddatz.veyq.mobile.memories.MemoryMedia.moved(tenant, ref.scopeId, ref.nodeId, since)
        override suspend fun treeMoved() = tenant.treeMoved(ref.scopeId).also { if (it) refresh() }
        override suspend fun trash(mediaId: String) =
            if (queues(me.raddatz.veyq.mobile.offline.Queued.MEDIA_TRASH)) {
                if (me.raddatz.veyq.mobile.memories.QueuedMemories.trash(tenant.account, tenantId, ref.scopeId, ref.nodeId, mediaId, wallClock())) me.raddatz.veyq.mobile.memories.GalleryModel.Removal.DONE
                else me.raddatz.veyq.mobile.memories.GalleryModel.Removal.FAILED
            } else me.raddatz.veyq.mobile.memories.MemoryMedia.trash(tenant, ref.scopeId, mediaId)
        override suspend fun restore(mediaId: String) = me.raddatz.veyq.mobile.memories.MemoryMedia.restore(tenant, ref.scopeId, mediaId)
        override suspend fun createChapter(name: String) =
            createMemoryNode(ref.scopeId, ref.nodeId, me.raddatz.veyq.mobile.memories.MemoryCreate.CHAPTER, name)
        override suspend fun cover(nodeId: String) = NodeCover.openNode(tenant, nodeId)
        override val online = tenant.account.reach.map { it == Reach.Online }.distinctUntilChanged()
        override suspend fun facts() = me.raddatz.veyq.mobile.memories.place.MemoryFacts.of(tenant, ref.scopeId, ref.nodeId)
        override suspend fun settle(items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>) =
            me.raddatz.veyq.mobile.memories.place.MemoryFacts.reconcile(tenant, ref.scopeId, ref.nodeId, items, placeNames)
        override suspend fun placeName(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, mayWrite: Boolean, slideshow: Boolean) =
            me.raddatz.veyq.mobile.memories.place.PhotoPlace.resolve(tenant, item, placeNames, mayWrite, slideshow)
        override val personal = tenant.scopeKind(ref.scopeId) == "PERSONAL"
        override fun marks() = me.raddatz.veyq.mobile.memories.ItemMarks.of(tenant, ref.scopeId, ref.nodeId)
        override suspend fun report(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, category: String, reason: String, contact: String) =
            me.raddatz.veyq.mobile.memories.MediumReport.report(tenant, item, category, reason, contact)
        override suspend fun reportOffered() = this@galleryModel.reportOffered()
        override val origin get() = tenant.origin
        override suspend fun members() = tenant.scopeMembers(ref.scopeId)
        override suspend fun setParticipants(wanted: List<String>, openedWith: List<String>) =
            me.raddatz.veyq.mobile.memories.Participants.set(tenant, ref.scopeId, ref.nodeId, wanted, openedWith).also { runCatching { refresh() } }
        override suspend fun captureTime(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, overrideMs: Long?) =
            if (queues(me.raddatz.veyq.mobile.offline.Queued.MEDIA_CAPTURE_TIME)) {
                // offline it waits to be sent, the field the person saw stated (ADR 0153 decision 4)
                if (me.raddatz.veyq.mobile.memories.QueuedMemories.captureTime(tenant.account, tenantId, item, overrideMs, wallClock()))
                    me.raddatz.veyq.mobile.memories.CaptureTime.Outcome.Done(overrideMs?.let(me.raddatz.veyq.mobile.memories.CaptureTime::minute))
                else me.raddatz.veyq.mobile.memories.CaptureTime.Outcome.Failed("no_room")
            } else if (overrideMs == null) me.raddatz.veyq.mobile.memories.CaptureTime.reset(tenant, item)
            else me.raddatz.veyq.mobile.memories.CaptureTime.set(tenant, item, overrideMs)
        override suspend fun shiftCaptureTime(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, overrideMs: Long?) =
            if (overrideMs == null) me.raddatz.veyq.mobile.memories.CaptureTime.reset(tenant, item)
            else me.raddatz.veyq.mobile.memories.CaptureTime.set(tenant, item, overrideMs, exact = true)
        override suspend fun bannerPicture() =
            tenant.node(ref.scopeId, ref.nodeId)?.let { me.raddatz.veyq.mobile.memories.banner.Banner.picture(tenant, it) }
        override suspend fun bannerReader() =
            tenant.node(ref.scopeId, ref.nodeId)?.let { me.raddatz.veyq.mobile.memories.banner.Banner.reader(tenant, it) }
        override suspend fun setBanner(
            openedWith: me.raddatz.veyq.mobile.memories.banner.Banner.Slots, picked: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Picked,
            items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>, progress: (Double) -> Unit,
        ) = me.raddatz.veyq.mobile.memories.banner.BannerWrite.set(tenant, ref.scopeId, ref.nodeId, openedWith, picked, items, progress)
            .also { runCatching { refresh() } }
        override suspend fun removeBanner(openedWith: me.raddatz.veyq.mobile.memories.banner.Banner.Slots) =
            me.raddatz.veyq.mobile.memories.banner.BannerWrite.remove(tenant, ref.scopeId, ref.nodeId, openedWith).also { runCatching { refresh() } }
    }, access = memoryAccess(ref), now = now, music = music,
        search = me.raddatz.veyq.mobile.memories.GalleryModel.Search(openRequests, searchContributions), calendar = calendar)

/** Who looks at Memory or chapter [ref] from this shell: a member, as the tenant's switches and the folder say. */
private fun ShellModel.memoryAccess(ref: NodeAt) = me.raddatz.veyq.mobile.memories.MemoryRights.Access.Member(
    memoriesOn = "memories" !in tenant.applicationsOff(), inIdentityFolder = inIdentityFolder(ref), viewOnly = viewOnly,
)

/** What a member may do on Memory or chapter [ref] here. */
private fun ShellModel.memoryRights(ref: NodeAt) = me.raddatz.veyq.mobile.memories.MemoryRights.of(memoryAccess(ref), ref.kind)

/**
 * Memory or chapter [gallery]'s date editor (ADR 0143 D5), where whoever looks may edit its meta and online; null
 * otherwise. A save re-reads the page's facts line and the lists (a row shows the range).
 */
fun ShellModel.dateEditor(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.place.DateEditor? {
    val g = gallery.current
    if (!g.editsFacts) return null
    return me.raddatz.veyq.mobile.memories.place.DateEditor(g.facts?.date, g.autoRange) { date ->
        me.raddatz.veyq.mobile.memories.place.MemoryFacts.setDate(tenant, gallery.node.scopeId, gallery.node.nodeId, date).also {
            gallery.factsChanged()
            runCatching { refresh() }
        }
    }
}

/**
 * Memory or chapter [gallery]'s place editor (ADR 0143 D5) over the shell's map services ([placeNames]; none: the name
 * field alone), where whoever looks may edit its meta and online; null otherwise.
 */
fun ShellModel.placeEditor(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.place.PlaceEditor? {
    val names = placeNames
    val g = gallery.current
    if (!g.editsFacts) return null
    val points = g.gpsPoints
    return me.raddatz.veyq.mobile.memories.place.PlaceEditor(
        g.facts?.location, me.raddatz.veyq.mobile.memories.place.MemoryLocation.medoid(points), points, names,
        save = { place ->
            me.raddatz.veyq.mobile.memories.place.MemoryFacts.setLocation(tenant, gallery.node.scopeId, gallery.node.nodeId, place).also {
                gallery.factsChanged()
                runCatching { refresh() }
            }
        },
    )
}

/** The Memories bar's Map tab over [memories] (its All tab's set, ADR 0143 D6): every located one a pin. */
suspend fun ShellModel.memoriesMap(memories: List<NodeRef>): me.raddatz.veyq.mobile.memories.place.MemoriesMap =
    me.raddatz.veyq.mobile.memories.place.MemoriesMap.of(tenant, memories)

/** The date of every Memory and chapter this tenant opens, by node id: a Memories row's second line (ADR 0143 D5). */
suspend fun ShellModel.memoryDates(): Map<String, me.raddatz.veyq.mobile.memories.place.MemoryDate> =
    runCatching { me.raddatz.veyq.mobile.memories.place.MemoryFacts.dates(tenant) }.getOrDefault(emptyMap())

/**
 * Memory or chapter [gallery]'s Map view's tracks over this tenant ([me.raddatz.veyq.mobile.memories.tracks.TrackList],
 * ADR 0143 D8): picked GPX files checked, queued through [uploads] with their labels, a file not taken handed to [left];
 * a track relabelled, reported as an ITEM.
 */
fun ShellModel.trackList(
    gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uploads: me.raddatz.veyq.mobile.memories.MediaUploads,
    left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit,
): me.raddatz.veyq.mobile.memories.tracks.TrackList =
    me.raddatz.veyq.mobile.memories.tracks.TrackList(gallery, object : me.raddatz.veyq.mobile.memories.tracks.TrackList.Door {
        override suspend fun check(source: me.raddatz.veyq.mobile.blob.UploadSource, held: Int) =
            me.raddatz.veyq.mobile.memories.tracks.TrackUpload.check(source, held)
        override fun queue(tracks: List<Pair<me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Check.Taken, me.raddatz.veyq.mobile.memories.music.TrackLabel.Label>>) =
            uploads.uploadTracks(gallery.node.scopeId, gallery.node.nodeId, gallery.node.name, tracks)
        override fun left(source: me.raddatz.veyq.mobile.blob.UploadSource) = left(source)
        override suspend fun relabel(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, label: me.raddatz.veyq.mobile.memories.music.TrackLabel.Label) =
            me.raddatz.veyq.mobile.memories.music.TrackLabel.relabel(tenant, item, label)
        override suspend fun report(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, category: String, reason: String, contact: String) =
            me.raddatz.veyq.mobile.memories.MediumReport.report(tenant, item, category, reason, contact)
        override suspend fun reportOffered() = this@trackList.reportOffered()
        override val origin get() = tenant.origin
    })

/**
 * Memory or chapter [gallery]'s Music view over this tenant ([me.raddatz.veyq.mobile.memories.music.MusicModel]): a
 * picked file checked with the platform's [measure] of its length, the tracks queued through [uploads], a picked file
 * not taken handed to [left]; a track renamed, reported as an ITEM.
 */
fun ShellModel.musicModel(
    gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uploads: me.raddatz.veyq.mobile.memories.MediaUploads,
    measure: me.raddatz.veyq.mobile.memories.music.MusicUpload.Measure, left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit,
): me.raddatz.veyq.mobile.memories.music.MusicModel =
    me.raddatz.veyq.mobile.memories.music.MusicModel(gallery, object : me.raddatz.veyq.mobile.memories.music.MusicModel.Door {
        override suspend fun check(source: me.raddatz.veyq.mobile.blob.UploadSource) =
            me.raddatz.veyq.mobile.memories.music.MusicUpload.check(
                source,
                endsOf = { type, durationMs, limitMs ->
                    kotlinx.coroutines.suspendCancellableCoroutine { c -> measure.ends(source, type, durationMs, limitMs) { ends -> c.resume(ends) { _, _, _ -> } } }
                },
            ) { type ->
                kotlinx.coroutines.suspendCancellableCoroutine { c -> measure.durationMs(source, type) { ms -> c.resume(ms) { _, _, _ -> } } }
            }
        override fun queue(tracks: List<me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken>) =
            uploads.uploadMusic(gallery.node.scopeId, gallery.node.nodeId, gallery.node.name, tracks)
        override fun left(source: me.raddatz.veyq.mobile.blob.UploadSource) = left(source)
        override suspend fun rename(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, name: String?) =
            me.raddatz.veyq.mobile.memories.music.TrackLabel.rename(tenant, item, name)
        override suspend fun report(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, category: String, reason: String, contact: String) =
            me.raddatz.veyq.mobile.memories.MediumReport.report(tenant, item, category, reason, contact)
        override suspend fun reportOffered() = this@musicModel.reportOffered()
        override val origin get() = tenant.origin
    })
