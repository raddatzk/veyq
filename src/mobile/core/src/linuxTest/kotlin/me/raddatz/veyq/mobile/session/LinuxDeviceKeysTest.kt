@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.hex
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A store of its own in the real Secret Service: `run.py test` runs the binary under `dbus-run-session` with
 * gnome-keyring-daemon, its login collection unlocked by a password drawn in the container (the CR's D3). Each store's
 * items carry a namespace of their own, so one test's unlock key is never another's.
 */
private fun ownKeys(): LinuxDeviceKeys = LinuxDeviceKeys(
    SecretService.system ?: error("no libsecret: run through `linux/run.py test`"),
    LibsodiumPrimitives,
    "test.${hex(LibsodiumPrimitives.randomBytes(8))}/",
)

/** The contract over Linux's store, every item a real Secret Service item. */
class LinuxDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = ownKeys()
}

class LinuxDeviceKeysTest {
    /** libsecret as a library that is not there: `dlopen` fails, as on a system without it. */
    private val absent = LinuxDeviceKeys(SecretService.load("libveyq-absent-secret.so.0"), LibsodiumPrimitives, "absent/")

    @Test
    fun anAbsentLibraryLoadsNothing() {
        assertNull(SecretService.load("libveyq-absent-secret.so.0"))
        assertFalse(absent.secretServiceAvailable)
    }

    @Test
    fun withoutLibsecretEveryCreationFailsAsNotCancelledAndNothingElseFails() = runTest {
        assertNoStore(absent)
    }

    @Test
    fun aStoreInTheSecretServiceKeepsNothingOfAnotherNamespace() = runTest {
        val a = ownKeys()
        val b = ownKeys()
        a.createBindKey("same")
        a.putSecret("same", "a's")
        a.sealUnlockable(byteArrayOf(1), ByteArray(0), StaySignedIn.LOGIN)
        assertNull(b.bindHmac("same", "m".encodeToByteArray()))
        assertNull(b.secret("same"))
        assertFalse(assertFailsWith<DeviceAuthFailed> { b.openUnlockable(ByteArray(12), ByteArray(17), ByteArray(0), StaySignedIn.LOGIN) }.cancelled)
    }

    @Test
    fun aKeyOutlivesItsStoreObject() = runTest {
        // what a new start reads: another object over the same items
        val namespace = "test.${hex(LibsodiumPrimitives.randomBytes(8))}/"
        fun keys() = LinuxDeviceKeys(SecretService.system!!, LibsodiumPrimitives, namespace)
        keys().createBindKey("a")
        val hmac = assertNotNull(keys().bindHmac("a", "m".encodeToByteArray()))
        assertContentEquals(hmac, keys().bindHmac("a", "m".encodeToByteArray()))
        val (iv, ct) = keys().sealUnlockable(byteArrayOf(4, 2), "aad".encodeToByteArray(), StaySignedIn.LOGIN)
        assertContentEquals(byteArrayOf(4, 2), keys().openUnlockable(iv, ct, "aad".encodeToByteArray(), StaySignedIn.LOGIN))
        keys().putSecret("a.refresh", "kept")
        assertEquals("kept", keys().secret("a.refresh"))
    }

    @Test
    fun theSecretServiceAnswers() {
        assertTrue(ownKeys().secretServiceAvailable, "no Secret Service answered under run.py test's session")
    }
}

/**
 * The app's own store (`LinuxDeviceKeys()`, libsecret by its soname) as the environment `run.py test` sets it up:
 * `VEYQ_SECRET_SERVICE` says which — `present` (the keyring's session), `absent` (`libsecret-1.so.0` moved away) or
 * `no-bus` (libsecret there, no session bus). Unset, it only prints what it finds.
 */
class SecretServiceProbeTest {
    @Test
    fun theAppsStoreIsAsTheEnvironmentSays() = runTest {
        val keys = LinuxDeviceKeys()
        val expected = getenv("VEYQ_SECRET_SERVICE")?.toKString()
        println("veyq: libsecret ${if (SecretService.system == null) "absent" else "loaded"}; Secret Service available: ${keys.secretServiceAvailable}; expected: $expected")
        when (expected) {
            "present" -> {
                assertNotNull(SecretService.system)
                assertTrue(keys.secretServiceAvailable)
                assertEquals(setOf(StaySignedIn.LOGIN), keys.staySignedIn, "the Secret Service offers the login level alone")
                val (iv, ct) = keys.sealUnlockable(byteArrayOf(3), ByteArray(0), StaySignedIn.LOGIN)
                assertContentEquals(byteArrayOf(3), keys.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.LOGIN))
                keys.deleteUnlockKey(StaySignedIn.LOGIN)
            }
            "absent" -> {
                assertNull(SecretService.system, "libsecret loaded though it was moved away")
                assertNoStore(keys)
            }
            "no-bus" -> {
                assertNotNull(SecretService.system, "libsecret did not load")
                assertNoStore(keys)
            }
        }
    }
}

/** What a store that keeps nothing answers: never available, creation `DeviceAuthFailed(false)`, reads nothing, deletes
 *  quietly — so the flows fall back and the app works as without a store. */
private suspend fun assertNoStore(keys: LinuxDeviceKeys) {
    assertFalse(keys.secretServiceAvailable)
    assertEquals(emptySet(), keys.staySignedIn, "a store that keeps nothing offers a level")
    assertFalse(assertFailsWith<DeviceAuthFailed> { keys.sealUnlockable(byteArrayOf(1), ByteArray(0), StaySignedIn.LOGIN) }.cancelled)
    assertFalse(assertFailsWith<DeviceAuthFailed> { keys.createBindKey("a") }.cancelled)
    assertFalse(assertFailsWith<DeviceAuthFailed> { keys.putSecret("a.refresh", "t") }.cancelled)
    assertFalse(assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(ByteArray(12), ByteArray(17), ByteArray(0), StaySignedIn.LOGIN) }.cancelled)
    assertNull(keys.bindHmac("a", "m".encodeToByteArray()))
    assertNull(keys.secret("a.refresh"))
    keys.deleteBindKey("a")
    keys.deleteSecret("a.refresh")
    keys.deleteUnlockKey(StaySignedIn.LOGIN)
}
