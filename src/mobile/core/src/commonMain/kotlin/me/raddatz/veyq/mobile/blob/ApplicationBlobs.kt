package me.raddatz.veyq.mobile.blob

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ApplicationBlobsApi
import me.raddatz.veyq.mobile.api.apis.BlobApi
import me.raddatz.veyq.mobile.api.models.ApplicationBlob
import me.raddatz.veyq.mobile.api.models.ApplicationBlobCompleteRequest
import me.raddatz.veyq.mobile.api.models.BlobDownloadRequestItem
import me.raddatz.veyq.mobile.api.models.BlobDownloadsBatchRequest
import me.raddatz.veyq.mobile.api.models.BlobUploadRequestItem
import me.raddatz.veyq.mobile.api.models.BlobUploadWindowRequestItem
import me.raddatz.veyq.mobile.api.models.BlobUploadWindowsRequest
import me.raddatz.veyq.mobile.api.models.BlobUploadsBatchRequest
import me.raddatz.veyq.mobile.api.models.PresignedUrl
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeEpochWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A node's application blobs (ADR 0112), core's for every application — a port of `src/web/src/core/blob/
 * applicationBlobs.ts` and the member half of `transport.ts`. A blob's bytes are chunked AEAD frames under its own random
 * key `K_blob` ([V1.sealFrame]); `K_blob` is wrapped under the node's content key, bound to (scope, node, blob, role);
 * its type, name and modification are sealed under `K_blob` ([BlobMeta]). The bytes go straight to the object store
 * through presigned URLs and never through the backend (ADR 0013), on the account's client that carries no bearer.
 * An application brings its role and the record a blob belongs to (its `ownerId` on the completion).
 */
class ApplicationBlobs(private val tenant: TenantSession) {
    private val account = tenant.account
    private val suite = account.suite
    private val blobApi get() = BlobApi(tenant.base, account.http)
    private val appApi get() = ApplicationBlobsApi(tenant.base, account.http)

    /** One listed blob as the server serves it (`ApplicationBlob`); times in epoch ms. */
    class Listed(
        val id: String, val ownerId: String, val role: String, val blobId: String, val chunkCount: Int, val byteSize: Long,
        internal val wrappedBlobKey: String, internal val wrappedBlobMeta: String?, val writer: String?, val createdAt: Long,
        val keptAt: Long?, val releasedAt: Long?, val purgeAfter: Long?, val blocked: Boolean,
    ) {
        /** Released by a revision that dropped it, and kept by none since: never held again. */
        val dropped: Boolean get() = releasedAt != null && keptAt == null

        /** The plaintext size: the sealed one less every frame's overhead. */
        val size: Long get() = byteSize - V1.FRAME_OVERHEAD.toLong() * chunkCount
    }

    /** A listing read: every page, and the first page's tag; null for "unchanged" (a `304` on [ifNoneMatch]). */
    class Listing(val blobs: List<Listed>, val etag: String?)

    /** Why an upload did not complete: the server's code (`budget_exceeded`, `quota_exceeded`, `too_many_blobs`, …). */
    class UploadRefused(val code: String) : Exception("upload_refused: $code")

    /** The blob-key seal's context: the wrap is bound to the record it belongs to (a node, a medium) and to its role. */
    private fun keyContext(scopeId: String, ownerId: String, blobId: String, role: String) =
        Context("blob-key", "scopeId" to scopeId, "ownerId" to ownerId, "blobId" to blobId, "role" to role)

    /** Every application blob of [nodeId], paged by cursor; only the first page is conditional on [ifNoneMatch]. */
    suspend fun list(scopeId: String, nodeId: String, ifNoneMatch: String? = null): Listing? {
        val out = ArrayList<Listed>()
        var after: String? = null
        var etag: String? = null
        var first = true
        while (true) {
            val r = account.respond { appApi.listApplicationBlobs(scopeId, nodeId, after, PAGE, if (first) ifNoneMatch else null) }
            if (first && r.status == 304) return null
            if (!r.success) throw UploadRefused("list ${r.status}")
            if (first) etag = r.headers["etag"]?.firstOrNull() ?: r.headers["ETag"]?.firstOrNull()
            val page = r.body()
            out += page.blobs.map(::listed)
            if (!page.more) break
            val next = page.cursor ?: throw UploadRefused("a page with more and no cursor")
            if (next == after) throw UploadRefused("a cursor that did not advance")
            after = next
            first = false
        }
        return Listing(out, etag)
    }

    private fun listed(b: ApplicationBlob) = Listed(
        b.id, b.ownerId, b.role, b.blobId, b.chunkCount, b.byteSize, b.wrappedBlobKey, b.wrappedBlobMeta, b.writer,
        epochMs(b.createdAt) ?: 0, b.keptAt?.let(::epochMs), b.releasedAt?.let(::epochMs), b.purgeAfter?.let(::epochMs), b.blockedByOperator,
    )

    /** A listed blob's key, opened under the node's key at the address it was read by; null when it does not open. */
    private fun keyOf(scopeId: String, node: OpenNode, b: Listed): ByteArray? = runCatching {
        suite.open(keyContext(scopeId, node.id, b.blobId, b.role), node.key, unb64(b.wrappedBlobKey))
    }.getOrNull()

    /** A listed blob's sealed meta, or null ("no meta": absent, not this blob's, malformed). */
    fun metaOf(scopeId: String, node: OpenNode, b: Listed): BlobMeta.Opened? {
        val sealed = b.wrappedBlobMeta ?: return null
        val key = keyOf(scopeId, node, b) ?: return null
        return BlobMeta.open(suite, key, unb64(sealed))
    }

    /**
     * Uploads [bytes] as blob [id] of [node] (whose epoch the completion states) for the record [ownerId] in [role], its
     * meta sealed, and completes it. At the node's epoch through [NodeEpochWrite]: an epoch refusal re-reads the node and
     * runs the whole upload again, since the wrap of the first is under the key the node no longer has. Throws
     * [UploadRefused] with the server's code otherwise.
     */
    suspend fun upload(
        scopeId: String, node: () -> OpenNode, reread: suspend () -> Boolean, belowFloor: () -> Boolean,
        id: String, ownerId: String, role: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?,
        /** A member's upload pulls the node's path forward where it lags ([NodeEpochWrite.run]'s `pull`). */
        pull: (suspend () -> Boolean)? = null,
    ): Listed {
        var done: Listed? = null
        val result = NodeEpochWrite.run(
            belowFloor = belowFloor,
            reread = reread,
            pull = pull,
            attempt = {
                val code = try {
                    done = uploadOnce(scopeId, node(), id, ownerId, role, bytes, type, name, modifiedAt)
                    null
                } catch (e: UploadRefused) {
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
            is NodeEpochWrite.Result.Answered -> done ?: throw UploadRefused(result.value ?: "refused")
            NodeEpochWrite.Result.Busy -> throw UploadRefused("node_rekey_pending")
            NodeEpochWrite.Result.Gone, NodeEpochWrite.Result.StaleAgain -> throw UploadRefused("stale_epoch")
        }
    }

    private suspend fun uploadOnce(
        scopeId: String, node: OpenNode, id: String, ownerId: String, role: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?,
    ): Listed {
        val (uploadId, plan) = reserveAndPump(scopeId, node, role, bytes, type, name, modifiedAt)
        val blobId = plan.blobId
        val r = account.respond {
            appApi.completeApplicationBlob(scopeId, node.id, ApplicationBlobCompleteRequest(node.epoch, id, ownerId, role, uploadId))
        }
        if (!r.success) throw UploadRefused(codeOf(r.response.bodyAsText()) ?: "complete ${r.status}")
        val blob = r.body()
        // The server echoes the ids it stored: anything else is an id substituted under the seals.
        if (blob.blobId != blobId || blob.id != id) throw UploadRefused("id_substituted")
        return listed(blob)
    }

    /** One blob as its upload planned it: what a cover's sealed meta names (`coverModel.ts`'s `ref`). */
    class Plan(val blobId: String, val chunkCount: Int, val byteSize: Long, val wrappedBlobKey: String, val ownerId: String)

    /**
     * Seals [bytes] as a new blob of [node] in [role] under a fresh `K_blob` (wrapped under the node's key, its meta sealed)
     * and uploads it direct-to-store, frame by frame: the web's `planBlob` and `reserveAndPump`. The upload is OPEN after:
     * whatever attaches it (an application's completion, a cover's `setNodeCover`) names its upload id.
     */
    internal suspend fun reserveAndPump(
        scopeId: String, node: OpenNode, role: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?,
    ): Pair<String, Plan> = reserveAndPumpFrames(scopeId, node, role, bytes.size.toLong(), type, name, modifiedAt) { from, length ->
        bytes.copyOfRange(from.toInt(), (from + length).toInt())
    }

    /**
     * [reserveAndPump] over a source of [size] bytes read a frame at a time ([read]: the plaintext from an offset, at most a
     * frame long), so a file of any size is sealed and sent without holding it whole.
     */
    internal suspend fun reserveAndPumpFrames(
        scopeId: String, node: OpenNode, role: String, size: Long, type: String, name: String?, modifiedAt: Long?,
        read: suspend (from: Long, length: Int) -> ByteArray,
    ): Pair<String, Plan> = reserveAndPumpAll(scopeId, node.id, node.key, listOf(Outgoing(role, size, type, name, modifiedAt, null, read))).single()

    /** One blob an upload sends: its role, its plaintext's size and bytes (a frame at a time), and what its meta seals. */
    internal class Outgoing(
        val role: String, val size: Long, val type: String, val name: String?, val modifiedAt: Long?, val sha256: String?,
        val read: suspend (from: Long, length: Int) -> ByteArray,
    )

    /**
     * [blobs] of the record [ownerId] (a node; a medium, whose original and tiers are one record), each sealed under a
     * fresh `K_blob` wrapped under [wrapKey] bound to [ownerId] and its role, reserved in ONE start request (the web's
     * `startBlobUploads`) and pumped one after another; their upload ids and plans, in order. Throws [UploadRefused] with
     * the first item's refusal (`quota_exceeded` refuses them all).
     */
    internal suspend fun reserveAndPumpAll(scopeId: String, ownerId: String, wrapKey: ByteArray, blobs: List<Outgoing>): List<Pair<String, Plan>> {
        val keys = blobs.map { account.primitives.randomBytes(32) }
        val items = blobs.mapIndexed { i, b ->
            val blobId = NodeCreate.uuid(account)
            val count = V1.chunkCount(b.size)
            BlobUploadRequestItem(
                blobId = blobId, ownerId = ownerId, role = b.role, chunkCount = count,
                byteSize = b.size + V1.FRAME_OVERHEAD.toLong() * count,
                wrappedBlobKey = b64(suite.seal(keyContext(scopeId, ownerId, blobId, b.role), wrapKey, keys[i])),
                wrappedBlobMeta = b64(BlobMeta.seal(suite, keys[i], b.type, b.name, b.modifiedAt, b.sha256)),
            )
        }
        val started = account.respond { blobApi.startBlobUploads(scopeId, BlobUploadsBatchRequest(items)) }
        if (!started.success) throw UploadRefused(codeOf(started.response.bodyAsText()) ?: "start ${started.status}")
        val results = started.body().results
        if (results.size != items.size) throw UploadRefused("start_mismatch")
        results.firstNotNullOfOrNull { it.error }?.let { throw UploadRefused(it) }
        return blobs.mapIndexed { i, b ->
            val res = results[i]
            val uploadId = res.uploadId ?: throw UploadRefused("no upload id")
            pump(scopeId, uploadId, keys[i], items[i].chunkCount, b.size, res.presignedPuts, b.read)
            uploadId to Plan(items[i].blobId, items[i].chunkCount, items[i].byteSize, items[i].wrappedBlobKey, ownerId)
        }
    }

    /** Seals and PUTs the frames of one reserved upload, signing each window as it is reached (and once more on a 403). */
    private suspend fun pump(
        scopeId: String, uploadId: String, key: ByteArray, count: Int, size: Long, first: List<PresignedUrl>?,
        read: suspend (from: Long, length: Int) -> ByteArray,
    ) {
        val signed = HashMap<Int, String>()
        fun keep(urls: List<PresignedUrl>?) = urls.orEmpty().forEach { signed[it.index] = it.url }
        keep(first)
        suspend fun sign(from: Int) {
            val w = account.respond { blobApi.presignBlobUploadWindows(scopeId, BlobUploadWindowsRequest(listOf(BlobUploadWindowRequestItem(uploadId, from)))) }
            if (!w.success) throw UploadRefused("window ${w.status}")
            val r = w.body().results.single()
            r.error?.let { throw UploadRefused(it) }
            keep(r.presignedPuts)
        }
        for (i in 0 until count) {
            if (i !in signed) sign(i)
            val from = i.toLong() * V1.FRAME_PLAINTEXT
            val length = minOf(size - from, V1.FRAME_PLAINTEXT.toLong()).toInt()
            val chunk = read(from, length)
            if (chunk.size != length) throw UploadRefused("source_changed")
            var status = account.putToStore(signed.getValue(i), suite.sealFrame(key, i, count, chunk))
            if (status == 403) {
                // The window's signature expired: sign again from here, seal the frame again (a fresh nonce), once.
                sign(i)
                status = account.putToStore(signed.getValue(i), suite.sealFrame(key, i, count, chunk))
            }
            if (status !in 200..299) throw UploadRefused("chunk_put_failed:$status")
        }
    }

    /**
     * A listed blob's plaintext: presigned GETs from the store, each frame opened at its place in the whole blob. A
     * network failure, a 5xx, 408 or 429 is tried again (at most 4 times, backing off); a 403 signs again (twice at most).
     */
    suspend fun download(scopeId: String, node: OpenNode, b: Listed): ByteArray {
        val key = keyOf(scopeId, node, b) ?: throw UploadRefused("key_unreadable")
        return frames(scopeId, b.blobId, b.chunkCount, key)
    }

    /** The key of blob [blobId] in [role] of [node], from its wrap, or null when it does not open there (not this blob's). */
    internal fun openKey(scopeId: String, node: OpenNode, blobId: String, role: String, wrappedBlobKey: String): ByteArray? = runCatching {
        suite.open(keyContext(scopeId, node.id, blobId, role), node.key, unb64(wrappedBlobKey))
    }.getOrNull()

    /** A blob's plaintext under [key], its [chunkCount] frames read through presigned GETs and opened in place. */
    internal suspend fun frames(scopeId: String, blobId: String, chunkCount: Int, key: ByteArray): ByteArray {
        val out = ArrayList<ByteArray>(chunkCount)
        framesTo(scopeId, blobId, chunkCount, key) { out += it }
        val all = ByteArray(out.sumOf { it.size })
        var o = 0
        for (part in out) {
            part.copyInto(all, o)
            o += part.size
        }
        return all
    }

    /**
     * A blob's plaintext under [key] handed to [sink] a frame at a time, in order, from frame [first] to [until] (exclusive):
     * a file of any size is read without holding it whole, and a page that decides by the first frame reads only that one.
     */
    internal suspend fun framesTo(
        scopeId: String, blobId: String, chunkCount: Int, key: ByteArray, first: Int = 0, until: Int = chunkCount,
        sink: suspend (ByteArray) -> Unit,
    ) = FrameSource(scopeId, blobId, chunkCount, key).read(first, until, sink)

    /**
     * A blob's frames read by index under [key], as often as asked (a player seeking through a video): its presigned GETs are
     * signed once and kept, and signed again when the store refuses one (they lapse). A network failure, a 5xx, 408 or 429
     * is tried again (at most 4 times, backing off); a 403 signs again (twice per read at most).
     */
    internal inner class FrameSource(private val scopeId: String, private val blobId: String, val chunkCount: Int, private val key: ByteArray) {
        private var urls: List<String>? = null

        suspend fun read(first: Int, until: Int, sink: suspend (ByteArray) -> Unit) {
            var resigned = 0
            for (i in first until minOf(until, chunkCount)) {
                var tries = 0
                var backoff = 250L
                while (true) {
                    val signed = urls ?: presignGets(scopeId, blobId).also { urls = it }
                    val (status, frame) = account.getFromStore(signed[i])
                    if (frame != null) {
                        sink(suite.openFrame(key, i, chunkCount, frame))
                        break
                    }
                    if (status == 403 && resigned < 2) {
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
                    throw UploadRefused("chunk_get_failed:$status")
                }
            }
        }
    }

    private suspend fun presignGets(scopeId: String, blobId: String): List<String> {
        val r = account.respond { blobApi.presignBlobDownloads(scopeId, BlobDownloadsBatchRequest(listOf(BlobDownloadRequestItem(blobId)))) }
        if (!r.success) throw UploadRefused(codeOf(r.response.bodyAsText()) ?: "download ${r.status}")
        val res = r.body().results.single()
        res.error?.let { throw UploadRefused(it) }
        return res.presignedGets.orEmpty().sortedBy { it.index }.map { it.url }
    }

    companion object {
        private const val PAGE = 1000

        private fun codeOf(body: String): String? =
            runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun epochMs(iso: String): Long? = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
    }
}
