package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.AccountApi
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
import me.raddatz.veyq.mobile.api.models.LoginChallengeRequest
import me.raddatz.veyq.mobile.api.models.LoginVerifyRequest
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.RefreshRequest
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.XWingKey
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.Library
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.SpaceRef

/** Why a sign-in did not complete, in words a member can act on (the platform localizes by [reason]). */
class SignInFailure(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason { UNREACHABLE, INCOMPATIBLE, CREDENTIALS, SECRET_KEY, NO_TENANT, PROTOCOL }
}

/**
 * A signed-in, unlocked member of one tenant (ADR 0123): the session tokens, the Master Key and the identity, and the
 * scope keys of every scope whose grant verified. Built only by [signIn]; nothing in it is written to disk.
 *
 * The flow is the web client's (`src/web/src/features/account/login`, `unlock`), call for call: `/api/setup`, the login
 * challenge and its Ed25519 answer from the Argon2id auth seed, the PASSWORD wrap opened with password and Secret Key,
 * the identity, then each scope grant — verified before it is opened. A grant signed by another member is refused for
 * now: the app does not yet anchor another member's key (the web's key-chain pin, ADR 0111), and refusing is the safe
 * side of that gap.
 */
class VeyqSession private constructor(
    private val suite: V1,
    private val http: HttpClient,
    private val base: String,
    private val tokens: Tokens,
    val userId: String,
    private val masterKey: ByteArray,
    private val identity: IdentityPrivate,
    private val identityKem: XWingKey,
    val tenant: MyTenant?,
    private val scopes: List<OpenScope>,
    private val applicationsOff: Set<String>,
) : Library {
    private class OpenScope(val summary: ScopeSummary, val key: ByteArray, val name: String)

    private class Tokens(var access: String, var refresh: String?) {
        val lock = Mutex()
    }

    private fun <T : ApiClient> api(make: (String, HttpClient) -> T): T = make(base, http)

    fun applicationsOff(): Set<String> = applicationsOff

    override suspend fun spaces(): List<SpaceRef> = scopes.map { SpaceRef(it.summary.id, it.summary.kind.value, it.name) }

    private var tree: Map<String, List<OpenNode>>? = null

    private suspend fun trees(): Map<String, List<OpenNode>> = tree ?: scopes.associate { s ->
        val rows = call { api(::ContentApi).listNodes(s.summary.id) }
        s.summary.id to Tree.open(suite, s.summary.id, s.key, rows)
    }.also { tree = it }

    override suspend fun objects(): List<ObjectRef> = trees().flatMap { (_, nodes) -> nodes.mapNotNull { it.asObject() } }

    override suspend fun favorites(): List<ObjectRef> {
        val all = objects().associateBy { it.scopeId to it.nodeId }
        return scopes.flatMap { s ->
            val f = call { api(::FavoritesApi).getFavorites(s.summary.id) }
            val ct = f.ciphertext ?: return@flatMap emptyList()
            val ids = try {
                Json.parseToJsonElement(suite.open(Context("favorites", "scopeId" to s.summary.id), masterKey, unb64(ct)).decodeToString())
                    .jsonArray.map { it.jsonPrimitive.content }
            } catch (e: OpenRefused) {
                emptyList()
            }
            ids.mapNotNull { all[s.summary.id to it] }
        }
    }

    suspend fun signOut() {
        runCatching { call { api(::SessionApi).logout() } }
        masterKey.fill(0)
        http.close()
    }

    /** One API call with the current access token; a 401 refreshes once (single-flight, ADR 0004) and retries. */
    private suspend fun <T : Any> call(f: suspend () -> HttpResponse<T>): T {
        val first = f()
        if (first.success) return first.body()
        if (first.status != 401 || tokens.refresh == null) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "HTTP ${first.status}")
        val seen = tokens.access
        tokens.lock.withLock {
            if (tokens.access == seen) {
                val r = api(::TokenApi).refreshSession(RefreshRequest(tokens.refresh!!))
                if (!r.success) throw SignInFailure(SignInFailure.Reason.CREDENTIALS, "refresh ${r.status}")
                val pair = r.body()
                tokens.access = pair.accessToken
                tokens.refresh = pair.refreshToken
            }
        }
        val second = f()
        if (!second.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "HTTP ${second.status}")
        return second.body()
    }

    companion object {
        /** The platform major this app speaks (ADR 0085): the one hard gate before sign-in. */
        const val MAJOR: Int = PLATFORM_MAJOR

        /**
         * Signs in and unlocks. [server] is the tenant's own origin (`https://family.example`), since the server picks
         * the tenant from the Host it is called by. [client] builds the platform's HTTP client from a configuration.
         */
        suspend fun signIn(
            primitives: Primitives,
            server: String,
            email: String,
            password: String,
            secretKey: String,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqSession {
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
                val myPublic = mine.serialize()

                // The tenant this origin serves, and its applications that are switched off (ADR 0088).
                val scopesApi = ScopesApi(base, http)
                val tenants = scopesApi.listMyTenants().let { if (it.success) it.body() else emptyList() }
                val summaries = scopesApi.listMyScopes().let {
                    if (it.status == 404) throw SignInFailure(SignInFailure.Reason.NO_TENANT)
                    if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "scopes ${it.status}")
                    it.body()
                }
                val tenant = tenants.firstOrNull { t -> summaries.any { it.tenantId == t.tenantId } }
                val off = tenant?.let { t ->
                    TenantsApi(base, http).getTenantCapabilities(t.tenantId).takeIf { it.success }?.body()?.capabilities?.map { it.id }?.toSet()
                }?.let { on -> KNOWN_APPLICATIONS - on } ?: emptySet()

                val open = summaries.filter { it.deletedAt == null }.mapNotNull { s ->
                    openGrant(suite, primitives, s, material.userId, myPublic, mine.sign, kem)?.let { key -> OpenScope(s, key, scopeName(s, tenant)) }
                }
                return VeyqSession(suite, http, base, tokens, material.userId, masterKey, identity, kem, tenant, open, off)
            } catch (e: Throwable) {
                http.close()
                throw if (e is SignInFailure) e else SignInFailure(SignInFailure.Reason.PROTOCOL, e.message)
            }
        }

        private val KNOWN_APPLICATIONS = setOf("memories", "depot", "expenses")

        private fun scopeName(s: ScopeSummary, tenant: MyTenant?): String = when (s.kind.value) {
            "TENANT" -> tenant?.name ?: "Tenant"
            "PERSONAL" -> "Personal"
            else -> "Group"
        }

        /**
         * Verifies a scope grant, then opens it (ADR 0028 decision 6). Only a self-signed grant is accepted: a grant from
         * another member needs that member's key anchored (the key-chain pin), which the app does not do yet. A stale
         * grant (below the scope's current epoch) is refused, as the web refuses it.
         */
        private fun openGrant(
            suite: V1, p: Primitives, s: ScopeSummary, me: String, myPublic: ByteArray, mySignPublic: ByteArray, kem: XWingKey,
        ): ByteArray? {
            if (s.grantedEpoch < s.currentEpoch) return null
            if (s.granterUserId != me) return null
            val signature = s.grantSignature ?: return null
            val wrapped = unb64(s.wrappedGrant)
            val statement = suite.grantStatement(s.id, s.kind.value, s.grantedEpoch.toLong(), me, myPublic, wrapped)
            if (!p.ed25519Verify(mySignPublic, statement, unb64(signature))) return null
            return try {
                suite.openAs(Context("scope-grant", "scopeId" to s.id, "epoch" to s.grantedEpoch.toLong()), kem, wrapped)
            } catch (e: OpenRefused) {
                null
            }
        }
    }
}
