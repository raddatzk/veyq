package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.Primitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The suite's primitives on the platform the common tests run on: BouncyCastle on the JVM, libsodium natively. */
expect val testPrimitives: Primitives

/**
 * What every [DeviceKeys] does, read only through the interface (ADR 0123's Consequences, the device trust): the flows
 * of `Account` and `SessionModel` rely on exactly this, so a platform's store passes it or the flows break there. A
 * store's subclass gives a fresh store per test ([keys]); one that asks the person must authenticate without asking, as
 * the fake does, or run only its unattended half here (Windows' DPAPI half in `mingwTest`; Hello's is the probe's).
 */
abstract class DeviceKeysContract {
    abstract fun keys(): DeviceKeys

    private val message = "veyq/v1/device-bind".encodeToByteArray()

    @Test
    fun aBindHmacIsDeterministicReplacedByANewKeyAndGoneAfterDelete() {
        val keys = keys()
        assertNull(keys.bindHmac("contract.a", message), "an HMAC under a key never created")
        keys.createBindKey("contract.a")
        val first = assertNotNull(keys.bindHmac("contract.a", message))
        assertEquals(32, first.size)
        assertContentEquals(first, keys.bindHmac("contract.a", message), "the bind HMAC is not deterministic")
        assertFalse(first.contentEquals(keys.bindHmac("contract.a", "another".encodeToByteArray())), "the HMAC ignores its message")
        keys.createBindKey("contract.b")
        assertFalse(first.contentEquals(keys.bindHmac("contract.b", message)), "two aliases share a key")
        keys.createBindKey("contract.a")
        val replaced = assertNotNull(keys.bindHmac("contract.a", message))
        assertFalse(first.contentEquals(replaced), "createBindKey did not replace the key")
        keys.deleteBindKey("contract.a")
        assertNull(keys.bindHmac("contract.a", message), "the bind key survived its delete")
        assertNotNull(keys.bindHmac("contract.b", message), "a delete took another alias's key")
    }

    @Test
    fun secretsRoundTripAreReplacedAndDeleted() {
        val keys = keys()
        assertNull(keys.secret("contract.refresh"))
        keys.putSecret("contract.refresh", "token-1 ü€")
        assertEquals("token-1 ü€", keys.secret("contract.refresh"))
        keys.putSecret("contract.refresh", "")
        assertEquals("", keys.secret("contract.refresh"), "an empty secret did not round-trip")
        keys.putSecret("contract.refresh", "token-2")
        keys.putSecret("contract.other", "other")
        assertEquals("token-2", keys.secret("contract.refresh"))
        keys.deleteSecret("contract.refresh")
        assertNull(keys.secret("contract.refresh"), "the secret survived its delete")
        assertEquals("other", keys.secret("contract.other"))
    }

    /** The levels this store offers: each runs the unlock key's half of the contract; one it does not, the refusal. */
    private fun offered(keys: DeviceKeys): Set<StaySignedIn> =
        keys.staySignedIn.also { assertTrue(it.isNotEmpty(), "a store that offers no level runs no unlock key's contract") }

    @Test
    fun aSealOpensUnderItsAadAndNotUnderAnotherAtEachOfferedLevel() = runTest {
        val keys = keys()
        for (level in offered(keys)) {
            val plaintext = ByteArray(32) { it.toByte() }
            val (iv, ciphertext) = keys.sealUnlockable(plaintext, "aad-1".encodeToByteArray(), level)
            assertContentEquals(plaintext, keys.openUnlockable(iv, ciphertext, "aad-1".encodeToByteArray(), level), "$level")
            assertTrue(runCatching { keys.openUnlockable(iv, ciphertext, "aad-2".encodeToByteArray(), level) }.isFailure, "$level opened under another AAD")
            val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertTrue(runCatching { keys.openUnlockable(iv, tampered, "aad-1".encodeToByteArray(), level) }.isFailure, "$level opened a tampered ciphertext")
            // a second seal is under the same unlock key: both open
            val (iv2, ciphertext2) = keys.sealUnlockable(byteArrayOf(7), "aad-3".encodeToByteArray(), level)
            assertContentEquals(byteArrayOf(7), keys.openUnlockable(iv2, ciphertext2, "aad-3".encodeToByteArray(), level))
            assertContentEquals(plaintext, keys.openUnlockable(iv, ciphertext, "aad-1".encodeToByteArray(), level))
        }
    }

    @Test
    fun anOpenWithoutAnUnlockKeyFailsAsNotCancelledAtEachOfferedLevel() = runTest {
        val keys = keys()
        for (level in offered(keys)) {
            val never = assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(ByteArray(12), ByteArray(48), ByteArray(0), level) }
            assertFalse(never.cancelled, "no $level unlock key reads as a cancelled prompt")
            val (iv, ciphertext) = keys.sealUnlockable(byteArrayOf(1, 2, 3), ByteArray(0), level)
            keys.deleteUnlockKey(level)
            val deleted = assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(iv, ciphertext, ByteArray(0), level) }
            assertFalse(deleted.cancelled, "a deleted $level unlock key reads as a cancelled prompt")
            // a new unlock key after the delete does not open what the old one sealed
            keys.sealUnlockable(byteArrayOf(9), ByteArray(0), level)
            assertTrue(runCatching { keys.openUnlockable(iv, ciphertext, ByteArray(0), level) }.isFailure, "a new $level unlock key opened the old one's seal")
            keys.deleteUnlockKey(level)
        }
    }

    @Test
    fun eachLevelIsAKeyOfItsOwn() = runTest {
        val keys = keys()
        val levels = offered(keys)
        if (levels.size < 2) return@runTest // one level: nothing to tell apart (Linux)
        val (pIv, pCt) = keys.sealUnlockable(byteArrayOf(1), ByteArray(0), StaySignedIn.PRESENCE)
        val (lIv, lCt) = keys.sealUnlockable(byteArrayOf(2), ByteArray(0), StaySignedIn.LOGIN)
        assertTrue(runCatching { keys.openUnlockable(pIv, pCt, ByteArray(0), StaySignedIn.LOGIN) }.isFailure, "the login key opened a presence seal")
        assertTrue(runCatching { keys.openUnlockable(lIv, lCt, ByteArray(0), StaySignedIn.PRESENCE) }.isFailure, "the presence key opened a login seal")
        keys.deleteUnlockKey(StaySignedIn.LOGIN)
        assertContentEquals(byteArrayOf(1), keys.openUnlockable(pIv, pCt, ByteArray(0), StaySignedIn.PRESENCE), "deleting the login key took the presence key")
        assertFalse(assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(lIv, lCt, ByteArray(0), StaySignedIn.LOGIN) }.cancelled)
        val (lIv2, lCt2) = keys.sealUnlockable(byteArrayOf(3), ByteArray(0), StaySignedIn.LOGIN)
        keys.deleteUnlockKey(StaySignedIn.PRESENCE)
        assertContentEquals(byteArrayOf(3), keys.openUnlockable(lIv2, lCt2, ByteArray(0), StaySignedIn.LOGIN), "deleting the presence key took the login key")
        keys.deleteUnlockKey(StaySignedIn.LOGIN)
    }

    @Test
    fun aLevelTheStoreDoesNotOfferIsRefusedAndNeverFallsBack() = runTest {
        val keys = keys()
        val refused = StaySignedIn.entries - offered(keys)
        for (level in refused) {
            assertFalse(assertFailsWith<DeviceAuthFailed> { keys.sealUnlockable(byteArrayOf(1), ByteArray(0), level) }.cancelled, "$level")
            assertFalse(assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(ByteArray(12), ByteArray(17), ByteArray(0), level) }.cancelled, "$level")
            keys.deleteUnlockKey(level) // quiet
        }
    }
}

/** The contract over the fake every flow test uses. */
class SoftwareDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = SoftwareDeviceKeys(testPrimitives)
}

/** The contract over the fake as a store with the login level alone (Linux's shape): the presence level is refused. */
class LoginOnlySoftwareDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = SoftwareDeviceKeys(testPrimitives).apply { staySignedIn = setOf(StaySignedIn.LOGIN) }
}
