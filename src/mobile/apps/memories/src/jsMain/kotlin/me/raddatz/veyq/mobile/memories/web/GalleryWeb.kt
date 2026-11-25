@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.memories.web

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.promise
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.MemoryRights
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.coroutines.CoroutineContext
import kotlin.js.Promise
import kotlin.js.collections.JsReadonlyArray

// The gallery's model on the web (ADR 0155 decision 8, § The gallery model's move): `GalleryModel` — what a Memory's or a
// chapter's page lists, in the grid's order, read anew by its live poll and after an upload, a member's trash with its Undo
// and a link guest's purge of its own medium asked first — run over a door the web writes (`GalleryDoorShape`): the web's
// listing, opened in its worker, as plain items, and its writes. Nothing of the session, the generated API client, Ktor or
// kotlinx.serialization is reached (the package's slice check): the model's I/O is the door. No key crosses: an item's
// key is the web's, which reads its tiles itself. The state crosses as plain values (`GalleryStateShape`), the model as an
// opaque handle (`GalleryHandle`), observed through `memoriesGalleryWatch` (decision 8: one composable, a `shallowRef`).

/** A page's model, opaque: no member in its `.d.mts`, held `markRaw` by the web, read only by this façade. */
@JsExport
class GalleryHandle internal constructor(internal val model: GalleryModel, internal val door: WebDoor)

/** A watch of a page's model ([memoriesGalleryWatch]); [memoriesGalleryUnwatch] ends it. */
@JsExport
class GalleryWatch internal constructor(internal val watch: GalleryModel.Watch)

/** The model's door over the web's ([GalleryDoorShape]): its reads and writes, each a `Promise` awaited. */
internal class WebDoor(private val web: GalleryDoorShape, private val scopeId: String, private val nodeId: String) : GalleryModel.Door {
    /** The listing in the server's order: the grid's is the model's ([MemoryMedia.order]). */
    override suspend fun gallery(): MemoryMedia.Gallery? {
        val items = web.gallery().await() ?: return null
        return MemoryMedia.Gallery(items.unsafeCast<Array<GalleryItemShape>>().map { itemOf(it) }, 0, 0)
    }

    private fun itemOf(s: GalleryItemShape): MemoryMedia.Item {
        val meta = if (s.capturedAt == null && s.capturedAtOverride == null && s.width == null && s.height == null) null
        else MemoryMedia.Meta(s.width?.toInt(), s.height?.toInt(), s.capturedAt?.toLong(), s.capturedAtOverride?.toLong(), null, null, null, null, null)
        // no key crosses: the web reads the medium's bytes itself
        return MemoryMedia.Item(
            scopeId, nodeId, s.mediaId, MemoryMedia.Blob(s.mediaId, 0, 0, "", null), null, null, meta, s.type, s.name, s.ownerKey,
            s.blocked, ByteArray(0),
        )
    }

    override suspend fun scopeNodes() = emptyList<me.raddatz.veyq.mobile.navigation.NodeRef>()
    override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
    override fun reader(item: MemoryMedia.Item): me.raddatz.veyq.mobile.blob.RangeReader? = null
    override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
    override suspend fun exif(item: MemoryMedia.Item): me.raddatz.veyq.mobile.memories.MediaDetails.Exif? = null
    override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, me.raddatz.veyq.mobile.memories.MediaDetails.Uploader>()
    override fun upload(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>) = 0

    override val revisions: Flow<Any?> = flowOf(Unit)
    override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override suspend fun moved(since: MemoryMedia.Gallery): Boolean = web.moved().await()
    override suspend fun trash(mediaId: String) = removalOf(web.trash(mediaId).await())
    override suspend fun restore(mediaId: String) = removalOf(web.restore(mediaId).await())
    override fun isOwn(mediaId: String) = web.isOwn(mediaId)
    override suspend fun purge(mediaId: String) = web.purge(mediaId).await()
    override suspend fun settle(items: List<MemoryMedia.Item>): Boolean = web.settle().await()

    private fun removalOf(s: String) = when (s) {
        "done" -> GalleryModel.Removal.DONE
        "gone" -> GalleryModel.Removal.GONE
        "scope_deleting" -> GalleryModel.Removal.SCOPE_DELETING
        "blocked" -> GalleryModel.Removal.BLOCKED
        else -> GalleryModel.Removal.FAILED
    }
}

private fun plain(): dynamic = js("({})")

/**
 * Where the web's model runs: each step a microtask, as the TypeScript it replaces awaited — no task hop between a read's
 * answer and the state it sets (the platform's default dispatcher queues each through `postMessage`), so a page draws
 * once a read lands, and a test's flushed promises see it (ADR 0155 § The gallery model's move, D6). Its delays are the default's
 * (`setTimeout`).
 */
private object Microtasks : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Promise.resolve(Unit).then { block.run() }
    }
}

private fun pageScope() = CoroutineScope(SupervisorJob() + Microtasks)

private fun ids(list: List<String>): JsReadonlyArray<String> = list.toTypedArray().unsafeCast<JsReadonlyArray<String>>()

/**
 * The page of [nodeId] ([kind]: `MEMORY` or `CHAPTER`) of [scopeId] over [door], for whoever looks: a member ([role] null;
 * [readonly] one who may not delete) or a link's guest ([role] its link's). It reads at once; [memoriesGalleryClose] ends it.
 */
@JsExport
fun memoriesGallery(door: GalleryDoorShape, scopeId: String, nodeId: String, kind: String, role: String?, readonly: Boolean): GalleryHandle {
    val access = if (role == null) MemoryRights.Access.Member(readonly = readonly) else MemoryRights.Access.Guest(role)
    val web = WebDoor(door, scopeId, nodeId)
    return GalleryHandle(GalleryModel(NodeAt(scopeId, nodeId, kind, ""), web, scope = pageScope(), saves = pageScope(), access = access), web)
}

/** Calls [listener] with the state now and with each change after; [memoriesGalleryUnwatch] ends it. */
@JsExport
fun memoriesGalleryWatch(h: GalleryHandle, listener: (GalleryStateShape) -> Unit): GalleryWatch =
    GalleryWatch(h.model.watch { listener(stateOf(it)) })

@JsExport
fun memoriesGalleryUnwatch(w: GalleryWatch) = w.watch.stop()

/** The live poll while the page shows ([memoriesGalleryUnwatch] on what it returns ends it: the page hidden). */
@JsExport
fun memoriesGalleryLive(h: GalleryHandle): GalleryWatch = GalleryWatch(h.model.startLive())

/** The gallery read anew, the promise settled once the rows hold that listing. */
@JsExport
fun memoriesGalleryReload(h: GalleryHandle): Promise<Unit> = h.model.scopePromise { h.model.readAnew() }

/** A medium this device uploaded into [nodeId] is in: the gallery reads anew where it is this page's node. */
@JsExport
fun memoriesGalleryUploaded(h: GalleryHandle, nodeId: String) {
    h.door.uploaded.tryEmit(nodeId)
}

/** The grid's direction: oldest first where [oldestFirst], else newest first; the rows follow at once. */
@JsExport
fun memoriesGallerySort(h: GalleryHandle, oldestFirst: Boolean) = h.model.sort(oldestFirst)

/** Medium [mediaId] deleted as the rights say (a member's to the trash with its Undo; a guest's own one asked first). */
@JsExport
fun memoriesGalleryTrash(h: GalleryHandle, mediaId: String): Promise<Unit> = h.model.scopePromise { h.model.trash(mediaId) }

/** The Undo of the last trash, while offered; what it said lands in the state ([GalleryStateShape.said]). */
@JsExport
fun memoriesGalleryUndo(h: GalleryHandle): Promise<Unit> = h.model.scopePromise { h.model.undo() }

/** The asked purge answered: done where [confirm], else declined. */
@JsExport
fun memoriesGalleryPurge(h: GalleryHandle, confirm: Boolean): Promise<Unit> =
    if (confirm) h.model.scopePromise { h.model.purge() } else { h.model.cancelAsk(); Promise.resolve(Unit) }

/** Media [mediaIds] restored from the node's trash by the page's Trash tab: the next read shows them. */
@JsExport
fun memoriesGalleryRestored(h: GalleryHandle, mediaIds: JsReadonlyArray<String>) =
    h.model.restored(mediaIds.unsafeCast<Array<String>>().asList())

/** What [GalleryStateShape.said] said was seen. */
@JsExport
fun memoriesGallerySeen(h: GalleryHandle) = h.model.seen()

/** The page went: nothing more is read or watched. */
@JsExport
fun memoriesGalleryClose(h: GalleryHandle) = h.model.close()

private fun <T> GalleryModel.scopePromise(block: suspend () -> T): Promise<Unit> = pageScope().promise { block(); Unit }

private fun stateOf(s: GalleryModel.State): GalleryStateShape {
    val o = plain()
    o.status = when {
        s.failed -> "error"
        s.rows == null -> "loading"
        else -> "ready"
    }
    o.rows = ids(s.rows.orEmpty().map { it.mediaId })
    o.music = ids(s.music.map { it.mediaId })
    o.tracks = ids(s.tracks.map { it.mediaId })
    o.undo = s.undo != null
    o.purging = s.purging
    o.said = s.said?.let(::saidOf)
    return o.unsafeCast<GalleryStateShape>()
}

/** What a delete or an Undo left to say, as the web words it: a restore refused (its name and reason), a purge failed. */
private fun saidOf(n: Notice): SaidShape? {
    val o = plain()
    when (n.msg) {
        Msg.MEMORY_RESTORE_REFUSED -> {
            o.kind = "restoreRefused"
            val name = n.args.getOrNull(0)
            if (name is Notice) o.unnamedAt = (name.args.getOrNull(0) as? Int) else o.name = name?.toString()
            o.reason = when ((n.args.getOrNull(1) as? Notice)?.msg) {
                Msg.MEMORY_REFUSED_GONE -> "gone"
                Msg.MEMORY_REFUSED_SCOPE_DELETING -> "scope_deleting"
                Msg.MEMORY_REFUSED_BLOCKED -> "blocked"
                else -> "failed"
            }
        }
        Msg.MEMORY_DELETE_OWN_FAILED -> o.kind = "purgeFailed"
        else -> return null
    }
    return o.unsafeCast<SaidShape>()
}
