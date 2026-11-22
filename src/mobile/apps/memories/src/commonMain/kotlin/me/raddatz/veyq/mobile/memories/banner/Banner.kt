package me.raddatz.veyq.mobile.memories.banner

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession
import kotlin.math.roundToLong

/**
 * A Memory's BANNER as the web models it (uc.memory.banner, ADR 0102, ADR 0144 decision 4) — a port of `bannerModel.ts`:
 * one image or one video above the Memory's header, independent of its cover. Its bytes are the node's own attachments —
 * the `BANNER` slot (the image, or the video) and, for a video, the `BANNER_POSTER` slot (its first frame) — and the
 * `banner` field of the node's sealed meta (`node-meta`, bound to scope and node, ADR 0072) names them:
 *
 *     banner = { medium: 'image' | 'video', blob: Ref, poster?: Ref, type?: string, sha256?: string,
 *                frame: 'fill' | 'fit', focal: { x, y } }   Ref = { blobId, chunkCount, byteSize, wrappedBlobKey }
 *
 * `frame` and `focal` are an earlier editor's (ADR 0102 decision 2): every banner now shows its whole medium, so no reader
 * draws them and this client writes `fit` around the centre ([sealed]) — read and kept, so an older client needs no
 * change. `sha256` is the SHA-256 of the file the banner was made from (base64url): a video banner's `BANNER` blob is that
 * file as it is, so a gallery upload of the same file shares its stored object, and a gallery medium whose original has
 * the same digest is the picture the banner shows (the trash question, decision 5).
 *
 * A ref carries neither owner nor role: both are the node's and the slot's, taken from where the meta is read, never from
 * the sealed payload (ADR 0072). A reader never throws: a malformed focal point is the centre, an unknown frame `fill`, an
 * unknown shape no banner — kept untouched by every other meta writer (`NodeMetaWrite` keeps unknown fields).
 */
object Banner {
    /** The node's meta field. */
    const val FIELD = "banner"

    /** The banner's image or video slot — also the role its upload is started in. */
    const val ROLE = "BANNER"

    /** A video banner's poster slot — also the role its upload is started in. */
    const val POSTER_ROLE = "BANNER_POSTER"

    /** A video banner's type where none (or a malformed one) is stored. */
    const val DEFAULT_VIDEO_TYPE = "video/mp4"

    private val VIDEO_TYPE = Regex("^video/[A-Za-z0-9.+-]{1,60}$")

    /** A digest as `sha256` carries it: 32 bytes in base64url, no padding, its last character one a 32-byte value ends in. */
    private val SHA256 = Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

    /** A video's type as the banner stores it: a well-formed `video/…`, else mp4 (the web's `videoType`, `bannerVideoType`). */
    fun videoType(type: String?): String = type?.takeIf { VIDEO_TYPE.matches(it) } ?: DEFAULT_VIDEO_TYPE

    /** [v] where it is a well-formed digest, else null. */
    fun digestOf(v: String?): String? = v?.takeIf { SHA256.matches(it) }

    enum class Medium(val wire: String) { IMAGE("image"), VIDEO("video") }

    /** An earlier editor's choice, kept and never drawn. */
    enum class Frame(val wire: String) { FILL("fill"), FIT("fit") }

    /** An earlier editor's point of the medium (0..1 of its width and height); kept, never drawn. */
    data class Focal(val x: Double, val y: Double)

    val CENTRE = Focal(0.5, 0.5)

    /** A stored ref: what the meta names an attachment by (owner and role are the node's and the slot's). */
    data class Ref(val blobId: String, val chunkCount: Int, val byteSize: Long, val wrappedBlobKey: String) {
        /** The plaintext's size: the stored bytes less a frame's overhead each. */
        val size: Long get() = maxOf(0L, byteSize - V1.FRAME_OVERHEAD.toLong() * chunkCount)
    }

    /** A banner as read. */
    data class Read(
        val medium: Medium,
        val blob: Ref,
        /** A video's poster; absent for an image, or a video whose poster is gone (it still plays). */
        val poster: Ref?,
        /** A video's media type; absent for an image. */
        val type: String?,
        /** The SHA-256 of the file the banner was made from; absent for a banner written before it. */
        val sha256: String?,
        val frame: Frame,
        val focal: Focal,
    ) {
        /** The picture the frame draws: an image's own blob, a video's poster (null: a video without one draws nothing). */
        val picture: Ref? get() = if (medium == Medium.IMAGE) blob else poster

        /** The role [picture] is stored in. */
        val pictureRole: String get() = if (medium == Medium.IMAGE) ROLE else POSTER_ROLE
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun unit(e: JsonElement?): Double? = num(e)?.takeIf { it in 0.0..1.0 }
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A focal point kept on the medium; anything malformed is the centre. */
    fun focal(raw: JsonElement?): Focal {
        val p = raw as? JsonObject ?: return CENTRE
        val x = unit(p["x"]) ?: return CENTRE
        val y = unit(p["y"]) ?: return CENTRE
        return Focal(x, y)
    }

    /** A stored ref, or null where a required field is off. */
    fun ref(raw: JsonElement?): Ref? {
        val r = raw as? JsonObject ?: return null
        val blobId = str(r["blobId"])?.takeIf { it.isNotEmpty() } ?: return null
        val key = str(r["wrappedBlobKey"])?.takeIf { it.isNotEmpty() } ?: return null
        val count = num(r["chunkCount"]) ?: return null
        val size = num(r["byteSize"]) ?: return null
        return Ref(blobId, count.toInt(), size.toLong(), key)
    }

    /**
     * A `banner` field's value (null: none, or a shape this client does not know). The medium and its ref are mandatory; a
     * video's poster is optional; `frame` falls back to `fill`, the focal point to the centre, a malformed digest is none.
     */
    fun read(raw: JsonElement?): Read? {
        val b = raw as? JsonObject ?: return null
        val medium = when (str(b["medium"])) {
            "image" -> Medium.IMAGE
            "video" -> Medium.VIDEO
            else -> return null
        }
        val blob = ref(b["blob"]) ?: return null
        val video = medium == Medium.VIDEO
        return Read(
            medium, blob,
            poster = if (video) ref(b["poster"]) else null,
            type = if (video) videoType(str(b["type"])) else null,
            sha256 = digestOf(str(b["sha256"])),
            frame = if (str(b["frame"]) == "fit") Frame.FIT else Frame.FILL,
            focal = focal(b["focal"]),
        )
    }

    /** The banner a node's meta names, or null. */
    fun of(meta: JsonObject): Read? = read(meta[FIELD])

    /** The blobs a banner's two slots hold, as its meta names them: what `setMemoryBanner` / `removeMemoryBanner` compare. */
    data class Slots(val blobId: String?, val posterBlobId: String?) {
        companion object {
            val NONE = Slots(null, null)
        }
    }

    /**
     * The blobs a stored `banner` names — the compare values of `setMemoryBanner` / `removeMemoryBanner`: what the writer
     * read in the two slots. Read from the raw value, so a banner this client cannot fully read still compares.
     */
    fun slots(raw: JsonElement?): Slots {
        val b = raw as? JsonObject
        fun id(e: JsonElement?) = str((e as? JsonObject)?.get("blobId"))?.takeIf { it.isNotEmpty() }
        return Slots(id(b?.get("blob")), id(b?.get("poster")))
    }

    /** Four decimals: a focal point needs no more, and the meta's size is bounded. */
    private fun round(v: Double): Double = (v.coerceIn(0.0, 1.0) * 10_000).roundToLong() / 10_000.0

    /** A number as the web's `JSON.stringify` writes it: an integral one without a fraction. */
    private fun n(v: Double): JsonPrimitive = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)

    private fun refJson(r: Ref) = buildJsonObject {
        put("blobId", r.blobId); put("chunkCount", r.chunkCount); put("byteSize", r.byteSize); put("wrappedBlobKey", r.wrappedBlobKey)
    }

    /**
     * The `banner` value to seal, in the web's key order: only the four fields of a ref, a poster and a type only for a video,
     * the digest only where well-formed, and the frame — this client writes the whole picture ([Frame.FIT] around
     * [CENTRE], ADR 0102), which an older client draws whole too.
     */
    fun sealed(medium: Medium, blob: Ref, poster: Ref?, type: String?, sha256: String?, frame: Frame = Frame.FIT, focal: Focal = CENTRE): JsonObject =
        buildJsonObject {
            put("medium", medium.wire)
            put("blob", refJson(blob))
            if (medium == Medium.VIDEO && poster != null) put("poster", refJson(poster))
            if (medium == Medium.VIDEO) put("type", videoType(type))
            digestOf(sha256)?.let { put("sha256", it) }
            put("frame", frame.wire)
            put("focal", buildJsonObject { put("x", n(round(focal.x))); put("y", n(round(focal.y))) })
        }

    // ---- what the page draws (ADR 0144 decision 4) ----

    /**
     * What the page shows of a banner: its [medium], a [key] that changes exactly when its picture does (the picture's blob
     * id — a platform's cache keys by it, as a tile's), and a video's [type] (its player is handed it). No ref, wrap or
     * digest: what a host draws.
     */
    data class Shown(val medium: Medium, val key: String, val type: String?)

    /** [read] as the page shows it; null where it has no picture to draw (a video without a poster draws no frame). */
    fun shown(read: Read?): Shown? {
        val r = read ?: return null
        val picture = r.picture ?: return null
        return Shown(r.medium, picture.blobId, r.type)
    }

    /** The banner of [node], where its meta opens and names one; null otherwise (decision 4: nothing shown). */
    fun of(tenant: TenantSession, node: OpenNode): Read? =
        runCatching { of(NodeMetaWrite.openForWrite(tenant.account.suite, node.scopeId, node.id, node.key, node.wrappedMeta)) }.getOrNull()

    /**
     * The picture [node]'s banner draws — an image's JPEG, a video's poster — read from the node's own attachments as its
     * cover is (before and without the gallery list); from the offline store where a pin kept it. Null where the meta does
     * not open, names no banner, or its slot does not read (decision 4: no frame, the header moves up).
     */
    @Throws(Exception::class)
    suspend fun picture(tenant: TenantSession, node: OpenNode): ByteArray? {
        val b = of(tenant, node) ?: return null
        val pic = b.picture ?: return null
        val blobs = ApplicationBlobs(tenant)
        val key = blobs.openKey(node.scopeId, node, pic.blobId, b.pictureRole, pic.wrappedBlobKey) ?: return null
        return runCatching { blobs.frames(node.scopeId, pic.blobId, pic.chunkCount, key) }.getOrNull()
    }

    /** A video banner's `BANNER` blob read at any offset (its player's, ADR 0137); null for an image or a wrap that does not open. */
    fun reader(tenant: TenantSession, node: OpenNode): RangeReader? {
        val b = of(tenant, node)?.takeIf { it.medium == Medium.VIDEO } ?: return null
        val blobs = ApplicationBlobs(tenant)
        val key = blobs.openKey(node.scopeId, node, b.blob.blobId, ROLE, b.blob.wrappedBlobKey) ?: return null
        val source = blobs.FrameSource(node.scopeId, b.blob.blobId, b.blob.chunkCount, key)
        return RangeReader(b.blob.size, b.blob.chunkCount) { first, until, sink -> source.read(first, until, sink) }
    }

    /** Every blob a banner names, by role: the slot and, for a video, its poster. */
    fun blobs(read: Read): List<Pair<String, Ref>> = listOfNotNull(ROLE to read.blob, read.poster?.let { POSTER_ROLE to it })

    /**
     * What a pin keeps of [node]'s banner (ADR 0144 decision 7, ADR 0140 decision 9): every blob it names whose key opens —
     * so a pinned Memory opens with its banner, and its video plays, offline. None where the meta names no banner.
     */
    fun pinned(tenant: TenantSession, node: OpenNode): List<me.raddatz.veyq.mobile.offline.PinBlob> {
        val b = of(tenant, node) ?: return emptyList()
        val store = ApplicationBlobs(tenant)
        return blobs(b).mapNotNull { (role, ref) ->
            store.openKey(node.scopeId, node, ref.blobId, role, ref.wrappedBlobKey)?.let { me.raddatz.veyq.mobile.offline.PinBlob(node.scopeId, ref.blobId, ref.chunkCount, ref.byteSize, it) }
        }
    }
}

/**
 * The banner's frame (ADR 0144 decision 4, the web's `MemoryBanner.vue`): the column's width, **16:9 above a 720-unit
 * column, 1:1 at or below** (dp, pt or effective px — each platform's own unit), and never taller than 60 % of the
 * viewport — the box is then lower, still the column's width, and the picture is contained in it with its surround.
 */
object BannerFrame {
    /** The widest column drawn 1:1. */
    const val NARROW_MAX = 720.0

    /** The frame's height at most, as a share of the viewport's. */
    const val MAX_VIEWPORT_SHARE = 0.6

    /** The surround's picture: scaled to at most this many pixels on its long edge, then stretched back with smooth filtering. */
    const val SURROUND_EDGE = 32

    /** A frame's size in the platform's units, and whether it is the wide (16:9) one. */
    data class Size(val width: Double, val height: Double, val wide: Boolean)

    fun of(columnWidth: Double, viewportHeight: Double): Size {
        val w = maxOf(0.0, columnWidth)
        val wide = w > NARROW_MAX
        val natural = if (wide) w * 9 / 16 else w
        return Size(w, minOf(natural, maxOf(0.0, viewportHeight) * MAX_VIEWPORT_SHARE), wide)
    }

    /**
     * The surround's size for a picture of [width] × [height] pixels: its long edge at most [SURROUND_EDGE], its aspect kept,
     * never enlarged, each side at least one pixel — what every platform scales the picture down to before stretching it
     * back behind the contained picture (the web's 32 px trick; no blur API, decision 4).
     */
    fun surround(width: Int, height: Int): IntArray {
        val long = maxOf(width, height)
        if (long <= SURROUND_EDGE || width <= 0 || height <= 0) return intArrayOf(maxOf(1, width), maxOf(1, height))
        val f = SURROUND_EDGE.toDouble() / long
        return intArrayOf((width * f).toInt().coerceAtLeast(1), (height * f).toInt().coerceAtLeast(1))
    }
}
