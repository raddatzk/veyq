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
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An address change is confirmed on the web, and the app takes the new address at its next sign-in (ADR 0148 decision
 * 6): the record this device keeps is the account's by its id, so the sign-in with the new address finds it — and from
 * then on names the new address (the account list, the next unlock's login). Over a server the test plays.
 */
class ReaddressTest {
    private val p = testPrimitives
    private val suite = V1(p)

    @Test
    fun theSignInWithTheNewAddressReaddressesTheRecordThisDeviceKeeps() = runTest {
        val masterKey = p.randomBytes(32)
        val secretKey = p.randomBytes(32)
        val salt = p.randomBytes(16)
        val floor = suite.seal(Context("master-key"), suite.kekPasswordSecret(secretKey, "pw", salt), masterKey)
        val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
        val device = testDevice(p, SoftwareDeviceKeys(p))
        device.remember(TrustedDevice("http://a.example", "old@a.example", "u1", TrustedDevice.Method.DEVICE, "w1", "veyq.device.test"))

        fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData = { r ->
            val path = r.url.encodedPath
            when {
                path == "/api/setup" -> json(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""",
                )
                path == "/api/login/challenge" -> json("""{"nonce":"${b64(ByteArray(32))}","instanceSalt":"${b64(ByteArray(16))}"}""")
                path == "/api/login/verify" -> json(
                    """{"accessToken":"a","refreshToken":"r","family":{"id":"f1","createdAt":"2026-11-18T00:00:00Z","lastUsedAt":"2026-11-18T00:00:00Z","current":true}}""",
                )
                path == "/api/account/key-material" -> json(
                    """{"userId":"u1","suiteId":"v1","wraps":[{"method":"PASSWORD","id":"w0","wrappedKey":"${b64(floor)}","kdfParams":"${b64(suite.encodeKdfParams(V1.ARGON, salt))}"},{"method":"DEVICE","id":"w1","wrappedKey":"${b64(ByteArray(60))}","kdfParams":"${b64(ByteArray(32))}"}],"wrappedIdentityPrivateKey":"${b64(identity)}"}""",
                )
                path == "/api/scopes/my-tenants" -> json("""[{"tenantId":"t1","name":"A","domain":"a.example","role":"OWNER","status":"ACTIVE","iconEpoch":0}]""")
                r.method == HttpMethod.Get && path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(MockEngine(handler), block) }
        val account = VeyqAccount.signIn(p, "http://a.example", "New@A.example", "pw", SecretKeyText.format(p, secretKey), device, client)
        assertEquals("New@A.example", account.trusted?.email, "the record kept the old address")
        assertEquals(listOf("New@A.example"), device.trusted().map { it.email })
        assertEquals("w1", device.trusted().single().wrapId, "re-addressing changed more than the address")
    }
}
