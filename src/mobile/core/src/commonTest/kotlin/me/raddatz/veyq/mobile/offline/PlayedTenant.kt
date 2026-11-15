package me.raddatz.veyq.mobile.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testPrimitives

/**
 * A server the offline tests play (ADR 0140 decisions 4–6), with real seals: one account remembered on this device
 * (DEVICE_KEY at LOGIN), its tenants each on a domain of its own, personal scopes granted to itself, Memories with media
 * whose tiers are blobs of sealed frames, served from a played object store — and a switch that cuts the network
 * ([reachable]). Every listing answers its revision as the server does: `listNodes` an `ETag`, `listNodeMedia` its
 * `mediaRevision`, each `304` on a matching `If-None-Match`.
 */
internal class PlayedTenant(files: DeviceFiles = deviceFiles, val clock: () -> Long = { 0L }) {
    val p = testPrimitives
    val suite = V1(p)
    val server = "http://a.example"
    val userId = "00000000-0000-4000-8000-000000000001"
    val masterKey: ByteArray = p.randomBytes(32)
    private val identity = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
    private val wrappedIdentity = suite.seal(Context("identity-priv"), masterKey, concat(lp(identity.kemSeed), lp(identity.signSeed)))
    private val kem = suite.xwingKey(identity.kemSeed)
    private val myPublic = suite.identityPublic(identity).serialize()

    // ---- the server's state

    /** Whether any request reaches the server or its store. */
    var reachable = true
    /** What a refresh of the session answers. */
    var refreshStatus = 200
    /** Whether `key-material` serves this device's wrap. */
    var serveWrap = true
    /** Tenants answered `403 tenant_suspended`, by id. */
    val suspended = HashSet<String>()
    /** How often the store served a frame. */
    var storeGets = 0
    /** The bytes the store served, frame by frame. */
    var storeBytes = 0L
    /** The network goes once the store served this many frames (a fetch cut in the middle); null: never. */
    var cutAfterStoreGets: Int? = null
    /** Every request's path, in order. */
    val requests = mutableListOf<String>()

    /** Every id the server hands out is a UUID (the seals' contexts require one); [id] makes one of a test's name. */
    private val names = HashMap<String, String>()

    fun id(name: String): String {
        val h = me.raddatz.veyq.mobile.crypto.hex(p.sha256(name.encodeToByteArray()))
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-4${h.substring(13, 16)}-8${h.substring(17, 20)}-${h.substring(20, 32)}".also { names[it] = name }
    }

    /** The test's name of id [id]. */
    fun nameOf(id: String): String = names[id] ?: id

    class Tenant(val id: String, val domain: String)
    inner class Scope(val name: String, val tenant: Tenant) {
        val id = id(name)
        val key: ByteArray = p.randomBytes(32)
        /** Whether the account is a member: not, and the scope is gone from its scopes and its tree answers `403`. */
        var member = true
        /** Listed in `listMyScopes` while not a member (a list that does not know yet). */
        var stillListed = false
        var revision = 1
        val nodes = mutableListOf<Node>()
        val wrappedGrant: ByteArray = suite.sealTo(Context("scope-grant", "scopeId" to id, "epoch" to 0L), kem.publicKey, key)
        val signature: ByteArray = p.ed25519Sign(identity.signSeed, suite.grantStatement(id, "PERSONAL", 0, userId, myPublic, wrappedGrant))
    }
    inner class Node(val scope: Scope, val label: String, val parent: Node?, val kind: String, val name: String) {
        val id = id(label)
        /** The node's key: a re-key ([rekey]) replaces it, and every wrap under it is sealed anew. */
        var key: ByteArray = p.randomBytes(32)
        var blocked = false
        /** The node's cover ([cover]): its meta names it, sealed under the node's key as `NodeCover` writes it. */
        var cover: Blob? = null
        var mediaRevision = 1
        val media = mutableListOf<Medium>()
        private val binding = arrayOf("scopeId" to scope.id, "nodeId" to id, "epoch" to 0L, "placementRevision" to 0L)
        val encryptedName: ByteArray get() = suite.sealField(Context("node-name", *binding), parent?.key ?: scope.key, name.encodeToByteArray())
        val wrappedKey: ByteArray get() = suite.seal(Context("content-key", *binding), parent?.key ?: scope.key, key)
    }
    inner class Blob(val label: String, val plain: List<ByteArray>, private val role: String, private val ownerId: String, private val nodeKey: () -> ByteArray, private val scopeId: String, type: String?) {
        val id = id(label)
        val key: ByteArray = p.randomBytes(32)
        val frames: List<ByteArray> = plain.mapIndexed { i, f -> suite.sealFrame(key, i, plain.size, f) }
        /** `K_blob` under its node's key as it is now: a re-key re-wraps it, the blob and its frames stay (R5). */
        val wrapped: ByteArray get() = suite.seal(Context("blob-key", "scopeId" to scopeId, "ownerId" to ownerId, "blobId" to id, "role" to role), nodeKey(), key)
        val meta: ByteArray? = type?.let { BlobMeta.seal(suite, key, it, "$id.bin", null) }
        val byteSize: Long get() = frames.sumOf { it.size.toLong() }
    }
    inner class Medium(val node: Node, val label: String, val original: Blob, val thumbnail: Blob?, val preview: Blob? = null) {
        val id = id(label)
        var trashed = false
        var blocked = false
    }

    val tenants = mutableListOf(Tenant("t1", "a.example"))
    val scopes = mutableListOf<Scope>()
    private val blobs = HashMap<String, Blob>()

    /** The stored size of blob [id] (its sealed frames'). */
    fun byteSizeOf(id: String): Long = blobs.getValue(id).byteSize

    fun scope(name: String, tenant: Tenant = tenants.first()): Scope = Scope(name, tenant).also { scopes += it }

    fun node(scope: Scope, label: String, kind: String = "MEMORY", parent: Node? = null, name: String = "Node $label"): Node =
        Node(scope, label, parent, kind, name).also { scope.nodes += it; scope.revision++ }

    /**
     * A medium of [node] whose original is [frames] frames, whose thumbnail (where [thumbnail]) one and whose preview (where
     * [preview]) [preview] frames, each with a marker.
     */
    fun medium(node: Node, name: String, frames: Int = 1, video: Boolean = false, thumbnail: Boolean = true, preview: Int = 0): Medium {
        val mediaId = id(name)
        val original = Blob("$name-o", (0 until frames).map { "original $name frame $it".encodeToByteArray() }, "ORIGINAL", mediaId, { node.key }, node.scope.id, if (video) "video/mp4" else "image/jpeg")
        val thumb = if (thumbnail) Blob("$name-t", listOf("thumbnail $name".encodeToByteArray()), "THUMBNAIL", mediaId, { node.key }, node.scope.id, null) else null
        blobs[original.id] = original
        val prev = if (preview > 0) Blob("$name-p", (0 until preview).map { "preview $name frame $it".encodeToByteArray() }, "PREVIEW", mediaId, { node.key }, node.scope.id, null) else null
        thumb?.let { blobs[it.id] = it }
        prev?.let { blobs[it.id] = it }
        return Medium(node, name, original, thumb, prev).also { node.media += it; node.mediaRevision++ }
    }

    /** Node [n]'s cover set to a one-frame picture with a marker: its meta names it, the tree's revision moves. */
    fun cover(n: Node, label: String): Blob =
        Blob(label, listOf("cover $label".encodeToByteArray()), "COVER", n.id, { n.key }, n.scope.id, null).also {
            blobs[it.id] = it
            n.cover = it
            n.scope.revision++
        }

    private fun metaOf(n: Node): String? {
        val c = n.cover ?: return null
        val ref = kotlinx.serialization.json.buildJsonObject {
            put("blobId", kotlinx.serialization.json.JsonPrimitive(c.id))
            put("chunkCount", kotlinx.serialization.json.JsonPrimitive(c.frames.size))
            put("byteSize", kotlinx.serialization.json.JsonPrimitive(c.byteSize))
            put("wrappedBlobKey", kotlinx.serialization.json.JsonPrimitive(b64(c.wrapped)))
            put("ownerId", kotlinx.serialization.json.JsonPrimitive(n.id))
            put("role", kotlinx.serialization.json.JsonPrimitive("COVER"))
        }
        val meta = kotlinx.serialization.json.buildJsonObject {
            put("cover", kotlinx.serialization.json.buildJsonObject {
                put("source", kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("upload"))
                    put("ref", ref)
                })
            })
        }
        return me.raddatz.veyq.mobile.session.NodeMetaWrite.seal(suite, n.scope.id, n.id, n.key, meta)
    }

    fun trash(m: Medium) { m.trashed = true; m.node.mediaRevision++ }

    fun block(n: Node) { n.blocked = true; n.scope.revision++ }

    fun unblock(n: Node) { n.blocked = false; n.scope.revision++ }

    /** Node [n] trashed: gone from its scope's tree (a trashed node is `listTrashedNodes`', never `listNodes`'). */
    fun trashNode(n: Node) { n.scope.nodes.remove(n); n.scope.revision++ }

    /**
     * Node [n] re-keyed as `NodeRekey` does it: a fresh key, its entry and every wrap under it sealed anew — each blob's
     * `K_blob` re-wrapped, the blob, its id and its frames kept (R5) —, the tree's and the media's revisions moved.
     */
    fun rekey(n: Node) { n.key = p.randomBytes(32); n.scope.revision++; n.mediaRevision++ }

    // ---- this device

    val prefs = MemoryDeviceStore()
    val keys = SoftwareDeviceKeys(p)
    val root: String = freshTestDirectory()
    val device = ThisDevice(prefs, keys, "test device", p, offlineRoot = root).also { it.offlineFiles = files; it.offlineClock = clock }
    val record = TrustedDevice(server, "u1@a.example", userId, TrustedDevice.Method.DEVICE_KEY, "w-1", "veyq.device.1", StaySignedIn.LOGIN)
    private lateinit var sealed: Pair<ByteArray, ByteArray>

    suspend fun remember() {
        sealed = keys.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), StaySignedIn.LOGIN)
        device.remember(record)
        keys.putSecret(record.refreshAlias, "refresh-0")
    }

    /** The account resumed on this device (online, or from its store where the server is unreachable). */
    suspend fun resume(): VeyqAccount = VeyqAccount.resume(p, record, device, client())

    fun client(): (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(MockEngine { answer(it) }, block) }

    // ---- the answers

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, etag: String? = null) =
        respond(body, status, if (etag != null) headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag)) else headersOf(HttpHeaders.ContentType, "application/json"))

    private fun tenantOf(r: HttpRequestData) = tenants.firstOrNull { it.domain == r.url.host }

    private fun scopeJson(s: Scope) = """{"id":"${s.id}","kind":"PERSONAL","role":"OWNER","tenantId":"${s.tenant.id}","nameEpoch":0,"iconEpoch":0,"currentEpoch":0,"grantedEpoch":0,"wrappedGrant":"${b64(s.wrappedGrant)}","granterUserId":"$userId","grantSignature":"${b64(s.signature)}"}"""

    private fun nodeJson(n: Node) = """{"blockedByOperator":${n.blocked},"createdAt":"2026-01-01T00:00:00.000Z","placementRevision":0,"id":"${n.id}","epoch":0,"scopeEpoch":0,"kind":"${n.kind}","encryptedName":"${b64(n.encryptedName)}","slugToken":"c2x1Zw==","orderIndex":0,"wrappedContentKey":"${b64(n.wrappedKey)}","metaRevision":0,"wrappedMeta":${metaOf(n)?.let { "\"$it\"" } ?: "null"},"parentId":${n.parent?.let { "\"${it.id}\"" } ?: "null"}}"""

    private fun blobRef(b: Blob) = """"blobId":"${b.id}","chunkCount":${b.frames.size},"byteSize":${b.byteSize},"wrappedBlobKey":"${b64(b.wrapped)}"${b.meta?.let { ",\"wrappedBlobMeta\":\"${b64(it)}\"" } ?: ""}"""

    private fun mediumJson(m: Medium) = """{"blockedByOperator":${m.blocked},"mediaId":"${m.id}",${blobRef(m.original)},"updatedAt":"2026-01-01T00:00:00.000Z","derivatives":[${listOfNotNull(m.thumbnail?.let { """{"kind":"THUMBNAIL",${blobRef(it)}}""" }, m.preview?.let { """{"kind":"PREVIEW",${blobRef(it)}}""" }).joinToString(",")}]}"""

    private suspend fun MockRequestHandleScope.answer(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        requests += path
        if (!reachable) throw IllegalStateException("the network is down")
        if (r.url.host == "store.example") {
            if (cutAfterStoreGets?.let { storeGets >= it } == true) {
                reachable = false
                throw IllegalStateException("the network is down")
            }
            val (blobId, index) = path.trim('/').split('/')
            storeGets++
            val frame = blobs.getValue(blobId).frames[index.toInt()]
            storeBytes += frame.size
            return respond(frame, HttpStatusCode.OK)
        }
        val tenant = tenantOf(r)
        if (tenant != null && tenant.id in suspended && path != "/api/setup") return json("""{"code":"tenant_suspended"}""", HttpStatusCode.Forbidden)
        return when {
            path == "/api/setup" -> json("""{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""")
            path == "/api/token/refresh" -> if (refreshStatus == 200) json("""{"accessToken":"access-1","refreshToken":"refresh-1"}""") else json("{}", HttpStatusCode.fromValue(refreshStatus))
            path == "/api/account/key-material" -> json(
                """{"userId":"$userId","suiteId":"v1","wraps":[${if (serveWrap) """{"method":"DEVICE_KEY","id":"${record.wrapId}","wrappedKey":"${b64(sealed.second)}","kdfParams":"${b64(sealed.first)}"}""" else ""}],"wrappedIdentityPrivateKey":"${b64(wrappedIdentity)}"}""",
            )
            path == "/api/scopes/my-tenants" -> json(tenants.joinToString(",", "[", "]") { """{"tenantId":"${it.id}","name":"${it.id}","domain":"${it.domain}","role":"OWNER","status":"ACTIVE","iconEpoch":0}""" })
            path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
            path == "/api/session/logout" -> respond("", HttpStatusCode.NoContent)
            path == "/api/scopes" -> json(scopes.filter { it.tenant == tenant && (it.member || it.stillListed) }.joinToString(",", "[", "]") { scopeJson(it) })
            path.endsWith("/blob-downloads-batch") -> {
                val body = (r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val blobId = Regex("\"blobId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                val b = blobs.getValue(blobId)
                json("""{"results":[{"presignedGets":[${b.frames.indices.joinToString(",") { """{"index":$it,"url":"http://store.example/$blobId/$it"}""" }}]}]}""")
            }
            path.matches(Regex("/api/scopes/[^/]+/nodes")) -> {
                val s = scopes.firstOrNull { it.id == path.split('/')[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                if (!s.member) return json("""{"code":"forbidden"}""", HttpStatusCode.Forbidden)
                val tag = "\"rev-${s.revision}\""
                if (r.headers[HttpHeaders.IfNoneMatch] == tag) return respond("", HttpStatusCode.NotModified)
                json(s.nodes.joinToString(",", "[", "]") { nodeJson(it) }, etag = tag)
            }
            path.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+/media")) -> {
                val parts = path.split('/')
                val s = scopes.firstOrNull { it.id == parts[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                if (!s.member) return json("{}", HttpStatusCode.Forbidden)
                val n = s.nodes.firstOrNull { it.id == parts[5] } ?: return json("{}", HttpStatusCode.NotFound)
                if (r.headers[HttpHeaders.IfNoneMatch] == n.mediaRevision.toString()) return respond("", HttpStatusCode.NotModified)
                json("""{"media":[${n.media.filter { !it.trashed }.joinToString(",") { mediumJson(it) }}],"more":false,"mediaRevision":${n.mediaRevision},"deleteRevision":0}""")
            }
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }
}
