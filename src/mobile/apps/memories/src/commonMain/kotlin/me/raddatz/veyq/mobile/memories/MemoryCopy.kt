package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaBatchRequest
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeKind
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeUpload
import me.raddatz.veyq.mobile.api.memories.models.MediaRequestItem
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.session.MemberNodeWriter
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeEpochWrite
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Memory's or a chapter's copy within its space (uc.platform.copy-node, ADR 0082 decision 11, ADR 0149 decision 3; the
 * web's copy walk with `addCopiedMedia`, `uploadCoverBlob` and the banner's restore): a new node of the same kind, born with
 * the date and the place sealed in its first meta; then per live medium its original and each existing tier (`THUMBNAIL`,
 * `PREVIEW`) read **a frame at a time** through the core's reader and uploaded as they are under fresh `K_blob`s bound to
 * the new medium, each with the meta its blob declared (type, name, modified time, digest), and its listed tier carried
 * verbatim and its EXIF bundle, both sealed anew under the new node's key — an original is **never decoded** (no tier is
 * derived again, no metadata read again from the bytes: a video is never held whole on a phone); the cover copied as its
 * blob (a cover-sized JPEG, read whole, never re-encoded); the banner uploaded again as its own blobs with its framing (a
 * banner that shared a medium's object, ADR 0102, is copied as its own). Chapters are the copy's children in core's replay
 * (`SubtreeCopy`). What a copy leaves behind is said by `copy_loss_memory`: who was there (ids mean nothing in a copy) and
 * who added each photo (the copier uploads every medium); a medium the operator blocked serves no bytes and is not copied.
 * Every write into the node it made — each medium, the cover, the banner — goes through core's [MemberNodeWriter], as the
 * web's through `memberNodeWriter`: a rotation another member makes mid-copy (`node_rekey_pending`, `stale_epoch`) pulls
 * the new node forward and re-sends that one write under its key now, the next write starting there (inbox #988). A failure
 * part-way trashes the node it made (the web's rollback), so nothing half-copied stays.
 */
object MemoryCopy {
    /** One medium as the copy hands it on: its blobs read by frame (the original first), its listed tier and EXIF raw. */
    @PlatformApi
    @HiddenFromObjC
    class Medium(val blobs: List<ApplicationBlobs.Outgoing>, val tier: JsonObject?, val exif: JsonObject?)

    /** What a create came to: the node made (its key and epoch), a name already held there, or a failure. */
    @PlatformApi
    @HiddenFromObjC
    sealed interface Created {
        class Made(val nodeId: String, val key: ByteArray, val epoch: Int) : Created
        data object Taken : Created
        data object Failed : Created
    }

    /** Where a copy writes: the server ([Server]); a test's own. Each write throws where it did not go through. */
    @PlatformApi
    @HiddenFromObjC
    interface Target {
        suspend fun create(kind: String, name: String, meta: JsonObject?): Created
        suspend fun medium(made: Created.Made, medium: Medium)
        suspend fun cover(made: Created.Made, jpeg: ByteArray, crop: NodeCover.Crop)
        suspend fun banner(made: Created.Made, prepared: BannerWrite.Prepared, frame: Banner.Frame, focal: Banner.Focal)
        suspend fun trash(made: Created.Made)
    }

    /** [node] copied into [scopeId] under [parentId] (null: the space's top) as [name]: `AppKinds.copies`' duplicate. */
    @Throws(Exception::class)
    suspend fun duplicate(tenant: TenantSession, node: OpenNode, scopeId: String, parentId: String?, name: String): SubtreeCopy.Made =
        duplicate(tenant, node, name, Server(tenant, scopeId, parentId))

    @PlatformApi
    @HiddenFromObjC
    suspend fun duplicate(tenant: TenantSession, node: OpenNode, name: String, target: Target): SubtreeCopy.Made {
        val suite = tenant.account.suite
        val meta = runCatching { NodeMetaWrite.openForWrite(suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull()
            ?: return SubtreeCopy.Made(null)
        val items = MemoryMedia.list(tenant, node.scopeId, node.id)?.items?.filter { !it.blocked } ?: return SubtreeCopy.Made(null)
        val birth = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        MemoryDate.of(meta)?.let { birth[MemoryDate.FIELD] = it.toJson() }
        MemoryLocation.of(meta)?.let { birth[MemoryLocation.FIELD] = it.toJson() }
        val made = when (val c = target.create(node.kind, name, if (birth.isEmpty()) null else JsonObject(mapOf("v" to JsonPrimitive(1)) + birth))) {
            is Created.Made -> c
            Created.Taken -> return SubtreeCopy.Made(null, nameTaken = true)
            Created.Failed -> return SubtreeCopy.Made(null)
        }
        try {
            for (item in items) {
                val readers = ArrayList<RangeReader>()
                try {
                    target.medium(made, medium(tenant, item, readers))
                } finally {
                    readers.forEach { it.close() }
                }
            }
            NodeCover.read(meta)?.let { c ->
                val jpeg = NodeCover.open(tenant, node) ?: throw IllegalStateException("cover_unreadable")
                target.cover(made, jpeg, c.crop)
            }
            Banner.of(meta)?.let { b -> banner(tenant, node, b)?.let { target.banner(made, it, b.frame, b.focal) } }
        } catch (e: CancellationException) {
            runCatching { target.trash(made) }
            throw e
        } catch (e: Exception) {
            runCatching { target.trash(made) }
            return SubtreeCopy.Made(null)
        }
        return SubtreeCopy.Made(made.nodeId)
    }

    /** [item] as the copy hands it on: the original and each tier by frame with its declared meta; the tier and EXIF raw. */
    internal suspend fun medium(tenant: TenantSession, item: MemoryMedia.Item, readers: MutableList<RangeReader>): Medium {
        val suite = tenant.account.suite
        fun reader(blob: MemoryMedia.Blob) = (MemoryMedia.reader(tenant, item, blob) ?: throw IllegalStateException("medium_unreadable")).also { readers += it }
        val original = reader(item.original)
        val blobs = arrayListOf(
            ApplicationBlobs.Outgoing(
                MemoryMedia.ORIGINAL, item.original.size, item.type ?: "application/octet-stream", item.name, item.modifiedAt, item.sha256,
            ) { from, length -> original.read(from, length) },
        )
        for ((role, blob) in listOfNotNull(item.thumbnail?.let { MemoryMedia.THUMBNAIL to it }, item.preview?.let { MemoryMedia.PREVIEW to it })) {
            val declared = blob.wrappedBlobMeta?.let { sealed ->
                MemoryMedia.openBlobKey(suite, item.scopeId, item.mediaId, item.key, blob, role)?.let { BlobMeta.open(suite, it, unb64(sealed)) }
            }
            val r = reader(blob)
            blobs += ApplicationBlobs.Outgoing(role, blob.size, declared?.type ?: "image/jpeg", declared?.name, declared?.modifiedAt, declared?.sha256) { from, length -> r.read(from, length) }
        }
        val tier = item.wrappedMeta?.let { wrapped ->
            val raw = Json.parseToJsonElement(
                suite.openField(Context("media-meta", "scopeId" to item.scopeId, "mediaId" to item.mediaId), item.key, unb64(wrapped)).decodeToString(),
            ) as JsonObject
            if (raw.containsKey("v")) raw else JsonObject(mapOf("v" to JsonPrimitive(1)) + raw)
        }
        // a copy that could not read the EXIF would drop the medium's camera data unseen: its read failing stops the copy
        return Medium(blobs, tier, MediaDetails.rawExif(tenant, item))
    }

    /** The banner's parts read by frame: the slot's file and, for a video, its poster; null where a key does not open. */
    private fun banner(tenant: TenantSession, node: OpenNode, read: Banner.Read): BannerWrite.Prepared? {
        val blobs = ApplicationBlobs(tenant)
        fun part(ref: Banner.Ref, role: String, type: String, sha256: String?): BannerWrite.Part.Upload? {
            val key = blobs.openKey(node.scopeId, node, ref.blobId, role, ref.wrappedBlobKey) ?: return null
            val source = blobs.FrameSource(node.scopeId, ref.blobId, ref.chunkCount, key)
            val reader = RangeReader(ref.size, ref.chunkCount) { first, until, sink -> source.read(first, until, sink) }
            return BannerWrite.Part.Upload(ref.size, type, sha256) { from, length -> reader.read(from, length) }
        }
        if (read.medium == Banner.Medium.IMAGE) {
            val main = part(read.blob, Banner.ROLE, "image/jpeg", null) ?: return null
            return BannerWrite.Prepared(Banner.Medium.IMAGE, main, null, null, read.sha256 ?: "")
        }
        val type = Banner.videoType(read.type)
        val main = part(read.blob, Banner.ROLE, type, read.sha256) ?: return null
        val poster = read.poster?.let { part(it, Banner.POSTER_ROLE, "image/jpeg", null) } ?: return null
        return BannerWrite.Prepared(Banner.Medium.VIDEO, main, poster, type, read.sha256 ?: "")
    }

    /** The server's target: core's create, the application blobs' upload and `completeMedia`, the cover's and the banner's writes. */
    private class Server(private val tenant: TenantSession, private val scopeId: String, private val parentId: String?) : Target {
        override suspend fun create(kind: String, name: String, meta: JsonObject?): Created {
            val input = me.raddatz.veyq.mobile.AppKinds.tokenInput(kind, name) ?: return Created.Failed
            val seal = meta?.let { m -> { key: ByteArray, nodeId: String -> NodeMetaWrite.seal(tenant.account.suite, scopeId, nodeId, key, m) } }
            val r = NodeCreate.create(tenant, scopeId, parentId, kind, name, input, seal)
            tenant.forgetTree()
            return when (r) {
                is NodeCreate.Result.Done -> Created.Made(r.created.nodeId, r.created.key, r.created.epoch)
                is NodeCreate.Result.Refused -> if (r.reason == NodeCreate.Reason.NAME_TAKEN) Created.Taken else Created.Failed
            }
        }

        /** The node this copy made, written through one writer: what a re-key moved, the next write starts at. */
        private var writer: Pair<String, MemberNodeWriter>? = null

        private fun writer(made: Created.Made): MemberNodeWriter = writer?.takeIf { it.first == made.nodeId }?.second
            ?: MemberNodeWriter.of(tenant, scopeId, made.nodeId, made.key, made.epoch).also { writer = made.nodeId to it }

        /** [attempt] under the made node's key now; throws with the code of a write that did not go through. */
        private suspend fun written(made: Created.Made, attempt: suspend (MemberNodeWriter.At) -> String?) {
            when (val r = writer(made).write(attempt)) {
                is NodeEpochWrite.Result.Answered -> r.value?.let { throw ApplicationBlobs.UploadRefused(it) }
                NodeEpochWrite.Result.Busy -> throw ApplicationBlobs.UploadRefused("node_rekey_pending")
                NodeEpochWrite.Result.Gone -> throw ApplicationBlobs.UploadRefused("node_unreadable")
                NodeEpochWrite.Result.StaleAgain -> throw ApplicationBlobs.UploadRefused("stale_epoch")
            }
        }

        override suspend fun medium(made: Created.Made, medium: Medium) = written(made) { at ->
            val suite = tenant.account.suite
            val mediaId = NodeCreate.uuid(tenant.account)
            try {
                val sent = ApplicationBlobs(tenant).reserveAndPumpAll(scopeId, mediaId, at.key, medium.blobs)
                MediaUpload.complete(
                    tenant, scopeId,
                    MediaRequestItem(
                        mediaId = mediaId, nodeId = made.nodeId, nodeEpoch = at.epoch, originalUploadId = sent[0].first,
                        derivatives = medium.blobs.drop(1).mapIndexed { i, b ->
                            MediaDerivativeUpload(if (b.role == MemoryMedia.THUMBNAIL) MediaDerivativeKind.THUMBNAIL else MediaDerivativeKind.PREVIEW, sent[i + 1].first)
                        },
                        wrappedMeta = medium.tier?.let { MediaMetadata.sealMeta(suite, at.key, scopeId, mediaId, it) },
                        wrappedExif = medium.exif?.let { MediaMetadata.sealExif(suite, at.key, scopeId, mediaId, it) },
                    ),
                    sent[0].second.blobId,
                )
                null
            } catch (e: ApplicationBlobs.UploadRefused) {
                e.code
            }
        }

        // the cover's and the banner's writes re-read the node themselves; an epoch refusal they end on pulls it forward here
        override suspend fun cover(made: Created.Made, jpeg: ByteArray, crop: NodeCover.Crop) = written(made) {
            when (NodeCover.set(tenant, scopeId, made.nodeId, jpeg, crop)) {
                NodeCover.Outcome.DONE -> null
                NodeCover.Outcome.BUSY -> "node_rekey_pending"
                NodeCover.Outcome.STALE -> "stale_epoch"
                else -> "cover_not_copied"
            }
        }

        override suspend fun banner(made: Created.Made, prepared: BannerWrite.Prepared, frame: Banner.Frame, focal: Banner.Focal) = written(made) {
            when (val out = BannerWrite.copy(tenant, scopeId, made.nodeId, prepared, frame, focal)) {
                BannerWrite.Outcome.Done -> null
                BannerWrite.Outcome.Busy -> "node_rekey_pending"
                is BannerWrite.Outcome.Refused -> out.code ?: "banner_not_copied"
                BannerWrite.Outcome.Stale -> "banner_not_copied"
            }
        }

        override suspend fun trash(made: Created.Made) {
            NodeTrash.delete(tenant, scopeId, made.nodeId)
            tenant.forgetTree()
        }
    }
}
