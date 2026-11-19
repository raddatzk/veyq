package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaDerivativeKind
import me.raddatz.veyq.mobile.api.memories.models.MediaSummary
import me.raddatz.veyq.mobile.api.memories.models.MediaListPage
import me.raddatz.veyq.mobile.offline.KeptRead
import io.ktor.client.statement.readRawBytes
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
        /** A track's own name, as a member gave it (`trackName`, ADR 0135 decision 4: a music track's title before its file's). */
        val trackName: String? = null,
        /** A music track's facts, where the tier holds all three and they make a span (`AudioFacts.from`). */
        val facts: me.raddatz.veyq.mobile.memories.music.AudioFacts? = null,
        /** A GPX track's activity as sealed (`trackActivity`), known to this build or not (`TrackLabel.activityOf` reads it). */
        val trackActivity: String? = null,
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
        /** The revision its listed tier is at: what a write of it states (`updateMediaMeta`, ADR 0077). */
        val metaRevision: Long = 0,
        /** Its listed tier as listed (sealed): what a write re-opens raw (`TrackLabel`), never the parsed [meta]. */
        internal val wrappedMeta: String? = null,
        /**
         * The SHA-256 of its original's plaintext as its `blob-meta` carries it (base64url); null for a medium uploaded
         * before the digest existed. What an equal-file match compares (`StoredMatch`, ADR 0013 § 5) and what tells the
         * banner's picture (ADR 0144 decision 5) — sealed under the blob's own key, so only a holder of it reads it.
         */
        internal val sha256: String? = null,
        /** Its original's modified time as its sealed `blob-meta` names it (a download's payload time where no capture time is). */
        internal val modifiedAt: Long? = null,
        /** The uploader bucket it is in (a sub-bucket, or a materialized primary); null: its uploader's unbucketed media. */
        val bucketId: String? = null,
    ) {
        val isVideo: Boolean get() = type?.startsWith("video/") == true

        /** A music track: its original's sealed type is an audio one (ADR 0135 decision 2; `GalleryMedium.isAudio`). */
        val isAudio: Boolean get() = GalleryMedium.isAudio(type)

        /** A GPX track: its original's sealed type is GPX's, or its name ends in `.gpx` (ADR 0130; `GalleryMedium.isTrack`). */
        val isTrack: Boolean get() = GalleryMedium.isTrack(type, name)

        /** The lightbox shows a picture of it: not for a video without a poster, whose original is no picture. */
        val hasPicture: Boolean get() = !(isVideo && preview == null)
        /** The tier the grid draws: the thumbnail, else a small enough original, else none (the grid shows an icon). */
        val gridBlob: Blob? get() = thumbnail ?: original.takeIf { it.size <= GRID_ORIGINAL_MAX && !isVideo }
        /** The tier the lightbox shows: the preview, else the original. */
        val lightboxBlob: Blob get() = preview ?: original
    }

    /** A listing: its media, the revisions the live poll continues from, and — read from the offline store with the
     *  server unreachable — as of when ([offline]; null when it is the server's). */
    class Gallery(val items: List<Item>, val mediaRevision: Int, val deleteRevision: Int, val offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every live medium of [nodeId] of [scopeId], ordered as the grid shows them; null where the node is not opened here.
     * A kept read (ADR 0140 decision 5): the kept `mediaRevision` sent as `If-None-Match`, a newer listing replacing the
     * kept one — never merged, so a medium trashed on the server is gone from it —, the kept listing while the server is
     * unreachable.
     */
    @Throws(Exception::class)
    suspend fun list(tenant: TenantSession, scopeId: String, nodeId: String): Gallery? {
        val node = tenant.node(scopeId, nodeId) ?: return null
        val suite = tenant.account.suite
        val api = MediaApi(tenant.base, tenant.account.http)
        val read = try {
            tenant.account.kept(KeptRead.NODE_MEDIA, tenant.tenant.tenantId, scopeId, nodeId, conditional = true) { tag -> pages(tenant, api, scopeId, nodeId, tag) }
        } catch (e: me.raddatz.veyq.mobile.session.VeyqAccount.Refused) {
            throw IllegalStateException("media list ${e.status}")
        }
        val rows = ArrayList<MediaSummary>()
        var mediaRevision = 0
        var deleteRevision = 0
        var first = true
        var sealedDrop: String? = null
        val wrappedIdentities = HashMap<String, String>()
        for (raw in read.pages) {
            val page = me.raddatz.veyq.mobile.session.VeyqAccount.KEPT_JSON.decodeFromString(MediaListPage.serializer(), raw.decodeToString())
            rows += page.media
            // the live poll continues from the FIRST page's revisions (read before its rows: a change committed while the
            // pages came in moves the server past them, and the next poll reads it)
            if (first) {
                mediaRevision = page.mediaRevision
                deleteRevision = page.deleteRevision
                first = false
            }
            page.sealedDropPrivateKey?.let { sealedDrop = it }
            page.uploaderKeys.orEmpty().forEach { wrappedIdentities[it.identityId] = it.wrappedIdentityKey }
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
                row.ownerKey, row.blockedByOperator, key, row.metaRevision ?: 0, row.wrappedMeta, opened?.sha256, opened?.modifiedAt, row.bucketId,
            )
        }
        return Gallery(order(items), mediaRevision, deleteRevision, read.offline)
    }

    /** Every page of [nodeId]'s media as the server sent them, the first conditional on [tag] (the kept `mediaRevision`). */
    private suspend fun pages(tenant: TenantSession, api: MediaApi, scopeId: String, nodeId: String, tag: String?): me.raddatz.veyq.mobile.session.VeyqAccount.Fetched {
        val out = ArrayList<ByteArray>()
        var since: String? = null
        var revision: String? = null
        while (true) {
            val first = out.isEmpty()
            val r = tenant.account.respond { api.listNodeMedia(scopeId, nodeId, ifNoneMatch = if (first) tag else null, since = since) }
            if (first && r.status == 304) return me.raddatz.veyq.mobile.session.VeyqAccount.Fetched.NotModified
            if (!r.success) return me.raddatz.veyq.mobile.session.VeyqAccount.Fetched.Refused(r.status, tenant.account.errorCode(r))
            val raw = r.response.readRawBytes()
            out += raw
            val page = me.raddatz.veyq.mobile.session.VeyqAccount.KEPT_JSON.decodeFromString(MediaListPage.serializer(), raw.decodeToString())
            // the live poll and the store continue from the FIRST page's revision
            if (first) revision = page.mediaRevision.toString()
            if (!page.more) break
            val next = page.cursor ?: break
            if (next == since) break
            since = next
        }
        return me.raddatz.veyq.mobile.session.VeyqAccount.Fetched.Pages(out, revision)
    }

    /**
     * Whether the offline store keeps what is read of [blob] (ADR 0140 decision 8): a tile and a preview, and a video's,
     * a music track's (its frames as played, ADR 0142 decision 12) and a GPX track's original (its Map view lists it offline,
     * ADR 0143 D9) — never a photo's original, for which the preview stands in.
     */
    internal fun keeps(item: Item, blob: Blob): Boolean = roleOf(item, blob) != ORIGINAL || playsOriginal(item)

    /** Whether [item] is shown from its original: a video, a music track, a GPX track (ADR 0143 D9). */
    internal fun playsOriginal(item: Item): Boolean = item.isVideo || item.isAudio || item.isTrack

    /**
     * What a pin of Memory or chapter [node] keeps (ADR 0140 decisions 8 and 9): each medium's tiles and preview and a
     * video's, a music track's and a GPX track's original — what [keeps] keeps when it is played —, a medium the operator blocked none;
     * each blob with its key.
     */
    internal suspend fun pinned(tenant: TenantSession, node: me.raddatz.veyq.mobile.session.OpenNode): List<me.raddatz.veyq.mobile.offline.PinBlob> {
        val g = list(tenant, node.scopeId, node.id) ?: return emptyList()
        val suite = tenant.account.suite
        return g.items.filter { !it.blocked }.flatMap { item ->
            listOfNotNull(item.thumbnail, item.preview, item.original.takeIf { playsOriginal(item) }).filter { keeps(item, it) }.mapNotNull { b ->
                openBlobKey(suite, item.scopeId, item.mediaId, item.key, b, roleOf(item, b))?.let {
                    me.raddatz.veyq.mobile.offline.PinBlob(item.scopeId, b.blobId, b.chunkCount, b.byteSize, it)
                }
            }
        }
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
            str("trackName"), me.raddatz.veyq.mobile.memories.music.AudioFacts.from(o), str("trackActivity"),
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
        return ApplicationBlobs(tenant).frames(item.scopeId, blob.blobId, blob.chunkCount, key, keep = keeps(item, blob))
    }

    /**
     * [blob] read at any offset (a video played as it is read, never held whole): null where its key does not open or it is
     * blocked. One reader per playback; it keeps the blob's presigned reads and its last frames.
     */
    fun reader(tenant: TenantSession, item: Item, blob: Blob): RangeReader? {
        if (item.blocked) return null
        val key = openBlobKey(tenant.account.suite, item.scopeId, item.mediaId, item.key, blob, roleOf(item, blob)) ?: return null
        val source = ApplicationBlobs(tenant).FrameSource(item.scopeId, blob.blobId, blob.chunkCount, key, keeps(item, blob))
        return RangeReader(blob.size, blob.chunkCount) { first, until, sink -> source.read(first, until, sink) }
    }

    /** [blob]'s plaintext handed to [sink] a frame at a time (a download, a video), frames [first] to [until] (exclusive). */
    @Throws(Exception::class)
    suspend fun stream(tenant: TenantSession, item: Item, blob: Blob, first: Int = 0, until: Int = Int.MAX_VALUE, sink: suspend (ByteArray) -> Unit): Boolean {
        if (item.blocked) return false
        val suite = tenant.account.suite
        val key = openBlobKey(suite, item.scopeId, item.mediaId, item.key, blob, roleOf(item, blob)) ?: return false
        ApplicationBlobs(tenant).framesTo(item.scopeId, blob.blobId, blob.chunkCount, key, first, minOf(until, blob.chunkCount), keeps(item, blob), sink)
        return true
    }

    /**
     * Whether node [nodeId]'s media moved since the listing [since] (the web's live poll, `listNodeMedia` conditional on
     * its media revision: a `304` is no, nothing read); a throw where the read failed.
     */
    @Throws(Exception::class)
    suspend fun moved(tenant: TenantSession, scopeId: String, nodeId: String, since: Gallery): Boolean {
        val api = MediaApi(tenant.base, tenant.account.http)
        val r = tenant.account.respond {
            api.listNodeMedia(scopeId, nodeId, ifNoneMatch = since.mediaRevision.toString(), sinceDelete = since.deleteRevision, limit = 1)
        }
        if (r.status == 304) return false
        if (!r.success) throw IllegalStateException("media list ${r.status}")
        return true
    }

    /** Medium [mediaId] of [scopeId] to its node's trash (`deleteMedia`, idempotent). */
    suspend fun trash(tenant: TenantSession, scopeId: String, mediaId: String): GalleryModel.Removal = removal {
        tenant.account.respond { MediaApi(tenant.base, tenant.account.http).deleteMedia(scopeId, mediaId) }.status
    }

    /** Medium [mediaId] of [scopeId] back from the trash (`restoreMedia`). */
    suspend fun restore(tenant: TenantSession, scopeId: String, mediaId: String): GalleryModel.Removal = removal {
        tenant.account.respond { MediaApi(tenant.base, tenant.account.http).restoreMedia(scopeId, mediaId) }.status
    }

    /** What a trash's or a restore's answer [status] says (`mediaRefusal.ts`' reasons); a throw is a failure. */
    internal suspend fun removal(status: suspend () -> Int): GalleryModel.Removal = when (runCatching { status() }.getOrDefault(-1)) {
        in 200..299 -> GalleryModel.Removal.DONE
        404 -> GalleryModel.Removal.GONE
        409 -> GalleryModel.Removal.SCOPE_DELETING
        451 -> GalleryModel.Removal.BLOCKED
        else -> GalleryModel.Removal.FAILED
    }
}
