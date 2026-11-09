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

    /** The blob-key seal's context: the wrap is bound to the node it belongs to and to its role. */
    private fun keyContext(scopeId: String, nodeId: String, blobId: String, role: String) =
        Context("blob-key", "scopeId" to scopeId, "ownerId" to nodeId, "blobId" to blobId, "role" to role)

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
    ): Listed {
        var done: Listed? = null
        val result = NodeEpochWrite.run(
            belowFloor = belowFloor,
            reread = reread,
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
    ): Pair<String, Plan> {
        val key = account.primitives.randomBytes(32)
        val blobId = NodeCreate.uuid(account)
        val count = V1.chunkCount(bytes.size.toLong())
        val item = BlobUploadRequestItem(
            blobId = blobId, ownerId = node.id, role = role, chunkCount = count,
            byteSize = bytes.size.toLong() + V1.FRAME_OVERHEAD.toLong() * count,
            wrappedBlobKey = b64(suite.seal(keyContext(scopeId, node.id, blobId, role), node.key, key)),
            wrappedBlobMeta = b64(BlobMeta.seal(suite, key, type, name, modifiedAt)),
        )
        val started = account.respond { blobApi.startBlobUploads(scopeId, BlobUploadsBatchRequest(listOf(item))) }
        if (!started.success) throw UploadRefused(codeOf(started.response.bodyAsText()) ?: "start ${started.status}")
        val res = started.body().results.single()
        res.error?.let { throw UploadRefused(it) }
        val uploadId = res.uploadId ?: throw UploadRefused("no upload id")
        val signed = HashMap<Int, String>()
        fun keep(urls: List<PresignedUrl>?) = urls.orEmpty().forEach { signed[it.index] = it.url }
        keep(res.presignedPuts)
        suspend fun sign(from: Int) {
            val w = account.respond { blobApi.presignBlobUploadWindows(scopeId, BlobUploadWindowsRequest(listOf(BlobUploadWindowRequestItem(uploadId, from)))) }
            if (!w.success) throw UploadRefused("window ${w.status}")
            val r = w.body().results.single()
            r.error?.let { throw UploadRefused(it) }
            keep(r.presignedPuts)
        }
        for (i in 0 until count) {
            if (i !in signed) sign(i)
            val from = i * V1.FRAME_PLAINTEXT
            val chunk = bytes.copyOfRange(from, minOf(bytes.size, from + V1.FRAME_PLAINTEXT))
            var status = account.putToStore(signed.getValue(i), suite.sealFrame(key, i, count, chunk))
            if (status == 403) {
                // The window's signature expired: sign again from here, seal the frame again (a fresh nonce), once.
                sign(i)
                status = account.putToStore(signed.getValue(i), suite.sealFrame(key, i, count, chunk))
            }
            if (status !in 200..299) throw UploadRefused("chunk_put_failed:$status")
        }
        return uploadId to Plan(blobId, count, item.byteSize, item.wrappedBlobKey, node.id)
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
        val b = object { val blobId = blobId; val chunkCount = chunkCount }
        var urls = presignGets(scopeId, b.blobId)
        var resigned = 0
        val out = ArrayList<ByteArray>(b.chunkCount)
        for (i in 0 until b.chunkCount) {
            var tries = 0
            var backoff = 250L
            while (true) {
                val (status, frame) = account.getFromStore(urls[i])
                if (frame != null) {
                    out += suite.openFrame(key, i, b.chunkCount, frame)
                    break
                }
                if (status == 403 && resigned < 2) {
                    resigned++
                    urls = presignGets(scopeId, b.blobId)
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
        val all = ByteArray(out.sumOf { it.size })
        var o = 0
        for (part in out) {
            part.copyInto(all, o)
            o += part.size
        }
        return all
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
