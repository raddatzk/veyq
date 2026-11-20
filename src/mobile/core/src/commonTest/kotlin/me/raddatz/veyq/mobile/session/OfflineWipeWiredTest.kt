package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.deviceFiles
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.offline.EntryKey
import me.raddatz.veyq.mobile.offline.OfflineStores
import me.raddatz.veyq.mobile.offline.freshTestDirectory
import me.raddatz.veyq.mobile.offline.removeAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline store's wipe where ADR 0140 decision 11 wires it (the CR's R10): the account's sign-out, "Stop trusting
 * this device" and removing the device each wipe that account's store — another account's on the same device stays —,
 * and a wipe a crash interrupted is finished at the next start. Over a server the test plays.
 * Bite: drop the wipe from [VeyqAccount.signOut] or from its `forget` and the store's directory outlives it.
 */
class OfflineWipeWiredTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val root = freshTestDirectory()
    private val keys = SoftwareDeviceKeys(p)
    private val prefs = MemoryDeviceStore()
    private val device = ThisDevice(prefs, keys, "test device", p, offlineRoot = root)

    @AfterTest
    fun cleanUp() = removeAll(root)

    /** [userId] remembered on this device and resumed against a played server; its store opened and one entry kept. */
    private suspend fun resumed(userId: String): Pair<VeyqAccount, String> {
        val masterKey = p.randomBytes(32)
        val (iv, sealed) = keys.sealUnlockable(masterKey, "veyq/v1/device-key-wrap".encodeToByteArray(), StaySignedIn.LOGIN)
        val identity = suite.seal(Context("identity-priv"), masterKey, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
        val record = TrustedDevice("http://a.example", "$userId@a.example", userId, TrustedDevice.Method.DEVICE_KEY, "w-$userId", "veyq.device.$userId", StaySignedIn.LOGIN)
        device.remember(record)
        keys.putSecret(record.refreshAlias, "refresh-0")
        fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { r ->
            val path = r.url.encodedPath
            when {
                r.method == HttpMethod.Get && path == "/api/setup" -> json(
                    """{"setupRequired":false,"version":{"major":${VeyqAccount.MAJOR},"build":1},"capabilities":[],"suites":["v1"],"instanceSalt":"${b64(ByteArray(16))}"}""",
                )
                r.method == HttpMethod.Post && path == "/api/token/refresh" -> json("""{"accessToken":"access-1","refreshToken":"refresh-1"}""")
                r.method == HttpMethod.Get && path == "/api/account/key-material" -> json(
                    """{"userId":"$userId","suiteId":"v1","wraps":[{"method":"DEVICE_KEY","id":"w-$userId","wrappedKey":"${b64(sealed)}","kdfParams":"${b64(iv)}"}],"wrappedIdentityPrivateKey":"${b64(identity)}"}""",
                )
                r.method == HttpMethod.Get && path == "/api/scopes/my-tenants" -> json(
                    """[{"tenantId":"t1","name":"A","domain":"a.example","role":"OWNER","status":"ACTIVE","iconEpoch":0}]""",
                )
                r.method == HttpMethod.Get && path == "/api/account/device-register" -> json("""{"revision":0,"blob":null}""")
                r.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                r.method == HttpMethod.Post && path == "/api/session/logout" -> respond("", HttpStatusCode.NoContent)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val account = VeyqAccount.resume(p, record, device, { block -> HttpClient(MockEngine(handler), block) })
        val store = device.offline!!.open(account.key, account.masterKey)
        store.put(EntryKey(account.server, "t1", "s1", "listNodes", "s1"), ByteArray(10))
        store.close()
        return account to store.directory
    }

    private fun exists(dir: String) = deviceFiles.list(dir) != null

    @Test
    fun a_sign_out_wipes_the_accounts_store_and_leaves_anothers() = runTest {
        val (one, oneDir) = resumed("u1")
        val (two, twoDir) = resumed("u2")
        assertTrue(exists(oneDir) && exists(twoDir))
        // a session this device no longer trusts (after "Stop trusting this device" it stays open): only the sign-out's
        // own wipe reaches what it keeps — a remembered account's goes through forgetting its record too
        one.forgetThisDevice()
        assertNull(one.trusted)
        assertFalse(exists(oneDir))
        val kept = device.offline!!.open(one.key, one.masterKey).apply { put(EntryKey(one.server, "t1", "s1", "listNodes", "s1"), ByteArray(10)); close() }
        assertTrue(exists(kept.directory))
        one.signOut()
        assertFalse(exists(kept.directory), "the store outlived its account's sign-out")
        assertFalse(device.offline!!.has(one.key))
        assertTrue(exists(twoDir), "another account's store went with the sign-out")
        two.signOut()
        assertEquals(emptyList(), deviceFiles.list(root))
    }

    @Test
    fun stop_trusting_this_device_and_removing_it_wipe_the_store() = runTest {
        val (one, oneDir) = resumed("u1")
        one.forgetThisDevice()
        assertFalse(exists(oneDir), "the store outlived Stop trusting this device")
        val (two, twoDir) = resumed("u2")
        two.removeThisDevice()
        assertFalse(exists(twoDir), "the store outlived removing this device")
        assertNull(prefs.read("veyq.offline-stores"))
    }

    @Test
    fun a_wipe_a_crash_interrupted_is_finished_at_the_next_start() = runTest {
        val (one, oneDir) = resumed("u1")
        val model = SessionModel.accounts(
            p, device, { block -> HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }, block) },
            Hidden({}, emptySet()), RecentUses({}, emptyList()),
        )
        // the wipe stopped after its first step, as a crash there leaves it
        assertFailsWith<IllegalStateException> {
            device.offline!!.wipe(one.key) { if (it == OfflineStores.WipeStep.MARKED) throw IllegalStateException("crash") }
        }
        assertTrue(exists(oneDir))
        model.start()
        assertFalse(exists(oneDir), "the start did not finish the interrupted wipe")
    }

    /**
     * A rotation this device made (ADR 0148 decision 7, ADR 0140 decision 2 as written): the account takes the new
     * Master Key, and what the store kept under the old one is gone — wiped, not re-wrapped: no file of the old `K_off`
     * is left, but the header the new key wraps — while the store opens anew under the new key. Only the outbox is
     * carried (ADR 0153 decision 2, `OutboxTest`). Bite (CR 3i j): keep the store across [VeyqAccount.swapKeys] and the
     * kept entry outlives it.
     */
    @Test
    fun a_rotation_wipes_the_store_and_opens_it_anew_under_the_new_key() = runTest {
        val (one, oneDir) = resumed("u1")
        val entry = EntryKey(one.server, "t1", "s1", "listNodes", "s1")
        val newMk = p.randomBytes(32)
        val identity = me.raddatz.veyq.mobile.crypto.IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val material = me.raddatz.veyq.mobile.api.models.AccountKeyMaterial(
            "u1", me.raddatz.veyq.mobile.api.models.AccountKeyMaterial.SuiteId.v1, emptyList(),
            b64(suite.seal(Context("identity-priv"), newMk, concat(lp(identity.kemSeed), lp(identity.signSeed)))),
        )
        val before = deviceFiles.list(oneDir).orEmpty().toSet()
        one.swapKeys(newMk, identity, material)
        assertTrue(one.masterKey.contentEquals(newMk))
        val reopened = assertNotNull(one.offline, "the store was not opened anew under the new Master Key")
        assertEquals(setOf(OfflineStores.HEADER_NAME), before intersect deviceFiles.list(reopened.store.directory).orEmpty().toSet(), "the store kept under the old Master Key outlived the rotation")
        assertNull(reopened.store.get(entry))
        one.signOut()
    }
}
