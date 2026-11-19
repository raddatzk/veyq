package me.raddatz.veyq.mobile.sharing

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64

/**
 * What a link's instance serves below its root, played on top of a [PlayedShare] (ADR 0150 decision 5): blobs in a store
 * (presigned by the share's blob route, read and written by URL), a Depot file listing, a Memory's media, a Pot's
 * revisions and receipts, and the guest's writes — uploads started and completed, a drop folder, a node created —, each
 * answered as the server answers it and recorded by the share.
 */
class PlayedGuestContent(private val share: PlayedShare) {
    private val suite = share.suite
    private val p = share.p

    /** The store: frames by `blobId/index`. */
    val store = HashMap<String, ByteArray>()
    private var uploads = 0
    /** Started uploads: id → (blobId, chunkCount). */
    private val started = HashMap<String, Pair<String, Int>>()
    val files = mutableListOf<JsonObject>()
    val media = mutableListOf<JsonObject>()
    val completed = mutableListOf<String>()
    val created = mutableListOf<JsonObject>()
    var dropFolders = 0

    /** A blob of [plaintext] put in the store under a fresh key wrapped for [ownerId] in [role] under [ownerKey]: its JSON ref. */
    fun blob(ownerId: String, role: String, ownerKey: ByteArray, plaintext: ByteArray, type: String = "application/octet-stream", name: String? = null): JsonObject {
        val blobId = share.uuid()
        val key = p.randomBytes(32)
        val count = V1.chunkCount(plaintext.size.toLong())
        for (i in 0 until count) {
            val from = i * V1.FRAME_PLAINTEXT
            store["$blobId/$i"] = suite.sealFrame(key, i, count, plaintext.copyOfRange(from, minOf(plaintext.size, from + V1.FRAME_PLAINTEXT)))
        }
        return buildJsonObject {
            put("blobId", blobId)
            put("chunkCount", count)
            put("byteSize", plaintext.size.toLong() + V1.FRAME_OVERHEAD.toLong() * count)
            put("wrappedBlobKey", b64(suite.seal(Context("blob-key", "scopeId" to share.scopeId, "ownerId" to ownerId, "blobId" to blobId, "role" to role), ownerKey, key)))
            put("wrappedBlobMeta", b64(BlobMeta.seal(suite, key, type, name, null)))
        }
    }

    init {
        val base = "/api/shares/${share.shareId}"
        share.routes += { r, rec ->
            val path = r.url.encodedPath
            fun answer(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            when {
                path.startsWith("$base/blobs/") -> {
                    val blobId = path.substringAfterLast('/')
                    val count = store.keys.count { it.startsWith("$blobId/") }
                    answer("""{"presignedGets":[${(0 until count).joinToString(",") { """{"index":$it,"url":"https://store.example/$blobId/$it"}""" }}]}""")
                }
                r.url.host == "store.example" && rec.method == "GET" -> {
                    val frame = store[path.removePrefix("/")]
                    if (frame == null) respond("", HttpStatusCode.NotFound) else respond(frame, HttpStatusCode.OK)
                }
                r.url.host == "store.example" && rec.method == "PUT" -> {
                    store[path.removePrefix("/")] = rec.body
                    respond("", HttpStatusCode.OK)
                }
                path == "$base/files" -> answer("""{"items":${JsonArray(files)}}""")
                path == "$base/media" && rec.method == "GET" -> answer(
                    """{"media":${JsonArray(media.filter { it["nodeId"]!!.jsonPrimitive.content == r.url.parameters["nodeId"] })},"more":false,"identities":[]}""",
                )
                path.startsWith("$base/pots/") -> answer("""{"revisions":[],"more":false}""")
                path.endsWith("/application-blobs") -> answer("""{"blobs":[],"more":false}""")
                path == "$base/blob-uploads" -> {
                    val items = rec.json()["items"]!!.jsonArray
                    answer(
                        """{"results":${buildJsonArray {
                            for (it in items) {
                                val o = it.jsonObject
                                val id = "up-${++uploads}"
                                val blobId = o["blobId"]!!.jsonPrimitive.content
                                val count = o["chunkCount"]!!.jsonPrimitive.content.toInt()
                                started[id] = blobId to count
                                add(buildJsonObject {
                                    put("uploadId", id)
                                    put("presignedPuts", JsonArray((0 until count).map { i -> buildJsonObject { put("index", i); put("url", "https://store.example/$blobId/$i") } }))
                                })
                            }
                        }}}""",
                    )
                }
                path.startsWith("$base/files/") && path.endsWith("/content") -> {
                    val uploadId = rec.json()["uploadId"]!!.jsonPrimitive.content
                    completed += uploadId
                    val (blobId, count) = started.getValue(uploadId)
                    answer("""{"blobId":"$blobId","chunkCount":$count,"byteSize":1,"wrappedBlobKey":"x"}""")
                }
                path == "$base/media" && rec.method == "POST" -> {
                    val item = rec.json()["items"]!!.jsonArray.single().jsonObject
                    val uploadId = item["originalUploadId"]!!.jsonPrimitive.content
                    completed += uploadId
                    answer("""{"results":[{"blobId":"${started.getValue(uploadId).first}","mediaId":"${item["mediaId"]!!.jsonPrimitive.content}"}]}""")
                }
                path == "$base/files/drop-folder" -> {
                    dropFolders++
                    val o = rec.json()
                    answer(
                        buildJsonObject {
                            put("nodeId", o["nodeId"]!!)
                            put("epoch", 0)
                            put("scopeEpoch", o["scopeEpoch"]!!)
                            put("placementRevision", 0)
                            put("wrappedContentKey", o["wrappedContentKey"]!!)
                            put("created", true)
                        }.toString(),
                    )
                }
                path == "$base/nodes" -> {
                    val o = rec.json()
                    created += o
                    answer("""{"nodeId":${o["nodeId"]}}""")
                }
                else -> null
            }
        }
    }

    /** A Depot file under [parentId] (key [parentKey]) holding [content]; its node id and key. */
    fun file(parentId: String, parentKey: ByteArray, name: String, content: ByteArray): Pair<String, ByteArray> {
        val id = share.uuid()
        val key = share.add(id, parentId, parentKey, "FILE", name)
        files += buildJsonObject {
            put("nodeId", id)
            put("fileBlob", blob(id, "FILE_CONTENT", key, content, "text/plain", name))
        }
        return id to key
    }

    /** A Memory under [parentId] holding one medium whose original is [content]; its node id and key. */
    fun memory(parentId: String, parentKey: ByteArray, name: String, content: ByteArray): Pair<String, ByteArray> {
        val id = share.uuid()
        val key = share.add(id, parentId, parentKey, "MEMORY", name)
        val mediaId = share.uuid()
        val original = blob(mediaId, "ORIGINAL", key, content, "image/jpeg", "a.jpg")
        media += buildJsonObject {
            put("blockedByOperator", false)
            put("nodeId", id)
            put("mediaId", mediaId)
            for ((k, v) in original) put(k, v)
        }
        return id to key
    }

    /** A Pot under [parentId]; its node id. */
    fun pot(parentId: String, parentKey: ByteArray, name: String): String {
        val id = share.uuid()
        share.add(id, parentId, parentKey, "EXPENSES_POT", name)
        return id
    }

    /** A WRITE_ONLY link's drop key: its public half sealed under `K_share` into the resolve; the seed. */
    fun dropKey(): ByteArray {
        val seed = suite.generateDropKey()
        share.extraResolve["sealedDropPublicKey"] = JsonPrimitive(b64(suite.seal(Context("drop-public-key"), share.kShare, suite.serializeHybridPublic(suite.dropKeyPublic(seed)))))
        return seed
    }
}
