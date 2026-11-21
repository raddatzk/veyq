package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaBatchRequest
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeKind
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeUpload
import me.raddatz.veyq.mobile.api.memories.models.MediaRequestItem
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.export.Sha256
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.media.VideoHeader
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeEpochWrite
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A member's upload of one photo or video into a Memory or a chapter (the web's `media.ts` `addMedia`, `uploadMediaVia`):
 * on the device first, with no key — the original's SHA-256 read frame by frame, its metadata ([MediaMetadata]) —, then a
 * fresh medium id, its two tiers sealed under the node's key bound to it, and its blobs — the original and, where the
 * platform derived them ([Derivation.Derived]), the `THUMBNAIL` and the `PREVIEW` JPEG — each under its own `K_blob` wrapped bound
 * to the medium and its role (`blob-key` with `ownerId` the medium's id), reserved in one start request, sent frame by
 * frame direct to the store, and attached by one `media-batch` completion. At the node's epoch ([NodeEpochWrite]): a
 * re-keyed node is pulled forward and its key read again, and the whole medium is sealed again under it. An app runs it
 * as a [Job] of its upload queue ([UploadQueue], the web's `uploadQueue.ts`), which retries a failure once — every run
 * mints its ids and keys anew — and never a quota or an application switched off.
 */
object MediaUpload {
    const val SNIFF_BYTES = 1024

    /** The `PREVIEW` tier's long edge in pixels, at most (the web's `mediaDeriver.ts` `TIER_SPECS.PREVIEW`); never enlarged. */
    const val PREVIEW_EDGE = 2048
    /** The `PREVIEW` JPEG's quality, 0 to 1. */
    const val PREVIEW_QUALITY = 0.85
    /** The `THUMBNAIL` tier's long edge in pixels, at most (`TIER_SPECS.THUMBNAIL`), made from the preview. */
    const val THUMBNAIL_EDGE = 512
    /** The `THUMBNAIL` JPEG's quality, 0 to 1. */
    const val THUMBNAIL_QUALITY = 0.8
    /** Where a video's poster is taken at most (the web's `min(0.1, duration / 2)`), in milliseconds. */
    const val POSTER_AT_MS = 100L

    /** Where a video of [durationMs] has its poster taken: [POSTER_AT_MS], or half its length where it is shorter. */
    fun posterAtMs(durationMs: Long): Long = minOf(POSTER_AT_MS, maxOf(0L, durationMs) / 2)

    /** What a platform hands over of a picked file: its bytes at any offset, its given type, name and modification time. */
    class Original(val source: ByteSource, val type: String?, val name: String?, val modifiedAt: Long?)

    enum class Outcome { DONE, NO_ROOM, SWITCHED_OFF, BUSY, FAILED }

    class Result(val outcome: Outcome, val mediaId: String? = null, val code: String? = null)

    /** What the device works out before anything is sent: no key, no request (the job's on-device stage). */
    internal class Prepared(val type: String, val sha256: String, val derived: Derivation.Derived, val metaTier: JsonObject?, val exif: JsonObject?)

    /** The type of [original] and its bytes' head, then its SHA-256 and metadata, with the tiers from [derive]. */
    internal suspend fun prepare(original: Original, now: Long, derive: suspend (type: String) -> Derivation.Derived): Prepared {
        val head = original.source.read(0, minOf(original.source.size, SNIFF_BYTES.toLong()).toInt())
        val type = typeOf(original.type, original.name, head)
        val derived = derive(type)
        val sha256 = sha256(original.source)
        val read = MediaMetadata.read(original.source, type, now)
        // a video's size is the one its header names (the web's probe), else the platform's; a photo's is its decode's
        val probed = if (type.startsWith("video/")) runCatching { VideoHeader.size(original.source) }.getOrNull()?.let { it.width to it.height } else null
        val size = probed ?: if (derived.width != null && derived.height != null) derived.width to derived.height else null
        val metaTier = MediaMetadata.metaTier(size?.first, size?.second, read, original.modifiedAt)
        return Prepared(type, sha256, derived, metaTier, read.exif)
    }

    /**
     * Uploads [original] with [derived] into node [nodeId] of [scopeId], once; [now] bounds a video's capture time,
     * [progress] is told the fraction sent.
     */
    @Throws(Exception::class)
    suspend fun upload(
        tenant: TenantSession, scopeId: String, nodeId: String, original: Original, derived: Derivation.Derived, now: Long,
        progress: (Double) -> Unit = {},
    ): Result = once(tenant, scopeId, nodeId, original, prepare(original, now) { derived }, progress)

    /**
     * One picked [source] added to Memory or chapter [nodeId] of [scopeId] as a job of the app's upload queue: its tiers
     * ([deriver]), digest and metadata are its on-device stage, prepared ahead while the job before it is on the wire and
     * kept for a retry; [mediaId] is the medium it added. A quota fails it with [UploadQueue.QUOTA_EXCEEDED], Memories
     * switched off with [UploadQueue.APPLICATION_OFF]; [now] is the clock a video's capture time is bounded by.
     */
    class Job(
        private val tenant: TenantSession, val scopeId: String, val nodeId: String, private val source: UploadSource,
        private val deriver: Derivation.Deriver, private val now: () -> Long,
    ) : UploadQueue.Job {
        override val name get() = source.name
        override val sizeBytes get() = source.size
        override val prepares get() = true

        /** The file it uploads (where the app reads it). */
        val picked: UploadSource get() = source

        /** The medium this job added, once it is done. */
        var mediaId: String? = null
            private set

        private val lock = Mutex()
        private var prepared: Prepared? = null

        private val original get() = Original(source.bytes(), source.type, source.name, source.modifiedAt)

        override suspend fun prepare() {
            ready()
        }

        private suspend fun ready(): Prepared = lock.withLock {
            prepared ?: try {
                prepare(original, now()) { type -> suspendCancellableCoroutine { c -> deriver.derive(source, type) { c.resume(it) { _, _, _ -> } } } }
            } finally {
                source.close()
            }.also { prepared = it }
        }

        override suspend fun run(progress: (Double) -> Unit) {
            val p = ready()
            val r = try {
                once(tenant, scopeId, nodeId, original, p, progress)
            } finally {
                source.close()
            }
            when (r.outcome) {
                Outcome.DONE -> {
                    mediaId = r.mediaId
                    prepared = null // what it prepared is sent: its tiers need not stay in memory
                }
                Outcome.NO_ROOM -> throw UploadQueue.Failed(UploadQueue.QUOTA_EXCEEDED)
                Outcome.SWITCHED_OFF -> throw UploadQueue.Failed(UploadQueue.APPLICATION_OFF)
                Outcome.BUSY -> throw UploadQueue.Failed("node_rekey_pending")
                Outcome.FAILED -> throw UploadQueue.Failed(r.code ?: "failed")
            }
        }
    }

    /**
     * A medium queued offline (ADR 0153 decision 1): its id minted then, its original and tiers sealed then under their own
     * `K_blob`s — sent as they are, only the wraps under the node's key made by the send ([once]).
     */
    internal class SealedMedium(
        val mediaId: String, val original: ApplicationBlobs.Sealed, val thumbnail: ApplicationBlobs.Sealed?, val preview: ApplicationBlobs.Sealed?,
    )

    internal suspend fun once(
        tenant: TenantSession, scopeId: String, nodeId: String, original: Original, prepared: Prepared, progress: (Double) -> Unit,
    ): Result = once(tenant, scopeId, nodeId, original, prepared, null, progress)

    /** [once] of a medium [sealed] ahead (queued offline): its frames sent as they are, only the wraps made now. */
    internal suspend fun once(
        tenant: TenantSession, scopeId: String, nodeId: String, original: Original, prepared: Prepared, sealed: SealedMedium?, progress: (Double) -> Unit,
    ): Result {
        val type = prepared.type
        val sha256 = prepared.sha256
        val derived = prepared.derived
        val metaTier = prepared.metaTier
        val exif = prepared.exif
        val (_, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Result(Outcome.FAILED, code = "scope_unreadable")
        var node: OpenNode = tenant.node(scopeId, nodeId) ?: return Result(Outcome.FAILED, code = "node_unreadable")
        val suite = tenant.account.suite
        var ended: Result? = null
        val result = NodeEpochWrite.run(
            pull = { NodeRekey.pull(tenant, scopeId, listOf(nodeId)) },
            // the epoch held NOW: the node's read may have re-opened a scope another device rotated (inbox #1021)
            belowFloor = { node.scopeEpoch < (tenant.scopeEpoch(scopeId) ?: scopeEpoch) },
            reread = {
                tenant.forgetTree()
                tenant.node(scopeId, nodeId)?.also { node = it } != null
            },
            attempt = {
                val n = node
                val code = try {
                    ended = attach(tenant, suite, scopeId, n, original, type, sha256, derived, metaTier, exif, progress, sealed)
                    null
                } catch (e: ApplicationBlobs.UploadRefused) {
                    when (e.code) {
                        "quota_exceeded" -> ended = Result(Outcome.NO_ROOM, code = e.code)
                        "capability_disabled" -> ended = Result(Outcome.SWITCHED_OFF, code = e.code)
                    }
                    e.code
                }
                val answer = when (code) {
                    null -> NodeEpochWrite.Answer.DONE
                    "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
                    "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
                    else -> NodeEpochWrite.Answer.OTHER
                }
                answer to code
            },
        )
        return when (result) {
            is NodeEpochWrite.Result.Answered -> ended ?: Result(Outcome.FAILED, code = result.value)
            NodeEpochWrite.Result.Busy -> Result(Outcome.BUSY, code = "node_rekey_pending")
            else -> Result(Outcome.FAILED, code = "stale_epoch")
        }
    }

    /** One medium sealed under [node]'s key, sent and completed; throws [ApplicationBlobs.UploadRefused] with a refusal's code. */
    private suspend fun attach(
        tenant: TenantSession, suite: V1, scopeId: String, node: OpenNode, original: Original, type: String, sha256: String,
        derived: Derivation.Derived, metaTier: JsonObject?, exif: JsonObject?, progress: (Double) -> Unit, sealed: SealedMedium? = null,
    ): Result {
        val mediaId = sealed?.mediaId ?: NodeCreate.uuid(tenant.account)
        val wrappedMeta = metaTier?.let { MediaMetadata.sealMeta(suite, node.key, scopeId, mediaId, it) }
        val wrappedExif = exif?.let { MediaMetadata.sealExif(suite, node.key, scopeId, mediaId, it) }
        // A1 of ADR 0144 decision 5: the node's video banner is this very file — the original is a new row on its object
        val shared = sharedOriginal(suite, scopeId, node, mediaId, NodeCreate.uuid(tenant.account), original, type, sha256)
        val blobs = ArrayList<ApplicationBlobs.Outgoing>()
        if (shared == null) blobs += if (sealed != null) ApplicationBlobs.Outgoing(MemoryMedia.ORIGINAL, sealed.original.size, type, original.name, original.modifiedAt, sha256, sealed.original)
        else ApplicationBlobs.Outgoing(MemoryMedia.ORIGINAL, original.source.size, type, original.name, original.modifiedAt, sha256, original.source.read)
        val tiers: List<Pair<String, ApplicationBlobs.Outgoing>> = if (sealed != null) {
            listOfNotNull(
                sealed.thumbnail?.let { MemoryMedia.THUMBNAIL to ApplicationBlobs.Outgoing(MemoryMedia.THUMBNAIL, it.size, "image/jpeg", null, null, null, it) },
                sealed.preview?.let { MemoryMedia.PREVIEW to ApplicationBlobs.Outgoing(MemoryMedia.PREVIEW, it.size, "image/jpeg", null, null, null, it) },
            ).takeIf { it.size == 2 }.orEmpty()
        } else {
            listOfNotNull(
                derived.thumbnail?.let { MemoryMedia.THUMBNAIL to it },
                derived.preview?.let { MemoryMedia.PREVIEW to it },
            ).takeIf { derived.thumbnail != null && derived.preview != null }.orEmpty().map { (role, bytes) ->
                role to ApplicationBlobs.Outgoing(role, bytes.size.toLong(), "image/jpeg", null, null, null, { from, length ->
                    bytes.copyOfRange(from.toInt(), (from + length).toInt())
                })
            }
        }
        blobs += tiers.map { it.second }
        val total = blobs.sumOf { it.size }.coerceAtLeast(1).toDouble()
        progress(0.0)
        val sent = if (blobs.isEmpty()) emptyList() else ApplicationBlobs(tenant).reserveAndPumpAll(scopeId, mediaId, node.key, blobs) { progress(it / total) }
        val first = if (shared == null) 1 else 0 // where the tiers' uploads start
        val item = MediaRequestItem(
            mediaId = mediaId, nodeId = node.id, nodeEpoch = node.epoch, originalUploadId = if (shared == null) sent[0].first else null,
            originalShared = shared?.request?.let { me.raddatz.veyq.mobile.api.memories.models.SharedBlob(it.sourceBlobId, it.blobId, it.wrappedBlobKey, it.wrappedBlobMeta) },
            derivatives = tiers.mapIndexed { i, (role, _) ->
                MediaDerivativeUpload(if (role == MemoryMedia.THUMBNAIL) MediaDerivativeKind.THUMBNAIL else MediaDerivativeKind.PREVIEW, sent[i + first].first)
            },
            wrappedMeta = wrappedMeta, wrappedExif = wrappedExif,
        )
        complete(tenant, scopeId, item, shared?.blobId ?: sent[0].second.blobId)
        return Result(Outcome.DONE, mediaId)
    }

    /**
     * One medium attached by its `media-batch` completion ([item], its original [blobId]); throws [ApplicationBlobs.UploadRefused]
     * with a refusal's code, and where the server echoes other ids than it was sent (an id substituted under the seals).
     * An upload's and a copy's (`MemoryCopy`).
     */
    internal suspend fun complete(tenant: TenantSession, scopeId: String, item: MediaRequestItem, blobId: String) {
        val r = tenant.account.respond { MediaApi(tenant.base, tenant.account.http).completeMedia(scopeId, MediaBatchRequest(listOf(item))) }
        if (!r.success) throw ApplicationBlobs.UploadRefused(codeOf(runCatching { r.response.bodyAsText() }.getOrDefault("")) ?: "complete ${r.status}")
        val res = r.body().results.singleOrNull() ?: throw ApplicationBlobs.UploadRefused("complete_mismatch")
        res.error?.let { throw ApplicationBlobs.UploadRefused(it) }
        if (res.mediaId != item.mediaId || res.blobId != blobId) throw ApplicationBlobs.UploadRefused("id_substituted")
    }

    /**
     * A link guest's upload of one medium (ADR 0150 decision 5; the web's `addSharePhoto` over `shareUploadTransport`): the
     * same medium as a member's — its tiers sealed under [sealKey] (a READ_WRITE guest's node key from the link's chain, a
     * WRITE_ONLY uploader's own `K_up`) bound to a fresh medium id, its blobs reserved through [reserve] (the share's own
     * upload start) and attached by [complete] (`completeShareMedia`). Never a shared row (ADR 0013 § 5: a guest's upload
     * matches no banner). Throws [ApplicationBlobs.UploadRefused] with a refusal's code.
     */
    internal suspend fun attachAsGuest(
        suite: V1, mediaId: String, scopeId: String, sealKey: ByteArray, original: Original, prepared: Prepared,
        reserve: suspend (mediaId: String, blobs: List<ApplicationBlobs.Outgoing>, sent: (Long) -> Unit) -> List<Pair<String, ApplicationBlobs.Plan>>,
        complete: suspend (item: me.raddatz.veyq.mobile.api.memories.models.ShareMediaRequestItem) -> Unit,
        progress: (Double) -> Unit,
    ): Result {
        val derived = prepared.derived
        val wrappedMeta = prepared.metaTier?.let { MediaMetadata.sealMeta(suite, sealKey, scopeId, mediaId, it) }
        val wrappedExif = prepared.exif?.let { MediaMetadata.sealExif(suite, sealKey, scopeId, mediaId, it) }
        val blobs = ArrayList<ApplicationBlobs.Outgoing>()
        blobs += ApplicationBlobs.Outgoing(MemoryMedia.ORIGINAL, original.source.size, prepared.type, original.name, original.modifiedAt, prepared.sha256, original.source.read)
        val tiers = listOfNotNull(
            derived.thumbnail?.let { MemoryMedia.THUMBNAIL to it },
            derived.preview?.let { MemoryMedia.PREVIEW to it },
        ).takeIf { derived.thumbnail != null && derived.preview != null }.orEmpty()
        for ((role, bytes) in tiers) {
            blobs += ApplicationBlobs.Outgoing(role, bytes.size.toLong(), "image/jpeg", null, null, null) { from, length ->
                bytes.copyOfRange(from.toInt(), (from + length).toInt())
            }
        }
        val total = blobs.sumOf { it.size }.coerceAtLeast(1).toDouble()
        progress(0.0)
        val sent = reserve(mediaId, blobs) { progress(it / total) }
        complete(
            me.raddatz.veyq.mobile.api.memories.models.ShareMediaRequestItem(
                mediaId = mediaId, originalUploadId = sent[0].first,
                derivatives = tiers.mapIndexed { i, (role, _) ->
                    me.raddatz.veyq.mobile.api.memories.models.ShareMediaDerivativeUpload(
                        if (role == MemoryMedia.THUMBNAIL) me.raddatz.veyq.mobile.api.memories.models.ShareMediaDerivativeUpload.Kind.THUMBNAIL
                        else me.raddatz.veyq.mobile.api.memories.models.ShareMediaDerivativeUpload.Kind.PREVIEW,
                        sent[i + 1].first,
                    )
                },
                wrappedMeta = wrappedMeta, wrappedExif = wrappedExif,
            ),
        )
        return Result(Outcome.DONE, mediaId)
    }

    /**
     * A link guest's medium as a job of the guest view's upload queue: [MediaUpload.Job]'s on-device stage, then [send]
     * (the guest door's [attachAsGuest]). A quota fails it with [UploadQueue.QUOTA_EXCEEDED].
     */
    class GuestJob internal constructor(
        private val source: UploadSource, private val deriver: Derivation.Deriver, private val now: () -> Long,
        private val send: suspend (original: Original, prepared: Prepared, progress: (Double) -> Unit) -> Result,
    ) : UploadQueue.Job {
        override val name get() = source.name
        override val sizeBytes get() = source.size
        override val prepares get() = true
        val picked: UploadSource get() = source
        var mediaId: String? = null
            private set
        private val lock = Mutex()
        private var prepared: Prepared? = null
        private val original get() = Original(source.bytes(), source.type, source.name, source.modifiedAt)

        override suspend fun prepare() {
            ready()
        }

        private suspend fun ready(): Prepared = lock.withLock {
            prepared ?: try {
                prepare(original, now()) { type -> suspendCancellableCoroutine { c -> deriver.derive(source, type) { c.resume(it) { _, _, _ -> } } } }
            } finally {
                source.close()
            }.also { prepared = it }
        }

        override suspend fun run(progress: (Double) -> Unit) {
            val p = ready()
            val r = try {
                send(original, p, progress)
            } catch (e: ApplicationBlobs.UploadRefused) {
                when (e.code) {
                    "quota_exceeded" -> Result(Outcome.NO_ROOM, code = e.code)
                    "capability_disabled" -> Result(Outcome.SWITCHED_OFF, code = e.code)
                    else -> Result(Outcome.FAILED, code = e.code)
                }
            } finally {
                source.close()
            }
            when (r.outcome) {
                Outcome.DONE -> {
                    mediaId = r.mediaId
                    prepared = null
                }
                Outcome.NO_ROOM -> throw UploadQueue.Failed(UploadQueue.QUOTA_EXCEEDED)
                Outcome.SWITCHED_OFF -> throw UploadQueue.Failed(UploadQueue.APPLICATION_OFF)
                Outcome.BUSY -> throw UploadQueue.Failed("node_rekey_pending")
                Outcome.FAILED -> throw UploadQueue.Failed(r.code ?: "failed")
            }
        }
    }

    /**
     * Where [node]'s video banner is [original]'s very file — its sealed `sha256` and its size (`StoredMatch.bannerOriginal`,
     * ADR 0144 decision 5's A1) — the new medium [mediaId]'s original as a new row [blobId] on the banner's stored object,
     * its `blob-meta` what an upload of the file would seal; null otherwise (the original is uploaded). A member's upload
     * only: a guest never reaches here (`MediaUpload` is the member's door).
     */
    internal fun sharedOriginal(
        suite: V1, scopeId: String, node: OpenNode, mediaId: String, blobId: String, original: Original, type: String, sha256: String,
    ): me.raddatz.veyq.mobile.blob.SharedBlob.Shared? {
        val meta = runCatching { me.raddatz.veyq.mobile.session.NodeMetaWrite.openForWrite(suite, scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull()
        val stored = me.raddatz.veyq.mobile.memories.banner.StoredMatch.bannerOriginal(
            meta, node.id, node.key, sha256, me.raddatz.veyq.mobile.blob.SharedBlob.sealedByteSize(original.source.size),
        ) ?: return null
        return runCatching {
            me.raddatz.veyq.mobile.blob.SharedBlob.share(
                suite, scopeId, stored, node.key, mediaId, MemoryMedia.ORIGINAL, blobId,
                me.raddatz.veyq.mobile.blob.SharedBlob.Meta(type, original.name, original.modifiedAt, sha256),
            )
        }.getOrNull()
    }

    /** The original's SHA-256 over its plaintext, read a frame at a time, base64url without padding (`digest.ts`). */
    internal suspend fun sha256(source: ByteSource): String {
        val h = Sha256()
        var at = 0L
        while (at < source.size) {
            val chunk = source.read(at, minOf(source.size - at, V1.FRAME_PLAINTEXT.toLong()).toInt())
            if (chunk.isEmpty()) throw ApplicationBlobs.UploadRefused("source_changed")
            h.update(chunk)
            at += chunk.size
        }
        return b64url(h.digest())
    }

    private fun codeOf(body: String): String? =
        runCatching { ((Json.parseToJsonElement(body) as JsonObject)["code"] as? JsonPrimitive)?.content }.getOrNull()

    /**
     * The type an original is sealed and derived under (`resolveMediaType`, `inferMediaType`): its given one, else the one
     * its name's extension names, else sniffed from its first bytes ([sniff]), else `application/octet-stream`.
     */
    fun typeOf(given: String?, name: String?, head: ByteArray): String {
        given?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN }?.let { return it }
        name?.substringAfterLast('.', "")?.lowercase()?.let { EXTENSIONS[it] }?.let { return it }
        return sniff(head) ?: UNKNOWN
    }

    /** Whether a picked file is a medium a Memory takes: an image or a video by its type, else by its extension. */
    fun isMedia(type: String?, name: String?): Boolean =
        type?.startsWith("image/") == true || type?.startsWith("video/") == true || name?.substringAfterLast('.', "")?.lowercase() in EXTENSIONS

    /** Whether a picked file claims to be a video: by its type, else by its extension (a placeholder's glyph, nothing more). */
    fun claimsVideo(type: String?, name: String?): Boolean =
        type?.startsWith("video/") ?: (EXTENSIONS[name?.substringAfterLast('.', "")?.lowercase()]?.startsWith("video/") == true)

    private const val UNKNOWN = "application/octet-stream"

    private val EXTENSIONS = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "avif" to "image/avif", "heic" to "image/heic", "heif" to "image/heif", "tif" to "image/tiff", "tiff" to "image/tiff",
        "bmp" to "image/bmp", "mov" to "video/quicktime", "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mkv" to "video/x-matroska",
    )

    /** A media type from an original's leading bytes (`download.ts` `sniffMediaType`), or null. */
    fun sniff(b: ByteArray): String? {
        fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun ascii(at: Int, s: String) = at + s.length <= b.size && s.indices.all { u(at + it) == s[it].code }
        return when {
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "image/jpeg"
            u(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "%PDF") -> "application/pdf"
            b.size >= 12 && ascii(4, "ftyp") -> when (val brand = b.decodeToString(8, 12)) {
                "heic", "heif", "mif1", "msf1" -> "image/heic"
                "qt  " -> "video/quicktime"
                else -> if (brand == "avif") "image/avif" else "video/mp4"
            }
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 ->
                if (b.decodeToString(0, minOf(b.size, 64)).contains("matroska")) "video/x-matroska" else "video/webm"
            else -> null
        }
    }
}
