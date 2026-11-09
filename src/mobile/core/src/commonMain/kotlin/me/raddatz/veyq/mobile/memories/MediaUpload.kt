package me.raddatz.veyq.mobile.memories

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaBatchRequest
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeKind
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeUpload
import me.raddatz.veyq.mobile.api.memories.models.MediaRequestItem
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
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
 * platform derived them ([Derived]), the `THUMBNAIL` and the `PREVIEW` JPEG — each under its own `K_blob` wrapped bound
 * to the medium and its role (`blob-key` with `ownerId` the medium's id), reserved in one start request, sent frame by
 * frame direct to the store, and attached by one `media-batch` completion. At the node's epoch ([NodeEpochWrite]): a
 * re-keyed node is pulled forward and its key read again, and the whole medium is sealed again under it. The job is
 * tried twice, as the web's queue tries it ([ATTEMPTS]): every attempt mints its ids and keys anew; a quota or an
 * application switched off is never tried again.
 */
object MediaUpload {
    const val ATTEMPTS = 2
    const val SNIFF_BYTES = 1024

    /** What a platform hands over of a picked file: its bytes at any offset, its given type, name and modification time. */
    class Original(val source: ByteSource, val type: String?, val name: String?, val modifiedAt: Long?)

    /**
     * What the platform derived (the web's `mediaDeriver.ts`): the `PREVIEW` JPEG (long edge ≤ 2048, quality 0.85, upright)
     * and the `THUMBNAIL` JPEG made from it (≤ 512, 0.8) — a video's poster as both —, and the size as it is shown (a
     * video's only where its header names none); all null where the original does not decode here (it then goes alone).
     */
    class Derived(val preview: ByteArray?, val thumbnail: ByteArray?, val width: Int?, val height: Int?)

    enum class Outcome { DONE, NO_ROOM, SWITCHED_OFF, BUSY, FAILED }

    class Result(val outcome: Outcome, val mediaId: String? = null, val code: String? = null)

    /** Uploads [original] with [derived] into node [nodeId] of [scopeId]; [now] bounds a video's capture time. */
    @Throws(Exception::class)
    suspend fun upload(tenant: TenantSession, scopeId: String, nodeId: String, original: Original, derived: Derived, now: Long): Result {
        val head = original.source.read(0, minOf(original.source.size, SNIFF_BYTES.toLong()).toInt())
        val type = typeOf(original.type, original.name, head)
        val sha256 = sha256(original.source)
        val read = MediaMetadata.read(original.source, type, now)
        // a video's size is the one its header names (the web's probe), else the platform's; a photo's is its decode's
        val probed = if (type.startsWith("video/")) runCatching { VideoHeader.size(original.source) }.getOrNull()?.let { it.width to it.height } else null
        val size = probed ?: if (derived.width != null && derived.height != null) derived.width to derived.height else null
        val metaTier = MediaMetadata.metaTier(size?.first, size?.second, read, original.modifiedAt)
        var last = Result(Outcome.FAILED)
        repeat(ATTEMPTS) {
            last = once(tenant, scopeId, nodeId, original, type, sha256, derived, metaTier, read.exif)
            if (last.outcome != Outcome.FAILED) return last
        }
        return last
    }

    private suspend fun once(
        tenant: TenantSession, scopeId: String, nodeId: String, original: Original, type: String, sha256: String,
        derived: Derived, metaTier: JsonObject?, exif: JsonObject?,
    ): Result {
        val (_, scopeEpoch) = tenant.scopeKey(scopeId) ?: return Result(Outcome.FAILED, code = "scope_unreadable")
        var node: OpenNode = tenant.node(scopeId, nodeId) ?: return Result(Outcome.FAILED, code = "node_unreadable")
        val suite = tenant.account.suite
        var ended: Result? = null
        val result = NodeEpochWrite.run(
            pull = { NodeRekey.pull(tenant, scopeId, listOf(nodeId)) },
            belowFloor = { node.scopeEpoch < scopeEpoch },
            reread = {
                tenant.forgetTree()
                tenant.node(scopeId, nodeId)?.also { node = it } != null
            },
            attempt = {
                val n = node
                val code = try {
                    ended = attach(tenant, suite, scopeId, n, original, type, sha256, derived, metaTier, exif)
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
        derived: Derived, metaTier: JsonObject?, exif: JsonObject?,
    ): Result {
        val mediaId = NodeCreate.uuid(tenant.account)
        val wrappedMeta = metaTier?.let { MediaMetadata.sealMeta(suite, node.key, scopeId, mediaId, it) }
        val wrappedExif = exif?.let { MediaMetadata.sealExif(suite, node.key, scopeId, mediaId, it) }
        val blobs = ArrayList<ApplicationBlobs.Outgoing>()
        blobs += ApplicationBlobs.Outgoing(MemoryMedia.ORIGINAL, original.source.size, type, original.name, original.modifiedAt, sha256, original.source.read)
        val tiers = listOfNotNull(
            derived.thumbnail?.let { MemoryMedia.THUMBNAIL to it },
            derived.preview?.let { MemoryMedia.PREVIEW to it },
        ).takeIf { derived.thumbnail != null && derived.preview != null }.orEmpty()
        for ((role, bytes) in tiers) {
            blobs += ApplicationBlobs.Outgoing(role, bytes.size.toLong(), "image/jpeg", null, null, null) { from, length ->
                bytes.copyOfRange(from.toInt(), (from + length).toInt())
            }
        }
        val sent = ApplicationBlobs(tenant).reserveAndPumpAll(scopeId, mediaId, node.key, blobs)
        val item = MediaRequestItem(
            mediaId = mediaId, nodeId = node.id, nodeEpoch = node.epoch, originalUploadId = sent[0].first,
            derivatives = tiers.mapIndexed { i, (role, _) ->
                MediaDerivativeUpload(if (role == MemoryMedia.THUMBNAIL) MediaDerivativeKind.THUMBNAIL else MediaDerivativeKind.PREVIEW, sent[i + 1].first)
            },
            wrappedMeta = wrappedMeta, wrappedExif = wrappedExif,
        )
        val r = tenant.account.respond { MediaApi(tenant.base, tenant.account.http).completeMedia(scopeId, MediaBatchRequest(listOf(item))) }
        if (!r.success) throw ApplicationBlobs.UploadRefused(codeOf(runCatching { r.response.bodyAsText() }.getOrDefault("")) ?: "complete ${r.status}")
        val res = r.body().results.singleOrNull() ?: throw ApplicationBlobs.UploadRefused("complete_mismatch")
        res.error?.let { throw ApplicationBlobs.UploadRefused(it) }
        // the server echoes the ids it stored: anything else is an id substituted under the seals
        if (res.mediaId != mediaId || res.blobId != sent[0].second.blobId) throw ApplicationBlobs.UploadRefused("id_substituted")
        return Result(Outcome.DONE, mediaId)
    }

    /** The original's SHA-256 over its plaintext, read a frame at a time, base64url without padding (`digest.ts`). */
    private suspend fun sha256(source: ByteSource): String {
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
