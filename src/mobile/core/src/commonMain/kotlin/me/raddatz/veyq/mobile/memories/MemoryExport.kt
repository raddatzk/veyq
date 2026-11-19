package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Memory's or a chapter's part in a download (ADR 0082, ADR 0149 decision 3; the web's `exportMemoryContent`, purpose
 * `download`): its content as `contracts/export/memories/content.schema.json` holds it —
 *
 *   `{type: memories.memory | memories.chapter, schema: 1, media: [...], date?, location?, participants?, cover?, banner?}`
 *
 * — each medium's original streamed a frame at a time into the archive (its name, type, and its effective capture time as
 * the payload's modified time, so an import that reads no capture time from the bytes falls back to it), with its capture
 * time, whether a member corrected it, its coordinates and place name, a GPX track's own name and activity, a music track's
 * facts, its EXIF copy and its contributor; the node's date and place; who was there **as display names** (ids mean nothing
 * outside the space, ADR 0083); the cover and the banner as files of their own (`cover.jpg`, `banner.jpg` or the video as
 * it is with `banner-poster.jpg`). A chapter is a node of its own under its Memory: its media sit in its own content.
 *
 * **A guest's download carries no names** — neither participants nor contributors — and no EXIF copy: a guest never reads
 * the member directory, and the link does not serve the EXIF tier ([names] null; the apps reach no link as a guest yet,
 * inbox #435). A medium the operator blocked serves no bytes and is left out; a cover or a banner whose bytes do not read
 * is left out, never a failed download.
 */
object MemoryExport {
    const val MEMORY_CONTENT = "memories.memory"
    const val CHAPTER_CONTENT = "memories.chapter"
    const val SCHEMA = 1

    /** A video banner's file extension by its type (the web's `MIME_TO_EXT`, its video half). */
    private val VIDEO_EXT = mapOf("video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/x-matroska" to "mkv")

    /**
     * [node]'s content for a download; null where its media do not list (nothing to export). [names] names a member by user
     * id (a member's download: the tenant's directory); null for a guest's, which names no one.
     */
    @Throws(Exception::class)
    suspend fun content(
        tenant: TenantSession, node: OpenNode, names: ((userId: String) -> String?)? = { tenant.people[it]?.displayName },
    ): NodeExport.Content? {
        val items = MemoryMedia.list(tenant, node.scopeId, node.id)?.items?.filter { !it.blocked } ?: return null
        val member = names != null
        val name: (String) -> String? = { id -> names?.invoke(id) }
        val payloads = ArrayList<NodeExport.Payload>()
        // per medium its payload's index, with what the content says of it beside the file
        val media = items.map { item ->
            val meta = item.meta
            val captured = meta?.effectiveCapturedAt
            val index = payloads.size
            payloads += NodeExport.Payload(item.name ?: item.mediaId, item.original.size, item.type ?: "application/octet-stream", captured ?: item.modifiedAt) { emit ->
                check(MemoryMedia.stream(tenant, item, item.original) { emit(it) }) { "medium_unreadable" }
            }
            val exif = if (member) runCatching { MediaDetails.rawExif(tenant, item) }.getOrNull() else null
            index to facts(item, captured, exif, item.ownerKey?.takeIf { it.startsWith("user:") }?.let { name(it.removePrefix("user:")) })
        }
        val raw = runCatching { NodeMetaWrite.openForWrite(tenant.account.suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull()
            ?: JsonObject(emptyMap())
        val date = MemoryDate.of(raw)?.toJson()
        val location = MemoryLocation.of(raw)?.toJson()
        val participants = if (member) Participants.read(raw).mapNotNull(name) else emptyList()

        val cover = NodeCover.read(raw)?.let { c ->
            NodeCover.open(tenant, node)?.let { bytes ->
                payloads += NodeExport.Payload.whole("cover.jpg", bytes.size.toLong(), "image/jpeg") { bytes }
                payloads.size - 1 to NodeCover.cropJson(c.crop)
            }
        }
        val banner = Banner.of(raw)?.let { banner(tenant, node, it, payloads) }

        val type = if (node.kind == MemoryCreate.CHAPTER) CHAPTER_CONTENT else MEMORY_CONTENT
        return NodeExport.Content(type, SCHEMA, payloads) { refs ->
            buildJsonObject {
                put("type", type)
                put("schema", SCHEMA)
                put("media", JsonArray(media.map { (i, rest) -> JsonObject(mapOf("file" to refs[i]) + rest) }))
                date?.let { put("date", it) }
                location?.let { put("location", it) }
                if (participants.isNotEmpty()) put("participants", JsonArray(participants.map { JsonPrimitive(it) }))
                cover?.let { (i, crop) -> put("cover", buildJsonObject { put("file", refs[i]); put("crop", crop) }) }
                banner?.let { b ->
                    put("banner", buildJsonObject {
                        put("file", refs[b.file])
                        b.poster?.let { put("poster", refs[it]) }
                        put("medium", b.read.medium.wire)
                        put("frame", b.read.frame.wire)
                        put("focal", buildJsonObject { put("x", b.read.focal.x); put("y", b.read.focal.y) })
                    })
                }
            }
        }
    }

    /** What the content says of a medium beside its file, in the web's order. */
    private fun facts(item: MemoryMedia.Item, captured: Long?, exif: JsonObject?, contributor: String?): Map<String, JsonElement> {
        val meta = item.meta
        val out = LinkedHashMap<String, JsonElement>()
        out["name"] = JsonPrimitive(item.name ?: item.mediaId)
        captured?.let { out["capturedAt"] = JsonPrimitive(NodeExport.isoMs(it)) }
        if (meta?.capturedAtOverride != null) out["capturedAtCorrected"] = JsonPrimitive(true)
        if (meta != null && (meta.gpsLat != null || meta.gpsLng != null || meta.placeName != null)) {
            out["location"] = buildJsonObject {
                meta.gpsLat?.let { put("lat", it) }
                meta.gpsLng?.let { put("lon", it) }
                meta.placeName?.let { put("place", it) }
            }
        }
        val activity = TrackLabel.activityOf(meta?.trackActivity)
        if (meta?.trackName != null || activity != null) {
            out["track"] = buildJsonObject {
                meta?.trackName?.let { put("name", it) }
                activity?.let { put("activity", it) }
            }
        }
        if (item.isAudio) meta?.facts?.let { f ->
            out["audio"] = buildJsonObject { put("durationMs", f.durationMs); put("trimStartMs", f.trimStartMs); put("trimEndMs", f.trimEndMs) }
        }
        exif?.let { out["exif"] = it }
        contributor?.let { out["contributor"] = JsonPrimitive(it) }
        return out
    }

    private class Exported(val read: Banner.Read, val file: Int, val poster: Int?)

    /**
     * The banner's files: an image or a poster read whole (one JPEG each), a video streamed a frame at a time; null where
     * its file's key does not open or an image does not read (left out, never a failed download).
     */
    private suspend fun banner(tenant: TenantSession, node: OpenNode, read: Banner.Read, payloads: MutableList<NodeExport.Payload>): Exported? {
        val blobs = ApplicationBlobs(tenant)
        suspend fun whole(name: String, ref: Banner.Ref, role: String): Int? {
            val key = blobs.openKey(node.scopeId, node, ref.blobId, role, ref.wrappedBlobKey) ?: return null
            val bytes = runCatching { blobs.frames(node.scopeId, ref.blobId, ref.chunkCount, key) }.getOrNull() ?: return null
            payloads += NodeExport.Payload.whole(name, bytes.size.toLong(), "image/jpeg") { bytes }
            return payloads.size - 1
        }
        if (read.medium == Banner.Medium.IMAGE) return whole("banner.jpg", read.blob, Banner.ROLE)?.let { Exported(read, it, null) }
        val key = blobs.openKey(node.scopeId, node, read.blob.blobId, Banner.ROLE, read.blob.wrappedBlobKey) ?: return null
        val type = Banner.videoType(read.type)
        payloads += NodeExport.Payload("banner.${VIDEO_EXT[type] ?: "mp4"}", read.blob.size, type) { emit ->
            blobs.framesTo(node.scopeId, read.blob.blobId, read.blob.chunkCount, key) { emit(it) }
        }
        val file = payloads.size - 1
        return Exported(read, file, read.poster?.let { whole("banner-poster.jpg", it, Banner.POSTER_ROLE) })
    }
}
