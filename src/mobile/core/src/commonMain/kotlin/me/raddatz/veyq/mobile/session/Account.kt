package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.CancellationException
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
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.api.models.NodeSummary
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.PairingGrant
import me.raddatz.veyq.mobile.crypto.PairingMarks
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
import me.raddatz.veyq.mobile.offline.AccountOffline
import me.raddatz.veyq.mobile.offline.EntryKey
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.api.models.MyTenant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

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
    /** The `key-material` answer this account opened with (wraps and the sealed identity: what the store keeps of it). */
    private val material: AccountKeyMaterial,
    /** The `listMyTenants` answer as the server sent it, kept for an offline unlock; null where it came from the store. */
    private val tenantsBody: ByteArray?,
    /**
     * Opened from the offline store with the server unreachable (ADR 0140 decision 4): it holds no access token and sends
     * nothing until the probe reached the server and the resume steps ran ([reconnect]).
     */
    openedOffline: Boolean = false,
    /** A password-trusted account opened offline: its login key, derived at the unlock, to sign in at [reconnect]; zeroed then. */
    private var authSeed: ByteArray? = null,
) : Connection {
    internal class Tokens(var access: String, var refresh: String?) {
        val lock = Mutex()
    }

    private val trust = Trust(suite)
    private val marks = TrustMarks(device.store, primitives, masterKey)

    // ---- reachability and the offline store (ADR 0140 decisions 4 and 5)

    private val _reach = MutableStateFlow<Reach>(Reach.Online)

    /** Whether the server is reached: the core's, from what the requests came to ([Reach]). */
    val reach: StateFlow<Reach> = _reach.asStateFlow()

    /** Sends nothing until [reconnect] ran: the account was opened offline and holds no session yet. */
    private var sendsNothing = openedOffline

    /** When the server last answered (epoch ms): what a screen offline says its content is as of. */
    private var lastAnswer: Long? = null

    /** This account's offline store on this device, open while the account is (none where the device keeps no trust in it,
     *  or the host named no directory). */
    internal var offline: AccountOffline? = null
        private set

    private var pinsHeld: me.raddatz.veyq.mobile.offline.OfflinePins? = null

    /** This account's pins on this device and their sync (ADR 0140 decision 9); null where it keeps no offline store. */
    val offlinePins: me.raddatz.veyq.mobile.offline.OfflinePins?
        get() {
            val o = offline ?: return null
            pinsHeld?.takeIf { it.offline === o }?.let { return it }
            return me.raddatz.veyq.mobile.offline.OfflinePins(this, o, background, ::now).also { pinsHeld = it }
        }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var probing: kotlinx.coroutines.Job? = null
    private val reconnecting = Mutex()

    /** The store opened (where this device trusts the account and the host named a directory) and the opening answers kept. */
    internal suspend fun ensureOffline() {
        if (offline != null || trusted == null) return
        val stores = device.offline ?: return
        val o = runCatching { stores.openShared(key, masterKey, server, device.offlineLimit) }
            .onFailure { println("veyq: the offline store did not open: $it") }.getOrNull() ?: return
        offline = o
        keepOpening(o)
        runCatching { o.store.sweep() }
    }

    /** The answers an offline unlock opens with: the key material (its wraps and sealed identity only) and the tenants. */
    private suspend fun keepOpening(o: AccountOffline) {
        val kept = AccountKeyMaterial(material.userId, material.suiteId, material.wraps, material.wrappedIdentityPrivateKey)
        o.keep(o.key(KeptRead.KEY_MATERIAL), listOf(KEPT_JSON.encodeToString(AccountKeyMaterial.serializer(), kept).encodeToByteArray()), null)
        tenantsBody?.let { o.keep(o.key(KeptRead.MY_TENANTS), listOf(it), null) }
        o.sweep()
    }

    /** The object store did not answer a presigned read: the account is offline, as for a request to the server. */
    internal fun storeUnreachable() = wentOffline()

    /** The server answered: it is reached. */
    private fun answered() {
        lastAnswer = now()
    }

    /** A request did not reach the server: the account is offline, and the probe starts. */
    private fun wentOffline() {
        if (_reach.value != Reach.Online) return
        _reach.value = Reach.Offline(lastAnswer)
        startProbe()
    }

    /**
     * The probe while offline: `GET /api/setup` after 5 s, then doubling up to 60 s ([LivePoll.next] at [PROBE]), until
     * [reconnect] ran — or the trust ended.
     */
    private fun startProbe() {
        if (probing?.isActive == true) return
        probing = background.launch {
            val poll = LivePoll(PROBE, ::now)
            var wait = poll.next(LivePoll.Tick.SKIP)
            while (_reach.value is Reach.Offline) {
                kotlinx.coroutines.delay(wait)
                poll.started()
                if (reconnect()) break
                wait = poll.next(LivePoll.Tick.FAILED)
            }
        }
    }

    /**
     * Back online: the server's answer to `/api/setup`, then the resume's own steps (ADR 0140 decision 4) — the session
     * (a remembered account's refresh, a password-trusted one's login with the key its offline unlock derived) and the
     * `key-material` read for this device's wrap. A refused refresh or login, or a wrap the server no longer serves, ends
     * the account here ([Reach.Ended]): the store is wiped (a refused refresh forgets the record too, as the resume does),
     * the person signs in again. True once online; false while the server does not answer.
     */
    internal suspend fun reconnect(): Boolean = reconnecting.withLock {
        when (_reach.value) {
            Reach.Online -> return@withLock true
            is Reach.Ended -> return@withLock false
            is Reach.Offline -> Unit
        }
        try {
            if (!SetupApi(server, http).getSetupStatus().success) return@withLock false
            val refresh = tokens.refresh
            val seed = authSeed
            if (refresh != null) {
                val r = TokenApi(server, http).refreshSession(RefreshRequest(refresh))
                if (r.status in 400..499) return@withLock false.also { end(SignInFailure.Reason.SESSION_ENDED, forgetRecord = trusted?.method == TrustedDevice.Method.DEVICE_KEY) }
                if (!r.success) return@withLock false
                val pair = r.body()
                tokens.access = pair.accessToken
                tokens.refresh = pair.refreshToken
                val kept = trusted
                if (kept?.method == TrustedDevice.Method.DEVICE_KEY && pair.refreshToken != null) device.keepRefresh(kept.refreshAlias, pair.refreshToken)
            } else if (seed != null) {
                val login = LoginApi(server, http)
                val challenge = login.requestLoginChallenge(LoginChallengeRequest(email)).takeIf { it.success }?.body() ?: return@withLock false
                val signature = primitives.ed25519Sign(seed, unb64(challenge.nonce))
                val session = login.verifyLogin(LoginVerifyRequest(email = email, nonce = challenge.nonce, signature = b64(signature)))
                if (session.status == 401) return@withLock false.also { end(SignInFailure.Reason.CREDENTIALS, forgetRecord = false) }
                if (!session.success) return@withLock false
                session.body().let { tokens.access = it.accessToken; tokens.refresh = it.refreshToken }
            } else {
                return@withLock false.also { end(SignInFailure.Reason.SESSION_ENDED, forgetRecord = false) }
            }
            val r = AccountApi(server, http).getAccountKeyMaterial()
            if (!r.success) return@withLock false
            val now = r.body()
            val record = trusted
            if (record != null) {
                val method = if (record.method == TrustedDevice.Method.DEVICE_KEY) MasterKeyWrap.Method.DEVICE_KEY else MasterKeyWrap.Method.DEVICE
                val wrap = now.wraps.firstOrNull { it.method == method && it.id == record.wrapId }
                    ?: return@withLock false.also { end(SignInFailure.Reason.DEVICE_NOT_TRUSTED, forgetRecord = false) }
                device.keepWrap(key, ThisDevice.KeptWrap(record.wrapId, wrap.wrappedKey, wrap.kdfParams))
            }
            sendsNothing = false
            authSeed?.fill(0)
            authSeed = null
            answered()
            _reach.value = Reach.Online
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * This device's trust in the account gone, found at a reconnect: the store closed and wiped, the local wrap copy
     * removed, the record forgotten where [forgetRecord] (a refused refresh, as the resume forgets it) — then
     * [Reach.Ended], on which the way in signs the account out.
     */
    private suspend fun end(reason: SignInFailure.Reason, forgetRecord: Boolean) {
        val record = trusted
        if (forgetRecord && record != null) {
            device.keys.deleteSecret(record.refreshAlias)
            device.forget(record.key)
            trusted = null
        } else {
            device.dropWrap(key)
        }
        wipeOffline()
        _reach.value = Reach.Ended(reason)
    }

    /** This device's own store: what an application keeps per device and not per account (the day's exchange rates). */
    internal val deviceStore: DeviceStore get() = device.store

    /** This installation as the account's device register names it: its id there and the label it starts with. */
    internal val thisDevice: ThisDevice get() = device

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
        val scopesRead = kept(KeptRead.MY_SCOPES, tenant.tenantId, "", "") { fetched(respond { ScopesApi(base, http).listMyScopes() }, null) }
        val summaries = KEPT_JSON.decodeFromString(ListSerializer(ScopeSummary.serializer()), scopesRead.pages.single().decodeToString())
            .filter { it.deletedAt == null }
        // offline (ADR 0140 decision 4): only the scope keys this device held when it last verified them open — no
        // granter's chain can be read, so nothing new is judged —, and no members, capabilities or services
        if (scopesRead.offline != null) return openOffline(tenant, base, summaries)
        val capabilities = runCatching { call { TenantsApi(base, http).getTenantCapabilities(tenant.tenantId) }.capabilities }.getOrNull()
        val on = capabilities?.map { it.id }?.toSet()
        // The external services' addresses the tenant's set names (ADR 0088 decision 7): none when the set did not read.
        val services = capabilities.orEmpty().mapNotNull { c -> c.serviceUrl?.let { c.id to it } }.toMap()
        // the tile provider's attribution beside its address (`memories.map`, shown on every map: ADR 0018)
        val attributions = capabilities.orEmpty().mapNotNull { c -> c.serviceAttribution?.let { c.id to it } }.toMap()
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
        val members = tenantScope?.let { members(base, tenant.tenantId, tenant.tenantId) }
        val view = if (tenantScope == null || members == null) TenantView.unavailable(tenant.tenantId, suite)
        else TenantView.of(tenant.tenantId, members, tenantScope.key, tenantScope.summary.grantedEpoch.toLong(), suite, anchoring)
        others.forEach { openOne(it, view) }
        val people = if (tenantScope == null || members == null) emptyMap()
        else People.of(JoinRecords(suite), tenant.tenantId, tenantScope.key, tenantScope.summary.grantedEpoch.toLong(), members)
        return TenantSession(
            this, tenant, base, open, off, closed, people, services, ShareLinks.Rules.of(capabilities),
            attributions = attributions, servicesRead = capabilities != null, closedKinds = kindsOf(summaries, closed),
        )
    }

    private suspend fun openOffline(tenant: TenantRef, base: String, summaries: List<ScopeSummary>): TenantSession {
        val grants = grants(base)
        val open = mutableListOf<TenantSession.OpenScope>()
        val closed = mutableMapOf<String, GrantOutcome.Reason>()
        for (s in summaries) when (val o = grants.openHeld(s)) {
            is GrantOutcome.Opened -> open += TenantSession.OpenScope(s, o.key, scopeName(s, tenant))
            is GrantOutcome.Refused -> closed[s.id] = o.reason
        }
        // the tenant's directory as kept (#948): offline a participant and an uploader are named as online, never judged
        val tenantScope = open.firstOrNull { it.summary.kind.value == "TENANT" && it.summary.id == tenant.tenantId }
        val members = tenantScope?.let { members(base, tenant.tenantId, tenant.tenantId) }
        val people = if (tenantScope == null || members == null) emptyMap()
        else People.of(JoinRecords(suite), tenant.tenantId, tenantScope.key, tenantScope.summary.grantedEpoch.toLong(), members)
        return TenantSession(this, tenant, base, open, emptySet(), closed, people, closedKinds = kindsOf(summaries, closed))
    }

    /**
     * Scope [scopeId]'s member list (`listScopeMembers`) of tenant [tenantId], a kept read (inbox #948): the server's answer
     * while it is reached, the store's while not; null where it did not read (refused, or offline and not kept).
     */
    internal suspend fun members(base: String, tenantId: String, scopeId: String): List<ScopeMember>? {
        val r = runCatching {
            kept(KeptRead.MEMBERS, tenantId, scopeId, scopeId) { fetched(respond { ScopesApi(base, http).listScopeMembers(scopeId) }, null) }
        }.getOrElse { e -> if (e is CancellationException) throw e else return null }
        return runCatching { KEPT_JSON.decodeFromString(ListSerializer(ScopeMember.serializer()), r.pages.single().decodeToString()) }.getOrNull()
    }

    private fun kindsOf(summaries: List<ScopeSummary>, closed: Map<String, GrantOutcome.Reason>): Map<String, String> =
        summaries.filter { it.id in closed }.associate { it.id to it.kind.value }

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
        wipeOffline()
        close()
    }

    /**
     * Ends this connection only, as the duplicate of a connection of the same account held already (`SessionModel`'s add),
     * or one whose trust a reconnect found gone (its store wiped already): its server session logged out and its keys
     * zeroed — nothing on the device changes: the record, the wrap copy and the offline store stay with the account, which
     * stays on this device (ADR 0140 decision 11: the store goes only when the account leaves this device).
     */
    suspend fun retire() {
        pinsHeld?.stop()
        pinsHeld = null
        offline?.close()
        offline = null
        close()
    }

    private suspend fun close() {
        probing?.cancel()
        background.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (!sendsNothing) runCatching { call { SessionApi(server, http).logout() } }
        masterKey.fill(0)
        authSeed?.fill(0)
        http.close()
        outside.close()
    }

    /** What enrolling this device came to: password-trusted, remembered, or not offered by this server (switched off). */
    enum class TrustOutcome { TRUSTED, REMEMBERED, NOT_OFFERED }

    /**
     * Trusts this device with the account (ADR 0005, ADR 0123 decision 8), replacing how it trusted it before (one device
     * wrap per account per device, ADR 0005 §9): a [level] enrols a DEVICE_KEY wrap under that level's unlock key (which,
     * at [StaySignedIn.PRESENCE], asks the person to authenticate) and keeps the refresh token; null a DEVICE wrap under
     * Argon2id(device factor, [password]), so the next start needs the password and no Secret Key. Only after the new wrap
     * is recorded is the old one deleted, and with it an unlock key no remembered account uses any more. A failure
     * before that leaves the trust it had untouched.
     */
    suspend fun trustThisDevice(password: String?, level: StaySignedIn?): TrustOutcome {
        val previous = trusted
        val alias = device.newAlias()
        val record = if (level != null) {
            val refresh = tokens.refresh ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no refresh token to keep")
            val (iv, sealed) = device.keys.sealUnlockable(masterKey, DEVICE_KEY_AAD, level)
            // a key the seal created for a level nothing ends up using goes again, whatever stopped the enrolment
            val id = try { addWrap(AddWrapRequest.Method.DEVICE_KEY, sealed, iv) } catch (e: Exception) { releaseUnlockKeys(); throw e }
                ?: return TrustOutcome.NOT_OFFERED.also { releaseUnlockKeys() }
            TrustedDevice(server, email, userId, TrustedDevice.Method.DEVICE_KEY, id, alias, level).also {
                device.keepWrap(it.key, ThisDevice.KeptWrap(id, b64(sealed), b64(iv)))
                try {
                    device.keys.putSecret(it.refreshAlias, refresh)
                } catch (e: Exception) {
                    // a store that cannot keep the token cannot resume: the wrap just added would open nothing, ever, so
                    // it goes before the caller falls back to the password trust (`RememberEnrolTest`)
                    runCatching { respond { AccountWrapsApi(server, http).deleteAccountWrap(id) } }
                    releaseUnlockKeys()
                    throw e
                }
            }
        } else {
            requireNotNull(password) { "a password-trusted device needs the password" }
            device.keys.createBindKey(alias)
            val factor = device.keys.bindHmac(alias, DEVICE_BIND) ?: throw IllegalStateException("bind key not created")
            val salt = primitives.randomBytes(16)
            val kek = suite.kekDevicePassword(factor, password, salt)
            factor.fill(0)
            val wrapped = try { suite.seal(Context("master-key"), kek, masterKey) } finally { kek.fill(0) }
            val kdf = suite.encodeKdfParams(V1.ARGON, salt)
            val id = addWrap(AddWrapRequest.Method.DEVICE, wrapped, kdf)
            if (id == null) {
                device.keys.deleteBindKey(alias)
                return TrustOutcome.NOT_OFFERED
            }
            TrustedDevice(server, email, userId, TrustedDevice.Method.DEVICE, id, alias).also {
                device.keepWrap(it.key, ThisDevice.KeptWrap(id, b64(wrapped), b64(kdf)))
            }
        }
        device.remember(record)
        trusted = record
        previous?.let { drop(it) }
        releaseUnlockKeys()
        ensureOffline()
        runCatching { register().remember(device.id, device.label, null, record.wrapId, now()) }
        return if (level != null) TrustOutcome.REMEMBERED else TrustOutcome.TRUSTED
    }

    /**
     * Whether [password] is this account's: a login with it on this server (ADR 0003), whose new session is revoked at
     * once. The password trust a remembered account goes back to is keyed by the password, which nothing else can check:
     * a DEVICE wrap under a mistyped one would open nothing at the next start. False only where the server refused the
     * password; a server that cannot be asked throws.
     */
    suspend fun isPassword(password: String): Boolean {
        val login = LoginApi(server, http)
        val challenge = login.requestLoginChallenge(LoginChallengeRequest(email)).let {
            if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "challenge ${it.status}")
            it.body()
        }
        val seed = suite.authSeed(password, suite.authSalt(unb64(challenge.instanceSalt), email))
        val signature = try { primitives.ed25519Sign(seed, unb64(challenge.nonce)) } finally { seed.fill(0) }
        val session = login.verifyLogin(LoginVerifyRequest(email = email, nonce = challenge.nonce, signature = b64(signature)))
        if (session.status == 401) return false
        if (!session.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "verify ${session.status}")
        // the check's own session is not this one: it ends here (best effort — it expires unused otherwise)
        runCatching { respond { SessionApi(server, http).revokeSessionFamily(session.body().family.id) } }
        return true
    }

    /**
     * Each level's unlock key that no DEVICE_KEY record on this device uses any more is deleted (one per level, shared by
     * every account remembered at it): after a sign-out, a stop, or a change of level.
     */
    private fun releaseUnlockKeys() {
        val used = device.trusted().mapNotNull { it.unlockLevel }.toSet()
        for (level in StaySignedIn.entries) if (level !in used) runCatching { device.keys.deleteUnlockKey(level) }
    }

    /** Ends this device's trust in the account: its wrap deleted on the server, its keys and record here. */
    suspend fun forgetThisDevice() {
        trusted?.let { forget(it) }
    }

    /**
     * Takes this device off the account for good, then signs out: its own trust forgotten (its wrap deleted), every other
     * device wrap the register joins to it deleted, every session family the register joins to it revoked (the current
     * one by the sign-out), and its entry taken out of the register with every row naming it — its own entry only, so
     * the register's one-writer rule holds ([DeviceRegisterStore.leave]). Each step is best effort and the next runs
     * whatever the last answered. No screen offers it yet: the live tests end every device they sign in with through it
     * (`LiveRun`), so a run leaves the account as it found it.
     */
    suspend fun removeThisDevice() {
        val id = device.id
        trusted?.let { forget(it) }
        val plain = runCatching { register().read() }.getOrNull()
        for (wrap in plain?.wraps.orEmpty().filterValues { it == id }.keys) {
            runCatching { respond { AccountWrapsApi(server, http).deleteAccountWrap(wrap) } }
        }
        val current = runCatching { call { SessionApi(server, http).listSessionFamilies() }.firstOrNull { it.current == true }?.id }.getOrNull()
        for (family in plain?.families.orEmpty().filterValues { it == id }.keys - setOfNotNull(current)) {
            runCatching { respond { SessionApi(server, http).revokeSessionFamily(family) } }
        }
        runCatching { register().leave(id) }
        signOut()
    }

    private suspend fun forget(record: TrustedDevice) {
        drop(record)
        device.forget(record.key)
        trusted = null
        releaseUnlockKeys()
        wipeOffline()
    }

    /**
     * This account's offline store on this device removed (ADR 0140 decision 11): at its sign-out (both trusts), "Stop
     * trusting this device" and removing the device. Best effort: a wipe a crash interrupted finishes at the next start.
     */
    private suspend fun wipeOffline() {
        pinsHeld?.stop()
        pinsHeld = null
        offline?.let { runCatching { it.closeForWipe() } }
        offline = null
        device.offline?.let { o -> runCatching { o.wipe(key) }.onFailure { println("veyq: the offline store was not wiped: $it") } }
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

    internal fun register() = DeviceRegisterStore(suite, object : RegisterApi {
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
    internal suspend fun recordThisDevice(familyId: String?, label: String = device.label) {
        runCatching { register().remember(device.id, label, familyId, trusted?.wrapId, now()) }
    }

    /** This device's trust-state marks for the account, as a link hands them over (ADR 0059, ADR 0028 Decision 7). */
    internal suspend fun linkMarks(): PairingMarks = marks.forLink()

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

    /**
     * [call]'s refresh, the response handed back whatever its status, for a caller that tells statuses apart. A request
     * that does not reach the server throws [Unreachable] and turns the account offline ([reach]); an account opened
     * offline sends nothing until it reconnected.
     */
    internal suspend fun <T : Any> respond(f: suspend () -> HttpResponse<T>): HttpResponse<T> {
        val first = sent(f)
        if (first.status != 401 || tokens.refresh == null) return first
        refresh(tokens.access)
        return sent(f)
    }

    /** One request: [Unreachable] where it did not reach the server, which turns the account offline. */
    private suspend fun <T : Any> sent(f: suspend () -> HttpResponse<T>): HttpResponse<T> {
        if (sendsNothing) throw Unreachable("opened offline")
        return try {
            f().also { answered() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInFailure) {
            throw e
        } catch (e: Unreachable) {
            throw e
        } catch (e: Exception) {
            wentOffline()
            throw Unreachable(e.message, e)
        }
    }

    /** What a kept read's fetch came to: its pages as the server sent them and its revision, a `304`, or a refusal. */
    internal sealed class Fetched {
        class Pages(val pages: List<ByteArray>, val revision: String?) : Fetched()
        object NotModified : Fetched()
        class Refused(val status: Int, val code: String?) : Fetched()
    }

    /** A kept read's answer: its pages, and — where they came from the store with the server unreachable — as of when. */
    internal class Read(val pages: List<ByteArray>, val offline: OfflineAsOf?)

    /** A read the server refused ([status], its error [code]); what was kept of it is gone. */
    class Refused(val status: Int, val code: String?) : Exception("HTTP $status${code?.let { " $it" } ?: ""}")

    /**
     * A [KeptRead] for [tenant], [scope] and [id] (ADR 0140 decision 5): the server first while it is reached — sent the
     * kept revision where the read is [conditional], a `304` marking the kept answer current, a newer answer **replacing**
     * it, never merged, a `403`/`404` dropping it ([Refused]) — and the store's answer while it is not. After a replace or a
     * drop the store is swept of what no current answer reaches ([settle]: a caller that reads many settles once). Without
     * a store it is the server's answer alone.
     */
    internal suspend fun kept(
        read: KeptRead, tenant: String, scope: String, id: String, conditional: Boolean = false, settle: Boolean = true,
        fetch: suspend (ifNoneMatch: String?) -> Fetched,
    ): Read {
        val o = offline
        val key = o?.key(read, tenant, scope, id)
        if (o != null && _reach.value != Reach.Online) {
            val kept = o.answer(key!!) ?: throw Unreachable("not kept")
            return Read(kept.pages, OfflineAsOf(kept.asOf))
        }
        val kept = if (o != null && conditional) o.answer(key!!) else null
        val fetched = try {
            fetch(kept?.revision)
        } catch (e: Unreachable) {
            val stored = kept ?: o?.answer(key!!) ?: throw e
            return Read(stored.pages, OfflineAsOf(stored.asOf))
        }
        return when (fetched) {
            is Fetched.NotModified -> {
                val k = kept ?: throw IllegalStateException("a 304 with no kept answer")
                o!!.confirm(key!!)
                Read(k.pages, null)
            }
            is Fetched.Pages -> {
                if (o != null) {
                    o.keep(key!!, fetched.pages, fetched.revision)
                    sweepDue = true
                    if (settle) settle()
                    // a pinned listing's revision moved (decision 9): the pins' sync fetches what is new
                    if (conditional && kept != null && kept.revision != fetched.revision && o.store.entry(key)?.pins?.isNotEmpty() == true) pinsHeld?.wake()
                }
                Read(fetched.pages, null)
            }
            is Fetched.Refused -> {
                if (o != null && (fetched.status == 403 || fetched.status == 404)) {
                    if (fetched.code == "tenant_suspended" && tenant.isNotEmpty()) o.suspend(tenant) else o.drop(key!!)
                    sweepDue = true
                    if (settle) settle()
                }
                throw Refused(fetched.status, fetched.code)
            }
        }
    }

    private var sweepDue = false

    /** The store swept, where a read replaced or dropped an answer since the last sweep. */
    internal suspend fun settle() {
        val o = offline ?: return
        if (!sweepDue) return
        sweepDue = false
        runCatching { o.sweep() }.onFailure { println("veyq: the offline store was not swept: $it") }
    }

    /** [r]'s body as the server sent it, its pages' one page; or its refusal with the error's code. */
    internal suspend fun <T : Any> fetched(r: HttpResponse<T>, revision: String? = r.response.headers[HttpHeaders.ETag]): Fetched = when {
        r.status == 304 -> Fetched.NotModified
        r.success -> Fetched.Pages(listOf(r.response.readRawBytes()), revision)
        else -> Fetched.Refused(r.status, errorCode(r))
    }

    /**
     * One rotation of the session (ADR 0004, single-flight): the access token [seen] is replaced unless another call
     * replaced it meanwhile. A refusal is [SignInFailure.Reason.CREDENTIALS].
     */
    internal suspend fun refresh(seen: String = tokens.access) {
        tokens.lock.withLock {
            if (tokens.access == seen) {
                val token = tokens.refresh ?: throw SignInFailure(SignInFailure.Reason.CREDENTIALS, "no refresh token")
                val r = sent { TokenApi(server, http).refreshSession(RefreshRequest(token)) }
                if (!r.success) throw SignInFailure(SignInFailure.Reason.CREDENTIALS, "refresh ${r.status}")
                val pair = r.body()
                tokens.access = pair.accessToken
                tokens.refresh = pair.refreshToken
                val kept = trusted
                if (kept?.method == TrustedDevice.Method.DEVICE_KEY && pair.refreshToken != null) {
                    device.keepRefresh(kept.refreshAlias, pair.refreshToken)
                }
            }
        }
    }

    /** The `code` of an error response's body (`ErrorResponse`), or null. */
    internal suspend fun errorCode(r: HttpResponse<*>): String? = runCatching {
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

        /** How long a connection to the server may take to open. */
        const val CONNECT_MS = 30_000L

        /** How long the server may work on a call without sending a byte (a large file's completion). */
        const val SERVER_WORK_MS = 10L * 60 * 1000

        /** The DEVICE_KEY wrap's associated data, the web's (`deviceKeyWrap.ts`); only this device ever opens it. */
        private val DEVICE_KEY_AAD = utf8("veyq/v1/device-key-wrap")

        /** The probe's cadence while offline (ADR 0140 decision 5): 5 s, doubling to 60 s. */
        internal val PROBE = LivePoll.Cadence(intervalMs = 5_000, maxBackoffMs = 60_000, jitter = 0.2, minGapMs = 0)

        /** How a kept answer is read back: as the generated client reads the server's. */
        internal val KEPT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** An HTTP client with the bearer of [tokens], and the server checked for its major and suite. */
        private class Connected(val suite: V1, val base: String, val tokens: Tokens, val http: HttpClient, val outside: HttpClient)

        private suspend fun connect(
            primitives: Primitives,
            server: String,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
            device: ThisDevice? = null,
        ): Connected {
            val c = clients(primitives, server, client)
            try {
                val setup = try {
                    SetupApi(c.base, c.http).getSetupStatus()
                } catch (e: Exception) {
                    throw SignInFailure(SignInFailure.Reason.UNREACHABLE, e.message)
                }
                if (!setup.success) throw SignInFailure(SignInFailure.Reason.UNREACHABLE, "setup ${setup.status}")
                val status = setup.body()
                if (!ClientWindow.speaks(status.version.major)) throw SignInFailure(SignInFailure.Reason.INCOMPATIBLE, "server major ${status.version.major}, app $MAJOR")
                if ("v1" !in status.suites) throw SignInFailure(SignInFailure.Reason.INCOMPATIBLE, "no v1 suite")
                // kept here, at every sign-in, not only at an online unlock: a password-trusted device signed in and closed
                // before its first unlock otherwise had no salt and opened nothing offline (ADR 0140's Linux pass)
                device?.keepInstanceSalt(Url(c.base).host, status.instanceSalt)
                return c
            } catch (e: Throwable) {
                c.http.close()
                c.outside.close()
                throw e
            }
        }

        /** The account's two HTTP clients, nothing sent yet: [connect]'s, and an offline unlock's (which cannot ask the server). */
        private fun clients(
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
                // the server answers some calls only after work that grows with a blob: a file's `completeFile` stats every
                // chunk it holds (8,193 for 2 GiB; measured 2026-11-12: past OkHttp's default 10 s read timeout, the upload
                // failed). A browser waits; so does the app, up to SERVER_WORK_MS without a byte, never a total bound.
                install(io.ktor.client.plugins.HttpTimeout) {
                    connectTimeoutMillis = CONNECT_MS
                    socketTimeoutMillis = SERVER_WORK_MS
                }
            }
            return Connected(V1(primitives), server.trimEnd('/'), tokens, http, client { expectSuccess = false })
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
            label: String = device.label,
        ): VeyqAccount {
            val suite = c.suite
            val identity = suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), masterKey, unb64(material.wrappedIdentityPrivateKey)))
            val kem = suite.xwingKey(identity.kemSeed)
            val mine = suite.identityPublic(identity)
            // Every tenant the account belongs to on this server (answered whatever Host it is asked at).
            // Every tenant the account belongs to on this server (answered whatever Host it is asked at), as sent: an offline
            // unlock reads it back from the store.
            val tenantsBody = ScopesApi(c.base, c.http).listMyTenants().let {
                if (!it.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "my-tenants ${it.status}")
                it.response.readRawBytes()
            }
            val tenants = tenantsOf(tenantsBody)
            if (tenants.isEmpty()) throw SignInFailure(SignInFailure.Reason.NO_TENANT)
            val account = VeyqAccount(
                suite, primitives, c.http, c.outside, c.base, c.tokens, material.userId, email.trim(), masterKey, identity, kem,
                mine.serialize(), mine.sign, tenants, device, material, tenantsBody,
            )
            account.answered()
            account.trusted?.let { device.touch(it.key) }
            account.ensureOffline()
            account.recordThisDevice(familyId, label)
            return account
        }

        private fun tenantsOf(body: ByteArray): List<TenantRef> =
            KEPT_JSON.decodeFromString(ListSerializer(MyTenant.serializer()), body.decodeToString()).map { TenantRef(it.tenantId, it.name, it.domain) }

        /**
         * An account opened from its offline store with the server unreachable (ADR 0140 decision 4): [masterKey] from this
         * device's kept wrap, the identity from the kept `key-material`, the tenants from the kept `listMyTenants`; no
         * session — it sends nothing until its probe reached the server and [reconnect] ran ([refresh]: the remembered
         * account's kept token; [authSeed]: a password-trusted one's login key). Null where this device keeps no store of
         * the account or the store holds no opening answers: then the unlock fails as unreachable.
         */
        private suspend fun openedOffline(
            primitives: Primitives,
            record: TrustedDevice,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
            masterKey: ByteArray,
            refresh: String?,
            authSeed: ByteArray?,
        ): VeyqAccount? {
            val stores = device.offline
            if (stores == null || !stores.has(record.key)) return null
            val o = runCatching { stores.openShared(record.key, masterKey, record.server, device.offlineLimit) }.getOrNull() ?: return null
            val material = o.answer(o.key(KeptRead.KEY_MATERIAL))?.pages?.singleOrNull()
                ?.let { runCatching { KEPT_JSON.decodeFromString(AccountKeyMaterial.serializer(), it.decodeToString()) }.getOrNull() }
            val tenantsAnswer = o.answer(o.key(KeptRead.MY_TENANTS))
            val tenants = tenantsAnswer?.pages?.singleOrNull()?.let { runCatching { tenantsOf(it) }.getOrNull() }
            if (material == null || material.userId != record.userId || tenants.isNullOrEmpty()) {
                o.close()
                return null
            }
            val c = clients(primitives, record.server, client)
            c.tokens.refresh = refresh
            return try {
                val suite = c.suite
                val identity = suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), masterKey, unb64(material.wrappedIdentityPrivateKey)))
                val kem = suite.xwingKey(identity.kemSeed)
                val mine = suite.identityPublic(identity)
                VeyqAccount(
                    suite, primitives, c.http, c.outside, c.base, c.tokens, material.userId, record.email, masterKey, identity, kem,
                    mine.serialize(), mine.sign, tenants, device, material, null, openedOffline = true, authSeed = authSeed,
                ).also {
                    it.offline = o
                    // the pins read now: offline no sync reads them, and a pinned node's toggle and Settings' items
                    // showed none until the next online open (phase 6's Linux pass)
                    runCatching { o.loaded() }
                    it._reach.value = Reach.Offline(tenantsAnswer.asOf)
                    it.startProbe()
                }
            } catch (e: Exception) {
                o.close()
                c.http.close()
                c.outside.close()
                null
            }
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
        ): VeyqAccount = signIn(primitives, server, email, password, device, client) {
            SecretKeyText.read(primitives, secretKey) ?: throw SignInFailure(SignInFailure.Reason.SECRET_KEY)
        }

        /**
         * [signIn] with the Secret Key's 32 bytes, as an Emergency Kit carries them (`SessionModel`'s held kit): the
         * same sign-in, [secretKey] this call's — zeroed once the key-encryption key is derived, or when it fails.
         */
        suspend fun signInWithKey(
            primitives: Primitives,
            server: String,
            email: String,
            password: String,
            secretKey: ByteArray,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount = try {
            signIn(primitives, server, email, password, device, client) { secretKey }
        } finally {
            secretKey.fill(0)
        }

        /** The sign-in itself: [secretKey] asked for once the password wrap is read, after the login, and zeroed after use. */
        private suspend fun signIn(
            primitives: Primitives,
            server: String,
            email: String,
            password: String,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
            secretKey: () -> ByteArray,
        ): VeyqAccount {
            // the device kept: its instance salt, which a password-trusted unlock offline derives the login key from
            val c = connect(primitives, server, client, device)
            return guarded(c) {
                val session = login(primitives, c, email, password)
                val material = keyMaterial(c)
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.PASSWORD }
                    ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no password wrap")
                val sk = secretKey()
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
        ): VeyqAccount = try {
            unlockOnDeviceOnline(primitives, record, password, device, client)
        } catch (e: SignInFailure) {
            if (e.reason != SignInFailure.Reason.UNREACHABLE) throw e
            unlockOnDeviceOffline(primitives, record, password, device, client) ?: throw e
        }

        private suspend fun unlockOnDeviceOnline(
            primitives: Primitives,
            record: TrustedDevice,
            password: String,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            require(record.method == TrustedDevice.Method.DEVICE) { "not a password-trusted record" }
            val c = connect(primitives, record.server, client, device)
            return guarded(c) {
                val session = login(primitives, c, record.email, password)
                val material = keyMaterial(c)
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.DEVICE && it.id == record.wrapId }
                    ?: throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap not served").also { notServed(device, record) }
                device.keepWrap(record.key, ThisDevice.KeptWrap(record.wrapId, wrap.wrappedKey, wrap.kdfParams))
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
         * A password-trusted account opened with the server unreachable: the kept DEVICE wrap opened as the online unlock
         * opens the served one, and the login key for [reconnect] derived from [password] and the server's kept instance
         * salt. Null where nothing is kept to open; a wrong password is DEVICE_NOT_TRUSTED, as online.
         */
        private suspend fun unlockOnDeviceOffline(
            primitives: Primitives,
            record: TrustedDevice,
            password: String,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount? {
            val kept = device.keptWrap(record.key)?.takeIf { it.wrapId == record.wrapId } ?: return null
            val salt = device.instanceSalt(Url(record.server).host) ?: return null
            if (device.offline?.has(record.key) != true) return null
            val suite = V1(primitives)
            val factor = device.keys.bindHmac(record.alias, DEVICE_BIND) ?: return null
            val masterKey = try {
                val (params, kdfSalt) = suite.decodeKdfParams(unb64(kept.kdfParams ?: throw OpenRefused("no kdfParams")))
                val kek = suite.kekDevicePassword(factor, password, kdfSalt, params)
                try { suite.open(Context("master-key"), kek, unb64(kept.wrappedKey)) } finally { kek.fill(0) }
            } catch (e: Exception) {
                throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap does not open")
            } finally {
                factor.fill(0)
            }
            val seed = suite.authSeed(password, suite.authSalt(unb64(salt), record.email))
            return openedOffline(primitives, record, device, client, masterKey, null, seed) ?: run {
                masterKey.fill(0)
                seed.fill(0)
                null
            }
        }

        /**
         * The server no longer serves this device's wrap (another device's "Sign out and remove saved key", ADR 0058
         * decision 2): the local copy goes and the store is wiped (ADR 0140 decision 4). The record stays — one answer of a
         * server never makes a device forget its trust.
         */
        private fun notServed(device: ThisDevice, record: TrustedDevice) {
            device.dropWrap(record.key)
            device.offline?.let { o -> runCatching { o.wipe(record.key) } }
        }

        /**
         * The new device's sign-in from a link's grant (ADR 0059 §2, ADR 0004 § Transport): the linker's marks raised
         * first, then the handed-over refresh token spent **once** at `POST /api/token/refresh` with the token in the body —
         * the rotation adoption itself runs; an app keeps no cookie, so adoption is the browser's verb — and the family it
         * belongs to read from `listSessionFamilies`' `current` mark; then the identity under the granted Master Key, and
         * write moment 1 under the name the grant gave this device (§9). No trust is enrolled here: a linked device enrols at
         * most a DEVICE_KEY wrap, by the offer that follows (§10). [grant] stays the caller's; its key is copied.
         *
         * The token is never presented twice: a second presentation is ADR 0004's clone case, which revokes the family of
         * the device standing here. A refusal of it is [SignInFailure.Reason.SESSION_ENDED].
         */
        suspend fun linked(
            primitives: Primitives,
            server: String,
            grant: PairingGrant,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            val c = connect(primitives, server, client)
            return guarded(c) {
                val masterKey = grant.masterKey.copyOf()
                try {
                    grant.marks?.let { TrustMarks(device.store, primitives, masterKey).raise(it) }
                    val r = try {
                        redeemOnce(c.base, grant.refreshToken, client)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw SignInFailure(SignInFailure.Reason.UNREACHABLE, e.message)
                    }
                    if (r.status in 400..499) throw SignInFailure(SignInFailure.Reason.SESSION_ENDED, "handed token ${r.status}")
                    if (r.status !in 200..299) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "refresh ${r.status}")
                    c.tokens.access = r.access ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no access token")
                    c.tokens.refresh = r.refresh ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "no body token")
                    val familyId = runCatching { SessionApi(c.base, c.http).listSessionFamilies() }.getOrNull()
                        ?.takeIf { it.success }?.body()?.firstOrNull { it.current == true }?.id
                    finish(c, primitives, grant.email, keyMaterial(c), masterKey, device, familyId, grant.label)
                } catch (e: Throwable) {
                    masterKey.fill(0)
                    throw e
                }
            }
        }

        /** What [redeemOnce] was answered: the status and, on a 2xx, the two tokens. */
        internal class Redeemed(val status: Int, val access: String?, val refresh: String?)

        /**
         * `POST /api/token/refresh` with [token] in the body, sent **once** — a handed-over token presented twice is ADR
         * 0004's clone case — through a client of its own, closed after: no pooled connection, so OkHttp (the Android app's
         * engine and the JVM tests') has no stale connection to re-send it on; the one retry it is left with is a connect
         * that failed, which never reached the server. A request the server read and whose answer it dropped is sent once
         * (measured, `OneShotRedeemTest`). A lost answer is a failure here, never a second try.
         */
        internal suspend fun redeemOnce(
            base: String,
            token: String,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): Redeemed {
            val once = client {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            try {
                val r = TokenApi(base.trimEnd('/'), once).refreshSession(RefreshRequest(token))
                if (!r.success) return Redeemed(r.status, null, null)
                val pair = r.body()
                return Redeemed(r.status, pair.accessToken, pair.refreshToken)
            } finally {
                once.close()
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
        ): VeyqAccount = try {
            resumeOnline(primitives, record, device, client)
        } catch (e: SignInFailure) {
            if (e.reason != SignInFailure.Reason.UNREACHABLE) throw e
            resumeOffline(primitives, record, device, client) ?: throw e
        }

        private suspend fun resumeOnline(
            primitives: Primitives,
            record: TrustedDevice,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount {
            require(record.method == TrustedDevice.Method.DEVICE_KEY) { "not a remembered record" }
            val c = connect(primitives, record.server, client, device)
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
                    device.offline?.let { o -> runCatching { o.wipe(record.key) } }
                    throw SignInFailure(SignInFailure.Reason.SESSION_ENDED, "refresh ${r.status}")
                }
                if (!r.success) throw SignInFailure(SignInFailure.Reason.UNREACHABLE, "refresh ${r.status}")
                val pair = r.body()
                c.tokens.access = pair.accessToken
                c.tokens.refresh = pair.refreshToken
                pair.refreshToken?.let { device.keepRefresh(record.refreshAlias, it) }
                val material = keyMaterial(c)
                val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.DEVICE_KEY && it.id == record.wrapId }
                    ?: throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap not served").also { notServed(device, record) }
                device.keepWrap(record.key, ThisDevice.KeptWrap(record.wrapId, wrap.wrappedKey, wrap.kdfParams))
                val masterKey = try {
                    device.keys.openUnlockable(unb64(wrap.kdfParams ?: throw OpenRefused("no iv")), unb64(wrap.wrappedKey), DEVICE_KEY_AAD, record.unlockLevel!!)
                } catch (e: DeviceAuthFailed) {
                    throw SignInFailure(if (e.cancelled) SignInFailure.Reason.DEVICE_AUTH_CANCELLED else SignInFailure.Reason.DEVICE_NOT_TRUSTED, e.message)
                } catch (e: OpenRefused) {
                    throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap does not open")
                }
                finish(c, primitives, record.email, material, masterKey, device, null)
            }
        }

        /**
         * A remembered account opened with the server unreachable (ADR 0140 decision 4): the kept DEVICE_KEY wrap opened
         * under the unlock key at the record's level — PRESENCE still asks the person, LOGIN still rests on the device's
         * sign-in —, then the store. Null where nothing is kept to open.
         */
        private suspend fun resumeOffline(
            primitives: Primitives,
            record: TrustedDevice,
            device: ThisDevice,
            client: (block: io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
        ): VeyqAccount? {
            val kept = device.keptWrap(record.key)?.takeIf { it.wrapId == record.wrapId } ?: return null
            if (device.offline?.has(record.key) != true) return null
            val refresh = device.keys.secret(record.refreshAlias) ?: return null
            val masterKey = try {
                device.keys.openUnlockable(unb64(kept.kdfParams ?: throw OpenRefused("no iv")), unb64(kept.wrappedKey), DEVICE_KEY_AAD, record.unlockLevel!!)
            } catch (e: DeviceAuthFailed) {
                throw SignInFailure(if (e.cancelled) SignInFailure.Reason.DEVICE_AUTH_CANCELLED else SignInFailure.Reason.DEVICE_NOT_TRUSTED, e.message)
            } catch (e: OpenRefused) {
                throw SignInFailure(SignInFailure.Reason.DEVICE_NOT_TRUSTED, "wrap does not open")
            }
            return openedOffline(primitives, record, device, client, masterKey, refresh, null) ?: run {
                masterKey.fill(0)
                null
            }
        }
    }
}

/**
 * Keeps a remembered account's rotated refresh token. The refresh itself succeeded, so a store that fails here (a Secret
 * Service gone, DPAPI refusing: `DeviceAuthFailed(false)`) never fails the call that refreshed: the session goes on, and
 * the next start's resume finds the old token refused and asks for a sign-in (SESSION_ENDED) — the honest outcome
 * (ADR 0123 decision 8; `RefreshKeptTest`).
 */
private fun ThisDevice.keepRefresh(alias: String, token: String) {
    runCatching { keys.putSecret(alias, token) }
        .onFailure { println("veyq: the rotated sign-in token was not kept; the next start signs in: $it") }
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
    /** The tenant's members as read when the tenant was opened ([people]). */
    people: Map<String, Person> = emptyMap(),
    /** The tenant's external services by capability id, each its configured address (`serviceUrl`): only while the
     *  tenant's set has the capability on; empty when the set did not read, so nothing is fetched then. */
    private val services: Map<String, String> = emptyMap(),
    /** The tenant's guest links as its capability set has them ([ShareLinks.Rules.UNKNOWN] when it did not read). */
    val linkRules: ShareLinks.Rules = ShareLinks.Rules.UNKNOWN,
    /** The external services' attributions by capability id (`serviceAttribution`: the tiles' on `memories.map`). */
    private val attributions: Map<String, String> = emptyMap(),
    /**
     * Whether the tenant's capability set was read when it opened: false offline (ADR 0140 decision 4) or when the read
     * failed, so which services and hosts are served now is unknown (the map's "needs a connection", ADR 0143 D9).
     */
    val servicesRead: Boolean = false,
    /** The kind of each scope of [closed] (`TENANT`, `GROUP`, `PERSONAL`), by id: what a walk over the tenant's and the groups'
     *  scopes counts as not read (a person's index, ADR 0144 decision 12). */
    internal val closedKinds: Map<String, String> = emptyMap(),
) : Library {
    /** The address the tenant configured for external service [capabilityId], or null: off, or not read. */
    fun serviceUrl(capabilityId: String): String? = services[capabilityId]

    /** The attribution the tenant configured for external service [capabilityId] (plain text), or null. */
    fun serviceAttribution(capabilityId: String): String? = attributions[capabilityId]

    internal class OpenScope(val summary: ScopeSummary, val key: ByteArray, val name: String)

    /**
     * The tenant's members by user id, named by the display rule, as read when the tenant was opened or last by
     * [readPeople]; empty when this account holds no tenant grant or the list did not read. A user it does not list is a
     * former member.
     */
    var people: Map<String, Person> = people
        private set

    /**
     * Reads the tenant's members anew (a person's refresh, `ShellModel.refresh(people = true)`), as the open read them, and
     * keeps them as [people]: a member's new name or picture revision shows from then on. Null, and [people] as it was,
     * where this account holds no tenant grant or the list did not read (offline: the list as last kept, #948).
     */
    @Throws(Exception::class)
    suspend fun readPeople(): Map<String, Person>? {
        val own = scopes.firstOrNull { it.summary.kind.value == "TENANT" && it.summary.id == tenant.tenantId } ?: return null
        val members = account.members(base, tenant.tenantId, tenant.tenantId) ?: return null
        return People.of(JoinRecords(account.suite), tenant.tenantId, own.key, own.summary.grantedEpoch.toLong(), members)
            .also { people = it }
    }

    private fun <T : ApiClient> api(make: (String, HttpClient) -> T): T = make(base, account.http)

    fun applicationsOff(): Set<String> = applicationsOff

    /** The tenant's origin: where its pages live (a link's address, the privacy notice). */
    val origin: String get() = base

    /** The members' profile pictures, synced from [people]'s revisions (`MemberAvatars`). */
    val avatars: MemberAvatars by lazy { MemberAvatars(this) }

    /** The members' pictures a UI shows, and the rule of when they are synced ([AvatarsModel]). */
    val pictures: AvatarsModel by lazy { AvatarsModel.of(this) }

    override suspend fun spaces(): List<SpaceRef> = scopes.map { SpaceRef(it.summary.id, it.summary.kind.value, it.name) }

    /** The kind of open scope [scopeId] (`TENANT`, `GROUP`, `PERSONAL`), or null where it is not open here. */
    internal fun scopeKind(scopeId: String): String? = scopes.firstOrNull { it.summary.id == scopeId }?.summary?.kind?.value

    private var tree: Map<String, List<OpenNode>>? = null

    /**
     * Each scope's tree revision as the server last answered it ([trees]: the `ETag` of a `200`, the one sent on a `304`):
     * what [treeMoved] asks against. Absent for a scope whose tree came from the store or was never read.
     */
    private val treeRevisions = HashMap<String, String>()

    /**
     * Whether scope [scopeId]'s tree moved since this session read it (ADR 0081: its revision moves with every node created,
     * moved, renamed, trashed, restored or whose meta was written): `listNodes` with the revision [trees] was answered with as
     * `If-None-Match`, a `304` being no. False where no revision is known (the tree came from the store, or was not read):
     * nothing to ask against. The answer's body is not kept: whoever asks reads the tree anew where it moved
     * ([forgetTree]). Throws where the server did not answer, as a live poll's failed tick.
     */
    @Throws(Exception::class)
    suspend fun treeMoved(scopeId: String): Boolean {
        val revision = treeRevisions[scopeId] ?: return false
        val r = account.respond { api(::ContentApi).listNodes(scopeId, revision) }
        return when {
            r.status == 304 -> false
            r.success -> true
            else -> throw IllegalStateException("tree ${r.status}")
        }
    }

    /**
     * The CURRENT members of [scopeId] (a proposed member who has not accepted is none yet), named by the tenant's
     * directory; null when the list does not read. A kept read (inbox #948): offline the list as last read online.
     */
    @Throws(Exception::class)
    suspend fun scopeMembers(scopeId: String): List<Person>? {
        val members = account.members(base, tenant.tenantId, scopeId) ?: return null
        return members.filter { it.pendingConsent != true }.map { people[it.userId] ?: Person(it.userId, it.username, it.username) }
    }

    /** Drops the read tree: the next read of it (a list, [trees]) reads every scope again — after a node was created, or a write met a re-keyed node. */
    fun forgetTree() {
        tree = null
        unreadTrees = emptySet()
        derived.clear()
    }

    /**
     * The tenant closes — a switch, a lock, a sign-out: the tree read and everything derived from it ([fromTree]) are
     * dropped now, not whenever this session is collected. A read after it reads anew.
     */
    fun close() = forgetTree()

    /**
     * What is derived from the tree as last read ([trees]) and held in memory only — never on disk (a person's index,
     * ADR 0144 decision 12): [derive] runs once per tree read and its value is handed out until the tree is read anew
     * ([forgetTree]) or the tenant closes ([close]), which drop it. Keyed by [key], the deriving code's own name; core's
     * session names no application.
     */
    internal suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V {
        val read = trees()
        derived[key]?.let { (from, value) ->
            @Suppress("UNCHECKED_CAST")
            if (from === read) return value as V
        }
        val value = derive(read)
        // a tree forgotten while [derive] ran is not held against: the next call derives from the new read
        if (tree === read) derived[key] = read to value
        return value
    }

    /** Whether a value derived under [key] is held now ([fromTree]): what a test of its lifetime reads. */
    internal fun holdsDerived(key: String): Boolean = key in derived

    private val derived = HashMap<String, Pair<Map<String, List<OpenNode>>, Any>>()

    /** The open scopes whose tree did not read in [trees]' last read (offline with nothing of it kept): listed there as empty. */
    internal var unreadTrees: Set<String> = emptySet()
        private set

    /**
     * The scope epoch this device holds [scopeId] at — its floor for writes (ADR 0109 point 6, layer 2): a node whose key
     * was minted below it is not re-keyed since the rotation, and nothing is sealed under its key. Null for a scope not open.
     */
    internal fun scopeEpoch(scopeId: String): Int? = scopes.firstOrNull { it.summary.id == scopeId }?.summary?.grantedEpoch

    /** How this app re-keys and folds each kind (its composition root's), for a write that pulls its path forward and for
     *  the background run that finishes a scope's re-key ([NodeRekey.pull], [NodeRekey.resume]); null: neither runs. */
    var rekeyRules: NodeRekey.Rules? = null

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val _rekeyed = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Moves each time a re-key may have changed keys this session read: the background run finished something, or a link
     * this device closed re-keyed what was below it ([noteRekeyed]). The models over this session read again on it — the
     * shell its lists, an open Pot its content under its key (`ShellModel`, `PotModel`) —, so no app re-reads by hand.
     */
    val rekeyed: kotlinx.coroutines.flow.StateFlow<Long> = _rekeyed

    /** A re-key ran through this session: the tree is read anew and every model over it reads again ([rekeyed]). */
    fun noteRekeyed() {
        forgetTree()
        _rekeyed.value = _rekeyed.value + 1
    }

    /**
     * Finishes, in the background, the re-key of every scope this session was served as re-keying (ADR 0114 decision 9: a
     * link's close or a rotation another device began, or this one left when it closed) — what this device can, top-down,
     * by [rekeyRules]; a run that cannot finish leaves the marks for the next start or another device. Started when the
     * tenant opens (`AccountDoor.opened`), never by an app. The run's outcome per scope, or null where none is due.
     */
    fun resumeRekeys(): kotlinx.coroutines.Deferred<Map<String, NodeRekey.Outcome>>? {
        if (rekeyRules == null || rekeyingScopes.isEmpty()) return null
        return background.async {
            val ran = runCatching { NodeRekey.resume(this@TenantSession) }.getOrDefault(emptyMap())
            if (ran.isNotEmpty()) noteRekeyed()
            ran
        }
    }

    /** Whether this account owns [scopeId] (an owner re-keys every node, a member all but the owner-only ones). */
    internal fun isScopeOwner(scopeId: String): Boolean =
        scopes.firstOrNull { it.summary.id == scopeId }?.summary?.role == me.raddatz.veyq.mobile.api.models.ScopeSummary.Role.OWNER

    /** The open scopes the server served as re-keying (a node of theirs is due, ADR 0114 decision 4), as this session read
     *  them: what a member's device continues in the background (ADR 0111 decision 11). */
    val rekeyingScopes: List<String> get() = scopes.filter { it.summary.rekeying == true }.map { it.summary.id }

    /** The scope key this device holds [scopeId] at, with its epoch; null for a scope not open. */
    internal fun scopeKey(scopeId: String): Pair<ByteArray, Int>? =
        scopes.firstOrNull { it.summary.id == scopeId }?.let { it.key to it.summary.grantedEpoch }

    /**
     * Each open scope's tree: `listNodes` as a kept read (ADR 0140 decision 5) — its kept `ETag` sent as `If-None-Match`,
     * a newer answer replacing the kept one, the store's answer offline ([offline] then says as of when). A scope the
     * server no longer serves the account (`403`/`404`: a removed membership) lists nothing, and what was kept of it is
     * swept (decision 6). The store is swept once, after every scope was read.
     */
    internal suspend fun trees(): Map<String, List<OpenNode>> = tree ?: run {
        var asOf: OfflineAsOf? = null
        var unkept: Unreachable? = null
        val unread = HashSet<String>()
        val read = try {
            scopes.associate { s ->
                val rows = try {
                    val r = account.kept(KeptRead.NODES, tenant.tenantId, s.summary.id, s.summary.id, conditional = true, settle = false) { tag ->
                        account.fetched(account.respond { api(::ContentApi).listNodes(s.summary.id, tag) }).also { f ->
                            // the revision a live poll asks against ([treeMoved])
                            val now = when (f) {
                                is VeyqAccount.Fetched.Pages -> f.revision
                                is VeyqAccount.Fetched.NotModified -> tag
                                else -> null
                            }
                            if (now != null) treeRevisions[s.summary.id] = now else treeRevisions.remove(s.summary.id)
                        }
                    }
                    if (r.offline != null) asOf = OfflineAsOf(minOfNotNull(asOf?.asOf, r.offline.asOf))
                    VeyqAccount.KEPT_JSON.decodeFromString(ListSerializer(NodeSummary.serializer()), r.pages.single().decodeToString())
                } catch (e: VeyqAccount.Refused) {
                    if (e.status != 403 && e.status != 404) throw e
                    emptyList()
                } catch (e: Unreachable) {
                    // offline, a scope nothing of which was kept lists nothing; the others show what was
                    unkept = e
                    unread += s.summary.id
                    emptyList()
                }
                s.summary.id to Tree.open(account.suite, s.summary.id, s.key, rows)
            }
        } finally {
            account.settle()
        }
        // offline with nothing kept at all: the read failed, as it would have without a store
        if (unkept != null && asOf == null) throw unkept!!
        if (unkept != null && account.reach.value == Reach.Online) throw unkept!!
        offline = asOf
        unreadTrees = unread
        read
    }.also { tree = it }

    /** Where the last read of the tree came from the store with the server unreachable: as of when; null when it was the server's. */
    var offline: OfflineAsOf? = null
        private set

    private fun minOfNotNull(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)

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
