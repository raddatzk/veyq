package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.RemoveNodeCoverRequest
import me.raddatz.veyq.mobile.api.models.SetNodeCoverRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs

/**
 * A node's cover (core's `core/node/cover/`, ADR 0110 decision 11, uc.platform.node-cover): one image its owner uploads as the
 * node's own `COVER` blob, named by the `cover` field of the node's sealed meta in the web's shape —
 * `{source: {kind: 'upload', ref: {blobId, chunkCount, byteSize, wrappedBlobKey, ownerId, role}}, crop: {x, y, width, height}}`
 * — the crop a normalised 0..1 rectangle of the image (the full frame: a centred fit). Setting one is ONE request that
 * attaches the upload and writes the meta (`setNodeCover`, compared against the blob the meta being replaced named); removing
 * one frees the blob in the same way (`removeNodeCover`); a moved crop is a plain meta write. Every write is revision-checked
 * ([NodeMetaWrite]), the meta's other fields kept. The image the app uploads is the device's one JPEG of the picked picture
 * (the web's `COVER_SPEC`: long edge 2048, quality 0.85), made by the platform to [LONG_EDGE] and [QUALITY].
 */
object NodeCover {
    const val ROLE = "COVER"
    const val FIELD = "cover"

    /**
     * The cover's JPEG as every client makes it (the web's `COVER_SPEC`): the picked picture whole, its long edge at most
     * [LONG_EDGE] pixels (never enlarged), at [QUALITY] — the platform decodes and encodes, these numbers are the core's.
     */
    const val LONG_EDGE = 2048
    const val QUALITY = 0.85

    /** [QUALITY] on the 0–100 scale an encoder that takes a percentage reads (Android's `compress`, gdk-pixbuf's saver). */
    const val QUALITY_PERCENT = 85

    /** A list's small picture of a cover is decoded at about this many pixels on an edge (a row draws it a few dozen wide). */
    const val THUMBNAIL_EDGE = 384

    /**
     * The size a picture of [width] × [height] pixels is encoded at as a cover: scaled so its long edge is at most
     * [LONG_EDGE], its aspect kept, never enlarged, each side at least one pixel.
     */
    fun encodedSize(width: Int, height: Int): IntArray {
        val long = maxOf(width, height)
        if (long <= LONG_EDGE || width <= 0 || height <= 0) return intArrayOf(width, height)
        val f = LONG_EDGE.toDouble() / long
        return intArrayOf((width * f).toInt().coerceAtLeast(1), (height * f).toInt().coerceAtLeast(1))
    }

    @kotlinx.serialization.Serializable
    data class Crop(val x: Double, val y: Double, val width: Double, val height: Double) {
        companion object {
            val FULL = Crop(0.0, 0.0, 1.0, 1.0)
        }
    }

    /** A node's cover as its meta names it: the blob, and where the frame sits on the image. */
    class Read(val blobId: String, val chunkCount: Int, val byteSize: Long, val wrappedBlobKey: String, val crop: Crop)

    enum class Outcome { DONE, STALE, BUSY, REFUSED, FAILED }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    /** The web's `normalizeCoverCrop`: a missing or broken crop is the full frame, a rectangle held inside the image. */
    fun crop(raw: JsonElement?): Crop {
        val c = raw as? JsonObject ?: return Crop.FULL
        val width = num(c["width"])?.takeIf { it > 0 }?.coerceIn(0.0, 1.0) ?: 1.0
        val height = num(c["height"])?.takeIf { it > 0 }?.coerceIn(0.0, 1.0) ?: 1.0
        val x = (num(c["x"]) ?: 0.0).coerceIn(0.0, 1.0 - width)
        val y = (num(c["y"]) ?: 0.0).coerceIn(0.0, 1.0 - height)
        return Crop(x, y, width, height)
    }

    /** The web's `readCover` for an uploaded cover: null when the meta names none (or none this app can draw). */
    fun read(meta: JsonObject): Read? {
        val c = meta[FIELD] as? JsonObject ?: return null
        val source = c["source"] as? JsonObject ?: return null
        if ((source["kind"] as? JsonPrimitive)?.content != "upload") return null
        val ref = source["ref"] as? JsonObject ?: return null
        val blobId = (ref["blobId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val key = (ref["wrappedBlobKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val count = (ref["chunkCount"] as? JsonPrimitive)?.longOrNull ?: return null
        val size = (ref["byteSize"] as? JsonPrimitive)?.longOrNull ?: return null
        return Read(blobId, count.toInt(), size, key, crop(c["crop"]))
    }

    /** The blob a meta's cover names, or null (the web's `uploadBlobIdOf`). */
    fun blobIdOf(meta: JsonObject): String? = read(meta)?.blobId

    /** The `cover` value of an uploaded cover, in the web's key order (`uploadedCover`). */
    fun uploaded(plan: ApplicationBlobs.Plan, crop: Crop = Crop.FULL): JsonObject = buildJsonObject {
        put("source", buildJsonObject {
            put("kind", "upload")
            put("ref", buildJsonObject {
                put("blobId", plan.blobId); put("chunkCount", plan.chunkCount); put("byteSize", plan.byteSize)
                put("wrappedBlobKey", plan.wrappedBlobKey); put("ownerId", plan.ownerId); put("role", ROLE)
            })
        })
        put("crop", cropJson(crop))
    }

    private fun cropJson(c: Crop) = buildJsonObject {
        fun n(v: Double): JsonPrimitive = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
        put("x", n(c.x)); put("y", n(c.y)); put("width", n(c.width)); put("height", n(c.height))
    }

    /** Which node of the tenant's trees names which cover blob (node id to blob id): what a list's thumbnails are keyed by,
     *  so a client opens a cover once and again only when it changed. */
    suspend fun named(tenant: TenantSession): Map<String, String> =
        tenant.trees().values.flatten().mapNotNull { n ->
            runCatching { read(NodeMetaWrite.openForWrite(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta)) }.getOrNull()?.let { n.id to it.blobId }
        }.toMap()

    /**
     * Which node names which cover, and how it is framed: a key of its blob AND its crop, by node id — so a list's picture is
     * opened once and again when the cover or only its crop changed.
     */
    suspend fun framed(tenant: TenantSession): Map<String, String> =
        tenant.trees().values.flatten().mapNotNull { n ->
            runCatching { read(NodeMetaWrite.openForWrite(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta)) }.getOrNull()
                ?.let { n.id to "${it.blobId}@${it.crop.x},${it.crop.y},${it.crop.width},${it.crop.height}" }
        }.toMap()

    /** What a list's covers do after a read: [keep] the pictures held whose key still holds, [open] the nodes to open anew. */
    class Changes(val keep: Set<String>, val open: Map<String, String>)

    /**
     * A list's covers after [framed] read [named] (node id to key): a picture [held] under the key the node still names is
     * kept; every other named node is opened — a new cover, another crop; one the node no longer names is dropped.
     */
    fun changes(named: Map<String, String>, held: Map<String, String>): Changes {
        val keep = held.filter { (id, key) -> named[id] == key }.keys
        return Changes(keep, named.filterKeys { it !in keep })
    }

    /** A cover opened here: its image's bytes and where its frame sits on it. */
    class Opened(val bytes: ByteArray, val crop: Crop)

    /** The cover of node [nodeId] wherever the tenant's trees hold it, with its crop, or null. */
    @Throws(Exception::class)
    suspend fun openFramed(tenant: TenantSession, nodeId: String): Opened? {
        val node = tenant.trees().values.flatten().firstOrNull { it.id == nodeId } ?: return null
        val meta = runCatching { NodeMetaWrite.openForWrite(tenant.account.suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull() ?: return null
        val crop = read(meta)?.crop ?: return null
        return open(tenant, node)?.let { Opened(it, crop) }
    }

    /** The cover of node [nodeId] wherever the tenant's trees hold it, or null. */
    @Throws(Exception::class)
    suspend fun openNode(tenant: TenantSession, nodeId: String): ByteArray? =
        tenant.trees().values.flatten().firstOrNull { it.id == nodeId }?.let { open(tenant, it) }

    /** The cover of [node], if its meta names one: its image's bytes (the JPEG the uploader made), opened here. */
    @Throws(Exception::class)
    suspend fun open(tenant: TenantSession, node: OpenNode): ByteArray? {
        val meta = runCatching { NodeMetaWrite.openForWrite(tenant.account.suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull() ?: return null
        val c = read(meta) ?: return null
        val blobs = ApplicationBlobs(tenant)
        val key = blobs.openKey(node.scopeId, node, c.blobId, ROLE, c.wrappedBlobKey) ?: return null
        return runCatching { blobs.frames(node.scopeId, c.blobId, c.chunkCount, key) }.getOrNull()
    }

    /** Sets [jpeg] as the cover of node [nodeId] with [crop]: uploaded, then attached and the meta written in one request. */
    @Throws(Exception::class)
    suspend fun set(tenant: TenantSession, scopeId: String, nodeId: String, jpeg: ByteArray, crop: Crop = Crop.FULL): Outcome {
        // uploaded once per node epoch: a stale revision rebuilds only the meta, a new epoch (a new key) the upload too
        var uploaded: Triple<Int, String, ApplicationBlobs.Plan>? = null
        return write(tenant, scopeId, nodeId) { node, current ->
            val up = uploaded?.takeIf { it.first == node.epoch }
                ?: ApplicationBlobs(tenant).reserveAndPump(scopeId, node, ROLE, jpeg, "image/jpeg", null, null)
                    .let { (id, plan) -> Triple(node.epoch, id, plan) }.also { uploaded = it }
            val uploadId = up.second
            val plan = up.third
            val old = blobIdOf(current)
            JsonObject(current + (FIELD to uploaded(plan, crop))) to { wrapped: String, revision: Long ->
                tenant.account.respond { ContentApi(tenant.base, tenant.account.http).setNodeCover(
                    scopeId, nodeId,
                    SetNodeCoverRequest(nodeEpoch = node.epoch, wrappedMeta = wrapped, metaRevision = revision, uploadId = uploadId, expectedBlobId = old),
                ) }
            }
        }
    }

    /**
     * Moves the crop of node [nodeId]'s cover to [crop], the image kept (the web's `keep` intent: a plain meta write, the
     * cover's blob untouched). Nothing to write where the node has no cover.
     */
    @Throws(Exception::class)
    suspend fun recrop(tenant: TenantSession, scopeId: String, nodeId: String, crop: Crop): Outcome =
        write(tenant, scopeId, nodeId) { node, current ->
            val cover = current[FIELD] as? JsonObject ?: return@write null
            if (read(current) == null) return@write null
            JsonObject(current + (FIELD to JsonObject(cover + ("crop" to cropJson(crop))))) to { wrapped: String, revision: Long ->
                tenant.account.respond { ContentApi(tenant.base, tenant.account.http).updateNode(
                    scopeId, nodeId, me.raddatz.veyq.mobile.api.models.UpdateNodeRequest(wrappedMeta = wrapped, metaRevision = revision, nodeEpoch = node.epoch),
                ) }
            }
        }

    /** Removes the cover of node [nodeId]: its blob freed and the field dropped in one request. */
    @Throws(Exception::class)
    suspend fun remove(tenant: TenantSession, scopeId: String, nodeId: String): Outcome =
        write(tenant, scopeId, nodeId) { node, current ->
            val old = blobIdOf(current) ?: return@write null
            JsonObject(current - FIELD) to { wrapped: String, revision: Long ->
                tenant.account.respond { ContentApi(tenant.base, tenant.account.http).removeNodeCover(
                    scopeId, nodeId, RemoveNodeCoverRequest(nodeEpoch = node.epoch, blobId = old, wrappedMeta = wrapped, metaRevision = revision),
                ) }
            }
        }

    /**
     * One cover write at the node's epoch: [prepare] builds, from the meta as last read, the meta to write and the request
     * that writes it (null: nothing to write). A stale revision re-reads the node and prepares again (a cover the server
     * holds otherwise is `cover_stale`, [Outcome.STALE]: nothing was written, the upload is freed).
     */
    private suspend fun write(
        tenant: TenantSession, scopeId: String, nodeId: String,
        prepare: suspend (OpenNode, JsonObject) -> Pair<JsonObject, suspend (String, Long) -> HttpResponse<*>>?,
    ): Outcome {
        repeat(3) {
            tenant.forgetTree()
            val node = tenant.node(scopeId, nodeId) ?: return Outcome.FAILED
            val current = runCatching { NodeMetaWrite.openForWrite(tenant.account.suite, scopeId, nodeId, node.key, node.wrappedMeta) }
                .getOrElse { return Outcome.FAILED }
            val (meta, send) = prepare(node, current) ?: return Outcome.DONE
            val wrapped = NodeMetaWrite.seal(tenant.account.suite, scopeId, nodeId, node.key, meta)
            val r = send(wrapped, node.metaRevision)
            if (r.success) {
                // the tree read before the write holds the meta it replaced: the next read takes the server's
                tenant.forgetTree()
                return Outcome.DONE
            }
            val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
            val code = runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject)?.get("code")?.jsonPrimitive?.content
            }.getOrNull()
            when {
                code == "cover_stale" -> return Outcome.STALE
                NodeMetaWrite.staleOf(body) != null || code == "stale_epoch" -> return@repeat
                code == "node_rekey_pending" -> return Outcome.BUSY
                else -> return Outcome.REFUSED
            }
        }
        return Outcome.STALE
    }
}
