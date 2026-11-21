package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.offline.Outbox
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedBlob
import me.raddatz.veyq.mobile.offline.QueuedNodes
import me.raddatz.veyq.mobile.offline.QueuedOutcome
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.offline.QueuedWrite
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * Memories' writes made offline (ADR 0153 decision 3's Memories row): a Memory or a chapter made, photos and videos added,
 * a medium's capture time set, a medium trashed, a mark set or taken away — queued as the person's intent ([Outbox]) and
 * sent through the online path each takes ([MemoryCreate], [MediaUpload], [CaptureTime], [MemoryMedia.trash], [ItemMarks]).
 *
 * - **A medium is sealed at once** ([media]): its tiers derived by the platform's [Derivation.Deriver], its digest and
 *   metadata read ([MediaUpload.prepare]) — exactly the on-device stage of an online upload —, then the original and the
 *   two tiers sealed frame by frame under fresh `K_blob`s ([Outbox.sealBlob]) and the picked file let go ([left]). Only
 *   each `K_blob`'s wrap under the node's key is made at the send, under the key the node has then (decision 1).
 * - **What waits for what:** a medium, a mark into a Memory or chapter made offline waits for its create
 *   ([QueuedWrite.after]); a mark of a medium added offline waits for it.
 * - **Sending** ends as decision 4 has it: a create or a medium answered `id_taken` is read back (there as the person made
 *   it: sent; otherwise held); a capture time someone else changed since the person saw it is held (a same-field change),
 *   any other change to the medium's tier merges as online; a mark sets the person's own set to what they chose, whatever
 *   it holds now (a set is the person's alone, never a conflict); a target gone is refused.
 */
object QueuedMemories {
    @Serializable
    @PlatformApi
    @HiddenFromObjC
    data class CreateIntent(val parent: String? = null, val kind: String, val name: String, val wasThere: Boolean = false, val autoPlace: Boolean = false)

    @Serializable
    internal data class MediumIntent(
        val type: String, val sha256: String, val name: String? = null, val modifiedAt: Long? = null, val size: Long,
        val metaTier: JsonObject? = null, val exif: JsonObject? = null, val thumbnail: String? = null, val preview: String? = null,
    )

    @Serializable
    @PlatformApi
    @HiddenFromObjC
    data class TimeIntent(val mediaId: String, val overrideMs: Long? = null, val seen: Long? = null)

    @Serializable
    @PlatformApi
    @HiddenFromObjC
    data class MediaIntent(val mediaId: String)

    @Serializable
    @PlatformApi
    @HiddenFromObjC
    data class MarkIntent(val mediaId: String, val marked: Boolean)

    // ---- queuing

    /**
     * A [kind] ([MemoryCreate.MEMORY] or [MemoryCreate.CHAPTER]) named [name] queued in [scope] of [tenant] under [parent]
     * (null: the space's top; one made offline: it waits for it), [wasThere] and [autoPlace] as New asked them. Null where
     * nothing was queued: no store, an empty name, or the volume below the floor.
     */
    suspend fun create(
        account: VeyqAccount, tenant: String, scope: String, parent: String?, kind: String, name: String, nowMs: Long,
        wasThere: Boolean = false, autoPlace: Boolean = false,
    ): QueuedWrite? {
        val box = account.outbox ?: return null
        val n = name.trim()
        if (n.isEmpty() || kind !in setOf(MemoryCreate.MEMORY, MemoryCreate.CHAPTER)) return null
        val id = NodeCreate.uuid(account)
        val write = QueuedWrite(
            id, Queued.MEMORY_CREATE.name, tenant, scope, node = id, after = parent?.takeIf { box.write(it) != null },
            intent = QueuedNodes.encode(CreateIntent.serializer(), CreateIntent(parent, kind, n, wasThere, autoPlace)), at = nowMs,
        )
        return write.takeIf { box.put(it) }
    }

    /**
     * The picked [sources] queued into Memory or chapter [node] of [scope]: each medium's on-device stage run now (its tiers
     * by [deriver], its digest and metadata, [now] bounding a video's capture time), sealed at once, and the picked file
     * handed back to [left] — whatever came of it. A source that is no medium is handed back unqueued. The writes queued, in
     * order; one the floor refused is not among them (and its frames are gone again).
     */
    suspend fun media(
        account: VeyqAccount, tenant: String, scope: String, node: String, sources: List<UploadSource>,
        deriver: Derivation.Deriver, now: () -> Long, left: (UploadSource) -> Unit,
    ): List<QueuedWrite> {
        val box = account.outbox
        val out = ArrayList<QueuedWrite>()
        for (source in sources) {
            try {
                if (box == null || !MediaUpload.isMedia(source.type, source.name)) continue
                medium(box, account, tenant, scope, node, source, deriver, now)?.let { out += it }
            } finally {
                source.close()
                runCatching { left(source) }
            }
        }
        return out
    }

    private suspend fun medium(
        box: Outbox, account: VeyqAccount, tenant: String, scope: String, node: String, source: UploadSource,
        deriver: Derivation.Deriver, now: () -> Long,
    ): QueuedWrite? {
        val original = MediaUpload.Original(source.bytes(), source.type, source.name, source.modifiedAt)
        val prepared = MediaUpload.prepare(original, now()) { type ->
            suspendCancellableCoroutine { c -> deriver.derive(source, type) { c.resume(it) { _, _, _ -> } } }
        }
        val sealed = ArrayList<QueuedBlob>()
        try {
            sealed += box.sealBlob(original.source) ?: return null
            val d = prepared.derived
            val tiers = d.thumbnail != null && d.preview != null
            val thumbnail = if (tiers) box.sealBlob(d.thumbnail!!)?.also { sealed += it } ?: return null else null
            val preview = if (tiers) box.sealBlob(d.preview!!)?.also { sealed += it } ?: return null else null
            d.thumbnail?.fill(0)
            d.preview?.fill(0)
            val id = NodeCreate.uuid(account)
            val intent = MediumIntent(
                prepared.type, prepared.sha256, source.name, source.modifiedAt, source.size, prepared.metaTier, prepared.exif,
                thumbnail?.id, preview?.id,
            )
            val write = QueuedWrite(
                id, Queued.MEDIA_ADD.name, tenant, scope, node = node, after = node.takeIf { box.write(it) != null },
                intent = QueuedNodes.encode(MediumIntent.serializer(), intent), blobs = sealed.toList(), at = now(),
            )
            if (!box.put(write)) return null
            sealed.clear()
            return write
        } finally {
            // what was sealed of a medium that was not queued is gone again
            sealed.forEach { box.dropBlob(it) }
        }
    }

    /**
     * Medium [item]'s capture time set to [overrideMs] (null: back to the original), stating the correction the person saw
     * ([MemoryMedia.Meta.capturedAtOverride]). A second change replaces the first and keeps what the first saw. False where
     * nothing was queued.
     */
    suspend fun captureTime(account: VeyqAccount, tenant: String, item: MemoryMedia.Item, overrideMs: Long?, nowMs: Long): Boolean {
        val box = account.outbox ?: return false
        val id = TIME + item.mediaId
        val seen = box.write(id)?.let { QueuedNodes.decode(TimeIntent.serializer(), it).seen } ?: item.meta?.capturedAtOverride
        val intent = TimeIntent(item.mediaId, overrideMs?.let(CaptureTime::minute), seen)
        return box.put(QueuedWrite(id, Queued.MEDIA_CAPTURE_TIME.name, tenant, item.scopeId, node = item.nodeId, intent = QueuedNodes.encode(TimeIntent.serializer(), intent), at = nowMs))
    }

    /**
     * Medium [mediaId] of [node] to the trash. One added offline is dropped from the queue instead, with what waits for it
     * (its marks); a queued capture time of it goes with it. False where nothing was queued.
     */
    suspend fun trash(account: VeyqAccount, tenant: String, scope: String, node: String, mediaId: String, nowMs: Long): Boolean {
        val box = account.outbox ?: return false
        box.write(TIME + mediaId)?.let { box.remove(it.id) }
        if (box.write(mediaId)?.op == Queued.MEDIA_ADD.name) {
            QueuedNodes.dropWithDependents(box, mediaId)
            return true
        }
        return box.put(QueuedWrite(TRASH + mediaId, Queued.MEDIA_TRASH.name, tenant, scope, node = node, intent = QueuedNodes.encode(MediaIntent.serializer(), MediaIntent(mediaId)), at = nowMs))
    }

    /** Medium [mediaId] of [node] marked or not ([marked]) by the person: the last choice stands. False where nothing was queued. */
    suspend fun mark(account: VeyqAccount, tenant: String, scope: String, node: String, mediaId: String, marked: Boolean, nowMs: Long): Boolean {
        val box = account.outbox ?: return false
        val after = mediaId.takeIf { box.write(it)?.op == Queued.MEDIA_ADD.name } ?: node.takeIf { box.write(it) != null }
        return box.put(
            QueuedWrite(MARK + node + ":" + mediaId, Queued.MEDIA_MARK.name, tenant, scope, node = node, after = after, intent = QueuedNodes.encode(MarkIntent.serializer(), MarkIntent(mediaId, marked)), at = nowMs),
        )
    }

    // ---- sending

    internal val createSender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(CreateIntent.serializer(), w)
        val made = MemoryCreate.create(tenant, w.scope, i.parent, i.kind, i.name, i.wasThere, i.autoPlace, nodeId = w.id)
        val r = made.refused
        when {
            made.node != null -> QueuedOutcome.Sent
            r != null && r.code == QueuedNodes.ID_TAKEN -> {
                // maybe sent already, its answer lost: the node there as the person made it is theirs
                tenant.forgetTree()
                val there = tenant.trees()[w.scope].orEmpty().firstOrNull { it.id == w.id }
                if (there != null && there.kind == i.kind && there.parentId == i.parent && there.name == i.name) QueuedOutcome.Sent
                else QueuedOutcome.Held(QueuedNodes.ID_TAKEN)
            }
            r != null -> when (r.reason) {
                NodeCreate.Reason.NAME_TAKEN -> QueuedOutcome.Held(QueuedNodes.NAME_TAKEN)
                NodeCreate.Reason.BUSY -> QueuedOutcome.Waiting
                NodeCreate.Reason.NOT_ALLOWED -> QueuedOutcome.Refused(MemoryCreate.ILLEGAL)
                NodeCreate.Reason.BLOCKED -> QueuedOutcome.Refused("blocked")
                NodeCreate.Reason.REFUSED -> QueuedOutcome.Refused(r.code ?: r.status?.let { "http_$it" } ?: "refused")
            }
            made.code == MemoryCreate.NAME_TAKEN -> QueuedOutcome.Held(QueuedNodes.NAME_TAKEN)
            made.code != null -> QueuedOutcome.Refused(made.code)
            else -> QueuedOutcome.Refused("refused")
        }
    }

    internal val mediumSender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(MediumIntent.serializer(), w)
        val node = w.node ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val box = tenant.account.outbox ?: return@QueuedSender QueuedOutcome.Refused("refused")
        fun blob(id: String?) = id?.let { b -> w.blobs.firstOrNull { it.id == b } }?.let(box::sealed)
        val originalBlob = w.blobs.firstOrNull { it.id != i.thumbnail && it.id != i.preview } ?: return@QueuedSender QueuedOutcome.Refused(Outbox.FRAME_GONE)
        val sealed = MediaUpload.SealedMedium(w.id, box.sealed(originalBlob), blob(i.thumbnail), blob(i.preview))
        // the plaintext is gone: only the frames sealed then are sent
        val original = MediaUpload.Original(ByteSource(i.size) { _, _ -> throw IllegalStateException("a queued medium is sent sealed") }, i.type, i.name, i.modifiedAt)
        val prepared = MediaUpload.Prepared(i.type, i.sha256, Derivation.Derived(null, null, null, null), i.metaTier, i.exif)
        val r = MediaUpload.once(tenant, w.scope, node, original, prepared, sealed) {}
        when (r.outcome) {
            MediaUpload.Outcome.DONE -> QueuedOutcome.Sent
            MediaUpload.Outcome.NO_ROOM -> QueuedOutcome.Refused(QUOTA_EXCEEDED)
            MediaUpload.Outcome.SWITCHED_OFF -> QueuedOutcome.Refused("capability_disabled")
            MediaUpload.Outcome.BUSY -> QueuedOutcome.Waiting
            MediaUpload.Outcome.FAILED -> when {
                r.code == QueuedNodes.ID_TAKEN ->
                    // maybe completed already, its answer lost: the medium listed under the node is the person's
                    if (MemoryMedia.list(tenant, w.scope, node)?.items?.any { it.mediaId == w.id } == true) QueuedOutcome.Sent
                    else QueuedOutcome.Held(QueuedNodes.ID_TAKEN)
                r.code == "node_unreadable" -> QueuedOutcome.Refused(QueuedNodes.NOT_FOUND)
                // the store not reached (a frame's PUT, a window's signing): tried again at the next reconnect
                r.code?.startsWith("chunk_put_failed") == true || r.code?.startsWith("window") == true -> QueuedOutcome.Waiting
                else -> QueuedOutcome.Refused(r.code ?: "refused")
            }
        }
    }

    internal val timeSender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(TimeIntent.serializer(), w)
        val node = w.node ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val item = MemoryMedia.list(tenant, w.scope, node)?.items?.firstOrNull { it.mediaId == i.mediaId }
            ?: return@QueuedSender QueuedOutcome.Refused(QueuedNodes.NOT_FOUND)
        // the field the person set was changed by someone else since they saw it: theirs is held, never written over
        val now = runCatching { CaptureTime.overrideOf(ListedTierWrite.openRaw(tenant.account.suite, item.scopeId, item.mediaId, item.key, item.wrappedMeta)) }
            .getOrElse { return@QueuedSender QueuedOutcome.Refused("meta_unreadable") }
        if (now != i.seen) return@QueuedSender QueuedOutcome.Held(STALE_REVISION)
        when (val o = if (i.overrideMs == null) CaptureTime.reset(tenant, item) else CaptureTime.set(tenant, item, i.overrideMs)) {
            is CaptureTime.Outcome.Done -> QueuedOutcome.Sent
            is CaptureTime.Outcome.Conflict -> QueuedOutcome.Held(STALE_REVISION)
            CaptureTime.Outcome.Unreadable -> QueuedOutcome.Refused("meta_unreadable")
            is CaptureTime.Outcome.Failed -> QueuedOutcome.Refused(o.code)
        }
    }

    internal val trashSender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(MediaIntent.serializer(), w)
        when (MemoryMedia.trash(tenant, w.scope, i.mediaId)) {
            GalleryModel.Removal.DONE -> QueuedOutcome.Sent
            GalleryModel.Removal.GONE -> QueuedOutcome.Refused(QueuedNodes.NOT_FOUND)
            GalleryModel.Removal.SCOPE_DELETING -> QueuedOutcome.Refused("scope_deleting")
            GalleryModel.Removal.BLOCKED -> QueuedOutcome.Refused("blocked")
            GalleryModel.Removal.FAILED -> QueuedOutcome.Refused("refused")
        }
    }

    internal val markSender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(MarkIntent.serializer(), w)
        val node = w.node ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val marks = ItemMarks.of(tenant, w.scope, node)
        if (!marks.load()) return@QueuedSender QueuedOutcome.Refused("refused")
        when (marks.set(i.mediaId, i.marked)) {
            ItemMarks.Toggled.DONE -> QueuedOutcome.Sent
            ItemMarks.Toggled.TOO_MANY -> QueuedOutcome.Refused("too_many")
            ItemMarks.Toggled.NOT_LOADED, ItemMarks.Toggled.SAVE_FAILED -> QueuedOutcome.Refused("refused")
        }
    }

    /** Memories' senders, by [Queued] value ([me.raddatz.veyq.mobile.AppKinds.queuedSender] hands them out). */
    internal fun sender(q: Queued): QueuedSender = when (q) {
        Queued.MEMORY_CREATE -> createSender
        Queued.MEDIA_ADD -> mediumSender
        Queued.MEDIA_CAPTURE_TIME -> timeSender
        Queued.MEDIA_TRASH -> trashSender
        Queued.MEDIA_MARK -> markSender
        else -> throw IllegalArgumentException("not Memories': $q")
    }

    private const val TIME = "time:"
    private const val TRASH = "trash-medium:"
    private const val MARK = "mark:"
    internal const val STALE_REVISION = "stale_revision"
    internal const val QUOTA_EXCEEDED = "quota_exceeded"
}
