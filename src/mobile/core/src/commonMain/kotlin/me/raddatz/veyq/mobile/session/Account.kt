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
import me.raddatz.veyq.mobile.api.apis.AccountDeviceRegisterApi
import me.raddatz.veyq.mobile.api.apis.AccountWrapsApi
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
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.discard
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.AddWrapRequest
import me.raddatz.veyq.mobile.api.models.DeviceRegister
import me.raddatz.veyq.mobile.api.models.DeviceRegisterUpdate
import me.raddatz.veyq.mobile.api.models.IdentityPinEntryPage
import me.raddatz.veyq.mobile.api.models.SessionResponse
import me.raddatz.veyq.mobile.api.models.IdentityPins
import me.raddatz.veyq.mobile.api.models.IdentityPinsWrite
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.api.models.LoginChallengeRequest
import me.raddatz.veyq.mobile.api.models.LoginVerifyRequest
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.api.models.RefreshRequest
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.JoinRecords
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
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Library
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef

/** Why a sign-in did not complete, in words a member can act on (the platform localizes by [reason]). */
class SignInFailure(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason {
        UNREACHABLE, INCOMPATIBLE, CREDENTIALS, SECRET_KEY, NO_TENANT, PROTOCOL,
        /** This device's trust in the account is gone (its wrap not served, its key lost): sign in with the Secret Key. */
        DEVICE_NOT_TRUSTED,
        /** The person did not authenticate for the unlock key. */
        DEVICE_AUTH_CANCELLED,
        /** A remembered session the server ended (a revoke, a password change): sign in again. */
        SESSION_ENDED,
    }
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
    /**
     * A client for a request to anyone but this server — an external service the tenant names (the day's exchange
     * rates): no bearer, no cookie, no header of the app's own. [http] puts this account's bearer on every request it
     * sends, so a request to a third party never goes through it.
     */
    internal val outside: HttpClient,
    /** The origin the account signed in at: its scheme and port are every tenant's on this server. */
    val server: String,
    private val tokens: Tokens,
    val userId: String,
    /** The email the account signed in with: what a trusted device's record names it by. */
    val email: String,
    internal val masterKey: ByteArray,
    private val identity: IdentityPrivate,
    internal val kem: XWingKey,
    internal val myPublic: ByteArray,
    internal val mySignPublic: ByteArray,
    override val tenants: List<TenantRef>,
    private val device: ThisDevice,
) : Connection {
    internal class Tokens(var access: String, var refresh: String?) {
        val lock = Mutex()
    }

    private val trust = Trust(suite)
    private val marks = TrustMarks(device.store, primitives, masterKey)

    /** This device's own store: what an application keeps per device and not per account (the day's exchange rates). */
    internal val deviceStore: DeviceStore get() = device.store

    /** A presigned `PUT` of [bytes] to the object store through the [outside] client: exactly `Content-Type:
     *  application/octet-stream` (it is part of the signature) and no bearer; the status, or 0 when the store was not
     *  reached. The answer's body is read to its end. */
    internal suspend fun putToStore(url: String, bytes: ByteArray): Int = runCatching {
        val r = outside.put(url) {
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        r.bodyAsChannel().discard()
        r.status.value
    }.getOrDefault(0)

    /** A presigned `GET` from the object store through the [outside] client: the status and, on a 2xx, the bytes; status
     *  0 when the store was not reached. */
    internal suspend fun getFromStore(url: String): Pair<Int, ByteArray?> = runCatching {
        val r = outside.get(url)
        r.status.value to (if (r.status.value in 200..299) r.readRawBytes() else null)
    }.getOrDefault(0 to null)

    /** The body of a plain `GET` of [url] through the [outside] client (no bearer, no cookie), or null for anything but
     *  a 2xx answer or a failure to reach it. */
    internal suspend fun fetchOutside(url: String): String? = runCatching {
        val r = outside.get(url)
        if (r.status.value in 200..299) r.bodyAsText() else null
    }.getOrNull()

    /** How this device trusts the account, if it does: what the next start unlocks with. */
    var trusted: TrustedDevice? = device.trusted("${Url(server).host}#$userId")
        private set

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
        val capabilities = runCatching { call { TenantsApi(base, http).getTenantCapabilities(tenant.tenantId) }.capabilities }.getOrNull()
        val on = capabilities?.map { it.id }?.toSet()
        // The external services' addresses the tenant's set names (ADR 0088 decision 7): none when the set did not read.
        val services = capabilities.orEmpty().mapNotNull { c -> c.serviceUrl?.let { c.id to it } }.toMap()
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
        val people = if (tenantScope == null || members == null) emptyMap()
        else People.of(JoinRecords(suite), tenant.tenantId, tenantScope.key, tenantScope.summary.grantedEpoch.toLong(), members)
        return TenantSession(this, tenant, base, open, off, closed, people, services, ShareLinks.Rules.of(capabilities))
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

    /**
     * Signs out of this server. A remembered account (DEVICE_KEY) is forgotten first: its wrap deleted on the server, its
     * refresh token and record here, since staying signed in is what the person leaves. A password-trusted account
     * (DEVICE) stays trusted, as on the web (`uc.account.sign-out`): the next sign-in needs only the password.
     */
    suspend fun signOut() {
        trusted?.takeIf { it.method == TrustedDevice.Method.DEVICE_KEY }?.let { forget(it) }
        runCatching { call { SessionApi(server, http).logout() } }
        masterKey.fill(0)
        http.close()
        outside.close()
    }

    /** What enrolling this device came to: password-trusted, remembered, or not offered by this server (switched off). */
    enum class TrustOutcome { TRUSTED, REMEMBERED, NOT_OFFERED }

    /**
     * Trusts this device with the account (ADR 0005, ADR 0123 decision 8), replacing how it trusted it before (one device
     * wrap per account per device): [stayingSignedIn] enrols a DEVICE_KEY wrap under the unlock key, which asks the person
     * to authenticate, and keeps the refresh token; otherwise a DEVICE wrap under Argon2id(device factor, [password]), so
     * the next start needs the password and no Secret Key. Only after the new wrap is recorded is the old one deleted.
     */
    suspend fun trustThisDevice(password: String?, stayingSignedIn: Boolean): TrustOutcome {
        val previous = trusted
        val alias = device.newAlias()
        val record = if (stayingSignedIn) {
            val refresh = tokens.refresh ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no refresh token to keep")
            val (iv, sealed) = device.keys.sealUnlockable(masterKey, DEVICE_KEY_AAD)
            val id = addWrap(AddWrapRequest.Method.DEVICE_KEY, sealed, iv) ?: return TrustOutcome.NOT_OFFERED
            TrustedDevice(server, email, userId, TrustedDevice.Method.DEVICE_KEY, id, alias).also { device.keys.putSecret(it.refreshAlias, refresh) }
        } else {
            requireNotNull(password) { "a password-trusted device needs the password" }
            device.keys.createBindKey(alias)
            val factor = device.keys.bindHmac(alias, DEVICE_BIND) ?: throw IllegalStateException("bind key not created")
            val salt = primitives.randomBytes(16)
            val kek = suite.kekDevicePassword(factor, password, salt)
            factor.fill(0)
            val wrapped = try { suite.seal(Context("master-key"), kek, masterKey) } finally { kek.fill(0) }
            val id = addWrap(AddWrapRequest.Method.DEVICE, wrapped, suite.encodeKdfParams(V1.ARGON, salt))
            if (id == null) {
                device.keys.deleteBindKey(alias)
                return TrustOutcome.NOT_OFFERED
            }
            TrustedDevice(server, email, userId, TrustedDevice.Method.DEVICE, id, alias)
        }
        device.remember(record)
        trusted = record
        previous?.let { drop(it) }
        runCatching { register().remember(device.id, device.label, null, record.wrapId, now()) }
        return if (stayingSignedIn) TrustOutcome.REMEMBERED else TrustOutcome.TRUSTED
    }

    /** Ends this device's trust in the account: its wrap deleted on the server, its keys and record here. */
    suspend fun forgetThisDevice() {
        trusted?.let { forget(it) }
    }

    private suspend fun forget(record: TrustedDevice) {
        drop(record)
        device.forget(record.key)
        trusted = null
        if (device.trusted().none { it.method == TrustedDevice.Method.DEVICE_KEY }) device.keys.deleteUnlockKey()
    }

    /** The wrap deleted on the server (best effort: a wrap left behind opens only with this device's key, now gone) and
     *  this device's key and token for it. */
    private suspend fun drop(record: TrustedDevice) {
        runCatching { respond { AccountWrapsApi(server, http).deleteAccountWrap(record.wrapId) } }
        device.keys.deleteBindKey(record.alias)
        device.keys.deleteSecret(record.refreshAlias)
    }

    /** `POST /api/account/wraps`: the new wrap's id, or null while the server switched this kind of trust off. */
    private suspend fun addWrap(method: AddWrapRequest.Method, wrappedKey: ByteArray, kdfParams: ByteArray): String? {
        val r = respond { AccountWrapsApi(server, http).addAccountWrap(AddWrapRequest(method, b64(wrappedKey), b64(kdfParams))) }
        if (r.success) return r.body().id
        if (r.status == 422 && errorCode(r) == "capability_disabled") return null
        throw SignInFailure(SignInFailure.Reason.PROTOCOL, "add wrap ${r.status}")
    }

    private fun register() = DeviceRegisterStore(suite, object : RegisterApi {
        override suspend fun get(): DeviceRegister = call { AccountDeviceRegisterApi(server, http).getDeviceRegister() }
        override suspend fun put(update: DeviceRegisterUpdate): Boolean {
            val r = respond { AccountDeviceRegisterApi(server, http).putDeviceRegister(update) }
            if (r.success) return true
            if (r.status == 409 && errorCode(r) == "stale_revision") return false
            throw SignInFailure(SignInFailure.Reason.PROTOCOL, "device register ${r.status}")
        }
    }, marks, masterKey)

    /** Write moment 1 of the device register: this device, the session family its login created, the wrap it holds.
     *  Never on the unlock path: a failure leaves the device unnamed in the sessions list, nothing else. */
    internal suspend fun recordThisDevice(familyId: String?) {
        runCatching { register().remember(device.id, device.label, familyId, trusted?.wrapId, now()) }
    }

    /** The account's device register as this device opens it (for a check of what [recordThisDevice] wrote). */
    internal suspend fun readRegister(): DeviceRegisterStore.Plain = register().read()

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

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
                val kept = trusted
                if (kept?.method == TrustedDevice.Method.DEVICE_KEY && pair.refreshToken != null) {
                    device.keys.putSecret(kept.refreshAlias, pair.refreshToken)
                }
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

        /** The DEVICE factor's message (`deviceKey.ts`: HMAC-SHA256(device key, "veyq/v1/device-bind")). */
        private val DEVICE_BIND = utf8("veyq/v1/device-bind")

        /** The DEVICE_KEY wrap's associated data, the web's (`deviceKeyWrap.ts`); only this device ever opens it. */
        private val DEVICE_KEY_AAD = utf8("veyq/v1/device-key-wrap")

        /** An HTTP client with the bearer of [tokens], and the server checked for its major and suite. */
        private class Connected(val suite: V1, val base: String, val tokens: Tokens, val http: HttpClient, val outside: HttpClient)

        private suspend fun connect(
            primitives: Primitives,
            server: String,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): Connected {
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
            val c = Connected(V1(primitives), server.trimEnd('/'), tokens, http, client { expectSuccess = false })
            try {
                val setup = try {
                    SetupApi(c.base, http).getSetupStatus()
                } catch (e: Exception) {
                    throw SignInFailure(SignInFailure.Reason.UNREACHABLE, e.message)
                }
                if (!setup.success) throw SignInFailure(SignInFailure.Reason.UNREACHABLE, "setup ${setup.status}")
                val status = setup.body()
                if (!ClientWindow.speaks(status.version.major)) throw SignInFailure(SignInFailure.Reason.INCOMPATIBLE, "server major ${status.version.major}, app $MAJOR")
                if ("v1" !in status.suites) throw SignInFailure(SignInFailure.Reason.INCOMPATIBLE, "no v1 suite")
                return c
            } catch (e: Throwable) {
                http.close()
                c.outside.close()
                throw e
            }
        }

        /** Login (ADR 0003): the Ed25519 key of Argon2id(password, authSalt) answers the server's nonce. The refresh token
         *  comes in the body (no `sessionScope`): the app keeps it itself, never in a cookie. */
        private suspend fun login(primitives: Primitives, c: Connected, email: String, password: String): SessionResponse {
            val login = LoginApi(c.base, c.http)
            val challenge = login.requestLoginChallenge(LoginChallengeRequest(email.trim())).let {
                if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "challenge ${it.status}")
                it.body()
            }
            val seed = c.suite.authSeed(password, c.suite.authSalt(unb64(challenge.instanceSalt), email))
            val signature = primitives.ed25519Sign(seed, unb64(challenge.nonce))
            seed.fill(0)
            val session = login.verifyLogin(LoginVerifyRequest(email = email.trim(), nonce = challenge.nonce, signature = b64(signature)))
            if (session.status == 401) throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
            if (!session.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "verify ${session.status}")
            return session.body().also { c.tokens.access = it.accessToken; c.tokens.refresh = it.refreshToken }
        }

        private suspend fun keyMaterial(c: Connected): AccountKeyMaterial =
            AccountApi(c.base, c.http).getAccountKeyMaterial().let {
                if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "key-material ${it.status}")
                it.body()
            }

        /** The identity under the opened Master Key, the account's tenants, and write moment 1 of the device register. */
        private suspend fun finish(
            c: Connected,
            primitives: Primitives,
            email: String,
            material: AccountKeyMaterial,
            masterKey: ByteArray,
            device: ThisDevice,
            familyId: String?,
        ): VeyqAccount {
            val suite = c.suite
            val identity = suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), masterKey, unb64(material.wrappedIdentityPrivateKey)))
            val kem = suite.xwingKey(identity.kemSeed)
            val mine = suite.identityPublic(identity)
            // Every tenant the account belongs to on this server (answered whatever Host it is asked at).
            val tenants = ScopesApi(c.base, c.http).listMyTenants().let {
                if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "my-tenants ${it.status}")
                it.body()
            }.map { TenantRef(it.tenantId, it.name, it.domain) }
            if (tenants.isEmpty()) throw SignInFailure(SignInFailure.Reason.NO_TENANT)
            val account = VeyqAccount(
                suite, primitives, c.http, c.outside, c.base, c.tokens, material.userId, email.trim(), masterKey, identity, kem,
                mine.serialize(), mine.sign, tenants, device,
            )
            account.trusted?.let { device.touch(it.key) }
            account.recordThisDevice(familyId)
            return account
        }

        /** Every failure of a sign-in as a [SignInFailure], the HTTP client closed. */
        private suspend fun <T> guarded(c: Connected, f: suspend () -> T): T = try {
            f()
        } catch (e: Throwable) {
            c.http.close()
            c.outside.close()
            throw if (e is SignInFailure) e else SignInFailure(SignInFailure.Reason.PROTOCOL, e.message)
        }

        /**
         * Signs in and unlocks with the password and the Secret Key: the PASSWORD wrap under Argon2id(lp(secretKey) ‖
         * lp(password), salt). [server] is an origin of the server (`https://family.example`); any tenant's domain on it
         * serves sign-in. [client] builds the platform's HTTP client from a configuration.
         */
        suspend fun signIn(
            primitives: Primitives,
            server: String,
            email: String,
            password: String,
            secretKey: String,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            val c = connect(primitives, server, client)
            return guarded(c) {
                val session = login(primitives, c, email, password)
                val material = keyMaterial(c)
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.PASSWORD }
                    ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no password wrap")
                val sk = SecretKeyText.read(primitives, secretKey) ?: throw SignInFailure(SignInFailure.Reason.SECRET_KEY)
                val (params, salt) = c.suite.decodeKdfParams(unb64(wrap.kdfParams ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no kdfParams")))
                val kek = c.suite.kekPasswordSecret(sk, password, salt, params)
                sk.fill(0)
                val masterKey = try {
                    c.suite.open(Context("master-key"), kek, unb64(wrap.wrappedKey))
                } catch (e: OpenRefused) {
                    throw SignInFailure(SignInFailure.Reason.SECRET_KEY)
                } finally {
                    kek.fill(0)
                }
                finish(c, primitives, email, material, masterKey, device, session.family.id)
            }
        }

        /**
         * Signs in on a device that trusts the account with the password alone (the DEVICE wrap, ADR 0005): the login,
         * then the wrap this device enrolled, found by its id, under Argon2id(lp(device factor) ‖ lp(password)). Fails with
         * DEVICE_NOT_TRUSTED when the server does not serve that wrap or this device lost its key: the person then signs
         * in with the Secret Key. The record is kept on a wrap the server withholds (a server must not be able to make a
         * device forget its trust by one answer).
         */
        suspend fun unlockOnDevice(
            primitives: Primitives,
            record: TrustedDevice,
            password: String,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            require(record.method == TrustedDevice.Method.DEVICE) { "not a password-trusted record" }
            val c = connect(primitives, record.server, client)
            return guarded(c) {
                val session = login(primitives, c, record.email, password)
                val material = keyMaterial(c)
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.DEVICE && it.id == record.wrapId }
                    ?: throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap not served")
                val factor = device.keys.bindHmac(record.alias, DEVICE_BIND) ?: run {
                    device.forget(record.key)
                    throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "bind key gone")
                }
                val masterKey = try {
                    val (params, salt) = c.suite.decodeKdfParams(unb64(wrap.kdfParams ?: throw OpenRefused("no kdfParams")))
                    val kek = c.suite.kekDevicePassword(factor, password, salt, params)
                    try {
                        c.suite.open(Context("master-key"), kek, unb64(wrap.wrappedKey))
                    } finally {
                        kek.fill(0)
                    }
                } catch (e: Exception) {
                    throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap does not open")
                } finally {
                    factor.fill(0)
                }
                finish(c, primitives, record.email, material, masterKey, device, session.family.id)
            }
        }

        /**
         * Resumes an account the person chose to stay signed in to (the DEVICE_KEY wrap): its kept refresh token renews the
         * session, the wrap this device enrolled opens under the unlock key once the person authenticated. A refresh the
         * server refuses (4xx) ends the remembered session here: the token and the record go, the person signs in again;
         * a server that cannot be reached keeps both (UNREACHABLE).
         */
        suspend fun resume(
            primitives: Primitives,
            record: TrustedDevice,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            require(record.method == TrustedDevice.Method.DEVICE_KEY) { "not a remembered record" }
            val c = connect(primitives, record.server, client)
            return guarded(c) {
                val token = device.keys.secret(record.refreshAlias) ?: run {
                    device.forget(record.key)
                    throw SignInFailure(SignInFailure.Reason.SESSION_ENDED, "no refresh token kept")
                }
                val r = try {
                    TokenApi(c.base, c.http).refreshSession(RefreshRequest(token))
                } catch (e: Exception) {
                    throw SignInFailure(SignInFailure.Reason.UNREACHABLE, e.message)
                }
                if (r.status in 400..499) {
                    device.keys.deleteSecret(record.refreshAlias)
                    device.forget(record.key)
                    throw SignInFailure(SignInFailure.Reason.SESSION_ENDED, "refresh ${r.status}")
                }
                if (!r.success) throw SignInFailure(SignInFailure.Reason.UNREACHABLE, "refresh ${r.status}")
                val pair = r.body()
                c.tokens.access = pair.accessToken
                c.tokens.refresh = pair.refreshToken
                pair.refreshToken?.let { device.keys.putSecret(record.refreshAlias, it) }
                val material = keyMaterial(c)
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.DEVICE_KEY && it.id == record.wrapId }
                    ?: throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap not served")
                val masterKey = try {
                    device.keys.openUnlockable(unb64(wrap.kdfParams ?: throw OpenRefused("no iv")), unb64(wrap.wrappedKey), DEVICE_KEY_AAD)
                } catch (e: DeviceAuthFailed) {
                    throw SignInFailure(if (e.cancelled) SignInFailure.Reason.DEVICE_AUTH_CANCELLED else SignInFailure.Reason.DEVICE_NOT_TRUSTED, e.message)
                } catch (e: OpenRefused) {
                    throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap does not open")
                }
                finish(c, primitives, record.email, material, masterKey, device, null)
            }
        }
    }
}

/** One tenant of an account, opened: what the shell lists (ADR 0122). */
class TenantSession internal constructor(
    val account: VeyqAccount,
    val tenant: TenantRef,
    internal val base: String,
    private val scopes: List<OpenScope>,
    private val applicationsOff: Set<String>,
    /** The scopes this tenant serves the account that stay closed, by id, and why (a grant refused or awaiting a key). */
    val closed: Map<String, GrantOutcome.Reason> = emptyMap(),
    /** The tenant's members by user id, named by the display rule, as read when the tenant was opened; empty when this
     *  account holds no tenant grant or the list did not read. A user it does not list is a former member. */
    val people: Map<String, Person> = emptyMap(),
    /** The tenant's external services by capability id, each its configured address (`serviceUrl`): only while the
     *  tenant's set has the capability on; empty when the set did not read, so nothing is fetched then. */
    private val services: Map<String, String> = emptyMap(),
    /** The tenant's guest links as its capability set has them ([ShareLinks.Rules.UNKNOWN] when it did not read). */
    val linkRules: ShareLinks.Rules = ShareLinks.Rules.UNKNOWN,
) : Library {
    /** The address the tenant configured for external service [capabilityId], or null: off, or not read. */
    fun serviceUrl(capabilityId: String): String? = services[capabilityId]

    internal class OpenScope(val summary: ScopeSummary, val key: ByteArray, val name: String)

    private fun <T : ApiClient> api(make: (String, HttpClient) -> T): T = make(base, account.http)

    fun applicationsOff(): Set<String> = applicationsOff

    /** The tenant's origin: where its pages live (a link's address, the privacy notice). */
    val origin: String get() = base

    /** The members' profile pictures, synced from [people]'s revisions (`MemberAvatars`). */
    val avatars: MemberAvatars by lazy { MemberAvatars(this) }

    override suspend fun spaces(): List<SpaceRef> = scopes.map { SpaceRef(it.summary.id, it.summary.kind.value, it.name) }

    private var tree: Map<String, List<OpenNode>>? = null

    /**
     * The CURRENT members of [scopeId] (a proposed member who has not accepted is none yet), named by the tenant's
     * directory; null when the list does not read.
     */
    @Throws(Exception::class)
    suspend fun scopeMembers(scopeId: String): List<Person>? {
        val r = account.respond { api(::ScopesApi).listScopeMembers(scopeId) }
        if (!r.success) return null
        return r.body().filter { it.pendingConsent != true }.map { people[it.userId] ?: Person(it.userId, it.username, it.username) }
    }

    /** Drops the read tree: the next read of it (a list, [trees]) reads every scope again — after a node was created, or a write met a re-keyed node. */
    fun forgetTree() {
        tree = null
    }

    /**
     * The scope epoch this device holds [scopeId] at — its floor for writes (ADR 0109 point 6, layer 2): a node whose key
     * was minted below it is not re-keyed since the rotation, and nothing is sealed under its key. Null for a scope not open.
     */
    internal fun scopeEpoch(scopeId: String): Int? = scopes.firstOrNull { it.summary.id == scopeId }?.summary?.grantedEpoch

    /** How this app re-keys and folds each kind (its composition root's), for a write that pulls its path forward and for
     *  the background run that finishes a scope's re-key ([NodeRekey.pull], [NodeRekey.resume]); null: neither runs. */
    var rekeyRules: NodeRekey.Rules? = null

    /** Whether this account owns [scopeId] (an owner re-keys every node, a member all but the owner-only ones). */
    internal fun isScopeOwner(scopeId: String): Boolean =
        scopes.firstOrNull { it.summary.id == scopeId }?.summary?.role == me.raddatz.veyq.mobile.api.models.ScopeSummary.Role.OWNER

    /** The open scopes the server served as re-keying (a node of theirs is due, ADR 0114 decision 4), as this session read
     *  them: what a member's device continues in the background (ADR 0111 decision 11). */
    val rekeyingScopes: List<String> get() = scopes.filter { it.summary.rekeying == true }.map { it.summary.id }

    /** The scope key this device holds [scopeId] at, with its epoch; null for a scope not open. */
    internal fun scopeKey(scopeId: String): Pair<ByteArray, Int>? =
        scopes.firstOrNull { it.summary.id == scopeId }?.let { it.key to it.summary.grantedEpoch }

    internal suspend fun trees(): Map<String, List<OpenNode>> = tree ?: scopes.associate { s ->
        val rows = account.call { api(::ContentApi).listNodes(s.summary.id) }
        s.summary.id to Tree.open(account.suite, s.summary.id, s.key, rows)
    }.also { tree = it }

    override suspend fun nodes(): List<NodeRef> = trees().flatMap { (_, nodes) -> nodes.map { it.asNode() } }

    override suspend fun objects(): List<ObjectRef> = trees().flatMap { (_, nodes) -> nodes.mapNotNull { it.asObject() } }

    /**
     * Each scope's pin set as read (core's `favorites.ts`, ADR 0068 § 10): the node ids in the member's order, sealed under
     * the Master Key per scope. Only a scope whose read answered is here: a set that does not open or parse is loaded and
     * empty, one whose request failed is not loaded, and a pin there is refused. Ids that do not resolve are kept — a
     * trashed node's pin survives its restore.
     */
    private val pinSets = HashMap<String, LinkedHashSet<String>>()

    override suspend fun favorites(): List<ObjectRef> {
        val all = objects().associateBy { it.scopeId to it.nodeId }
        return scopes.flatMap { s ->
            val ids = readPins(s.summary.id) ?: return@flatMap emptyList()
            ids.mapNotNull { all[s.summary.id to it] }
        }
    }

    private suspend fun readPins(scopeId: String): Set<String>? {
        val f = runCatching { account.call { api(::FavoritesApi).getFavorites(scopeId) } }.getOrElse {
            if (it is kotlin.coroutines.cancellation.CancellationException) throw it
            return pinSets[scopeId]
        }
        val ids = f.ciphertext?.let { ct ->
            runCatching {
                Json.parseToJsonElement(account.suite.open(favoritesContext(scopeId), account.masterKey, unb64(ct)).decodeToString())
                    .jsonArray.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return LinkedHashSet(ids).also { pinSets[scopeId] = it }
    }

    private fun favoritesContext(scopeId: String) = Context("favorites", "scopeId" to scopeId)

    /** Node [nodeId] of [scopeId] as the tree holds it now (what an undo of its delete restores); null when not there. */
    @Throws(Exception::class)
    suspend fun node(scopeId: String, nodeId: String): OpenNode? = trees()[scopeId]?.firstOrNull { it.id == nodeId }

    /** Whether [nodeId] of [scopeId] is a guest's identity folder or lies in one, as the tree last read holds it (false before
     *  a read): its header and its level narrow to what a member does there (`NodeHeader.inIdentityFolder`). */
    fun inIdentityFolder(scopeId: String, nodeId: String): Boolean =
        tree?.get(scopeId)?.let { t -> NodeMove.inIdentityFolder(t.map { it.asNode() }, nodeId) } ?: false

    /** Whether [nodeId] of [scopeId] is pinned; null while that scope's set is not loaded (no pin is offered then). */
    fun isFavorite(scopeId: String, nodeId: String): Boolean? = pinSets[scopeId]?.contains(nodeId)

    /**
     * Pins [nodeId] of [scopeId] ([on]) or takes the pin off: the whole set sealed anew and stored (last writer wins, as the
     * contract says). Refused while the set is not loaded; a failed write puts the set back as it was. True when stored.
     */
    @Throws(Exception::class)
    suspend fun setFavorite(scopeId: String, nodeId: String, on: Boolean): Boolean {
        val set = pinSets[scopeId] ?: return false
        if (set.contains(nodeId) == on) return true
        val before = LinkedHashSet(set)
        if (on) set.add(nodeId) else set.remove(nodeId)
        val json = kotlinx.serialization.json.JsonArray(set.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        val stored = runCatching {
            val ciphertext = b64(account.suite.seal(favoritesContext(scopeId), account.masterKey, json.encodeToByteArray()))
            account.respond { api(::FavoritesApi).putFavorites(scopeId, me.raddatz.veyq.mobile.api.models.PutFavoritesRequest(ciphertext)) }.success
        }.getOrElse {
            if (it is kotlin.coroutines.cancellation.CancellationException) throw it
            false
        }
        if (!stored) {
            set.clear()
            set.addAll(before)
        }
        return stored
    }
}
