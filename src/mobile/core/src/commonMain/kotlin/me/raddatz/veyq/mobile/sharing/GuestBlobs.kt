package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.ShareBlobUploadRequestItem
import me.raddatz.veyq.mobile.api.models.ShareBlobUploadWindowRequestItem
import me.raddatz.veyq.mobile.api.models.ShareBlobUploadWindowsRequest
import me.raddatz.veyq.mobile.api.models.ShareBlobUploadsRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64

/**
 * A guest's blobs on its link (the web's `guestBlob.ts`, `shareChunkUrls`, `shareUploadTransport`): a blob read through
 * the share-scoped presign (`resolveShareBlob`, the password token as for the resolve) and its frames opened in place;
 * a blob written through the share's own upload start and windows (`startShareBlobUploads`,
 * `presignShareBlobUploadWindows`, the guest's contributor token in the body), sealed frame by frame under a fresh
 * `K_blob` wrapped bound to its owner and role — as a member's ([ApplicationBlobs]), only the gate differs. Every byte goes
 * to the store's presigned URL; nothing is kept on the device (a guest has no offline store).
 */
class GuestBlobs internal constructor(private val gates: ShareGates) {
    private val client get() = gates.guestClient
    private val suite: V1 get() = client.suite
    private val api get() = SharingApi(client.origin, client.http)

    /** The presigned GETs of [blobId], in frame order. Throws [ApplicationBlobs.UploadRefused] with the server's code. */
    private suspend fun presignGets(blobId: String): List<String> {
        val r = api.resolveShareBlob(gates.link.shareId, blobId, gates.passwordToken)
        if (!r.success) throw ApplicationBlobs.UploadRefused(codeOf(runCatching { r.response.bodyAsText() }.getOrDefault("")) ?: "download ${r.status}")
        return r.body().presignedGets.sortedBy { it.index }.map { it.url }
    }

    /** A blob's frames read by index under [key], signed once and again when lapsed, tried again on a failure. */
    inner class Frames(private val blobId: String, val chunkCount: Int, private val key: ByteArray) {
        private var urls: List<String>? = null
        private var resigned = 0

        suspend fun read(first: Int, until: Int, sink: suspend (ByteArray) -> Unit) {
            for (i in first until minOf(until, chunkCount)) sink(suite.openFrame(key, i, chunkCount, fetch(i)))
        }

        private suspend fun fetch(i: Int): ByteArray {
            var tries = 0
            var backoff = 250L
            while (true) {
                val signed = urls ?: presignGets(blobId).also { urls = it }
                val (status, frame) = runCatching {
                    val r = client.http.get(signed[i])
                    r.status.value to (if (r.status.value in 200..299) r.readRawBytes() else null)
                }.getOrDefault(0 to null)
                if (frame != null) {
                    resigned = 0
                    return frame
                }
                if (status in LAPSED && resigned < 2) {
                    resigned++
                    urls = null
                    continue
                }
                if ((status == 0 || status >= 500 || status == 408 || status == 429) && tries < 4) {
                    tries++
                    delay(backoff)
                    backoff = minOf(backoff * 2, 4_000)
                    continue
                }
                throw ApplicationBlobs.UploadRefused("chunk_get_failed:$status")
            }
        }
    }

    /** [blobId]'s plaintext whole, under [key]. */
    suspend fun whole(blobId: String, chunkCount: Int, key: ByteArray): ByteArray {
        val out = ArrayList<ByteArray>(chunkCount)
        Frames(blobId, chunkCount, key).read(0, chunkCount) { out += it }
        val all = ByteArray(out.sumOf { it.size })
        var o = 0
        for (part in out) {
            part.copyInto(all, o)
            o += part.size
        }
        return all
    }

    /** [blobId] read at any offset (a video played), its plaintext [size] bytes. */
    fun reader(blobId: String, chunkCount: Int, size: Long, key: ByteArray): RangeReader {
        val frames = Frames(blobId, chunkCount, key)
        return RangeReader(size, chunkCount) { first, until, sink -> frames.read(first, until, sink) }
    }

    /** The key of [blobId] in [role] of owner [ownerId] under [ownerKey] (`blob-key`), or null where its wrap does not open. */
    fun openKey(scopeId: String, ownerId: String, blobId: String, role: String, ownerKey: ByteArray, wrapped: String): ByteArray? = runCatching {
        suite.open(keyContext(scopeId, ownerId, blobId, role), ownerKey, me.raddatz.veyq.mobile.crypto.unb64(wrapped))
    }.getOrNull()

    private fun keyContext(scopeId: String, ownerId: String, blobId: String, role: String) =
        Context("blob-key", "scopeId" to scopeId, "ownerId" to ownerId, "blobId" to blobId, "role" to role)

    /**
     * [blobs] of record [ownerId] sealed under fresh `K_blob`s wrapped under [wrapKey], reserved in one share start request
     * as the guest [contributorToken] (into [nodeId], a WRITE_ONLY guest's folder, where given) and pumped one after another;
     * their upload ids and plans. Throws [ApplicationBlobs.UploadRefused] with the first refusal.
     */
    @PlatformApi
    @HiddenFromObjC
    suspend fun reserveAndPumpAll(
        scopeId: String, ownerId: String, wrapKey: ByteArray, contributorToken: String, blobs: List<ApplicationBlobs.Outgoing>,
        nodeId: String? = null, sent: (Long) -> Unit = {},
    ): List<Pair<String, ApplicationBlobs.Plan>> {
        val keys = blobs.map { suite.p.randomBytes(32) }
        try {
            val items = blobs.mapIndexed { i, b ->
                val blobId = client.uuid()
                val count = V1.chunkCount(b.size)
                ShareBlobUploadRequestItem(
                    blobId = blobId, ownerId = ownerId, role = b.role, chunkCount = count,
                    byteSize = b.size + V1.FRAME_OVERHEAD.toLong() * count,
                    wrappedBlobKey = b64(suite.seal(keyContext(scopeId, ownerId, blobId, b.role), wrapKey, keys[i])),
                    wrappedBlobMeta = b64(BlobMeta.seal(suite, keys[i], b.type, b.name, b.modifiedAt, b.sha256)),
                )
            }
            val started = api.startShareBlobUploads(gates.link.shareId, ShareBlobUploadsRequest(contributorToken, items, nodeId), gates.passwordToken)
            if (!started.success) throw ApplicationBlobs.UploadRefused(codeOf(started.response.bodyAsText()) ?: "start ${started.status}")
            val results = started.body().results
            if (results.size != items.size) throw ApplicationBlobs.UploadRefused("start_mismatch")
            results.firstNotNullOfOrNull { it.error }?.let { throw ApplicationBlobs.UploadRefused(it) }
            var before = 0L
            return blobs.mapIndexed { i, b ->
                val uploadId = results[i].uploadId ?: throw ApplicationBlobs.UploadRefused("no upload id")
                val base = before
                pump(uploadId, contributorToken, nodeId, keys[i], items[i].chunkCount, b.size, results[i].presignedPuts, b.read) { sent(base + it) }
                before += b.size
                uploadId to ApplicationBlobs.Plan(items[i].blobId, items[i].chunkCount, items[i].byteSize, items[i].wrappedBlobKey, ownerId)
            }
        } finally {
            keys.forEach { it.fill(0) }
        }
    }

    private suspend fun pump(
        uploadId: String, contributorToken: String, nodeId: String?, key: ByteArray, count: Int, size: Long,
        first: List<me.raddatz.veyq.mobile.api.models.PresignedUrl>?, read: suspend (from: Long, length: Int) -> ByteArray, sent: (Long) -> Unit,
    ) {
        val signed = HashMap<Int, String>()
        fun keep(urls: List<me.raddatz.veyq.mobile.api.models.PresignedUrl>?) = urls.orEmpty().forEach { signed[it.index] = it.url }
        keep(first)
        suspend fun sign(from: Int) {
            val w = api.presignShareBlobUploadWindows(
                gates.link.shareId, ShareBlobUploadWindowsRequest(contributorToken, listOf(ShareBlobUploadWindowRequestItem(uploadId, from)), nodeId), gates.passwordToken,
            )
            if (!w.success) throw ApplicationBlobs.UploadRefused("window ${w.status}")
            val r = w.body().results.single()
            r.error?.let { throw ApplicationBlobs.UploadRefused(it) }
            keep(r.presignedPuts)
        }
        suspend fun put(url: String, bytes: ByteArray): Int = runCatching {
            client.http.put(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }.status.value
        }.getOrDefault(0)
        for (i in 0 until count) {
            if (i !in signed) sign(i)
            val from = i.toLong() * V1.FRAME_PLAINTEXT
            val length = minOf(size - from, V1.FRAME_PLAINTEXT.toLong()).toInt()
            val chunk = read(from, length)
            if (chunk.size != length) throw ApplicationBlobs.UploadRefused("source_changed")
            var status = put(signed.getValue(i), suite.sealFrame(key, i, count, chunk))
            if (status in LAPSED) {
                sign(i)
                status = put(signed.getValue(i), suite.sealFrame(key, i, count, chunk))
            }
            if (status !in 200..299) throw ApplicationBlobs.UploadRefused("chunk_put_failed:$status")
            sent(from + length)
        }
    }

    @PlatformApi
    @HiddenFromObjC
    companion object {
        private val LAPSED = setOf(400, 401, 403)

        fun codeOf(body: String): String? =
            runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
    }
}
