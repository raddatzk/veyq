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
internal class PlayedTenant(
    files: DeviceFiles = SizedVolume(),
    val clock: () -> Long = { 0L },
    /** The level this device stays signed in at ([remember]). */
    level: StaySignedIn = StaySignedIn.LOGIN,
    /** Whether this device runs scheduled syncs (a phone, `BackgroundRun`). */
    backgroundRuns: Boolean = false,
) {
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
    /** What a refresh of the session answers (401 once a logout revoked the session family). */
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
    /** The tenant's capability set as `getTenantCapabilities` answers it (the JSON array of entries); null: not served. */
    var capabilities: String? = null
    /**
     * A test's own answers, asked before the played ones: a status and a JSON body for a request it serves, null for one it
     * leaves to this server (a node's re-key, `MemoryRekeyTest`).
     */
    var route: ((HttpRequestData) -> Pair<Int, String>?)? = null
    /** Every request to a third party under `map.example` (a map service, ADR 0143 D3), as sent: its URL and headers. */
    val mapRequests = mutableListOf<HttpRequestData>()

    /** Every id the server hands out is a UUID (the seals' contexts require one); [id] makes one of a test's name. */
    private val names = HashMap<String, String>()

    fun id(name: String): String {
        val h = me.raddatz.veyq.mobile.crypto.hex(p.sha256(name.encodeToByteArray()))
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-4${h.substring(13, 16)}-8${h.substring(17, 20)}-${h.substring(20, 32)}".also { names[it] = name }
    }

    /** The test's name of id [id]. */
    fun nameOf(id: String): String = names[id] ?: id

    class Tenant(val id: String, val domain: String)
    inner class Scope(val name: String, val tenant: Tenant, val kind: String = "PERSONAL") {
        val id = id(name)
        /** The scope's one key at its current [epoch]: a new lock ([relock]) replaces it. */
        var key: ByteArray = p.randomBytes(32)
        /** The scope's current epoch, the one the account's grant carries. */
        var epoch = 0
        /** Whether a node of it is due (served as `rekeying`): set by a new lock, whose nodes are not re-keyed. */
        var rekeying = false
        /** The succession statements of every epoch after 0 (`listScopeEpochs`): epoch, public key, signature. */
        val successions = mutableListOf<Triple<Int, ByteArray, ByteArray>>()
        /** Whether the account is a member: not, and the scope is gone from its scopes and its tree answers `403`. */
        var member = true
        /** Listed in `listMyScopes` while not a member (a list that does not know yet). */
        var stillListed = false
        var revision = 1
        val nodes = mutableListOf<Node>()
        /** Its members as `listScopeMembers` lists them: user id to username (no sealed name, so the username names them). */
        val members = linkedMapOf(userId to "me")
        /** Its trash: the roots of trashed subtrees, as `listTrashedNodes` lists them ([trashNode] puts one there). */
        val trashed = mutableListOf<Node>()
        var wrappedGrant: ByteArray = suite.sealTo(Context("scope-grant", "scopeId" to id, "epoch" to 0L), kem.publicKey, key)
        var signature: ByteArray = p.ed25519Sign(identity.signSeed, suite.grantStatement(id, kind, 0, userId, myPublic, wrappedGrant))
    }

    /**
     * Scope [s] given a new lock by another device of the account, as `rotateScope` commits it (ADR 0115): a fresh key at
     * the next epoch, its succession signed by the old one, the account re-granted to itself, every scope-root entry
     * re-sealed under the new key (each root's entry is under the scope key: [Node.entryKey]) — and no node re-keyed, so
     * the scope is served as `rekeying` and the tree's revision moves.
     */
    fun relock(s: Scope) {
        val trust = me.raddatz.veyq.mobile.crypto.Trust(suite)
        val (_, oldSeed) = trust.successionKeyPair(s.key)
        val next = p.randomBytes(32)
        val epoch = s.epoch + 1
        val (nextPublic, nextSeed) = trust.successionKeyPair(next)
        nextSeed.fill(0)
        s.successions += Triple(epoch, nextPublic, p.ed25519Sign(oldSeed, trust.scopeSuccessionStatement(s.id, epoch.toLong(), nextPublic)))
        oldSeed.fill(0)
        s.key = next
        s.epoch = epoch
        s.wrappedGrant = suite.sealTo(Context("scope-grant", "scopeId" to s.id, "epoch" to epoch.toLong()), kem.publicKey, next)
        s.signature = p.ed25519Sign(identity.signSeed, suite.grantStatement(s.id, s.kind, epoch.toLong(), userId, myPublic, s.wrappedGrant))
        s.rekeying = true
        s.revision++
    }
    inner class Node(val scope: Scope, val label: String, val parent: Node?, val kind: String, val name: String, fixedId: String? = null) {
        val id = fixedId ?: id(label)
        /** The node's key: a re-key ([rekey]) replaces it, and every wrap under it is sealed anew. */
        var key: ByteArray = p.randomBytes(32)
        var blocked = false
        /** The node's cover ([cover]): its meta names it, sealed under the node's key as `NodeCover` writes it. */
        var cover: Blob? = null
        /** The node's banner ([banner]): its slot's blob and a video's poster, named by its meta as `BannerWrite` seals it. */
        var banner: Pair<Blob, Blob?>? = null
        /** Further fields of its sealed meta ([participants]: who was there, ADR 0144 decision 1). */
        var meta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
        var mediaRevision = 1
        val media = mutableListOf<Medium>()
        private val binding = arrayOf("scopeId" to scope.id, "nodeId" to id, "epoch" to 0L, "placementRevision" to 0L)
        /** A drop box's drop key seed ([dropBox]): its private half sealed under the box's key is served as `sealedDropPrivateKey`. */
        var dropSeed: ByteArray? = null
        /** A guest's identity folder ([identityFolder]): its identity and that identity's `K_up`, under which its entry lives. */
        var identity: Pair<String, ByteArray>? = null
        /** Another drop key seed the guest's `K_up` is wrapped to (a wrap that does not open under the box's key); null: the box's. */
        var wrapSeed: ByteArray? = null
        /** A Pot's revisions as `listPotRevisions` serves them, in append order ([revise]); its counters with them. */
        val revisions = mutableListOf<PotRevision>()
        var ledgerRevision = 1
        var deleteRevision = 0
        /** The status `listPotRevisions` answers instead of the ledger (a Pot no longer the account's); null: the ledger. */
        var refuseLedger: Int? = null
        /** Its application blobs as `listApplicationBlobs` serves them ([receipt]), each with the record it belongs to. */
        val appBlobs = mutableListOf<Pair<Blob, String>>()
        var blobsRevision = 1
        /** The key this node's directory entry lives under: a guest's `K_up`, else its parent's key, else the scope key. */
        val entryKey: ByteArray get() = identity?.second ?: parent?.key ?: scope.key
        val encryptedName: ByteArray get() = suite.sealField(Context("node-name", *binding), entryKey, name.encodeToByteArray())
        val wrappedKey: ByteArray get() = suite.seal(Context("content-key", *binding), entryKey, key)
        /** `K_up` sealed to the drop box's public drop key (`upload-identity-key`), as a guest's browser sealed it. */
        val wrappedIdentityKey: ByteArray? get() = identity?.let { (identityId, kUp) ->
            suite.sealTo(Context("upload-identity-key", "scopeId" to scope.id, "nodeId" to parent!!.id, "identityId" to identityId), suite.dropKeyPublic(wrapSeed ?: parent.dropSeed!!), kUp)
        }
    }
    inner class Blob(
        val label: String, val plain: List<ByteArray>, private val role: String, private val ownerId: String, private val nodeKey: () -> ByteArray,
        private val scopeId: String, type: String?, fileName: String? = null, modifiedAt: Long? = null,
    ) {
        val id = id(label)
        val key: ByteArray = p.randomBytes(32)
        val frames: List<ByteArray> = plain.mapIndexed { i, f -> suite.sealFrame(key, i, plain.size, f) }
        /** `K_blob` under its node's key as it is now: a re-key re-wraps it, the blob and its frames stay (R5). */
        val wrapped: ByteArray get() = suite.seal(Context("blob-key", "scopeId" to scopeId, "ownerId" to ownerId, "blobId" to id, "role" to role), nodeKey(), key)
        val meta: ByteArray? = type?.let { BlobMeta.seal(suite, key, it, fileName ?: "$id.bin", modifiedAt) }
        val byteSize: Long get() = frames.sumOf { it.size.toLong() }
    }
    inner class Medium(val node: Node, val label: String, val original: Blob, val thumbnail: Blob?, val preview: Blob? = null) {
        val id = id(label)
        var trashed = false
        var blocked = false
        /** Its listed tier as the web seals it (`media-meta`, a JSON text), under its node's key; null: none sealed. */
        var tier: String? = null
        /** Who added it (`user:<id>`, `share:<identityId>`); null: not listed. */
        var owner: String? = null
    }

    /** One revision of a Pot's record, sealed under the Pot's key at its address as `PotWrites` seals it (ADR 0108). */
    class PotRevision(val entryId: String, val revision: Long, val sealed: me.raddatz.veyq.mobile.expenses.SealedRevision, val tombstone: Boolean)

    /** A Pot in [scope] whose settings are euros (its sealed meta's `pot`), with no revision yet. */
    fun pot(scope: Scope, label: String, name: String = "Pot $label"): Node =
        node(scope, label, me.raddatz.veyq.mobile.expenses.PotSession.KIND, name = name).also {
            it.meta = mapOf(me.raddatz.veyq.mobile.expenses.PotMeta.KEY to me.raddatz.veyq.mobile.expenses.PotMeta(me.raddatz.veyq.mobile.expenses.Ledger.BaseCurrency("EUR", 2), emptyList()).toJson())
        }

    /** The next revision of record [entryId] of Pot [pot] appended, carrying [content]; the ledger's revision moves. */
    fun revise(pot: Node, entryId: String, content: me.raddatz.veyq.mobile.expenses.RevisionContent): PotRevision {
        val revision = (pot.revisions.filter { it.entryId == entryId }.maxOfOrNull { it.revision } ?: 0) + 1
        val sealed = me.raddatz.veyq.mobile.expenses.RevisionCodec(suite).seal(
            me.raddatz.veyq.mobile.expenses.RevisionAddress(pot.scope.id, pot.id, entryId, revision), pot.key,
            me.raddatz.veyq.mobile.expenses.RevisionBody(emptyList(), content),
        )
        return PotRevision(entryId, revision, sealed, content == me.raddatz.veyq.mobile.expenses.Deleted).also {
            pot.revisions += it
            pot.ledgerRevision++
        }
    }

    /** Record [entryId] of Pot [pot] erased (`erasePotRecord`): every revision of it gone, both counters moved. */
    fun eraseRecord(pot: Node, entryId: String) {
        pot.revisions.removeAll { it.entryId == entryId }
        pot.ledgerRevision++
        pot.deleteRevision++
    }

    /** A receipt of entry [entryId] in Pot [pot]: a one-frame blob with a marker, its type sealed; the listing's tag moves. */
    fun receipt(pot: Node, label: String, entryId: String, type: String = "image/png"): Blob =
        Blob(label, listOf("receipt $label".encodeToByteArray()), me.raddatz.veyq.mobile.expenses.Receipts.ROLE, pot.id, { pot.key }, pot.scope.id, type, "$label.png").also {
            blobs[it.id] = it
            pot.appBlobs += it to entryId
            pot.blobsRevision++
        }

    val tenants = mutableListOf(Tenant("t1", "a.example"))
    val scopes = mutableListOf<Scope>()
    private val blobs = HashMap<String, Blob>()

    /** The stored size of blob [id] (its sealed frames'). */
    fun byteSizeOf(id: String): Long = blobs.getValue(id).byteSize

    fun scope(name: String, tenant: Tenant = tenants.first(), kind: String = "PERSONAL"): Scope = Scope(name, tenant, kind).also { scopes += it }

    /** Node [n]'s participants sealed into its meta as the web writes them (`{userIds}`), the tree's revision moved. */
    fun participants(n: Node, vararg userIds: String) {
        n.meta = n.meta + ("participants" to kotlinx.serialization.json.JsonObject(mapOf("userIds" to kotlinx.serialization.json.JsonArray(userIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))))
        n.scope.revision++
    }

    fun node(scope: Scope, label: String, kind: String = "MEMORY", parent: Node? = null, name: String = "Node $label"): Node =
        Node(scope, label, parent, kind, name).also { scope.nodes += it; scope.revision++ }

    /** A node the client minted [id] for (a create the played server took): the tree lists it from now on. */
    fun adopt(scope: Scope, id: String, kind: String, parent: Node?, name: String): Node =
        Node(scope, "adopted-$id", parent, kind, name, fixedId = id).also { scope.nodes += it; scope.revision++ }

    /**
     * A medium of [node] whose original is [frames] frames, whose thumbnail (where [thumbnail]) one and whose preview (where
     * [preview]) [preview] frames, each with a marker.
     */
    fun medium(
        node: Node, name: String, frames: Int = 1, video: Boolean = false, thumbnail: Boolean = true, preview: Int = 0,
        fileName: String? = null, modifiedAt: Long? = null,
    ): Medium {
        val mediaId = id(name)
        val original = Blob("$name-o", (0 until frames).map { "original $name frame $it".encodeToByteArray() }, "ORIGINAL", mediaId, { node.key }, node.scope.id, if (video) "video/mp4" else "image/jpeg", fileName, modifiedAt)
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

    /**
     * Node [n]'s banner set: a video of [frames] frames with a one-frame poster, or (not [video]) a one-frame image, each
     * with a marker; its meta names them, the tree's revision moves.
     */
    fun banner(n: Node, label: String, video: Boolean = true, frames: Int = 2): Pair<Blob, Blob?> {
        val main = Blob(label, (0 until (if (video) frames else 1)).map { "banner $label frame $it".encodeToByteArray() }, "BANNER", n.id, { n.key }, n.scope.id, null)
        val poster = if (video) Blob("$label-poster", listOf("poster $label".encodeToByteArray()), "BANNER_POSTER", n.id, { n.key }, n.scope.id, null) else null
        blobs[main.id] = main
        poster?.let { blobs[it.id] = it }
        n.banner = main to poster
        n.scope.revision++
        return main to poster
    }

    private fun metaOf(n: Node): String? {
        if (n.cover == null && n.banner == null && n.meta.isEmpty()) return null
        fun ref(b: Blob) = me.raddatz.veyq.mobile.memories.banner.Banner.Ref(b.id, b.frames.size, b.byteSize, b64(b.wrapped))
        val banner = n.banner?.let { (main, poster) ->
            me.raddatz.veyq.mobile.memories.banner.Banner.sealed(
                if (poster != null) me.raddatz.veyq.mobile.memories.banner.Banner.Medium.VIDEO else me.raddatz.veyq.mobile.memories.banner.Banner.Medium.IMAGE,
                ref(main), poster?.let(::ref), "video/mp4", null,
            )
        }
        val c = n.cover ?: return me.raddatz.veyq.mobile.session.NodeMetaWrite.seal(suite, n.scope.id, n.id, n.key, kotlinx.serialization.json.JsonObject(n.meta + listOfNotNull(banner?.let { "banner" to it })))
        val ref = kotlinx.serialization.json.buildJsonObject {
            put("blobId", kotlinx.serialization.json.JsonPrimitive(c.id))
            put("chunkCount", kotlinx.serialization.json.JsonPrimitive(c.frames.size))
            put("byteSize", kotlinx.serialization.json.JsonPrimitive(c.byteSize))
            put("wrappedBlobKey", kotlinx.serialization.json.JsonPrimitive(b64(c.wrapped)))
            put("ownerId", kotlinx.serialization.json.JsonPrimitive(n.id))
            put("role", kotlinx.serialization.json.JsonPrimitive("COVER"))
        }
        val meta = kotlinx.serialization.json.buildJsonObject {
            n.meta.forEach { (k, v) -> put(k, v) }
            put("cover", kotlinx.serialization.json.buildJsonObject {
                put("source", kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("upload"))
                    put("ref", ref)
                })
            })
            banner?.let { put("banner", it) }
        }
        return me.raddatz.veyq.mobile.session.NodeMetaWrite.seal(suite, n.scope.id, n.id, n.key, meta)
    }

    fun trash(m: Medium) { m.trashed = true; m.node.mediaRevision++ }

    fun block(n: Node) { n.blocked = true; n.scope.revision++ }

    fun unblock(n: Node) { n.blocked = false; n.scope.revision++ }

    /** Node [n] trashed: gone from its scope's tree (a trashed node is `listTrashedNodes`', never `listNodes`'), in its trash. */
    fun trashNode(n: Node) { n.scope.nodes.remove(n); n.scope.trashed += n; n.scope.revision++ }

    /** A Depot drop box at [parent] (ADR 0080 decision 11): a node with a drop key, its private half sealed under its key. */
    fun dropBox(scope: Scope, label: String, parent: Node? = null, name: String = "Box $label"): Node =
        node(scope, label, "DEPOT_DROPBOX", parent, name).also { it.dropSeed = p.randomBytes(32) }

    /** A guest's identity folder in drop box [box]: its entry sealed under the guest's own `K_up`, wrapped to the box's drop key. */
    fun identityFolder(box: Node, label: String, name: String): Node =
        Node(box.scope, label, box, "FOLDER", name).also {
            it.identity = id("$label-identity") to p.randomBytes(32)
            box.scope.nodes += it
            box.scope.revision++
        }

    /** Every `restoreNode` request's body, as sent. */
    val restores = mutableListOf<String>()

    /**
     * Node [n] re-keyed as `NodeRekey` does it: a fresh key, its entry and every wrap under it sealed anew — each blob's
     * `K_blob` re-wrapped, the blob, its id and its frames kept (R5) —, the tree's and the media's revisions moved.
     */
    fun rekey(n: Node) { n.key = p.randomBytes(32); n.scope.revision++; n.mediaRevision++ }

    // ---- this device

    val prefs = MemoryDeviceStore()
    val keys = SoftwareDeviceKeys(p)
    val root: String = freshTestDirectory()
    val device = ThisDevice(prefs, keys, "test device", p, offlineRoot = root, backgroundRuns = backgroundRuns).also { it.offlineFiles = files; it.offlineClock = clock }
    val record = TrustedDevice(server, "u1@a.example", userId, TrustedDevice.Method.DEVICE_KEY, "w-1", "veyq.device.1", level)
    private lateinit var sealed: Pair<ByteArray, ByteArray>

    suspend fun remember() {
        sealed = keys.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), record.level!!)
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

    private fun scopeJson(s: Scope) = """{"id":"${s.id}","kind":"${s.kind}","role":"OWNER","tenantId":"${s.tenant.id}","nameEpoch":0,"iconEpoch":0,"currentEpoch":${s.epoch},"grantedEpoch":${s.epoch},"rekeying":${s.rekeying},"wrappedGrant":"${b64(s.wrappedGrant)}","granterUserId":"$userId","grantSignature":"${b64(s.signature)}"}"""

    private fun nodeJson(n: Node) = """{"blockedByOperator":${n.blocked},"createdAt":"2026-01-01T00:00:00.000Z","placementRevision":0,"id":"${n.id}","epoch":0,"scopeEpoch":0,"kind":"${n.kind}","encryptedName":"${b64(n.encryptedName)}","slugToken":"c2x1Zw==","orderIndex":0,"wrappedContentKey":"${b64(n.wrappedKey)}","metaRevision":0,"wrappedMeta":${metaOf(n)?.let { "\"$it\"" } ?: "null"},"parentId":${n.parent?.let { "\"${it.id}\"" } ?: "null"}${extrasOf(n)}}"""

    /** A drop box's sealed private drop key, a guest's identity folder's identity and its wrapped `K_up`. */
    private fun extrasOf(n: Node): String = buildString {
        n.dropSeed?.let { append(""","sealedDropPrivateKey":"${b64(suite.seal(Context("drop-private-key", "scopeId" to n.scope.id, "nodeId" to n.id), n.key, suite.serializeDropKey(it)))}"""") }
        n.identity?.let { (identityId, _) -> append(""","shareIdentityId":"$identityId","wrappedIdentityKey":"${b64(n.wrappedIdentityKey!!)}"""") }
    }

    private fun trashedJson(n: Node) = """{"blockedByOperator":false,"id":"${n.id}","parentId":${n.parent?.let { "\"${it.id}\"" } ?: "null"},"kind":"${n.kind}","encryptedName":"${b64(n.encryptedName)}","epoch":0,"scopeEpoch":0,"placementRevision":0,"deletedAt":"2026-01-01T00:00:00Z","purgeAfter":"2026-02-01T00:00:00Z","attached":false${extrasOf(n).replace(Regex(",\"sealedDropPrivateKey\":\"[^\"]*\""), "")}}"""

    private fun blobRef(b: Blob) = """"blobId":"${b.id}","chunkCount":${b.frames.size},"byteSize":${b.byteSize},"wrappedBlobKey":"${b64(b.wrapped)}"${b.meta?.let { ",\"wrappedBlobMeta\":\"${b64(it)}\"" } ?: ""}"""

    private fun mediumJson(m: Medium) = """{"blockedByOperator":${m.blocked},"mediaId":"${m.id}",${blobRef(m.original)},${listedOf(m)}"updatedAt":"2026-01-01T00:00:00.000Z","derivatives":[${listOfNotNull(m.thumbnail?.let { """{"kind":"THUMBNAIL",${blobRef(it)}}""" }, m.preview?.let { """{"kind":"PREVIEW",${blobRef(it)}}""" }).joinToString(",")}]}"""

    /** A medium's listed tier and owner as `listNodeMedia` serves them (each with its trailing comma), or nothing. */
    private fun listedOf(m: Medium): String = buildString {
        m.tier?.let { append(""""wrappedMeta":"${b64(suite.sealField(Context("media-meta", "scopeId" to m.node.scope.id, "mediaId" to m.id), m.node.key, it.encodeToByteArray()))}",""") }
        m.owner?.let { append(""""ownerKey":"$it",""") }
    }

    private suspend fun MockRequestHandleScope.answer(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        requests += path
        if (!reachable) throw IllegalStateException("the network is down")
        if (r.url.host.endsWith("map.example")) {
            mapRequests += r
            return if (path == "/reverse") json("""{"address":{"city":"Berlin","country":"Deutschland"}}""")
            else respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("image/png"), HttpHeaders.CacheControl to listOf("max-age=600")))
        }
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
        route?.invoke(r)?.let { (status, body) -> return json(body, HttpStatusCode.fromValue(status)) }
        return when {
            path == "/api/setup" -> json("""{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""")
            path == "/api/token/refresh" -> if (refreshStatus == 200) json("""{"accessToken":"access-1","refreshToken":"refresh-1"}""") else json("{}", HttpStatusCode.fromValue(refreshStatus))
            path == "/api/account/key-material" -> json(
                """{"userId":"$userId","suiteId":"v1","wraps":[${if (serveWrap) """{"method":"DEVICE_KEY","id":"${record.wrapId}","wrappedKey":"${b64(sealed.second)}","kdfParams":"${b64(sealed.first)}"}""" else ""}],"wrappedIdentityPrivateKey":"${b64(wrappedIdentity)}"}""",
            )
            path == "/api/scopes/my-tenants" -> json(tenants.joinToString(",", "[", "]") { """{"tenantId":"${it.id}","name":"${it.id}","domain":"${it.domain}","role":"OWNER","status":"ACTIVE","iconEpoch":0}""" })
            path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
            // a logout revokes the session family, as the server does (`auth.openapi.yaml` `/api/logout`): the refresh token a
            // remembered device keeps is of it, so a refresh after it is refused
            path == "/api/logout" -> { refreshStatus = 401; respond("", HttpStatusCode.NoContent) }
            path.matches(Regex("/api/tenants/[^/]+/capabilities")) ->
                capabilities?.let { json("""{"capabilities":$it,"applicationErasures":[]}""") } ?: json("{}", HttpStatusCode.NotFound)
            path == "/api/scopes" -> json(scopes.filter { it.tenant == tenant && (it.member || it.stillListed) }.joinToString(",", "[", "]") { scopeJson(it) })
            path.endsWith("/blob-downloads-batch") -> {
                val body = (r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val blobId = Regex("\"blobId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                val b = blobs.getValue(blobId)
                json("""{"results":[{"presignedGets":[${b.frames.indices.joinToString(",") { """{"index":$it,"url":"http://store.example/$blobId/$it"}""" }}]}]}""")
            }
            path.matches(Regex("/api/scopes/[^/]+/epochs")) -> {
                val s = scopes.firstOrNull { it.id == path.split('/')[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                json(s.successions.joinToString(",", "[", "]") { (e, k, sig) -> """{"epoch":$e,"successionPublicKey":"${b64(k)}","successionSignature":"${b64(sig)}"}""" })
            }
            path.matches(Regex("/api/scopes/[^/]+/members")) -> {
                val s = scopes.firstOrNull { it.id == path.split('/')[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                if (!s.member) return json("""{"code":"scope_not_found"}""", HttpStatusCode.NotFound)
                json(s.members.entries.joinToString(",", "[", "]") { (id, name) -> """{"userId":"$id","username":"$name","avatarRevision":0,"role":"MEMBER","identityPublicKey":"AAAA","keyChainHead":"AAAA","pending":false}""" })
            }
            path.matches(Regex("/api/scopes/[^/]+/nodes")) -> {
                val s = scopes.firstOrNull { it.id == path.split('/')[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                if (!s.member) return json("""{"code":"forbidden"}""", HttpStatusCode.Forbidden)
                val tag = "\"rev-${s.revision}\""
                if (r.headers[HttpHeaders.IfNoneMatch] == tag) return respond("", HttpStatusCode.NotModified)
                json(s.nodes.joinToString(",", "[", "]") { nodeJson(it) }, etag = tag)
            }
            path.matches(Regex("/api/scopes/[^/]+/trash/nodes")) -> {
                val s = scopes.firstOrNull { it.id == path.split('/')[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                json(s.trashed.joinToString(",", "[", "]") { trashedJson(it) })
            }
            path.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+/restore")) -> {
                val parts = path.split('/')
                val s = scopes.firstOrNull { it.id == parts[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                val n = s.trashed.firstOrNull { it.id == parts[5] } ?: return json("""{"code":"node_not_found"}""", HttpStatusCode.NotFound)
                restores += (r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                s.trashed.remove(n)
                s.nodes += n
                s.revision++
                respond("", HttpStatusCode.NoContent)
            }
            path.matches(Regex("/api/scopes/[^/]+/media")) -> {
                val s = scopes.firstOrNull { it.id == path.split('/')[3] && it.tenant == tenant } ?: return json("""{"code":"scope_not_found"}""", HttpStatusCode.NotFound)
                if (!s.member) return json("""{"code":"scope_not_found"}""", HttpStatusCode.NotFound)
                // the tag is a function of the tree and every node's media, as the server's
                val tag = "\"sm-${s.revision}-${s.nodes.sumOf { it.mediaRevision }}\""
                if (r.headers[HttpHeaders.IfNoneMatch] == tag) return respond("", HttpStatusCode.NotModified)
                val rows = s.nodes.flatMap { n -> n.media.filter { !it.trashed }.map { n to it } }
                json(
                    """{"media":[${rows.joinToString(",") { (n, m) -> """{"blockedByOperator":${m.blocked},"nodeId":"${n.id}","mediaId":"${m.id}",${blobRef(m.original)},"metaRevision":0}""" }}],"more":false,"nodes":[]}""",
                    etag = tag,
                )
            }
            path.matches(Regex("/api/scopes/[^/]+/pots/[^/]+/revisions")) -> {
                val parts = path.split('/')
                val s = scopes.firstOrNull { it.id == parts[3] && it.tenant == tenant } ?: return json("""{"code":"scope_not_found"}""", HttpStatusCode.NotFound)
                if (!s.member) return json("""{"code":"scope_not_found"}""", HttpStatusCode.NotFound)
                val n = s.nodes.firstOrNull { it.id == parts[5] } ?: return json("""{"code":"node_not_found"}""", HttpStatusCode.NotFound)
                n.refuseLedger?.let { return json("""{"code":"node_not_found"}""", HttpStatusCode.fromValue(it)) }
                if (r.headers[HttpHeaders.IfNoneMatch] == n.ledgerRevision.toString()) return respond("", HttpStatusCode.NotModified)
                // the cursor is a position in the append order: what is past it is the delta
                val since = r.url.parameters["since"]?.toInt() ?: 0
                val rows = n.revisions.drop(since)
                val sinceDelete = r.url.parameters["sinceDelete"]?.toInt() ?: 0
                val ids = if (sinceDelete < n.deleteRevision) n.revisions.joinToString(",", "[", "]") { """{"entryId":"${it.entryId}","revision":${it.revision}}""" } else "null"
                json(
                    """{"revisions":[${rows.joinToString(",") { """{"entryId":"${it.entryId}","revision":${it.revision},"tombstone":${it.tombstone},"erasure":false,"sealedBody":"${b64(it.sealed.sealedBody)}","wrappedRevisionKey":"${b64(it.sealed.wrappedRevisionKey)}","writer":"user:$userId","createdAt":"2026-01-01T00:00:00.000Z"}""" }}],""" +
                        """"more":false,"cursor":${if (rows.isEmpty()) "null" else "\"${n.revisions.size}\""},"ledgerRevision":${n.ledgerRevision},"deleteRevision":${n.deleteRevision},"revisionIds":$ids,"guestWriters":[]}""",
                )
            }
            path.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+/application-blobs")) -> {
                val parts = path.split('/')
                val s = scopes.firstOrNull { it.id == parts[3] && it.tenant == tenant } ?: return json("{}", HttpStatusCode.NotFound)
                if (!s.member) return json("{}", HttpStatusCode.Forbidden)
                val n = s.nodes.firstOrNull { it.id == parts[5] } ?: return json("{}", HttpStatusCode.NotFound)
                val tag = "\"ab-${n.blobsRevision}\""
                if (r.headers[HttpHeaders.IfNoneMatch] == tag) return respond("", HttpStatusCode.NotModified)
                json(
                    """{"blobs":[${n.appBlobs.joinToString(",") { (b, owner) -> """{"id":"${b.id}","ownerId":"$owner","role":"${me.raddatz.veyq.mobile.expenses.Receipts.ROLE}",${blobRef(b)},"writer":"user:$userId","createdAt":"2026-01-01T00:00:00.000Z","blockedByOperator":false}""" }}],"more":false}""",
                    etag = tag,
                )
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
