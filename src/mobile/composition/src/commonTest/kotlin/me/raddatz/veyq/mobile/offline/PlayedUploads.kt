package me.raddatz.veyq.mobile.offline

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The upload half of a played server, beside [PlayedTenant] (which plays reads): `startBlobUploads` answering every frame's
 * presigned PUT to `put.example`, the PUTs kept as they arrive, and the completions an application makes — `completeMedia`,
 * `completeApplicationBlob`, `completeFile` — each echoing the blob it was handed. A test's [PlayedTenant.route] is
 * [route], or [route] after its own.
 */
internal class PlayedUploads(private val t: PlayedTenant) {
    /** One reserved blob as its start request named it. */
    class Reserved(val uploadId: String, val blobId: String, val ownerId: String, val role: String, val chunkCount: Int, val wrappedBlobKey: String, val wrappedBlobMeta: String?)

    val reserved = mutableListOf<Reserved>()

    /** Every frame PUT, by blob id, in index order. */
    val frames = HashMap<String, MutableMap<Int, ByteArray>>()

    /** Every completion's body, as sent, with the operation it was. */
    val completions = mutableListOf<Pair<String, JsonObject>>()

    /** Answers a start with this instead (e.g. `quota_exceeded`), where set. */
    var refuseStart: String? = null

    /** Answers `completeMedia` with this error, where set. */
    var refuseMedia: String? = null

    /** Every write operation asked, in order. */
    val operations = mutableListOf<String>()

    private fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    fun route(r: HttpRequestData): Pair<Int, String>? {
        val p = r.url.encodedPath
        if (r.url.host == "put.example") {
            val (blobId, index) = p.trim('/').split('/')
            frames.getOrPut(blobId) { HashMap() }[index.toInt()] = (r.body as OutgoingContent.ByteArrayContent).bytes()
            return 200 to ""
        }
        return when {
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/blob-uploads-batch")) -> {
                operations += "startBlobUploads"
                refuseStart?.let { return 200 to """{"results":[{"error":"$it"}]}""" }
                val items = r.json()["items"]!!.jsonArray
                val results = items.map { it.jsonObject }.map { item ->
                    val blobId = item["blobId"]!!.jsonPrimitive.content
                    val count = item["chunkCount"]!!.jsonPrimitive.content.toInt()
                    val res = Reserved("up-$blobId", blobId, item["ownerId"]!!.jsonPrimitive.content, item["role"]!!.jsonPrimitive.content, count, item["wrappedBlobKey"]!!.jsonPrimitive.content, item["wrappedBlobMeta"]?.jsonPrimitive?.content)
                    reserved += res
                    """{"uploadId":"${res.uploadId}","presignedPuts":[${(0 until count).joinToString(",") { """{"index":$it,"url":"http://put.example/$blobId/$it"}""" }}]}"""
                }
                200 to """{"results":[${results.joinToString(",")}]}"""
            }
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/media-batch")) -> {
                operations += "completeMedia"
                val item = r.json()["items"]!!.jsonArray.single().jsonObject
                completions += "completeMedia" to item
                val mediaId = item["mediaId"]!!.jsonPrimitive.content
                refuseMedia?.let { return 200 to """{"results":[{"mediaId":"$mediaId","error":"$it"}]}""" }
                val blobId = reserved.first { it.uploadId == item["originalUploadId"]!!.jsonPrimitive.content }.blobId
                200 to """{"results":[{"mediaId":"$mediaId","blobId":"$blobId"}]}"""
            }
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+/application-blobs")) -> {
                operations += "completeApplicationBlob"
                val body = r.json()
                completions += "completeApplicationBlob" to body
                val res = reserved.first { it.uploadId == body["uploadId"]!!.jsonPrimitive.content }
                200 to """{"id":"${body["id"]!!.jsonPrimitive.content}","ownerId":"${res.ownerId}","role":"${res.role}","blobId":"${res.blobId}","chunkCount":${res.chunkCount},"byteSize":1,"wrappedBlobKey":"${res.wrappedBlobKey}","writer":"user:${t.userId}","createdAt":"2026-01-01T00:00:00.000Z","blockedByOperator":false}"""
            }
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/files/[^/]+/content")) -> {
                operations += "completeFile"
                val body = r.json()
                completions += "completeFile" to body
                val res = reserved.first { it.uploadId == body["uploadId"]!!.jsonPrimitive.content }
                200 to """{"blobId":"${res.blobId}","chunkCount":${res.chunkCount},"byteSize":1,"wrappedBlobKey":"${res.wrappedBlobKey}"}"""
            }
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/pots/[^/]+/revisions")) -> {
                operations += "appendPotRevisions"
                val parts = p.split('/')
                val pot = t.scopes.first { it.id == parts[3] }.nodes.first { it.id == parts[5] }
                val items = r.json()["revisions"]!!.jsonArray.map { it.jsonObject }
                // all or none: each the next revision of its record
                for (item in items) {
                    val entryId = item["entryId"]!!.jsonPrimitive.content
                    val latest = pot.revisions.filter { it.entryId == entryId }.maxOfOrNull { it.revision } ?: 0
                    if (item["revision"]!!.jsonPrimitive.content.toLong() != latest + 1) return 409 to """{"code":"stale_revision"}"""
                }
                for (item in items) {
                    appended += item
                    pot.revisions += PlayedTenant.PotRevision(
                        item["entryId"]!!.jsonPrimitive.content, item["revision"]!!.jsonPrimitive.content.toLong(),
                        me.raddatz.veyq.mobile.expenses.SealedRevision(me.raddatz.veyq.mobile.crypto.unb64(item["sealedBody"]!!.jsonPrimitive.content), me.raddatz.veyq.mobile.crypto.unb64(item["wrappedRevisionKey"]!!.jsonPrimitive.content)),
                        item["tombstone"]?.jsonPrimitive?.content == "true",
                    )
                }
                pot.ledgerRevision++
                200 to "{}"
            }
            else -> null
        }
    }

    /** Every revision appended, as sent. */
    val appended = mutableListOf<JsonObject>()

    /** Blob [blobId]'s frames as PUT, opened under [key] — the plaintext the store will hand back. */
    fun plaintext(blobId: String, key: ByteArray): ByteArray {
        val f = frames.getValue(blobId)
        return f.keys.sorted().map { t.suite.openFrame(key, it, f.size, f.getValue(it)) }.fold(ByteArray(0)) { a, b -> a + b }
    }

    @Suppress("unused")
    private fun JsonObject.arr(k: String): JsonArray = this[k]!!.jsonArray
}
