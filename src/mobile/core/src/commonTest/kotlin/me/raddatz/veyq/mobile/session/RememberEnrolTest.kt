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
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Staying signed in whose refresh token the device's store refuses to keep (a Secret Service gone, DPAPI refusing):
 * the DEVICE_KEY wrap just added on the server could never be opened, since no start can resume without the token, so
 * the enrolment deletes it before it fails — and the caller's fallback to the password trust leaves nothing orphaned
 * (CR device-trust-on-linux-and-windows, Phase 4). Over a server the test plays.
 */
class RememberEnrolTest {
    private val p = testPrimitives
    private val suite = V1(p)

    @Test
    fun aRefreshTokenTheStoreRefusesDeletesTheWrapItWasFor() = runTest {
        val soft = SoftwareDeviceKeys(p)
        var refusing = false
        val keys = object : DeviceKeys by soft {
            override fun putSecret(alias: String, value: String) =
                if (refusing) throw DeviceAuthFailed(cancelled = false, detail = "the store is gone") else soft.putSecret(alias, value)
        }
        val masterKey = p.randomBytes(32)
        val (iv, sealed) = soft.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), StaySignedIn.PRESENCE)
        val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
        val device = testDevice(p, keys)
        val record = TrustedDevice("http://a.example", "p@a.example", "u1", TrustedDevice.Method.DEVICE_KEY, "w1", "veyq.device.test", StaySignedIn.PRESENCE)
        device.remember(record)
        soft.putSecret(record.refreshAlias, "refresh-0")

        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData = { r ->
            val path = r.url.encodedPath
            when {
                r.method == HttpMethod.Get && path == "/api/setup" -> json(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""",
                )
                r.method == HttpMethod.Post && path == "/api/token/refresh" -> json("""{"accessToken":"access-1","refreshToken":"refresh-1"}""")
                r.method == HttpMethod.Get && path == "/api/account/key-material" -> json(
                    """{"userId":"u1","suiteId":"v1","wraps":[{"method":"DEVICE_KEY","id":"w1","wrappedKey":"${b64(sealed)}","kdfParams":"${b64(iv)}"}],"wrappedIdentityPrivateKey":"${b64(identity)}"}""",
                )
                r.method == HttpMethod.Get && path == "/api/scopes/my-tenants" -> json(
                    """[{"tenantId":"t1","name":"A","domain":"a.example","role":"OWNER","status":"ACTIVE","iconEpoch":0}]""",
                )
                r.method == HttpMethod.Get && path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
                r.method == HttpMethod.Post && path == "/api/account/wraps" -> {
                    val id = "w${added.size + 2}"
                    added += id
                    json("""{"id":"$id"}""", HttpStatusCode.Created)
                }
                r.method == HttpMethod.Delete && path.startsWith("/api/account/wraps/") -> {
                    deleted += path.removePrefix("/api/account/wraps/")
                    respond("", HttpStatusCode.NoContent)
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(MockEngine(handler), block) }
        val account = VeyqAccount.resume(p, record, device, client)

        // staying signed in anew, the token refused: the enrolment fails as the store's failure, its wrap deleted
        refusing = true
        val failed = assertFailsWith<DeviceAuthFailed> { account.trustThisDevice(null, StaySignedIn.PRESENCE) }
        assertEquals(false, failed.cancelled)
        assertEquals(listOf("w2"), added)
        assertEquals(listOf("w2"), deleted, "the wrap the refused token was for stayed on the server")

        // the trust it had is untouched: its record here, its wrap there
        assertEquals(listOf("w1"), device.trusted().map { it.wrapId })
    }
}
