package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The device trust against a running dev instance, as the seeded principal (`VEYQ_LIVE`): password-trusted (DEVICE)
 * opens with the password alone and survives sign-out; remembered (DEVICE_KEY) resumes with nothing typed after the
 * person authenticates, keeps the rotated refresh token, refuses without the authentication, and is gone after sign-out;
 * every unlock names the device in the account's register. Each run removes what it enrolled. What it asserts of the
 * keys is `DeviceKeys`' observable behaviour (DeviceKeysContract's), so it holds over any store; only [SoftwareDeviceKeys.prompts]
 * and [SoftwareDeviceKeys.authenticates], the fake's extras, play and count the person.
 */
class DeviceTrustLiveTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }

    @Test
    fun aTrustedDeviceUnlocksWithThePasswordAndARememberedOneWithNothingTyped() = runTest(timeout = 3.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val email = env.getValue("VEYQ_EMAIL")
        val password = env.getValue("VEYQ_PASSWORD")
        val keys = SoftwareDeviceKeys(p)
        val device = testDevice(p, keys)

        // Signed in with the Secret Key, this device is trusted with the password (the default).
        val first = VeyqAccount.signIn(p, origin, email, password, env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.TRUSTED, first.trustThisDevice(password, stayingSignedIn = false))
        val trusted = assertNotNull(device.trusted(first.key))
        assertEquals(TrustedDevice.Method.DEVICE, trusted.method)
        first.signOut()
        assertNotNull(device.trusted(first.key), "signing out forgot a password-trusted device")

        // The password alone opens it; a wrong one does not.
        assertEquals(
            SignInFailure.Reason.CREDENTIALS,
            assertFailsWith<SignInFailure> { VeyqAccount.unlockOnDevice(p, trusted, password + "x", device, client) }.reason,
        )
        val second = VeyqAccount.unlockOnDevice(p, trusted, password, device, client)
        assertTrue(first.tenants.map { it.tenantId } == second.tenants.map { it.tenantId })
        val register = second.readRegister()
        assertEquals("test device", register.devices[device.id]?.label, "the device did not name itself in the register")
        assertEquals(device.id, register.wraps[trusted.wrapId], "the register does not join the wrap to the device")
        assertTrue(device.id in register.families.values, "no unlock joined its session family to the device (write moment 1)")

        // Staying signed in replaces the password trust: one wrap per device, the bind key gone.
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, second.trustThisDevice(null, stayingSignedIn = true))
        val remembered = assertNotNull(device.trusted(second.key))
        assertEquals(TrustedDevice.Method.DEVICE_KEY, remembered.method)
        assertNull(keys.bindHmac(trusted.alias, "veyq/v1/device-bind".encodeToByteArray()), "the password trust's key was kept")
        val tokenAtEnrol = keys.secret(remembered.refreshAlias)
        second.http.close() // the app is closed, not signed out

        // A remembered account resumes once the person authenticates, and keeps the rotated refresh token.
        val prompts = keys.prompts
        val third = VeyqAccount.resume(p, remembered, device, client)
        assertEquals(prompts + 1, keys.prompts)
        assertNotEquals(tokenAtEnrol, keys.secret(remembered.refreshAlias), "the rotated refresh token was not kept")
        assertEquals(first.tenants.map { it.tenantId }, third.tenants.map { it.tenantId })
        third.http.close()

        // Without the authentication it stays closed, and stays remembered.
        keys.authenticates = false
        assertEquals(
            SignInFailure.Reason.DEVICE_AUTH_CANCELLED,
            assertFailsWith<SignInFailure> { VeyqAccount.resume(p, remembered, device, client) }.reason,
        )
        assertNotNull(device.trusted(remembered.key))
        keys.authenticates = true

        // Signing out ends staying signed in: the record, the token and the unlock key go, and the wrap on the server.
        val fourth = VeyqAccount.resume(p, remembered, device, client)
        val canary = keys.sealUnlockable(byteArrayOf(1), ByteArray(0))
        fourth.signOut()
        assertNull(device.trusted(remembered.key))
        assertNull(keys.secret(remembered.refreshAlias))
        val gone = assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(canary.first, canary.second, ByteArray(0)) }
        assertFalse(gone.cancelled, "the unlock key survived the sign-out")
    }
}
