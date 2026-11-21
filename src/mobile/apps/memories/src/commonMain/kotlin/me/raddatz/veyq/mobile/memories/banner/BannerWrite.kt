package me.raddatz.veyq.mobile.memories.banner

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.memories.apis.MemoryBannerApi
import me.raddatz.veyq.mobile.api.memories.models.RemoveMemoryBannerRequest
import me.raddatz.veyq.mobile.api.memories.models.SetMemoryBannerRequest
import me.raddatz.veyq.mobile.api.models.UpdateNodeRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.SharedBlob
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.api.memories.models.SharedBlob as SharedBlobWire

/**
 * The WRITE side of a Memory's banner (uc.memory.banner, ADR 0102, ADR 0144 decision 5) — a port of the web's
 * `bannerWrite.ts` and `useBannerEditor.ts`: the picked file's bytes prepared on the device, sealed and uploaded as the
 * node's own `BANNER` (and `BANNER_POSTER`) blobs, and the slots written with the sealed meta that names them in ONE
 * request through the generated client (`setMemoryBanner` / `removeMemoryBanner`) — so the slots and the meta never part,
 * and a failed new banner leaves the old one exactly as it was (the server compares both slots and the meta revision
 * first and writes nothing on a miss).
 *
 * Operator-blind: every byte is sealed here under a fresh `K_blob` wrapped under the node's key bound to (blob, node, slot)
 * (ADR 0072) — or, where the Memory already stores the very bytes a slot would hold ([StoredMatch]), the slot is a new row
 * on that stored object ([SharedBlob]), its `K_blob` wrapped again for the slot. The server sees ciphertext, the opaque
 * attachment rows and the sealed meta; for a shared slot which medium's object it names (ADR 0102's accepted residual).
 *
 * What a banner is made of, always from a file picked in its dialog:
 *  - an image: one JPEG the platform derives (the cover's spec, [me.raddatz.veyq.mobile.session.NodeCover.LONG_EDGE] and
 *    `QUALITY`) — or the PREVIEW of a gallery medium whose original is the same file, shared; an image above
 *    [MAX_IMAGE_BYTES] is refused before a byte of it is read;
 *  - a video: the file as it is, read by frame (never held whole), its SHA-256 in its `blob-meta` — or a gallery medium's
 *    ORIGINAL of the same file, shared — and its first frame as the poster (the medium's PREVIEW when shared, else the
 *    platform's decode). A video whose first frame does not decode is refused.
 */
object BannerWrite {
    /** The largest picked image taken as a banner source (decoded whole on the device): the web's cover bound. */
    const val MAX_IMAGE_BYTES = 40L * 1024 * 1024

    /**
     * [MAX_IMAGE_BYTES] as its words name it (`memory_banner_too_large`): "40 MB", the web's BannerDialog's — the binary
     * bound named in decimal units, so written by `Shown.size` it reads as the web's does.
     */
    const val MAX_IMAGE_NAMED = 40_000_000L

    /** An image above [MAX_IMAGE_BYTES] (`banner_too_large`). */
    const val TOO_LARGE = "banner_too_large"

    /** A picture the platform cannot decode (`banner_unreadable`). */
    const val UNREADABLE = "banner_unreadable"

    /** A video whose first frame the platform cannot decode (`banner_video_unreadable`). */
    const val VIDEO_UNREADABLE = "banner_video_unreadable"

    /** Neither an image nor a video. */
    const val NOT_MEDIA = "banner_not_media"

    /** The JPEG an image banner and every poster is stored as. */
    const val JPEG = "image/jpeg"

    /**
     * What a platform decodes for a banner (Android's `ImageDecoder` / `MediaMetadataRetriever`, Apple's ImageIO /
     * AVFoundation, the desktops' over the bridge): the picked image as the cover's JPEG (long edge at most 2048, never
     * enlarged, quality 0.85, upright), and a video's first frame as a JPEG of the same spec; null where it does not decode.
     */
    interface Pictures {
        suspend fun image(source: UploadSource): ByteArray?
        suspend fun poster(source: UploadSource): ByteArray?
    }

    /**
     * A file picked in the banner's dialog, decoded: the [source] (read again for its digest and, for a video, its bytes),
     * its [medium], a video's [type], and the [picture] the platform decoded — an image's JPEG, a video's poster — which the
     * dialog's two previews draw and an unshared banner uploads.
     */
    class Picked internal constructor(val source: UploadSource, val medium: Banner.Medium, val type: String?, val picture: ByteArray)

    /** Why a pick was refused: one of [TOO_LARGE], [UNREADABLE], [VIDEO_UNREADABLE], [NOT_MEDIA]. */
    class Refused(val code: String) : Exception(code)

    /**
     * [source] taken as a banner: an image above [MAX_IMAGE_BYTES] refused before a byte of it is read (its type from the
     * listing or its name; the bytes are sniffed only where neither says), then decoded by [pictures]. Throws [Refused].
     */
    suspend fun pick(source: UploadSource, pictures: Pictures): Picked {
        var type = MediaUpload.typeOf(source.type, source.name, ByteArray(0))
        val image = type.startsWith("image/")
        if (image && source.size > MAX_IMAGE_BYTES) throw Refused(TOO_LARGE)
        if (!image && !type.startsWith("video/")) {
            type = MediaUpload.typeOf(null, null, source.read(0, minOf(source.size, MediaUpload.SNIFF_BYTES.toLong()).toInt()))
            if (type.startsWith("image/") && source.size > MAX_IMAGE_BYTES) throw Refused(TOO_LARGE)
        }
        return when {
            type.startsWith("image/") -> {
                val jpeg = runCatching { pictures.image(source) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: throw Refused(UNREADABLE)
                Picked(source, Banner.Medium.IMAGE, null, jpeg)
            }
            type.startsWith("video/") -> {
                val poster = runCatching { pictures.poster(source) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: throw Refused(VIDEO_UNREADABLE)
                Picked(source, Banner.Medium.VIDEO, Banner.videoType(type), poster)
            }
            else -> throw Refused(NOT_MEDIA)
        }
    }

    /** One slot's bytes, ready: bytes to upload, or a stored blob of the Memory the slot shares (ADR 0013 § 5). */
    sealed interface Part {
        class Upload(val size: Long, val type: String, val sha256: String?, val read: suspend (from: Long, length: Int) -> ByteArray) : Part
        class Share(val stored: SharedBlob.Stored) : Part
    }

    /** A banner's bytes, ready to write: an image's one JPEG, or a video as it is (with its file's digest) plus its poster. */
    class Prepared(val medium: Banner.Medium, val main: Part, val poster: Part?, val type: String?, val sha256: String)

    private fun bytesPart(bytes: ByteArray, type: String = JPEG) =
        Part.Upload(bytes.size.toLong(), type, null) { from, length -> bytes.copyOfRange(from.toInt(), (from + length).toInt()) }

    /**
     * [picked]'s parts, its file's digest [sha256] already read: the stored tiers of an equal gallery medium of [items]
     * where there is one ([StoredMatch]), else the image's JPEG, or the video as it is with its poster.
     */
    fun prepare(picked: Picked, sha256: String, items: List<MemoryMedia.Item>): Prepared {
        val equal = StoredMatch.equalMedium(items, picked.source.size, sha256)
        if (picked.medium == Banner.Medium.IMAGE) {
            val preview = equal?.let { StoredMatch.tier(it, MemoryMedia.PREVIEW) }
            return Prepared(Banner.Medium.IMAGE, preview?.let { Part.Share(it) } ?: bytesPart(picked.picture), null, null, sha256)
        }
        val type = Banner.videoType(picked.type)
        val original = equal?.let { StoredMatch.tier(it, MemoryMedia.ORIGINAL) }
        val preview = if (original != null) equal.let { StoredMatch.tier(it, MemoryMedia.PREVIEW) } else null
        val main = original?.let { Part.Share(it) }
            ?: Part.Upload(picked.source.size, type, sha256) { from, length -> picked.source.read(from, length) }
        return Prepared(Banner.Medium.VIDEO, main, preview?.let { Part.Share(it) } ?: bytesPart(picked.picture), type, sha256)
    }

    /** What a banner's preparation leaves: per slot an open upload or a shared row, and the refs the meta names. */
    class Uploaded(
        val medium: Banner.Medium,
        val uploadId: String?, val posterUploadId: String?,
        val shared: SharedBlob.Request?, val posterShared: SharedBlob.Request?,
        val blob: Banner.Ref, val poster: Banner.Ref?, val type: String?, val sha256: String,
        val frame: Banner.Frame = Banner.Frame.FIT, val focal: Banner.Focal = Banner.CENTRE,
    ) {
        /** The `banner` value naming these slots: the whole picture (or a copy's framing), the file's digest (decision 5). */
        val banner: JsonObject get() = Banner.sealed(medium, blob, poster, type, sha256, frame, focal)
    }

    /**
     * [prepared] sealed for node [nodeId] under [nodeKey]: each shared part a new row ([SharedBlob.share], its blob id from
     * [newId]), the rest handed to [pump] in ONE start, the poster first (it is small, and a banner whose poster is up shows
     * at once). The uploads are OPEN afterwards, not yet anyone's banner: [write] attaches them; unattached, the server's
     * stale-session purge reclaims them.
     */
    internal suspend fun upload(
        suite: V1, scopeId: String, nodeId: String, nodeKey: ByteArray, prepared: Prepared, newId: () -> String,
        frame: Banner.Frame = Banner.Frame.FIT, focal: Banner.Focal = Banner.CENTRE,
        pump: suspend (List<ApplicationBlobs.Outgoing>) -> List<Pair<String, ApplicationBlobs.Plan>>,
    ): Uploaded {
        fun share(p: Part.Share, role: String, meta: SharedBlob.Meta) = SharedBlob.share(suite, scopeId, p.stored, nodeKey, nodeId, role, newId(), meta)
        val mainMeta = if (prepared.medium == Banner.Medium.IMAGE) SharedBlob.Meta(JPEG) else SharedBlob.Meta(prepared.type ?: Banner.DEFAULT_VIDEO_TYPE, sha256 = prepared.sha256)
        val main = prepared.main
        val poster = prepared.poster
        val mainShared = (main as? Part.Share)?.let { share(it, Banner.ROLE, mainMeta) }
        val posterShared = (poster as? Part.Share)?.let { share(it, Banner.POSTER_ROLE, SharedBlob.Meta(JPEG)) }
        val outgoing = ArrayList<Pair<String, ApplicationBlobs.Outgoing>>()
        (poster as? Part.Upload)?.let { outgoing += Banner.POSTER_ROLE to ApplicationBlobs.Outgoing(Banner.POSTER_ROLE, it.size, it.type, null, null, null, it.read) }
        (main as? Part.Upload)?.let { outgoing += Banner.ROLE to ApplicationBlobs.Outgoing(Banner.ROLE, it.size, it.type, null, null, it.sha256, it.read) }
        val sent = if (outgoing.isEmpty()) emptyList() else pump(outgoing.map { it.second })
        fun of(role: String) = outgoing.indexOfFirst { it.first == role }.takeIf { it >= 0 }?.let { sent[it] }
        fun ref(plan: ApplicationBlobs.Plan) = Banner.Ref(plan.blobId, plan.chunkCount, plan.byteSize, plan.wrappedBlobKey)
        fun ref(s: SharedBlob.Shared) = Banner.Ref(s.blobId, s.chunkCount, s.byteSize, s.wrappedBlobKey)
        val mainUp = of(Banner.ROLE)
        val posterUp = of(Banner.POSTER_ROLE)
        val blob = mainShared?.let(::ref) ?: ref(mainUp!!.second)
        val posterRef = posterShared?.let(::ref) ?: posterUp?.second?.let(::ref)
        return Uploaded(
            prepared.medium, mainUp?.first, posterUp?.first, mainShared?.request, posterShared?.request,
            blob, posterRef, prepared.type, prepared.sha256, frame, focal,
        )
    }

    /** What a write came to. */
    sealed interface Outcome {
        data object Done : Outcome

        /** A peer changed the banner between the dialog's open and the save (`banner_stale`, or its meta): nothing written. */
        data object Stale : Outcome

        /** The Memory is due for its re-key (`node_rekey_pending`): nothing written, try again. */
        data object Busy : Outcome

        /** Refused otherwise (or a pick refused: [code] one of its codes); nothing written. */
        data class Refused(val code: String?) : Outcome
    }

    /** What one send carries: the slots and the meta, or the meta alone. */
    sealed interface Send {
        val wrappedMeta: String
        val metaRevision: Long

        data class Set(val uploaded: Uploaded, val expectedBlobId: String?, val expectedPosterBlobId: String?, override val wrappedMeta: String, override val metaRevision: Long) : Send
        data class Remove(val blobId: String, val posterBlobId: String?, override val wrappedMeta: String, override val metaRevision: Long) : Send
        data class Update(override val wrappedMeta: String, override val metaRevision: Long) : Send
    }

    /**
     * Writes the node's `banner` field to the banner [upload] makes — or removes it, for null — over the meta at [base], in
     * the same request as the slot step it implies:
     *  - an upload: `setMemoryBanner` attaches the open uploads or creates the shared rows, replacing what the meta being
     *    replaced named (the compare: both slots) — an image replacing a video frees the poster;
     *  - none, where the meta being replaced named a banner blob: `removeMemoryBanner` frees both slots;
     *  - otherwise a plain meta write.
     * [upload] runs ONCE, when the first edit finds the banner still the one the dialog opened on: a `409 stale_revision`
     * re-applies the banner on the meta the server handed back and sends the SAME uploads again ([NodeMetaWrite.write]) —
     * but where that meta's banner slots are no longer [openedWith] (what the dialog opened on), a peer changed the banner:
     * [Outcome.Stale], nothing sent. A slot a peer changed meanwhile is the server's `banner_stale`: [Outcome.Stale] too.
     * Every other field of the meta is kept as it was.
     */
    suspend fun write(
        base: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String,
        openedWith: Banner.Slots,
        upload: (suspend () -> Uploaded)?,
        send: suspend (Send) -> NodeMetaWrite.Answer,
    ): Outcome {
        var replaced = Banner.Slots.NONE
        var peer = false
        var uploaded: Uploaded? = null
        val edit: suspend (JsonObject) -> JsonObject? = edit@{ meta ->
            val stored = Banner.slots(meta[Banner.FIELD])
            if (stored != openedWith) {
                peer = true
                return@edit null
            }
            replaced = stored
            when {
                upload != null -> JsonObject(meta + (Banner.FIELD to (uploaded ?: upload().also { uploaded = it }).banner))
                meta.containsKey(Banner.FIELD) -> JsonObject(meta - Banner.FIELD)
                else -> null
            }
        }
        val r = NodeMetaWrite.write(base, open, seal, edit) { wrapped, revision ->
            val u = uploaded
            send(when {
                u != null -> Send.Set(u, replaced.blobId, replaced.posterBlobId, wrapped, revision)
                replaced.blobId != null -> Send.Remove(replaced.blobId!!, replaced.posterBlobId, wrapped, revision)
                else -> Send.Update(wrapped, revision)
            })
        }
        return when (r) {
            is NodeMetaWrite.Result.Wrote -> Outcome.Done
            is NodeMetaWrite.Result.Unchanged -> if (peer) Outcome.Stale else Outcome.Done
            is NodeMetaWrite.Result.Refused -> when (r.code) {
                "banner_stale" -> Outcome.Stale
                "node_rekey_pending" -> Outcome.Busy
                else -> Outcome.Refused(r.code)
            }
        }
    }

    // ---- against the server ----

    /** At most this many runs where the node's epoch moved under a save (its key replaced): each uploads again under the new key. */
    private const val EPOCH_RUNS = 3

    /**
     * [picked] made node [nodeId]'s banner, the dialog having opened on [openedWith]: its digest read once frame by frame,
     * matched against the node's gallery [items], its parts uploaded (once per node epoch: a stale revision sends the same
     * uploads again, a moved epoch — a new key — uploads again) and written. [progress] is told the fraction of the uploaded
     * bytes sent. A cancel (the caller's coroutine) leaves the old banner as it was.
     */
    @Throws(Exception::class)
    suspend fun set(
        tenant: TenantSession, scopeId: String, nodeId: String, openedWith: Banner.Slots, picked: Picked, items: List<MemoryMedia.Item>,
        progress: (Double) -> Unit = {},
    ): Outcome {
        val sha256 = MediaUpload.sha256(picked.source.bytes())
        val prepared = prepare(picked, sha256, items)
        return onServer(tenant, scopeId, nodeId) { node ->
            val total = listOfNotNull(prepared.main as? Part.Upload, prepared.poster as? Part.Upload).sumOf { it.size }.coerceAtLeast(1).toDouble()
            write(tenant, node, openedWith) {
                progress(0.0)
                upload(tenant.account.suite, scopeId, node.id, node.key, prepared, { NodeCreate.uuid(tenant.account) }) { outgoing ->
                    ApplicationBlobs(tenant).reserveAndPumpAll(scopeId, node.id, node.key, outgoing) { progress(it / total) }
                }
            }
        }
    }

    /**
     * A copied Memory's banner (`MemoryCopy`, ADR 0149 decision 3): [prepared]'s parts — the original's files read by frame —
     * uploaded as they are into the new node [nodeId]'s slots with the original's [frame] and [focal], and written over its
     * meta, which names no banner yet.
     */
    @Throws(Exception::class)
    suspend fun copy(
        tenant: TenantSession, scopeId: String, nodeId: String, prepared: Prepared, frame: Banner.Frame, focal: Banner.Focal,
    ): Outcome = onServer(tenant, scopeId, nodeId) { node ->
        write(tenant, node, Banner.Slots.NONE) {
            upload(tenant.account.suite, scopeId, node.id, node.key, prepared, { NodeCreate.uuid(tenant.account) }, frame, focal) { outgoing ->
                ApplicationBlobs(tenant).reserveAndPumpAll(scopeId, node.id, node.key, outgoing)
            }
        }
    }

    /** Node [nodeId]'s banner removed — both slots freed and the meta written without it — the dialog having opened on [openedWith]. */
    @Throws(Exception::class)
    suspend fun remove(tenant: TenantSession, scopeId: String, nodeId: String, openedWith: Banner.Slots): Outcome =
        onServer(tenant, scopeId, nodeId) { node -> write(tenant, node, openedWith, null) }

    private suspend fun onServer(tenant: TenantSession, scopeId: String, nodeId: String, run: suspend (OpenNode) -> Outcome): Outcome {
        repeat(EPOCH_RUNS) {
            tenant.forgetTree()
            val node = tenant.node(scopeId, nodeId) ?: return Outcome.Refused("node_unreadable")
            val out = try {
                run(node)
            } catch (e: ApplicationBlobs.UploadRefused) {
                Outcome.Refused(e.code)
            } catch (e: SharedBlob.OpenRefused) {
                Outcome.Refused(e.message)
            } catch (e: NodeMetaWrite.Unreadable) {
                Outcome.Refused("meta_unreadable")
            }
            if (out is Outcome.Refused && out.code == "stale_epoch") return@repeat
            if (out == Outcome.Done) tenant.forgetTree() // the tree read before holds the meta it replaced
            return out
        }
        return Outcome.Refused("stale_epoch")
    }

    private suspend fun write(tenant: TenantSession, node: OpenNode, openedWith: Banner.Slots, upload: (suspend () -> Uploaded)?): Outcome {
        val suite = tenant.account.suite
        return write(
            NodeMetaWrite.State(node.wrappedMeta, node.metaRevision),
            { NodeMetaWrite.openForWrite(suite, node.scopeId, node.id, node.key, it) },
            { NodeMetaWrite.seal(suite, node.scopeId, node.id, node.key, it) },
            openedWith, upload,
        ) { s -> sendTo(tenant, node, s) }
    }

    private fun wire(r: SharedBlob.Request?) = r?.let { SharedBlobWire(it.sourceBlobId, it.blobId, it.wrappedBlobKey, it.wrappedBlobMeta) }

    private suspend fun request(tenant: TenantSession, node: OpenNode, s: Send): HttpResponse<Unit> = tenant.account.respond {
        val api = MemoryBannerApi(tenant.base, tenant.account.http)
        when (s) {
            is Send.Set -> api.setMemoryBanner(
                node.scopeId, node.id,
                SetMemoryBannerRequest(
                    nodeEpoch = node.epoch, wrappedMeta = s.wrappedMeta, metaRevision = s.metaRevision,
                    uploadId = s.uploaded.uploadId, shared = wire(s.uploaded.shared),
                    posterUploadId = s.uploaded.posterUploadId, posterShared = wire(s.uploaded.posterShared),
                    expectedBlobId = s.expectedBlobId, expectedPosterBlobId = s.expectedPosterBlobId,
                ),
            )
            is Send.Remove -> api.removeMemoryBanner(
                node.scopeId, node.id,
                RemoveMemoryBannerRequest(nodeEpoch = node.epoch, blobId = s.blobId, wrappedMeta = s.wrappedMeta, metaRevision = s.metaRevision, posterBlobId = s.posterBlobId),
            )
            is Send.Update -> ContentApi(tenant.base, tenant.account.http).updateNode(
                node.scopeId, node.id, UpdateNodeRequest(wrappedMeta = s.wrappedMeta, metaRevision = s.metaRevision, nodeEpoch = node.epoch),
            )
        }
    }

    private suspend fun sendTo(tenant: TenantSession, node: OpenNode, s: Send): NodeMetaWrite.Answer = try {
        val r = request(tenant, node, s)
        if (r.success) NodeMetaWrite.Answer.Taken
        else {
            val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
            NodeMetaWrite.staleOf(body)?.let { NodeMetaWrite.Answer.Stale(it) }
                ?: NodeMetaWrite.Answer.Refused(r.status, runCatching { ((Json.parseToJsonElement(body) as JsonObject)["code"] as? JsonPrimitive)?.content }.getOrNull())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        NodeMetaWrite.Answer.Refused(-1, null)
    }
}
