package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.AccountApi
import me.raddatz.veyq.mobile.api.apis.AccountIdentityPinsApi
import me.raddatz.veyq.mobile.api.apis.KeyChainApi
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.apis.FavoritesApi
import me.raddatz.veyq.mobile.api.apis.LoginApi
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.apis.SessionApi
import me.raddatz.veyq.mobile.api.apis.SetupApi
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.apis.TokenApi
import me.raddatz.veyq.mobile.api.infrastructure.ApiClient
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.api.models.IdentityPinEntryPage
import me.raddatz.veyq.mobile.api.models.IdentityPins
import me.raddatz.veyq.mobile.api.models.IdentityPinsWrite
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.api.models.LoginChallengeRequest
import me.raddatz.veyq.mobile.api.models.LoginVerifyRequest
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.api.models.RefreshRequest
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.XWingKey
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Library
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef

/** Why a sign-in did not complete, in words a member can act on (the platform localizes by [reason]). */
class SignInFailure(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason { UNREACHABLE, INCOMPATIBLE, CREDENTIALS, SECRET_KEY, NO_TENANT, PROTOCOL }
}

/**
 * A signed-in, unlocked account on one server (ADR 0123): the session tokens, the Master Key, the identity, and the
 * tenants the account belongs to there. A session is the account's, not a tenant's (ADR 0004), so one account opens
 * every tenant of its server ([open]); another server is another account. Built only by [signIn]; nothing in it is
 * written to disk.
 *
 * The flow is the web client's (`src/web/src/features/account/login`, `unlock`), call for call: `/api/setup`, the login
 * challenge and its Ed25519 answer from the Argon2id auth seed, the PASSWORD wrap opened with password and Secret Key,
 * the identity.
 */
class VeyqAccount private constructor(
    internal val suite: V1,
    internal val primitives: Primitives,
    internal val http: HttpClient,
    /** The origin the account signed in at: its scheme and port are every tenant's on this server. */
    val server: String,
    private val tokens: Tokens,
    val userId: String,
    internal val masterKey: ByteArray,
    private val identity: IdentityPrivate,
    internal val kem: XWingKey,
    internal val myPublic: ByteArray,
    internal val mySignPublic: ByteArray,
    override val tenants: List<TenantRef>,
    deviceStore: DeviceStore,
) : Connection {
    internal class Tokens(var access: String, var refresh: String?) {
        val lock = Mutex()
    }

    private val trust = Trust(suite)
    private val marks = TrustMarks(deviceStore, primitives, masterKey)

    /** The server calls of the key check, on the account's own origin (the pin store and key chains are the account's). */
    private val trustApi = object : TrustApi {
        override suspend fun pinHead(): IdentityPins = call { AccountIdentityPinsApi(server, http).getIdentityPins() }
        override suspend fun pinEntries(after: String?, limit: Int): IdentityPinEntryPage =
            call { AccountIdentityPinsApi(server, http).listIdentityPinEntries(after, limit) }
        override suspend fun writePins(write: IdentityPinsWrite): Boolean {
            val r = respond { AccountIdentityPinsApi(server, http).writeIdentityPins(write) }
            if (r.success) return true
            if (r.status == 409 && errorCode(r) == "stale_revision") return false
            throw SignInFailure(SignInFailure.Reason.PROTOCOL, "identity-pins write ${r.status}")
        }
        override suspend fun keyChain(userId: String): KeyChain? {
            val r = respond { KeyChainApi(server, http).getKeyChain(userId) }
            if (r.success) return r.body()
            if (r.status == 404) return null
            throw SignInFailure(SignInFailure.Reason.PROTOCOL, "key-chain ${r.status}")
        }
    }

    private val pins = PinStore(suite, trust, trustApi, marks, masterKey)

    @OptIn(kotlin.time.ExperimentalTime::class)
    private val anchoring = Anchoring(suite, trust, trustApi, pins) { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    private fun grants(base: String) = GrantOpener(suite, trust, primitives, userId, myPublic, mySignPublic, kem, marks, anchoring) { scopeId ->
        val r = respond { ScopesApi(base, http).listScopeEpochs(scopeId) }
        when {
            r.success -> r.body()
            r.status == 404 || r.status == 403 -> null
            else -> throw SignInFailure(SignInFailure.Reason.PROTOCOL, "scope epochs ${r.status}")
        }
    }

    /** One account per user per server: signing in to the same one twice is the same connection. */
    override val key: String get() = "${Url(server).host}#$userId"

    /** The server's name as the switcher shows it: its host, without a scheme or a port. */
    override val label: String get() = Url(server).host

    /** The tenant the account opens on: the one whose domain the sign-in went to, else its first. */
    val defaultTenant: TenantRef? get() = tenants.firstOrNull { it.domain.equals(Url(server).host, ignoreCase = true) } ?: tenants.firstOrNull()

    /** A tenant's own origin: the server picks the tenant from the Host it is called by, so each tenant is its domain. */
    fun originOf(tenant: TenantRef): String {
        val u = Url(server)
        val port = if (u.port != u.protocol.defaultPort) ":${u.port}" else ""
        return "${u.protocol.name}://${tenant.domain}$port"
    }

    /**
     * Opens one tenant: its scopes, each grant verified before it is opened ([GrantOpener]), and its switched-off
     * applications. The TENANT scope opens first: its member list is what a group's granter is judged against
     * ([TenantView]). A scope that stays closed is listed in [TenantSession.closed] with its reason.
     */
    suspend fun open(tenant: TenantRef): TenantSession {
        val base = originOf(tenant)
        val summaries = call { ScopesApi(base, http).listMyScopes() }.filter { it.deletedAt == null }
        val on = runCatching { call { TenantsApi(base, http).getTenantCapabilities(tenant.tenantId) }.capabilities.map { it.id }.toSet() }.getOrNull()
        val off = on?.let { KNOWN_APPLICATIONS - it } ?: emptySet()
        val grants = grants(base)
        val open = mutableListOf<TenantSession.OpenScope>()
        val closed = mutableMapOf<String, GrantOutcome.Reason>()
        suspend fun openOne(s: ScopeSummary, view: TenantView?) {
            when (val o = grants.open(s, view)) {
                is GrantOutcome.Opened -> open += TenantSession.OpenScope(s, o.key, scopeName(s, tenant))
                is GrantOutcome.Refused -> closed[s.id] = o.reason
            }
        }
        val (tenantScopes, others) = summaries.partition { it.kind.value == "TENANT" }
        tenantScopes.forEach { openOne(it, null) }
        val tenantScope = open.firstOrNull { it.summary.kind.value == "TENANT" && it.summary.id == tenant.tenantId }
        val members = tenantScope?.let {
            val r = respond { ScopesApi(base, http).listScopeMembers(tenant.tenantId) }
            if (r.success) r.body() else null
        }
        val view = if (tenantScope == null || members == null) TenantView.unavailable(tenant.tenantId, suite)
        else TenantView.of(tenant.tenantId, members, tenantScope.key, tenantScope.summary.grantedEpoch.toLong(), suite, anchoring)
        others.forEach { openOne(it, view) }
        return TenantSession(this, tenant, base, open, off, closed)
    }

    /** A grant of a scope key to this account itself, as a scope's birth posts it (ADR 0028 decision 6), all base64. */
    class SelfGrant(val wrappedGrant: String, val grantSignature: String, val recipientIdentityDigest: String)

    /**
     * Seals [scopeKey] to this account's own identity at ([scopeId], [epoch]) and signs the grant statement with its
     * signing key: the grant a member posts for a scope it creates (the web's `sealGrant` to itself).
     */
    fun sealSelfGrant(scopeKey: ByteArray, scopeId: String, scopeKind: String, epoch: Long): SelfGrant {
        val wrapped = suite.sealTo(Context("scope-grant", "scopeId" to scopeId, "epoch" to epoch), kem.publicKey, scopeKey)
        val statement = suite.grantStatement(scopeId, scopeKind, epoch, userId, myPublic, wrapped)
        return SelfGrant(b64(wrapped), b64(primitives.ed25519Sign(identity.signSeed, statement)), b64(primitives.sha256(myPublic)))
    }

    suspend fun signOut() {
        runCatching { call { SessionApi(server, http).logout() } }
        masterKey.fill(0)
        http.close()
    }

    /** One API call with the current access token; a 401 refreshes once (single-flight, ADR 0004) and retries. */
    internal suspend fun <T : Any> call(f: suspend () -> HttpResponse<T>): T {
        val r = respond(f)
        if (!r.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "HTTP ${r.status}")
        return r.body()
    }

    /** [call]'s refresh, the response handed back whatever its status, for a caller that tells statuses apart. */
    internal suspend fun <T : Any> respond(f: suspend () -> HttpResponse<T>): HttpResponse<T> {
        val first = f()
        if (first.status != 401 || tokens.refresh == null) return first
        val seen = tokens.access
        tokens.lock.withLock {
            if (tokens.access == seen) {
                val r = TokenApi(server, http).refreshSession(RefreshRequest(tokens.refresh!!))
                if (!r.success) throw SignInFailure(SignInFailure.Reason.CREDENTIALS, "refresh ${r.status}")
                val pair = r.body()
                tokens.access = pair.accessToken
                tokens.refresh = pair.refreshToken
            }
        }
        return f()
    }

    /** The `code` of an error response's body (`ErrorResponse`), or null. */
    private suspend fun errorCode(r: HttpResponse<*>): String? = runCatching {
        (Json.parseToJsonElement(r.response.bodyAsText()) as? JsonObject)?.get("code")?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        /** The platform major this app speaks (ADR 0085): the one hard gate before sign-in. */
        const val MAJOR: Int = PLATFORM_MAJOR

        private val KNOWN_APPLICATIONS = setOf("memories", "depot", "expenses")

        private fun scopeName(s: ScopeSummary, tenant: TenantRef): String = when (s.kind.value) {
            "TENANT" -> tenant.name
            "PERSONAL" -> "Personal"
            else -> "Group"
        }

        /**
         * Signs in and unlocks. [server] is an origin of the server (`https://family.example`); any tenant's domain on
         * it serves sign-in. [client] builds the platform's HTTP client from a configuration.
         */
        suspend fun signIn(
            primitives: Primitives,
            server: String,
            email: String,
            password: String,
            secretKey: String,
            deviceStore: DeviceStore,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            val suite = V1(primitives)
            val base = server.trimEnd('/')
            val tokens = Tokens(access = "", refresh = null)
            val bearer = createClientPlugin("VeyqBearer") {
                onRequest { request, _ -> if (tokens.access.isNotEmpty()) request.headers[HttpHeaders.Authorization] = "Bearer ${tokens.access}" }
            }
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val http = client {
                expectSuccess = false
                install(ContentNegotiation) { json(json) }
                install(bearer)
            }
            try {
                val setup = try {
                    SetupApi(base, http).getSetupStatus()
                } catch (e: Exception) {
                    throw SignInFailure(SignInFailure.Reason.UNREACHABLE, e.message)
                }
                if (!setup.success) throw SignInFailure(SignInFailure.Reason.UNREACHABLE, "setup ${setup.status}")
                val status = setup.body()
                if (status.version.major != MAJOR) throw SignInFailure(SignInFailure.Reason.INCOMPATIBLE, "server major ${status.version.major}, app $MAJOR")
                if ("v1" !in status.suites) throw SignInFailure(SignInFailure.Reason.INCOMPATIBLE, "no v1 suite")

                // Login (ADR 0003): the Ed25519 key of Argon2id(password, authSalt) answers the server's nonce.
                val login = LoginApi(base, http)
                val challenge = login.requestLoginChallenge(LoginChallengeRequest(email.trim())).let {
                    if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "challenge ${it.status}")
                    it.body()
                }
                val seed = suite.authSeed(password, suite.authSalt(unb64(challenge.instanceSalt), email))
                val signature = primitives.ed25519Sign(seed, unb64(challenge.nonce))
                seed.fill(0)
                val session = login.verifyLogin(
                    LoginVerifyRequest(email = email.trim(), nonce = challenge.nonce, signature = me.raddatz.veyq.mobile.crypto.b64(signature)),
                )
                if (session.status == 401) throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
                if (!session.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "verify ${session.status}")
                session.body().let { tokens.access = it.accessToken; tokens.refresh = it.refreshToken }

                // Unlock: the PASSWORD wrap under Argon2id(lp(secretKey) ‖ lp(password), salt).
                val material = AccountApi(base, http).getAccountKeyMaterial().let {
                    if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "key-material ${it.status}")
                    it.body()
                }
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.PASSWORD }
                    ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no password wrap")
                val sk = SecretKeyText.read(primitives, secretKey) ?: throw SignInFailure(SignInFailure.Reason.SECRET_KEY)
                val (params, salt) = suite.decodeKdfParams(unb64(wrap.kdfParams ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no kdfParams")))
                val kek = suite.kekPasswordSecret(sk, password, salt, params)
                sk.fill(0)
                val masterKey = try {
                    suite.open(Context("master-key"), kek, unb64(wrap.wrappedKey))
                } catch (e: OpenRefused) {
                    throw SignInFailure(SignInFailure.Reason.SECRET_KEY)
                } finally {
                    kek.fill(0)
                }
                val identity = suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), masterKey, unb64(material.wrappedIdentityPrivateKey)))
                val kem = suite.xwingKey(identity.kemSeed)
                val mine = suite.identityPublic(identity)

                // Every tenant the account belongs to on this server (answered whatever Host it is asked at).
                val tenants = ScopesApi(base, http).listMyTenants().let {
                    if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "my-tenants ${it.status}")
                    it.body()
                }.map { TenantRef(it.tenantId, it.name, it.domain) }
                if (tenants.isEmpty()) throw SignInFailure(SignInFailure.Reason.NO_TENANT)
                return VeyqAccount(suite, primitives, http, base, tokens, material.userId, masterKey, identity, kem, mine.serialize(), mine.sign, tenants, deviceStore)
            } catch (e: Throwable) {
                http.close()
                throw if (e is SignInFailure) e else SignInFailure(SignInFailure.Reason.PROTOCOL, e.message)
            }
        }
    }
}

/** One tenant of an account, opened: what the shell lists (ADR 0122). */
class TenantSession internal constructor(
    val account: VeyqAccount,
    val tenant: TenantRef,
    private val base: String,
    private val scopes: List<OpenScope>,
    private val applicationsOff: Set<String>,
    /** The scopes this tenant serves the account that stay closed, by id, and why (a grant refused or awaiting a key). */
    val closed: Map<String, GrantOutcome.Reason> = emptyMap(),
) : Library {
    internal class OpenScope(val summary: ScopeSummary, val key: ByteArray, val name: String)

    private fun <T : ApiClient> api(make: (String, HttpClient) -> T): T = make(base, account.http)

    fun applicationsOff(): Set<String> = applicationsOff

    override suspend fun spaces(): List<SpaceRef> = scopes.map { SpaceRef(it.summary.id, it.summary.kind.value, it.name) }

    private var tree: Map<String, List<OpenNode>>? = null

    private suspend fun trees(): Map<String, List<OpenNode>> = tree ?: scopes.associate { s ->
        val rows = account.call { api(::ContentApi).listNodes(s.summary.id) }
        s.summary.id to Tree.open(account.suite, s.summary.id, s.key, rows)
    }.also { tree = it }

    override suspend fun nodes(): List<NodeRef> = trees().flatMap { (_, nodes) -> nodes.map { it.asNode() } }

    override suspend fun objects(): List<ObjectRef> = trees().flatMap { (_, nodes) -> nodes.mapNotNull { it.asObject() } }

    override suspend fun favorites(): List<ObjectRef> {
        val all = objects().associateBy { it.scopeId to it.nodeId }
        return scopes.flatMap { s ->
            val f = account.call { api(::FavoritesApi).getFavorites(s.summary.id) }
            val ct = f.ciphertext ?: return@flatMap emptyList()
            val ids = try {
                Json.parseToJsonElement(account.suite.open(Context("favorites", "scopeId" to s.summary.id), account.masterKey, unb64(ct)).decodeToString())
                    .jsonArray.map { it.jsonPrimitive.content }
            } catch (e: OpenRefused) {
                emptyList()
            }
            ids.mapNotNull { all[s.summary.id to it] }
        }
    }
}
