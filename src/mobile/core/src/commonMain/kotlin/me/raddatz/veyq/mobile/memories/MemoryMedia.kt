package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeKind
import me.raddatz.veyq.mobile.api.memories.models.MediaSummary
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Memory's or a chapter's gallery, read (the web's `features/memories/media.ts` listing, `mediaOpen.ts`, `mediaKey.ts`):
 * every page of the node's media, each row opened under the key its origin fixes ([mediaKey]: the node's `K_node`, or —
 * for a row a `WRITE_ONLY` guest added — the guest's `K_up`, opened through the node's drop key), its listed tier
 * (`media-meta`: geometry, capture time, GPS, place) and its original's sealed meta (type, name), ordered as the web's grid
 * orders it: by the effective capture time (a correction over the derived one), newest first, a row without one last.
 * A medium's bytes are its tiers' (`ORIGINAL`, `THUMBNAIL`, `PREVIEW`), each a blob of core's blob service whose `K_blob`
 * is wrapped under that key bound to the medium (`blob-key` with `ownerId` = the medium's id, ADR 0113).
 */
object MemoryMedia {
    const val ORIGINAL = "ORIGINAL"
    const val THUMBNAIL = "THUMBNAIL"
    const val PREVIEW = "PREVIEW"

    /** An original without a smaller tier is shown in the grid only up to this size (larger: its icon). */
    const val GRID_ORIGINAL_MAX = 8L * 1024 * 1024

    /** One stored blob of a medium. */
    class Blob(val blobId: String, val chunkCount: Int, val byteSize: Long, internal val wrappedBlobKey: String, internal val wrappedBlobMeta: String?) {
        /** The plaintext's size: the stored bytes less a frame's overhead each. */
        val size: Long get() = maxOf(0L, byteSize - V1.FRAME_OVERHEAD.toLong() * chunkCount)
    }

    /** A medium's listed tier, projected for display (the web's `normalizeMediaMeta`): what does not parse is absent. */
    class Meta(
        val width: Int?, val height: Int?, val capturedAt: Long?, val capturedAtOverride: Long?, val capturedAtSource: String?,
        val gpsLat: Double?, val gpsLng: Double?, val placeName: String?, val placeLevel: String?,
    ) {
        /** The user's correction if set, else the derived capture time (`effectiveCapturedAt`). */
        val effectiveCapturedAt: Long? get() = capturedAtOverride ?: capturedAt
    }

    class Item(
        val scopeId: String,
        val nodeId: String,
        val mediaId: String,
        val original: Blob,
        val thumbnail: Blob?,
        val preview: Blob?,
        val meta: Meta?,
        /** The original's sealed type and name (claims, the bytes decide what is shown). */
        val type: String?,
        val name: String?,
        /** Who added it: `user:<id>` a member, `share:<identityId>` a link's guest. */
        val ownerKey: String?,
        /** The operator blocked it: no bytes are served (`451`). */
        val blocked: Boolean,
        internal val key: ByteArray,
    ) {
        val isVideo: Boolean get() = type?.startsWith("video/") == true
        /** The tier the grid draws: the thumbnail, else a small enough original, else none (the grid shows an icon). */
        val gridBlob: Blob? get() = thumbnail ?: original.takeIf { it.size <= GRID_ORIGINAL_MAX && !isVideo }
        /** The tier the lightbox shows: the preview, else the original. */
        val lightboxBlob: Blob get() = preview ?: original
    }

    class Gallery(val items: List<Item>, val mediaRevision: Int, val deleteRevision: Int)

    private val json = Json { ignoreUnknownKeys = true }

    /** Every live medium of [nodeId] of [scopeId], ordered as the grid shows them; null where the node is not opened here. */
    @Throws(Exception::class)
    suspend fun list(tenant: TenantSession, scopeId: String, nodeId: String): Gallery? {
        val node = tenant.node(scopeId, nodeId) ?: return null
        val suite = tenant.account.suite
        val api = MediaApi(tenant.base, tenant.account.http)
        val rows = ArrayList<MediaSummary>()
        var since: String? = null
        var mediaRevision = 0
        var deleteRevision = 0
        var sealedDrop: String? = null
        val wrappedIdentities = HashMap<String, String>()
        while (true) {
            val page = tenant.account.call { api.listNodeMedia(scopeId, nodeId, since = since) }
            rows += page.media
            mediaRevision = page.mediaRevision
            deleteRevision = page.deleteRevision
            page.sealedDropPrivateKey?.let { sealedDrop = it }
            page.uploaderKeys.orEmpty().forEach { wrappedIdentities[it.identityId] = it.wrappedIdentityKey }
            if (!page.more) break
            val next = page.cursor ?: break
            if (next == since) break
            since = next
        }
        // K_up per guest identity, opened once: an X-Wing decapsulation each
        val upKeys = HashMap<String, ByteArray?>()
        fun keyOf(row: MediaSummary): ByteArray? {
            val owner = row.ownerKey
            if (owner == null || !owner.startsWith("share:") || sealedDrop == null) return node.key
            val identity = owner.removePrefix("share:")
            val wrapped = wrappedIdentities[identity] ?: return node.key
            return upKeys.getOrPut(identity) {
                runCatching {
                    val seed = suite.deserializeDropKey(suite.open(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to nodeId), node.key, unb64(sealedDrop!!)))
                    suite.openAs(Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to nodeId, "identityId" to identity), suite.xwingKey(seed), unb64(wrapped))
                }.getOrNull()
            }
        }
        val items = rows.mapNotNull { row ->
            val key = keyOf(row) ?: return@mapNotNull null
            val original = Blob(row.blobId, row.chunkCount, row.byteSize, row.wrappedBlobKey, row.wrappedBlobMeta)
            fun tier(kind: MediaDerivativeKind) = row.derivatives.orEmpty().firstOrNull { it.kind == kind }?.let {
                Blob(it.blobId, it.chunkCount, it.byteSize, it.wrappedBlobKey, it.wrappedBlobMeta)
            }
            val opened = original.wrappedBlobMeta?.let { sealed ->
                openBlobKey(suite, scopeId, row.mediaId, key, original, ORIGINAL)?.let { BlobMeta.open(suite, it, unb64(sealed)) }
            }
            Item(
                scopeId, nodeId, row.mediaId, original, tier(MediaDerivativeKind.THUMBNAIL), tier(MediaDerivativeKind.PREVIEW),
                openMeta(suite, scopeId, row.mediaId, key, row.wrappedMeta), opened?.type?.takeIf { it.isNotBlank() }, opened?.name,
                row.ownerKey, row.blockedByOperator, key,
            )
        }
        return Gallery(order(items), mediaRevision, deleteRevision)
    }

    /** The grid's order (`useMediaGalleryLive`'s date sort, newest first): a row without a capture time last, ties by id. */
    fun order(items: List<Item>): List<Item> = items.sortedWith(
        compareBy<Item> { it.meta?.effectiveCapturedAt == null }
            .thenByDescending { it.meta?.effectiveCapturedAt ?: 0L }
            .thenBy { it.mediaId },
    )

    /** A medium's listed tier under [key] (`openMediaMeta`), or null where none was sealed or it does not open or parse. */
    internal fun openMeta(suite: V1, scopeId: String, mediaId: String, key: ByteArray, wrapped: String?): Meta? {
        if (wrapped == null) return null
        return runCatching {
            val text = suite.openField(Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId), key, unb64(wrapped)).decodeToString()
            metaOf(json.parseToJsonElement(text) as JsonObject)
        }.getOrNull()
    }

    /** The display projection of a raw listed tier (`normalizeMediaMeta`): numbers finite, geometry positive, strings trimmed. */
    internal fun metaOf(o: JsonObject): Meta {
        fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val w = num("w")
        val h = num("h")
        val geo = w != null && h != null && w > 0 && h > 0
        val source = str("capturedAtSource")?.takeIf { it in setOf("exif", "container", "file") }
        return Meta(
            if (geo) w!!.toInt() else null, if (geo) h!!.toInt() else null,
            num("capturedAt")?.toLong(), num("capturedAtOverride")?.toLong(), source,
            num("gpsLat"), num("gpsLng"), str("placeName"), str("placeLevel"),
        )
    }

    /** The key of [blob] of medium [mediaId] in [role] under [key], or null when its wrap does not open there. */
    internal fun openBlobKey(suite: V1, scopeId: String, mediaId: String, key: ByteArray, blob: Blob, role: String): ByteArray? = runCatching {
        suite.open(Context("blob-key", "scopeId" to scopeId, "ownerId" to mediaId, "blobId" to blob.blobId, "role" to role), key, unb64(blob.wrappedBlobKey))
    }.getOrNull()

    /** The role a tier of [item] is stored in. */
    private fun roleOf(item: Item, blob: Blob): String = when (blob) {
        item.thumbnail -> THUMBNAIL
        item.preview -> PREVIEW
        else -> ORIGINAL
    }

    /** [blob]'s plaintext, whole (a grid tile, a preview); null where its key does not open or it is blocked. */
    @Throws(Exception::class)
    suspend fun read(tenant: TenantSession, item: Item, blob: Blob): ByteArray? {
        if (item.blocked) return null
        val suite = tenant.account.suite
        val key = openBlobKey(suite, item.scopeId, item.mediaId, item.key, blob, roleOf(item, blob)) ?: return null
        return ApplicationBlobs(tenant).frames(item.scopeId, blob.blobId, blob.chunkCount, key)
    }

    /**
     * [blob] read at any offset (a video played as it is read, never held whole): null where its key does not open or it is
     * blocked. One reader per playback; it keeps the blob's presigned reads and its last frames.
     */
    fun reader(tenant: TenantSession, item: Item, blob: Blob): RangeReader? {
        if (item.blocked) return null
        val key = openBlobKey(tenant.account.suite, item.scopeId, item.mediaId, item.key, blob, roleOf(item, blob)) ?: return null
        val source = ApplicationBlobs(tenant).FrameSource(item.scopeId, blob.blobId, blob.chunkCount, key)
        return RangeReader(blob.size, blob.chunkCount) { first, until, sink -> source.read(first, until, sink) }
    }

    /** [blob]'s plaintext handed to [sink] a frame at a time (a download, a video), frames [first] to [until] (exclusive). */
    @Throws(Exception::class)
    suspend fun stream(tenant: TenantSession, item: Item, blob: Blob, first: Int = 0, until: Int = Int.MAX_VALUE, sink: suspend (ByteArray) -> Unit): Boolean {
        if (item.blocked) return false
        val suite = tenant.account.suite
        val key = openBlobKey(suite, item.scopeId, item.mediaId, item.key, blob, roleOf(item, blob)) ?: return false
        ApplicationBlobs(tenant).framesTo(item.scopeId, blob.blobId, blob.chunkCount, key, first, minOf(until, blob.chunkCount), sink)
        return true
    }
}
