package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.SessionModel.RecoveryAsked
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in's two entries the web has (ADR 0154 § As built: the plans aligned, P9): the locked sign-in
 * (uc.account.sessions E3 — the login alone, every device signed out, the way back keeping nothing) and *Forgot your
 * password?* (uc.account.recover-access steps 1–2 — the link asked for; the mailed link's page is the web's). The model's
 * half over a door the test plays, the account's half over [AccountDoor] and a server the test plays.
 *
 * Bites (each restored after): the sign-out's two calls swapped in [SessionModel.signOutEverywhere] turns
 * [theLockedSignOutRevokesTheOthersFirstThenThisOne] red; `door.trust` called on the way back's sign-in turns
 * [theWayBackSignsInKeepingNothing] red; the key material read after the login in [VeyqAccount.signInLocked] turns
 * [theLockedLoginReadsNoKeyAndKeepsNothingOnTheDevice] red; the model's `MAIL_UNAVAILABLE` worded as a failed request
 * turns [aTenantWithoutMailAndAFailedAskSayWhy] red (measured 2026-11-23, seven of twelve red, restored with `/bin/cp -f`).
 *
 * The rate limit (inbox #1190): the login's `429` read as `PROTOCOL` again in [SignInFailure.refused] turns
 * [aLoginTheRateLimitRefusedSaysToWait] red; the resume's `429` check deleted from [VeyqAccount.resumeOnline] turns
 * [aResumeTheRateLimitRefusedKeepsTheRecordAndItsToken] red (measured 2026-11-23, restored with `/bin/cp -f`).
 */
class SignInEntriesTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private class Locked(val calls: MutableList<String>) : SessionModel.LockedSession {
        var revokeFails = 0
        var logoutFails = 0
        var dropped = false
        override suspend fun revokeOthers() {
            calls += "revoke-others"
            if (revokeFails-- > 0) throw IllegalStateException("revoke refused")
        }
        override suspend fun logout() {
            calls += "logout"
            if (logoutFails-- > 0) throw IllegalStateException("logout refused")
        }
        override fun drop() { dropped = true }
    }

    private class Door : SessionModel.Door<Acc, String> {
        val calls = mutableListOf<String>()
        val trusted = mutableListOf<String>()
        var locked: Locked? = null
        var lockedRefusal: SignInFailure.Reason? = null
        var recovery: RecoveryAsked = RecoveryAsked.SENT
        val asked = mutableListOf<Pair<String, String>>()
        private val acc = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example")))

        override val primitives = testPrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String) = acc.also { calls += "sign-in $server" }
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray) = acc
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = acc
        override suspend fun resume(record: TrustedDevice) = acc
        override suspend fun trust(account: Acc, password: String) { trusted += account.key }
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.DONE
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.DONE
        override fun defaultTenant(account: Acc) = account.tenants.first()
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = emptyList<SpaceRef>()
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = setOf(StaySignedIn.LOGIN)
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
        override suspend fun signInLocked(server: String, email: String, password: String): SessionModel.LockedSession {
            calls += "locked $server $email"
            lockedRefusal?.let { throw SignInFailure(it) }
            return Locked(calls).also { locked = it }
        }
        override suspend fun requestRecovery(server: String, email: String): RecoveryAsked {
            asked += server to email
            return recovery
        }
    }

    private fun model(door: Door) = SessionModel(door, Hidden({}, emptySet()))

    private suspend fun signInGate(door: Door): SessionModel<Acc, String> = model(door).also { it.start() }

    @Test
    fun theSignInListsTheLockedEntryAfterSubmitAndForgotBeforeOpenALink() {
        assertEquals(
            listOf(
                SignInEntry.SUBMIT, SignInEntry.CANCEL, SignInEntry.LOCKED, SignInEntry.LINK_SHOW, SignInEntry.LINK_SCAN,
                SignInEntry.KIT_OPEN, SignInEntry.KIT_SCAN, SignInEntry.FORGOT, SignInEntry.OPEN_LINK,
            ),
            SignInPlan.entries(adding = true, camera = true),
        )
        assertEquals(listOf(LockedEntry.SIGN_OUT_EVERYWHERE, LockedEntry.BACK), LockedPlan.entries())
        assertEquals(listOf(RecoverEntry.SEND, RecoverEntry.BACK), RecoverPlan.entries(sent = false))
        assertEquals(listOf(RecoverEntry.BACK), RecoverPlan.entries(sent = true))
    }

    @Test
    fun theLockedSignOutRevokesTheOthersFirstThenThisOne() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.signInLocked("a.example", " p@a.example ", "pw")
        assertEquals(Gate.Locked(server = "a.example", email = "p@a.example"), m.current)
        assertEquals(listOf("locked https://a.example p@a.example"), d.calls)
        assertEquals(emptyList(), d.trusted, "the locked sign-in enrolled a trust")

        m.signOutEverywhere()
        assertEquals(listOf("revoke-others", "logout"), d.calls.drop(1))
        assertTrue(d.locked!!.dropped)
        assertEquals(Gate.SignIn(server = "a.example", email = "p@a.example", notice = Msg.LOCKED_DONE), m.current)
    }

    @Test
    fun aRevokeThatFailsCutsNothingOffAndKeepsTheSessionForTheRetry() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.signInLocked("a.example", "p@a.example", "pw")
        d.locked!!.revokeFails = 1
        m.signOutEverywhere()
        assertEquals(Gate.Locked("a.example", "p@a.example", error = Msg.ACCOUNT_SETTINGS_REVOKE_FAILED), m.current)
        assertEquals(listOf("revoke-others"), d.calls.drop(1), "this device signed out after a revoke that failed")
        assertEquals(false, d.locked!!.dropped)

        m.signOutEverywhere()
        assertEquals(Msg.LOCKED_DONE, (m.current as Gate.SignIn).notice)
    }

    @Test
    fun aSignOutOfThisDeviceThatFailsSaysSoAndRunsAgain() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.signInLocked("a.example", "p@a.example", "pw")
        d.locked!!.logoutFails = 1
        m.signOutEverywhere()
        assertEquals(Gate.Locked("a.example", "p@a.example", error = Msg.LOCKED_DONE_STILL_HERE), m.current)
        assertEquals(false, d.locked!!.dropped)
        m.signOutEverywhere()
        assertEquals(listOf("revoke-others", "logout", "revoke-others", "logout"), d.calls.drop(1))
        assertTrue(d.locked!!.dropped)
        assertEquals(Msg.LOCKED_DONE, (m.current as Gate.SignIn).notice)
    }

    @Test
    fun aLockedLoginThatFailsStaysOnTheSignInWithWhy() = runTest {
        val d = Door().apply { lockedRefusal = SignInFailure.Reason.CREDENTIALS }
        val m = signInGate(d)
        m.signInLocked("a.example", "p@a.example", "wrong")
        assertEquals(Gate.SignIn(error = Msg.SIGNIN_ERROR_CREDENTIALS), m.current)
        // nothing without the email or the password: the web's entry is disabled then
        d.lockedRefusal = null
        m.signInLocked("a.example", "", "pw")
        m.signInLocked("a.example", "p@a.example", "")
        assertEquals(1, d.calls.size)
    }

    @Test
    fun theWayBackSignsInKeepingNothing() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.signInLocked("a.example", "p@a.example", "pw")
        m.lockedBack()
        assertEquals(listOf("logout"), d.calls.drop(1), "the locked session outlived the way back")
        assertTrue(d.locked!!.dropped)
        assertEquals(Gate.SignIn(server = "a.example", email = "p@a.example", keepsNothing = true), m.current)

        val opened = m.signIn("a.example", "p@a.example", "pw", "A3-SECRET")
        assertEquals("a.example#u1/ta", opened?.session)
        assertEquals(emptyList(), d.trusted, "the way back's sign-in enrolled a trust")
        assertEquals(Gate.Signed("a.example#u1", "ta", offer = null), m.current)
    }

    @Test
    fun anOrdinarySignInStillTrustsAndOffers() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.signIn("a.example", "p@a.example", "pw", "A3-SECRET")
        assertEquals(listOf("a.example#u1"), d.trusted)
        assertIs<SessionModel.Offer>((m.current as Gate.Signed).offer)
    }

    @Test
    fun forgotPasswordAsksForTheLinkAndBackKeepsTheForm() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.forgotPassword("a.example", " p@a.example ")
        assertEquals(Gate.Recover(server = "a.example", email = "p@a.example"), m.current)
        assertEquals(listOf(RecoverEntry.SEND, RecoverEntry.BACK), RecoverPlan.entries((m.current as Gate.Recover).sent))

        m.requestRecovery("a.example", "p@a.example")
        assertEquals(listOf("https://a.example" to "p@a.example"), d.asked)
        assertEquals(Gate.Recover(server = "a.example", email = "p@a.example", sent = true), m.current)
        m.requestRecovery("a.example", "p@a.example") // sent: no second ask from this screen
        assertEquals(1, d.asked.size)

        m.backToSignIn()
        assertEquals(Gate.SignIn(server = "a.example", email = "p@a.example"), m.current)
    }

    @Test
    fun aRecoveryFinishedHereFillsTheSignInFromTheGatesBeforeAShell() = runTest {
        val d = Door()
        val m = signInGate(d)
        m.forgotPassword("a.example", "p@a.example")
        m.recovered("https://b.example", "q@b.example")
        assertEquals(Gate.SignIn(server = "https://b.example", email = "q@b.example"), m.current)
        m.recovered("https://c.example", "r@c.example") // from the sign-in itself too
        assertEquals(Gate.SignIn(server = "https://c.example", email = "r@c.example"), m.current)
    }

    @Test
    fun aTenantWithoutMailAndAFailedAskSayWhy() = runTest {
        val d = Door().apply { recovery = RecoveryAsked.MAIL_UNAVAILABLE }
        val m = signInGate(d)
        m.forgotPassword(null, null)
        assertEquals(Gate.Recover(), m.current)
        m.requestRecovery("", "p@a.example")
        assertEquals(emptyList(), d.asked, "asked with no server")
        m.requestRecovery("a.example", "p@a.example")
        assertEquals(Msg.RECOVERY_MAIL_UNAVAILABLE, (m.current as Gate.Recover).error)
        d.recovery = RecoveryAsked.FAILED
        m.requestRecovery("a.example", "p@a.example")
        assertEquals(Gate.Recover(server = "a.example", email = "p@a.example", error = Msg.RECOVERY_REQUEST_FAILED), m.current)
    }

    // --- the account's half, over AccountDoor and a played server ---

    private val p = testPrimitives
    private val suite = V1(p)
    private val email = "p@a.example"
    private val password = "throwaway horse"
    private val instanceSalt = ByteArray(16) { 7 }

    /** A device store that records every write: the locked login must make none. */
    private class CountingStore : DeviceStore {
        private val inner = MemoryDeviceStore()
        val writes = mutableListOf<String>()
        override fun read(key: String) = inner.read(key)
        override fun write(key: String, value: String) { writes += key; inner.write(key, value) }
        override fun remove(key: String) { writes += "-$key"; inner.remove(key) }
    }

    private inner class Server {
        val seen = mutableListOf<String>()
        var recoveryStatus = HttpStatusCode.Accepted
        var expireFirstAccess = false
        /** The one path the server answers `429 rate_limited` on (ADR 0051's per-address budget), or null. */
        var limited: String? = null
        /** The status `/api/login/verify` answers with in place of its own, or null. */
        var verifyStatus: HttpStatusCode? = null
        val bodies = mutableListOf<String>()
        private val authPublic = p.ed25519Public(suite.authSeed(password, suite.authSalt(instanceSalt, email)))

        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(MockEngine { r -> handle(r) }, block) }

        private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

        private fun text(r: HttpRequestData) = (r.body as? TextContent)?.text ?: (r.body as ByteArrayContent).bytes().decodeToString()

        private fun MockRequestHandleScope.handle(r: HttpRequestData): io.ktor.client.request.HttpResponseData {
            val path = r.url.encodedPath
            val bearer = r.headers[HttpHeaders.Authorization]
            seen += "${r.method.value} $path${bearer?.let { " $it" } ?: ""}"
            return when {
                path == limited -> respond(
                    """{"code":"rate_limited"}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("17")),
                )
                path == "/api/login/verify" && verifyStatus != null -> json("{}", verifyStatus!!)
                r.method == HttpMethod.Get && path == "/api/setup" -> json(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(instanceSalt)}"}""",
                )
                r.method == HttpMethod.Post && path == "/api/login/challenge" ->
                    json("""{"nonce":"${b64(ByteArray(32) { 3 })}","instanceSalt":"${b64(instanceSalt)}"}""")
                r.method == HttpMethod.Post && path == "/api/login/verify" -> {
                    val sent = Json.parseToJsonElement(text(r)).jsonObject
                    val ok = p.ed25519Verify(authPublic, unb64(sent.getValue("nonce").jsonPrimitive.content), unb64(sent.getValue("signature").jsonPrimitive.content))
                    if (!ok) json("""{"code":"invalid_credentials"}""", HttpStatusCode.Unauthorized)
                    else json("""{"accessToken":"login-access","refreshToken":"login-refresh","family":{"id":"family-1"}}""")
                }
                r.method == HttpMethod.Post && path == "/api/token/refresh" -> json("""{"accessToken":"renewed-access","refreshToken":"renewed-refresh"}""")
                r.method == HttpMethod.Post && path == "/api/sessions/revoke-others" ->
                    if (expireFirstAccess && bearer == "Bearer login-access") json("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)
                    else respond("", HttpStatusCode.NoContent)
                r.method == HttpMethod.Post && path == "/api/logout" -> respond("", HttpStatusCode.NoContent)
                r.method == HttpMethod.Post && path == "/api/recovery/request" -> {
                    bodies += text(r)
                    if (recoveryStatus == HttpStatusCode.Accepted) respond("", recoveryStatus)
                    else json("""{"code":"mail_unavailable"}""", recoveryStatus)
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun theLockedLoginReadsNoKeyAndKeepsNothingOnTheDevice() = runTest {
        val server = Server()
        val store = CountingStore()
        val device = ThisDevice(store, SoftwareDeviceKeys(p), "test device", p)
        val door = AccountDoor(p, device, server.client)
        val session = door.signInLocked("http://a.example", email, password)
        assertEquals(
            listOf("GET /api/setup", "POST /api/login/challenge", "POST /api/login/verify"),
            server.seen,
            "the locked login read more than the login",
        )
        session.revokeOthers()
        session.logout()
        session.drop()
        assertEquals(
            listOf("POST /api/sessions/revoke-others Bearer login-access", "POST /api/logout Bearer login-access"),
            server.seen.drop(3),
        )
        assertEquals(emptyList(), store.writes, "the locked login kept something on the device")
        assertEquals(emptyList(), device.trusted())
    }

    @Test
    fun anExpiredAccessTokenIsRenewedOnceAndAWrongPasswordIsRefused() = runTest {
        val server = Server().apply { expireFirstAccess = true }
        val door = AccountDoor(p, ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "test device", p), server.client)
        val session = door.signInLocked("http://a.example", email, password)
        session.revokeOthers()
        assertEquals(
            listOf(
                "POST /api/sessions/revoke-others Bearer login-access",
                "POST /api/token/refresh",
                "POST /api/sessions/revoke-others Bearer renewed-access",
            ),
            server.seen.drop(3),
        )
        session.drop()

        val refused = assertFailsWith<SignInFailure> { door.signInLocked("http://a.example", email, "not the password") }
        assertEquals(SignInFailure.Reason.CREDENTIALS, refused.reason)
    }

    @Test
    fun theRecoveryAskMapsTheServersThreeAnswers() = runTest {
        val server = Server()
        val door = AccountDoor(p, ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "test device", p), server.client)
        assertEquals(RecoveryAsked.SENT, door.requestRecovery("http://a.example", email))
        assertEquals("""{"email":"$email"}""", server.bodies.single())
        assertNull(server.seen.single().substringAfter("/api/recovery/request").trim().ifEmpty { null }, "the ask carried a bearer")
        server.recoveryStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(RecoveryAsked.MAIL_UNAVAILABLE, door.requestRecovery("http://a.example", email))
        server.recoveryStatus = HttpStatusCode.TooManyRequests
        assertEquals(RecoveryAsked.FAILED, door.requestRecovery("http://a.example", email))
        val unreachable = AccountDoor(p, ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "test device", p)) { block ->
            HttpClient(MockEngine { throw IllegalStateException("no route") }, block)
        }
        assertEquals(RecoveryAsked.FAILED, unreachable.requestRecovery("http://a.example", email))
    }

    @Test
    fun aLoginTheRateLimitRefusedSaysToWait() = runTest {
        val server = Server()
        val door = AccountDoor(p, ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "test device", p), server.client)
        for (step in listOf("/api/login/challenge", "/api/login/verify")) {
            server.limited = step
            val refused = assertFailsWith<SignInFailure> { door.signInLocked("http://a.example", email, password) }
            assertEquals(SignInFailure.Reason.RATE_LIMITED, refused.reason, "a 429 at $step")
        }
        // the control: another refusal of the verify is no rate limit
        server.limited = null
        server.verifyStatus = HttpStatusCode.InternalServerError
        assertEquals(SignInFailure.Reason.PROTOCOL, assertFailsWith<SignInFailure> { door.signInLocked("http://a.example", email, password) }.reason)
        // and the sign-in screen says the owner's words for it, not "signing in did not work"
        assertEquals(Msg.SIGNIN_ERROR_RATE_LIMITED, SessionModel.words(SignInFailure.Reason.RATE_LIMITED))
    }

    @Test
    fun aResumeTheRateLimitRefusedKeepsTheRecordAndItsToken() = runTest {
        // the refresh shares the login's per-address budget (`TokenResource`, ADR 0051): its 429 ends no session, so the
        // remembered device keeps its record, its kept token and its store, where any other 4xx forgets them
        val server = Server().apply { limited = "/api/token/refresh" }
        val keys = SoftwareDeviceKeys(p)
        val device = ThisDevice(MemoryDeviceStore(), keys, "test device", p)
        val record = TrustedDevice("http://a.example", email, "u1", TrustedDevice.Method.DEVICE_KEY, "w1", "veyq.device.test", StaySignedIn.PRESENCE)
        device.remember(record)
        keys.putSecret(record.refreshAlias, "refresh-0")
        val refused = assertFailsWith<SignInFailure> { VeyqAccount.resume(p, record, device, server.client) }
        assertEquals(SignInFailure.Reason.RATE_LIMITED, refused.reason)
        assertEquals(listOf(record), device.trusted(), "the rate limit forgot the remembered device")
        assertEquals("refresh-0", keys.secret(record.refreshAlias), "the rate limit deleted the kept token")
        // the control: a refresh the server refuses (401) still ends the remembered session
        val refusing = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit ->
            HttpClient(MockEngine { r ->
                if (r.url.encodedPath == "/api/token/refresh") respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                else respond(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(instanceSalt)}"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }, block)
        }
        assertEquals(SignInFailure.Reason.SESSION_ENDED, assertFailsWith<SignInFailure> { VeyqAccount.resume(p, record, device, refusing) }.reason)
        assertEquals(emptyList(), device.trusted())
    }
}
