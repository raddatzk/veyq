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
 * The device trust against a running dev instance, as the seeded principal (`VEYQ_LIVE`), as D7 (the CR
 * device-trust-on-linux-and-windows) enrols it: the sign-in holds the password trust (DEVICE), which opens with the
 * password alone and survives sign-out; staying signed in is taken afterwards, at a level — LOGIN resumes with nothing
 * asked, PRESENCE after the person authenticates; a change of level replaces the wrap and the old level's key; the way
 * back to the password trust checks the password first; sign-out ends staying signed in. Every unlock names the device
 * in the account's register. Each run removes what it enrolled. What it asserts of the keys is `DeviceKeys`' observable
 * behaviour (DeviceKeysContract's), so it holds over any store; only [SoftwareDeviceKeys.prompts] and
 * [SoftwareDeviceKeys.authenticates], the fake's extras, play and count the person.
 */
class DeviceTrustLiveTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }
    private val bind = "veyq/v1/device-bind".encodeToByteArray()

    @Test
    fun aTrustedDeviceUnlocksWithThePasswordAndARememberedOneAtEachLevel() = runTest(timeout = 3.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        // `.run/.env` of this checkout, or VEYQ_ENV_FILE's (a worktree has none)
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val email = env.getValue("VEYQ_EMAIL")
        val password = env.getValue("VEYQ_PASSWORD")
        val keys = SoftwareDeviceKeys(p)
        val device = testDevice(p, keys)

        // Signed in with the Secret Key, this device is trusted with the password (what every sign-in holds).
        val first = VeyqAccount.signIn(p, origin, email, password, env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.TRUSTED, first.trustThisDevice(password, level = null))
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

        // Staying signed in at LOGIN replaces the password trust: one wrap per device, the bind key gone, nothing asked.
        val promptsAtLogin = keys.prompts
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, second.trustThisDevice(null, StaySignedIn.LOGIN))
        val atLogin = assertNotNull(device.trusted(second.key))
        assertEquals(TrustedDevice.Method.DEVICE_KEY, atLogin.method)
        assertEquals(StaySignedIn.LOGIN, atLogin.level)
        assertNull(keys.bindHmac(trusted.alias, bind), "the password trust's key was kept")
        assertEquals(promptsAtLogin, keys.prompts, "the login level asked the person")
        val tokenAtEnrol = keys.secret(atLogin.refreshAlias)
        second.http.close() // the app is closed, not signed out

        // It resumes with nothing asked, and keeps the rotated refresh token.
        val third = VeyqAccount.resume(p, atLogin, device, client)
        assertEquals(promptsAtLogin, keys.prompts, "the login level's resume asked the person")
        assertNotEquals(tokenAtEnrol, keys.secret(atLogin.refreshAlias), "the rotated refresh token was not kept")
        assertEquals(first.tenants.map { it.tenantId }, third.tenants.map { it.tenantId })

        // Changed to PRESENCE: the wrap replaced (the login one no longer served), the login key gone.
        val loginCanary = keys.sealUnlockable(byteArrayOf(2), ByteArray(0), StaySignedIn.LOGIN)
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, third.trustThisDevice(null, StaySignedIn.PRESENCE))
        val atPresence = assertNotNull(device.trusted(third.key))
        assertEquals(StaySignedIn.PRESENCE, atPresence.level)
        assertNotEquals(atLogin.wrapId, atPresence.wrapId)
        assertFalse(
            assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(loginCanary.first, loginCanary.second, ByteArray(0), StaySignedIn.LOGIN) }.cancelled,
            "the login key outlived the change of level",
        )
        assertEquals(
            SignInFailure.Reason.DEVICE_NOT_TRUSTED,
            assertFailsWith<SignInFailure> { VeyqAccount.resume(p, atLogin.copy(level = StaySignedIn.PRESENCE, alias = atPresence.alias), device, client) }.reason,
            "the replaced wrap was still served",
        )
        third.http.close()

        // A PRESENCE account resumes once the person authenticates; without it, it stays closed and remembered.
        val prompts = keys.prompts
        val fourth = VeyqAccount.resume(p, atPresence, device, client)
        assertEquals(prompts + 1, keys.prompts)
        fourth.http.close()
        keys.authenticates = false
        assertEquals(
            SignInFailure.Reason.DEVICE_AUTH_CANCELLED,
            assertFailsWith<SignInFailure> { VeyqAccount.resume(p, atPresence, device, client) }.reason,
        )
        assertNotNull(device.trusted(atPresence.key))
        keys.authenticates = true

        // Back to the password trust: a wrong password is told apart and changes nothing; the right one replaces the wrap.
        val fifth = VeyqAccount.resume(p, atPresence, device, client)
        assertFalse(fifth.isPassword(password + "x"), "a wrong password passed the check")
        assertEquals(atPresence, device.trusted(fifth.key))
        assertTrue(fifth.isPassword(password))
        val presenceCanary = keys.sealUnlockable(byteArrayOf(3), ByteArray(0), StaySignedIn.PRESENCE)
        assertEquals(VeyqAccount.TrustOutcome.TRUSTED, fifth.trustThisDevice(password, level = null))
        val back = assertNotNull(device.trusted(fifth.key))
        assertEquals(TrustedDevice.Method.DEVICE, back.method)
        assertNull(keys.secret(atPresence.refreshAlias), "the remembered token outlived the way back")
        assertFalse(
            assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(presenceCanary.first, presenceCanary.second, ByteArray(0), StaySignedIn.PRESENCE) }.cancelled,
            "the presence key outlived the way back",
        )
        fifth.http.close()
        val sixth = VeyqAccount.unlockOnDevice(p, back, password, device, client)

        // Stay signed in again, then sign out: the record, the token and the unlock key go, and the wrap on the server.
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, sixth.trustThisDevice(null, StaySignedIn.LOGIN))
        val again = assertNotNull(device.trusted(sixth.key))
        val canary = keys.sealUnlockable(byteArrayOf(1), ByteArray(0), StaySignedIn.LOGIN)
        sixth.signOut()
        assertNull(device.trusted(again.key))
        assertNull(keys.secret(again.refreshAlias))
        val gone = assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(canary.first, canary.second, ByteArray(0), StaySignedIn.LOGIN) }
        assertFalse(gone.cancelled, "the unlock key survived the sign-out")
    }
}
