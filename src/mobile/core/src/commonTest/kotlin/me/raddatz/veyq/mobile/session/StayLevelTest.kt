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
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.crypto.unb64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A change of this device's trust while signed in (the CR device-trust-on-linux-and-windows, D7), over a server the
 * test plays and the fake's keys: from one level to the other (the wrap replaced, the old level's key gone, the resume
 * at the new level), a cancelled presence prompt (nothing changed), and back to the password trust (a wrong password
 * told apart, the right one a DEVICE wrap that opens with it). Through [AccountDoor], which maps each outcome.
 */
class StayLevelTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val aad = "veyq/v1/device-key-wrap".encodeToByteArray()
    private val email = "p@a.example"
    private val password = "correct horse"
    private val instanceSalt = ByteArray(16) { 7 }

    private class Wrap(val method: String, val id: String, val wrappedKey: String, val kdfParams: String)

    /** The server: its wraps, the sessions it revoked, and what the account asks of it. */
    private inner class Server(first: Wrap) {
        val wraps = mutableListOf(first)
        val revoked = mutableListOf<String>()
        var next = 2
        private var refreshes = 0
        private val authPublic = p.ed25519Public(suite.authSeed(password, suite.authSalt(instanceSalt, email)))

        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(MockEngine { r -> handle(r) }, block) }

        private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

        private fun body(r: HttpRequestData) = Json.parseToJsonElement(
            (r.body as? TextContent)?.text ?: (r.body as ByteArrayContent).bytes().decodeToString(),
        ).jsonObject

        private fun MockRequestHandleScope.handle(r: HttpRequestData): io.ktor.client.request.HttpResponseData {
            val path = r.url.encodedPath
            return when {
                r.method == HttpMethod.Get && path == "/api/setup" -> json(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(instanceSalt)}"}""",
                )
                r.method == HttpMethod.Post && path == "/api/token/refresh" -> {
                    refreshes++
                    json("""{"accessToken":"access-$refreshes","refreshToken":"refresh-$refreshes"}""")
                }
                r.method == HttpMethod.Post && path == "/api/login/challenge" ->
                    json("""{"nonce":"${b64(ByteArray(32) { 3 })}","instanceSalt":"${b64(instanceSalt)}"}""")
                r.method == HttpMethod.Post && path == "/api/login/verify" -> {
                    val sent = body(r)
                    val ok = p.ed25519Verify(authPublic, unb64(sent.getValue("nonce").jsonPrimitive.content), unb64(sent.getValue("signature").jsonPrimitive.content))
                    if (!ok) json("""{"code":"invalid_credentials"}""", HttpStatusCode.Unauthorized)
                    else json("""{"accessToken":"login-access","refreshToken":"login-refresh","family":{"id":"family-${next++}"}}""")
                }
                r.method == HttpMethod.Delete && path.startsWith("/api/sessions/") -> {
                    revoked += path.removePrefix("/api/sessions/")
                    respond("", HttpStatusCode.NoContent)
                }
                r.method == HttpMethod.Get && path == "/api/account/key-material" -> json(
                    """{"userId":"u1","suiteId":"v1","wraps":[${wraps.joinToString(",") { """{"method":"${it.method}","id":"${it.id}","wrappedKey":"${it.wrappedKey}","kdfParams":"${it.kdfParams}"}""" }}],"wrappedIdentityPrivateKey":"${b64(identity)}"}""",
                )
                r.method == HttpMethod.Get && path == "/api/scopes/my-tenants" -> json(
                    """[{"tenantId":"t1","name":"A","domain":"a.example","role":"OWNER","status":"ACTIVE","iconEpoch":0}]""",
                )
                r.method == HttpMethod.Get && path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
                r.method == HttpMethod.Post && path == "/api/account/wraps" -> {
                    val sent = body(r)
                    val id = "w${next++}"
                    wraps += Wrap(sent.getValue("method").jsonPrimitive.content, id, sent.getValue("wrappedKey").jsonPrimitive.content, sent.getValue("kdfParams").jsonPrimitive.content)
                    json("""{"id":"$id"}""", HttpStatusCode.Created)
                }
                r.method == HttpMethod.Delete && path.startsWith("/api/account/wraps/") -> {
                    wraps.removeAll { it.id == path.removePrefix("/api/account/wraps/") }
                    respond("", HttpStatusCode.NoContent)
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
    }

    private val masterKey = p.randomBytes(32)
    private val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))

    /** A device staying signed in to the account at LOGIN, as an offer's answer left it, and the server holding its wrap. */
    private suspend fun stayingAtLogin(): Triple<SoftwareDeviceKeys, ThisDevice, Server> {
        val keys = SoftwareDeviceKeys(p)
        val device = testDevice(p, keys)
        val (iv, sealed) = keys.sealUnlockable(masterKey, aad, StaySignedIn.LOGIN)
        val record = TrustedDevice("http://a.example", email, "u1", TrustedDevice.Method.DEVICE_KEY, "w1", "veyq.device.first", StaySignedIn.LOGIN)
        device.remember(record)
        keys.putSecret(record.refreshAlias, "refresh-0")
        return Triple(keys, device, Server(Wrap("DEVICE_KEY", "w1", b64(sealed), b64(iv))))
    }

    @Test
    fun aLevelChangedReplacesTheWrapDropsTheOldKeyAndResumesAtTheNewLevel() = runTest {
        val (keys, device, server) = stayingAtLogin()
        val door = AccountDoor(p, device, server.client)
        val account = door.resume(device.trusted().single())
        assertEquals(0, keys.prompts, "the login level asked the person")

        assertEquals(TrustChange.DONE, door.stay(account, StaySignedIn.PRESENCE))
        val now = device.trusted().single()
        assertEquals(TrustedDevice.Method.DEVICE_KEY, now.method)
        assertEquals(StaySignedIn.PRESENCE, now.level)
        assertEquals(listOf(now.wrapId), server.wraps.map { it.id }, "the login wrap stayed on the server: one wrap per device")
        assertFalse(keys.hasUnlockKey(StaySignedIn.LOGIN), "the login level's key outlived its last account")
        assertNull(keys.secret("veyq.device.first.refresh"), "the login record's token stayed")

        // the next start resumes at the new level: the person asked once, the Master Key the same
        val prompts = keys.prompts
        val resumed = VeyqAccount.resume(p, now, device, server.client)
        assertEquals(prompts + 1, keys.prompts)
        assertTrue(resumed.masterKey.contentEquals(masterKey))
    }

    @Test
    fun aCancelledPresencePromptChangesNothing() = runTest {
        val (keys, device, server) = stayingAtLogin()
        val door = AccountDoor(p, device, server.client)
        val account = door.resume(device.trusted().single())
        val before = device.trusted().single()
        keys.authenticates = false
        assertEquals(TrustChange.CANCELLED, door.stay(account, StaySignedIn.PRESENCE))
        assertEquals(before, device.trusted().single(), "a cancelled prompt changed the record")
        assertEquals(listOf("w1"), server.wraps.map { it.id })
        assertTrue(keys.hasUnlockKey(StaySignedIn.LOGIN))
        assertFalse(keys.hasUnlockKey(StaySignedIn.PRESENCE), "a cancelled prompt left a presence key")
    }

    @Test
    fun backToThePasswordTrustChecksThePasswordAndOpensWithItAlone() = runTest(timeout = 2.minutes) {
        val (keys, device, server) = stayingAtLogin()
        val door = AccountDoor(p, device, server.client)
        val account = door.resume(device.trusted().single())
        val before = device.trusted().single()

        assertEquals(TrustChange.WRONG_PASSWORD, door.trustWithPassword(account, "$password!"))
        assertEquals(before, device.trusted().single(), "a wrong password changed the trust")
        assertEquals(listOf("w1"), server.wraps.map { it.id })

        assertEquals(TrustChange.DONE, door.trustWithPassword(account, password))
        val back = device.trusted().single()
        assertEquals(TrustedDevice.Method.DEVICE, back.method)
        assertNull(back.level)
        assertEquals(listOf("DEVICE"), server.wraps.map { it.method }, "the DEVICE_KEY wrap stayed beside the password trust")
        assertEquals(listOf("family-2"), server.revoked, "the password check's own session was not revoked")
        assertFalse(keys.hasUnlockKey(StaySignedIn.LOGIN), "the unlock key outlived the way back")
        assertNull(keys.secret(before.refreshAlias), "the remembered token outlived the way back")

        // the next start: the password alone opens it
        val unlocked = VeyqAccount.unlockOnDevice(p, back, password, device, server.client)
        assertTrue(unlocked.masterKey.contentEquals(masterKey))
        assertNotEquals(before.wrapId, back.wrapId)
    }
}
