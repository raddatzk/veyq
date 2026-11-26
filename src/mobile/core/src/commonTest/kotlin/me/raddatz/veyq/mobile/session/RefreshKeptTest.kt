package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import io.ktor.http.content.ByteArrayContent
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A remembered account's rotated refresh token that the device's store fails to keep (a Secret Service gone, DPAPI
 * refusing) never fails the call that refreshed (ADR 0123 decision 8): neither the resume at a
 * start nor an API call's refresh after a 401. The session goes on; the store keeps the old token, which the next start
 * finds refused (SESSION_ENDED, a sign-in). Over a server the test plays.
 */
class RefreshKeptTest {
    private val p = testPrimitives
    private val suite = V1(p)

    /** A server the test plays for [sealed]/[iv]'s DEVICE_KEY wrap: each refresh answers the next numbered pair, and
     * the register answers 401 to the access token [Played.expired] names. */
    private class Played {
        var refreshes = 0
        var expired: String? = null
    }

    private fun client(played: Played, sealed: ByteArray, iv: ByteArray, identity: ByteArray): (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient {
        var register = """{"revision":0,"blob":null}""" // what the register's PUT stored, as its GET answers
        fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData = { r ->
            val bearer = r.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            when (r.method to r.url.encodedPath) {
                HttpMethod.Get to "/api/setup" -> json(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""",
                )
                HttpMethod.Post to "/api/token/refresh" -> {
                    played.refreshes++
                    json("""{"accessToken":"access-${played.refreshes}","refreshToken":"refresh-${played.refreshes}"}""")
                }
                HttpMethod.Get to "/api/account/key-material" -> json(
                    """{"userId":"u1","suiteId":"v1","wraps":[{"method":"DEVICE_KEY","id":"w1","wrappedKey":"${b64(sealed)}","kdfParams":"${b64(iv)}"}],"wrappedIdentityPrivateKey":"${b64(identity)}"}""",
                )
                HttpMethod.Get to "/api/scopes/my-tenants" -> json(
                    """[{"tenantId":"t1","name":"A","domain":"a.example","role":"OWNER","status":"ACTIVE","iconEpoch":0}]""",
                )
                HttpMethod.Get to "/api/account/device-register" ->
                    if (bearer == played.expired) json("{}", HttpStatusCode.Unauthorized) else json(register)
                HttpMethod.Put to "/api/account/device-register" -> {
                    val body = (r.body as? TextContent)?.text ?: (r.body as ByteArrayContent).bytes().decodeToString()
                    val sent = Json.parseToJsonElement(body).jsonObject
                    register = """{"revision":${(sent["revision"]?.jsonPrimitive?.longOrNull ?: 0) + 1},"blob":${sent.getValue("blob")}}"""
                    json(register)
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        return { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(MockEngine(handler), block) }
    }

    @Test
    fun aStoreThatFailsToKeepTheRotatedTokenFailsNeitherTheResumeNorTheCall() = runTest {
        val soft = SoftwareDeviceKeys(p)
        val failing = object : DeviceKeys by soft {
            override fun putSecret(alias: String, value: String) = throw DeviceAuthFailed(cancelled = false, detail = "the store is gone")
        }
        val masterKey = p.randomBytes(32)
        val (iv, sealed) = soft.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), StaySignedIn.PRESENCE)
        val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
        val device = testDevice(p, failing)
        val record = TrustedDevice("http://a.example", "p@a.example", "u1", TrustedDevice.Method.DEVICE_KEY, "w1", "veyq.device.test", StaySignedIn.PRESENCE)
        device.remember(record)
        soft.putSecret(record.refreshAlias, "refresh-0")
        val played = Played()
        val client = client(played, sealed, iv, identity)

        // the resume refreshed; the store refused the rotated token; the account opened all the same
        val account = VeyqAccount.resume(p, record, device, client)
        assertEquals(1, played.refreshes)
        assertEquals("u1", account.userId)

        // an API call answered 401 refreshes; the store refuses again; the call is answered all the same
        played.expired = "access-1"
        account.readRegister()
        assertEquals(2, played.refreshes, "the call did not refresh")

        // what the store holds is the token it had: the next start's resume is refused and signs in
        assertEquals("refresh-0", soft.secret(record.refreshAlias))
    }

    /**
     * A TV's PIN account (ADR 0160) keeps each token its open session rotates, as a remembered one does: kept only at the
     * PIN unlock, the stored token went stale at the session's first refresh and the next PIN unlock was refused and
     * forgot the account (tvOS pass 2026-11-26). The session here is resumed over the wrap's DEVICE_KEY, the device's
     * record for the account the PIN one — what the open session reads as its trust.
     */
    @Test
    fun aPinAccountKeepsTheTokenItsOpenSessionRotated() = runTest {
        val soft = SoftwareDeviceKeys(p)
        val masterKey = p.randomBytes(32)
        val (iv, sealed) = soft.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), StaySignedIn.PRESENCE)
        val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
        val device = testDevice(p, soft)
        val opened = TrustedDevice("http://a.example", "p@a.example", "u1", TrustedDevice.Method.DEVICE_KEY, "w1", "veyq.device.test", StaySignedIn.PRESENCE)
        val pin = opened.copy(method = TrustedDevice.Method.DEVICE_PIN, level = null)
        device.remember(pin)
        soft.putSecret(pin.refreshAlias, "refresh-0")
        val played = Played()

        val account = VeyqAccount.resume(p, opened, device, client(played, sealed, iv, identity))
        assertEquals(TrustedDevice.Method.DEVICE_PIN, account.trusted?.method)

        played.expired = "access-${played.refreshes}"
        account.readRegister()
        assertEquals("refresh-${played.refreshes}", soft.secret(pin.refreshAlias), "the rotated token was not kept for the PIN account")
    }
}
