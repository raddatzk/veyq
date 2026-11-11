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
 * What every [DeviceKeys] does, read only through the interface (the CR device-trust-on-linux-and-windows, D3): the flows
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

    @Test
    fun aSealOpensUnderItsAadAndNotUnderAnother() = runTest {
        val keys = keys()
        val plaintext = ByteArray(32) { it.toByte() }
        val (iv, ciphertext) = keys.sealUnlockable(plaintext, "aad-1".encodeToByteArray())
        assertContentEquals(plaintext, keys.openUnlockable(iv, ciphertext, "aad-1".encodeToByteArray()))
        assertTrue(runCatching { keys.openUnlockable(iv, ciphertext, "aad-2".encodeToByteArray()) }.isFailure, "opened under another AAD")
        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { keys.openUnlockable(iv, tampered, "aad-1".encodeToByteArray()) }.isFailure, "opened a tampered ciphertext")
        // a second seal is under the same unlock key: both open
        val (iv2, ciphertext2) = keys.sealUnlockable(byteArrayOf(7), "aad-3".encodeToByteArray())
        assertContentEquals(byteArrayOf(7), keys.openUnlockable(iv2, ciphertext2, "aad-3".encodeToByteArray()))
        assertContentEquals(plaintext, keys.openUnlockable(iv, ciphertext, "aad-1".encodeToByteArray()))
    }

    @Test
    fun anOpenWithoutAnUnlockKeyFailsAsNotCancelled() = runTest {
        val keys = keys()
        val never = assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(ByteArray(12), ByteArray(48), ByteArray(0)) }
        assertFalse(never.cancelled, "no unlock key reads as a cancelled prompt")
        val (iv, ciphertext) = keys.sealUnlockable(byteArrayOf(1, 2, 3), ByteArray(0))
        keys.deleteUnlockKey()
        val deleted = assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(iv, ciphertext, ByteArray(0)) }
        assertFalse(deleted.cancelled, "a deleted unlock key reads as a cancelled prompt")
        // a new unlock key after the delete does not open what the old one sealed
        keys.sealUnlockable(byteArrayOf(9), ByteArray(0))
        assertTrue(runCatching { keys.openUnlockable(iv, ciphertext, ByteArray(0)) }.isFailure, "a new unlock key opened the old one's seal")
    }
}

/** The contract over the fake every flow test uses. */
class SoftwareDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = SoftwareDeviceKeys(testPrimitives)
}
