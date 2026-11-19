package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.session.StaySignedIn.LOGIN
import me.raddatz.veyq.mobile.session.StaySignedIn.PRESENCE
import me.raddatz.veyq.mobile.session.TrustedDevice.Method.DEVICE
import me.raddatz.veyq.mobile.session.TrustedDevice.Method.DEVICE_KEY
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The changes a trust row offers ([trustChanges]; null is the password trust): staying signed in, the other levels and
 * back to the password; password-trusted, each level; not trusted at all — a password change took this device's saved
 * key (inbox #1009) — each level and the password trust, never nothing.
 */
class TrustChangesTest {
    private val both = listOf(PRESENCE, LOGIN)

    @Test
    fun eachTrustIsOfferedTheWaysOnFromIt() {
        assertEquals(listOf(LOGIN, null), trustChanges(DEVICE_KEY, PRESENCE, both))
        assertEquals(listOf(PRESENCE, null), trustChanges(DEVICE_KEY, LOGIN, both))
        assertEquals(listOf(PRESENCE, LOGIN), trustChanges(DEVICE, null, both))
        assertEquals(emptyList(), trustChanges(DEVICE, null, emptyList()))
    }

    @Test
    fun aDeviceWithoutTrustIsOfferedEachLevelAndThePassword() {
        assertEquals(listOf(PRESENCE, LOGIN, null), trustChanges(null, null, both))
        assertEquals(listOf(null), trustChanges(null, null, emptyList()))
        assertEquals(listOf(LOGIN, null), AccountSettings.Trust(null, null, listOf(LOGIN)).changes)
    }
}
