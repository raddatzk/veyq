package me.raddatz.veyq.mobile.sharing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One link's instance as a guest meets it, played (ADR 0150): its `/api/setup`, the resolve (locked behind [password]
 * where one is set), the terms, a write link's naming, and whatever a test adds to [routes] — each request recorded whole
 * in [requests] (method, URL, headers, body), so a test can assert what the server saw. The share is real ciphertext:
 * `K_share` wraps the root's key and seals its name and note, as a curator's client seals them.
 */
class PlayedShare(
    val p: Primitives,
    val role: String = "READ",
    val rootKind: String = "FOLDER",
    /** The link's password; a test changes it as its curator would (the guest's token is then refused). */
    var password: String? = null,
    /** The note's plaintext JSON (either shape of `shareNote.ts`); null: none. */
    val note: String? = null,
    val termsRevision: Int = 0,
    val major: Int = VeyqAccount.MAJOR,
    val capabilities: List<String> = listOf(ShareInstance.PUBLIC_SHARES),
    val origin: String = "https://photos.example.org",
) {
    val suite = V1(p)
    val kShare: ByteArray = p.randomBytes(32)
    val shareId: String = b64url(p.randomBytes(32))
    val rootKey: ByteArray = p.randomBytes(32)
    val scopeId = uuid()
    val rootId = uuid()
    val rootName = "Summer"
    val link: String get() = "$origin/s/$shareId#k=${b64url(kShare)}"
    val pwSalt: ByteArray = p.randomBytes(16)
    var setupAnswers = true

    /** The tree below the root, as `ShareNode` JSON objects (the root is added by the resolve). */
    val children = mutableListOf<JsonObject>()

    /** Every request the guest sent, in order. */
    // Copy-on-write: the client records from its own threads (a re-read after a write settles on Dispatchers.Default)
    // while a test searches the list; a plain ArrayList threw ConcurrentModificationException there (GuestMoveTest).
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private val recorded = kotlin.concurrent.atomics.AtomicReference<List<Recorded>>(emptyList())

    /** Every request the server saw so far, in order: a snapshot, safe to search while the client still sends. */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    val requests: List<Recorded> get() = recorded.load()

    /** Further routes a test plays; the first non-null answer wins. */
    val routes = mutableListOf<suspend MockRequestHandleScope.(HttpRequestData, Recorded) -> HttpResponseData?>()

    val identities = mutableListOf<String>()

    class Recorded(val method: String, val url: String, val headers: String, val body: ByteArray) {
        val path: String get() = url.substringAfter("://").substringAfter('/').let { "/" + it.substringBefore('?') }
        fun header(name: String): String? = headers.lineSequence().firstOrNull { it.startsWith("$name: ", ignoreCase = true) }?.substringAfter(": ")
        fun json(): JsonObject = Json.parseToJsonElement(body.decodeToString()) as JsonObject
        override fun toString() = "$method $url"
    }

    fun uuid(): String {
        val h = hex(p.randomBytes(16))
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-4${h.substring(13, 16)}-a${h.substring(17, 20)}-${h.substring(20)}"
    }

    private fun MockRequestHandleScope.answer(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val tokens = HashMap<String, String>()

    /** The password token the server expects (`Argon2id(password, pwSalt)`), for the [password] set now. */
    val pwToken: String? get() = password?.let { pw -> tokens.getOrPut(pw) { b64(suite.sharePasswordToken(pw, pwSalt)) } }

    /** How many resolves were answered `304` (the guest's `If-None-Match` named what it serves). */
    var notModified = 0
        private set

    /** The link revoked (expired, its space going): its resolve is the non-enumerable `404`. */
    var revoked = false

    /** Whether the resolve names an `ETag` (ADR 0081) and answers a matching `If-None-Match` with `304`; off: a server that names none. */
    var tags = true

    private var sealed: Triple<String, String, String>? = null

    /**
     * The unlocked resolve's tag and body: sealed once per content (a seal's nonce differs each time, the digest must not),
     * the tag a digest of what it serves — as the server's is, never a revision.
     */
    private fun served(): Pair<String, String> {
        val content = "$role|$rootKind|$note|${password != null}|$children|$extraResolve"
        val s = sealed?.takeIf { it.first == content } ?: Triple(content, "\"${hex(p.sha256(utf8(content))).take(32)}\"", resolved().toString()).also { sealed = it }
        return s.second to s.third
    }

    val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { r ->
        val rec = Recorded(
            r.method.value, r.url.toString(),
            r.headers.entries().joinToString("\n") { (k, v) -> "$k: ${v.joinToString(",")}" },
            runCatching { r.body.toByteArray() }.getOrDefault(ByteArray(0)),
        )
        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        while (true) { val now = recorded.load(); if (recorded.compareAndSet(now, now + rec)) break }
        val path = r.url.encodedPath
        val base = "/api/shares/$shareId"
        routes.firstNotNullOfOrNull { it(r, rec) } ?: when {
            path == "/api/setup" -> if (!setupAnswers) throw IllegalStateException("no route") else answer(
                """{"setupRequired":false,"version":{"major":$major,"build":1},"capabilities":[${capabilities.joinToString(",") { """{"id":"$it"}""" }}],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""",
            )
            path == base -> {
                val given = rec.header("X-Share-Password")
                when {
                    revoked -> answer("""{"code":"not_found"}""", HttpStatusCode.NotFound)
                    password != null && given == null -> answer("""{"role":"$role","passwordRequired":true,"nodes":[],"pwSalt":"${b64(pwSalt)}"}""")
                    password != null && given != pwToken -> answer("""{"code":"share_password_incorrect"}""", HttpStatusCode.Forbidden)
                    else -> {
                        val (tag, body) = served()
                        when {
                            !tags -> answer(body)
                            rec.header("If-None-Match") == tag -> {
                                notModified++
                                respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, tag))
                            }
                            else -> respond(body, HttpStatusCode.OK, io.ktor.http.Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                                append(HttpHeaders.ETag, tag)
                            })
                        }
                    }
                }
            }
            path == "$base/terms" -> answer(
                """{"tenantId":"t1","termsRevision":$termsRevision,"operatorSection":{"revision":3,"text":{"en":"Operator section"},"record":{"en":"The record"}},"terms":{"en":"Space terms","de":"Bedingungen"}}""",
            )
            path == "$base/identities" -> {
                if (rec.header("X-Share-Write") != b64(suite.shareWriteToken(kShare, shareId))) answer("""{"code":"share_write_refused"}""", HttpStatusCode.Forbidden)
                else {
                    val id = rec.json()["identityId"]!!.let { (it as JsonPrimitive).content }
                    identities += id
                    answer("""{"identityId":"$id","contributorToken":"tok-$id"}""")
                }
            }
            else -> answer("""{"code":"not_found"}""", HttpStatusCode.NotFound)
        }
    }

    /** The unlocked resolve: the root (its name sealed under `K_share`, its key wrapped under it) and [children]. */
    fun resolved(): JsonObject = buildJsonObject {
        put("role", role)
        put("passwordRequired", password != null)
        put("scopeId", scopeId)
        put("nodeId", rootId)
        put("rootKind", rootKind)
        put("rootEpoch", 1)
        put("rootScopeEpoch", 0)
        put("sealedName", b64(suite.sealField(Context("share-memory-name"), kShare, utf8(rootName))))
        if (role != "WRITE_ONLY") put("wrap", b64(suite.seal(Context("share-key"), kShare, rootKey)))
        note?.let { put("sealedNote", b64(suite.sealField(Context("share-note"), kShare, utf8(it)))) }
        extraResolve.forEach { (k, v) -> put(k, v) }
        put("nodes", JsonArray(listOf(node(rootId, null, rootKind, null, null)) + children))
    }

    /** Further fields of the resolve a test serves (a drop box's sealed public key, …). */
    val extraResolve = mutableMapOf<String, JsonElement>()

    /** A `ShareNode` row: the root's when [parentKey] is null, else its name and key sealed under [parentKey]. */
    fun node(id: String, parentId: String?, kind: String, name: String?, key: ByteArray?, parentKey: ByteArray? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("epoch", 1)
        put("scopeEpoch", 0)
        put("blockedByOperator", false)
        put("parentId", parentId?.let { JsonPrimitive(it) } ?: JsonNull)
        if (parentKey != null && name != null && key != null) {
            val binding = arrayOf<Pair<String, Any?>>("scopeId" to scopeId, "nodeId" to id, "epoch" to 0L, "placementRevision" to 1L)
            put("placementRevision", 1)
            put("encryptedName", b64(suite.sealField(Context("node-name", *binding), parentKey, utf8(name))))
            put("wrappedContentKey", b64(suite.seal(Context("content-key", *binding), parentKey, key)))
        }
    }

    /** A child [kind] named [name] under [parentId] (whose key is [parentKey]); its key. */
    fun add(id: String, parentId: String, parentKey: ByteArray, kind: String, name: String): ByteArray {
        val key = p.randomBytes(32)
        children += node(id, parentId, kind, name, key, parentKey)
        return key
    }

    /**
     * The guest's client of this instance; on [dispatcher] where given (a test's own, so a poll's requests run in the test's
     * virtual time rather than on the engine's threads).
     */
    fun client(dispatcher: kotlinx.coroutines.CoroutineDispatcher? = null): GuestClient = GuestClient.of(p, origin) { block ->
        HttpClient(MockEngine(io.ktor.client.engine.mock.MockEngineConfig().apply {
            addHandler(handler)
            dispatcher?.let { this.dispatcher = it }
        }), block)
    }

    /**
     * No request held `K_share` (ADR 0150 decision 3): its path, query, headers and body searched for the key as base64url,
     * standard base64 (padded and not), hex, and each as a URL decodes it.
     */
    fun assertNoKeyInAnyRequest() {
        assertTrue(requests.isNotEmpty(), "no request was recorded")
        val canaries = listOf(b64url(kShare), b64(kShare), b64(kShare).trimEnd('='), hex(kShare), hex(kShare).uppercase())
        for (r in requests) {
            val texts = listOf(r.url, r.url.decodeURLPart(), r.headers, r.body.decodeToString(), runCatching { r.body.decodeToString().decodeURLPart() }.getOrDefault(""))
            for (c in canaries) for (t in texts) if (t.contains(c)) fail("K_share reached the server in $r")
            // the key's own bytes in a binary body
            if (r.body.size >= 32 && r.body.asList().windowed(32).any { it.toByteArray().contentEquals(kShare) }) fail("K_share's bytes in the body of $r")
        }
    }
}
