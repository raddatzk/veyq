package me.raddatz.veyq.mobile.session.rotation

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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesRekeyRow
import me.raddatz.veyq.mobile.api.memories.models.OwnItemFavoritesEntry
import me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest
import me.raddatz.veyq.mobile.api.memories.models.ScopeItemFavoritesRekeyPage
import me.raddatz.veyq.mobile.api.memories.models.ScopeItemFavoritesRekeyStage
import me.raddatz.veyq.mobile.api.models.IdentityPinEntry
import me.raddatz.veyq.mobile.api.models.IdentityPinEntryPage
import me.raddatz.veyq.mobile.api.models.IdentityPins
import me.raddatz.veyq.mobile.api.models.IdentityPinsWrite
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.api.models.KeyChainStep
import me.raddatz.veyq.mobile.api.models.OpenInvite
import me.raddatz.veyq.mobile.api.models.RotateScopeRequest
import me.raddatz.veyq.mobile.api.models.RotationPlanScope
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeRekeyIcon
import me.raddatz.veyq.mobile.api.models.ScopeRekeyMember
import me.raddatz.veyq.mobile.api.models.ScopeRekeyParts
import me.raddatz.veyq.mobile.api.models.ScopeRekeyRootNode
import me.raddatz.veyq.mobile.api.models.ScopeRekeyStage
import me.raddatz.veyq.mobile.api.models.ScopeKind
import me.raddatz.veyq.mobile.api.models.ScopeRotated
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.ValueKeys
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice

/**
 * A server one account's rotation finish is played against: a tenant `T` whose TENANT scope is `waiting` at epoch 3, the
 * plan row carrying the account's self-grant of `E_3`; members — the account (an owner), B (an owner whose key chain is
 * served: anchored on first use) and C (a member with no join record: an anomaly) —; two open invites, one signed by B,
 * one not; the scope-level parts (a trashed root entry, B's name wrap under `E_3`, C's under a key nobody holds, an icon)
 * and one item mark. It records what the client sent, and answers `rotateScope` from [rotateAnswers] (success after).
 */
internal class PlayedRotation(val p: Primitives) {
    val suite = V1(p)
    private val trust = Trust(suite)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun uuid(): String {
        val b = p.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** One person: an identity, a proof key, and their one-step key chain as served. */
    inner class Person {
        val userId = uuid()
        val identity = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        private val proof = p.randomBytes(32)
        val bundle: ByteArray = suite.identityPublic(identity).serialize()
        val kem get() = suite.xwingKey(identity.kemSeed)
        fun chain(): KeyChain {
            val keys = Trust.KeyChainKeys(bundle, p.ed25519Public(proof))
            val head = trust.keyChainHead(trust.keyChainGenesisStatement(userId, keys))
            return KeyChain(userId, b64(head), listOf(KeyChainStep(0, b64(keys.identityPublicKey), b64(keys.secretKeyProofPublicKey), "2026-11-08T00:00:00Z")))
        }
        fun member(role: ScopeMember.Role) = ScopeMember(
            userId = userId, username = "x", avatarRevision = 0, role = role, identityPublicKey = b64(bundle),
            keyChainHead = chain().head, pending = false,
        )
    }

    val me = Person()
    val b = Person()
    val c = Person()
    val tenantId = uuid()
    val epoch = 3
    val k3: ByteArray = p.randomBytes(32)
    private val values = ValueKeys(suite)

    // ---- the scope-level parts under E_3 ----
    val rootNode = uuid()
    val nodeKey: ByteArray = p.randomBytes(32)
    val bNameKey: ByteArray = p.randomBytes(32)
    val cNameKey: ByteArray = p.randomBytes(32)
    val iconKey: ByteArray = p.randomBytes(32)
    val markKey: ByteArray = p.randomBytes(32)
    val markId = uuid()
    fun nameCtx(userId: String) = Context("member-name", "scopeId" to tenantId, "userId" to userId)
    val iconCtx = Context("space-icon", "scopeId" to tenantId, "iconEpoch" to 1L)
    val markCtx get() = Context("item-favorites", "scopeId" to tenantId, "nodeId" to rootNode)
    fun rootAt(revision: Long) = Context("node-name", "scopeId" to tenantId, "nodeId" to rootNode, "epoch" to epoch.toLong(), "placementRevision" to revision)
    fun keyAt(revision: Long) = Context("content-key", "scopeId" to tenantId, "nodeId" to rootNode, "epoch" to epoch.toLong(), "placementRevision" to revision)

    private val parts = ScopeRekeyParts(
        currentEpoch = epoch,
        rootNodes = listOf(
            ScopeRekeyRootNode(
                rootNode, trashed = true, placementRevision = 2, scopeEpoch = epoch,
                encryptedName = b64(suite.sealField(rootAt(2), k3, utf8("Trip"))), wrappedContentKey = b64(suite.seal(keyAt(2), k3, nodeKey)),
            ),
        ),
        shares = emptyList(),
        members = listOf(
            ScopeRekeyMember(b.userId, wrappedNameValueKey = b64(values.wrap(k3, epoch.toLong(), nameCtx(b.userId), bNameKey))),
            // a wrap under a key nobody holds: staged as one nobody opens, never dropped
            ScopeRekeyMember(c.userId, wrappedNameValueKey = b64(values.wrap(p.randomBytes(32), epoch.toLong(), nameCtx(c.userId), cNameKey))),
        ),
        verifications = emptyList(),
        inviteLabels = emptyList(),
        more = false,
        icon = ScopeRekeyIcon(1, b64(values.wrap(k3, epoch.toLong(), iconCtx, iconKey))),
    )

    // ---- the invites ----
    val expiresAt = "2027-01-01T00:00:00Z"
    val vouched = Person()
    val vouchedId = uuid()
    val unvouchedId = uuid()
    private fun invite(id: String, key: Person, signer: Person, forge: Boolean): OpenInvite {
        val statement = JoinRecords(suite).inviteKeyStatement(tenantId, id, key.bundle, 1798761600L)
        val sig = p.ed25519Sign(signer.identity.signSeed, statement).also { if (forge) it[0] = (it[0] + 1).toByte() }
        return OpenInvite(id, b64(key.bundle), b64(sig), signer.userId, b64(signer.bundle), signer.chain().head, expiresAt)
    }
    private val invites = listOf(invite(vouchedId, vouched, b, forge = false), invite(unvouchedId, Person(), b, forge = true))

    // ---- what the server holds and answers ----
    var states: List<RotationScopeState> = emptyList()
    lateinit var planRow: RotationPlanScope
    val members = listOf(me.member(ScopeMember.Role.OWNER), b.member(ScopeMember.Role.OWNER), c.member(ScopeMember.Role.MEMBER))
    /** What `rotateScope` answers, in turn (status, error code); a success once it is empty. */
    val rotateAnswers = ArrayDeque<Pair<Int, String>>()
    /** Released before the first `listRotationScopeStates` is answered, where set. */
    var statesGate: CompletableDeferred<Unit>? = null

    /** The account's own item marks as `listOwnItemFavorites` lists them, and its scopes (`listMyScopes`, none: 404). */
    var ownMarks: List<OwnItemFavoritesEntry> = emptyList()
    var myScopes: List<ScopeSummary>? = null
    val markPuts = mutableListOf<Triple<String, String, PutItemFavoritesRequest>>()

    /** The TENANT scope (or another of [kind] at [id]) as `listMyScopes` serves it: the account's self-grant of `E_3`. */
    fun tenantScope(account: VeyqAccount, id: String = tenantId, kind: ScopeKind = ScopeKind.TENANT): ScopeSummary {
        val g = account.sealSelfGrant(k3, id, kind.value, epoch.toLong())
        return ScopeSummary(
            id = id, kind = kind, role = ScopeSummary.Role.OWNER, tenantId = tenantId, nameEpoch = 0, iconEpoch = 0,
            currentEpoch = epoch, grantedEpoch = epoch, wrappedGrant = g.wrappedGrant, granterUserId = me.userId,
            grantSignature = g.grantSignature, granterIdentityPublicKey = b64(me.bundle),
        )
    }

    // ---- what the client sent ----
    val calls = mutableListOf<String>()
    val rotations = mutableListOf<RotateScopeRequest>()
    val stages = mutableListOf<Pair<String, ScopeRekeyStage>>()
    val markStages = mutableListOf<Pair<String, ScopeItemFavoritesRekeyStage>>()
    fun count(call: String) = calls.count { it == call }

    // the pin store, as a server keeps it
    private var pinRevision = 0L
    private var pinHead: String? = null
    private val pinEntries = LinkedHashMap<String, String>()

    /** The waiting TENANT scope's plan row: the account's own grant of `E_3` (its self-grant as the publish wrote it). */
    fun waiting(account: VeyqAccount) {
        val g = account.sealSelfGrant(k3, tenantId, "TENANT", epoch.toLong())
        planRow = RotationPlanScope(
            scopeId = tenantId, kind = RotationPlanScope.Kind.TENANT, tenantId = tenantId, currentEpoch = epoch,
            wrappedGrant = g.wrappedGrant, grantedEpoch = epoch, granterUserId = me.userId, grantSignature = g.grantSignature,
            granterIdentityPublicKey = b64(me.bundle),
        )
        states = listOf(RotationScopeState(tenantId, RotationScopeState.State.waiting))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun <T> MockRequestHandleScope.ok(serializer: KSerializer<T>, value: T) = json(json.encodeToString(serializer, value))

    private fun MockRequestHandleScope.refused(status: Int, code: String) = json("""{"code":"$code","message":"$code"}""", HttpStatusCode.fromValue(status))

    private fun bodyOf(r: HttpRequestData): String = (r.body as? TextContent)?.text ?: (r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private suspend fun MockRequestHandleScope.answer(r: HttpRequestData): HttpResponseData {
        val path = r.url.encodedPath
        val scope = "/api/scopes/$tenantId"
        return when {
            r.method == HttpMethod.Get && path == "/api/setup" -> json(
                """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""",
            )
            r.method == HttpMethod.Post && path == "/api/token/refresh" -> json("""{"accessToken":"access-1","refreshToken":"refresh-1"}""")
            r.method == HttpMethod.Get && path == "/api/account/key-material" -> json(material)
            r.method == HttpMethod.Get && path == "/api/scopes/my-tenants" -> json(
                """[{"tenantId":"$tenantId","name":"A","domain":"a.example","role":"OWNER","status":"ACTIVE","iconEpoch":0}]""",
            )
            r.method == HttpMethod.Get && path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
            r.method == HttpMethod.Get && path == "/api/account/rotation/scopes" -> {
                calls += "states"
                statesGate?.let { statesGate = null; it.await() }
                ok(ListSerializer(RotationScopeState.serializer()), states)
            }
            r.method == HttpMethod.Get && path == "/api/account/rotation/plan" -> {
                calls += "plan"
                ok(ListSerializer(RotationPlanScope.serializer()), listOf(planRow))
            }
            r.method == HttpMethod.Get && path == "$scope/members" -> {
                calls += "members"
                ok(ListSerializer(ScopeMember.serializer()), members)
            }
            r.method == HttpMethod.Get && path == "$scope/open-invites" -> ok(ListSerializer(OpenInvite.serializer()), invites)
            r.method == HttpMethod.Get && path == "/api/people/${b.userId}/key-chain" -> ok(KeyChain.serializer(), b.chain())
            r.method == HttpMethod.Get && path == "/api/account/identity-pins" -> ok(IdentityPins.serializer(), IdentityPins(pinRevision, pinHead))
            r.method == HttpMethod.Get && path == "/api/account/identity-pins/entries" ->
                ok(IdentityPinEntryPage.serializer(), IdentityPinEntryPage(pinEntries.entries.sortedBy { it.key }.map { IdentityPinEntry(it.key, it.value) }, false))
            r.method == HttpMethod.Put && path == "/api/account/identity-pins" -> {
                val write = json.decodeFromString(IdentityPinsWrite.serializer(), bodyOf(r))
                if (write.revision != pinRevision) return refused(409, "stale_revision")
                if (write.replaceAll == true) pinEntries.clear()
                write.put.forEach { pinEntries[it.pinId] = it.entry }
                write.remove.forEach { pinEntries.remove(it) }
                pinRevision++
                pinHead = write.head
                respond("", HttpStatusCode.NoContent)
            }
            r.method == HttpMethod.Get && path == "$scope/rekey/scope/parts" -> ok(ScopeRekeyParts.serializer(), parts)
            r.method == HttpMethod.Post && path.startsWith("$scope/rekey/scope/") && path.endsWith("/parts") -> {
                stages += path.removePrefix("$scope/rekey/scope/").removeSuffix("/parts") to json.decodeFromString(ScopeRekeyStage.serializer(), bodyOf(r))
                respond("", HttpStatusCode.NoContent)
            }
            r.method == HttpMethod.Get && path == "$scope/item-favorites-rekey" -> ok(
                ScopeItemFavoritesRekeyPage.serializer(),
                ScopeItemFavoritesRekeyPage(epoch, listOf(ItemFavoritesRekeyRow(markId, rootNode, b64(values.wrap(k3, epoch.toLong(), markCtx, markKey)))), false),
            )
            r.method == HttpMethod.Post && path.startsWith("$scope/item-favorites-rekey/") -> {
                markStages += path.removePrefix("$scope/item-favorites-rekey/") to json.decodeFromString(ScopeItemFavoritesRekeyStage.serializer(), bodyOf(r))
                respond("", HttpStatusCode.NoContent)
            }
            r.method == HttpMethod.Post && path == "$scope/epochs" -> {
                calls += "rotate"
                val request = json.decodeFromString(RotateScopeRequest.serializer(), bodyOf(r))
                rotations += request
                val refusal = rotateAnswers.removeFirstOrNull()
                if (refusal != null) return refused(refusal.first, refusal.second)
                states = listOf(RotationScopeState(tenantId, RotationScopeState.State.secured))
                ok(ScopeRotated.serializer(), ScopeRotated(request.epoch))
            }
            r.method == HttpMethod.Get && path == "/api/item-favorites" -> ok(ListSerializer(OwnItemFavoritesEntry.serializer()), ownMarks)
            r.method == HttpMethod.Get && path == "/api/scopes" && myScopes != null -> ok(ListSerializer(ScopeSummary.serializer()), myScopes!!)
            r.method == HttpMethod.Put && path.startsWith("/api/scopes/") && path.endsWith("/item-favorites") -> {
                val (scopeId, nodeId) = path.removePrefix("/api/scopes/").removeSuffix("/item-favorites").split("/nodes/")
                markPuts += Triple(scopeId, nodeId, json.decodeFromString(PutItemFavoritesRequest.serializer(), bodyOf(r)))
                respond("", HttpStatusCode.NoContent)
            }
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    // ---- the account: remembered on this device, resumed over this server ----
    private val masterKey = p.randomBytes(32)
    private val keys = SoftwareDeviceKeys(p)
    private var material = ""
    val device = testDevice(p, keys)

    suspend fun resume(): VeyqAccount {
        val (iv, sealed) = keys.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), StaySignedIn.PRESENCE)
        val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(me.identity.kemSeed), lp(me.identity.signSeed)))
        material = """{"userId":"${me.userId}","suiteId":"v1","wraps":[{"method":"DEVICE_KEY","id":"w1","wrappedKey":"${b64(sealed)}","kdfParams":"${b64(iv)}"}],"wrappedIdentityPrivateKey":"${b64(identity)}"}"""
        val record = TrustedDevice("http://a.example", "p@a.example", me.userId, TrustedDevice.Method.DEVICE_KEY, "w1", "veyq.device.test", StaySignedIn.PRESENCE)
        device.remember(record)
        keys.putSecret(record.refreshAlias, "refresh-0")
        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(MockEngine { answer(it) }, block) }
        return VeyqAccount.resume(p, record, device, client)
    }
}
