package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/** A Hello that must not be reached: `run.py test` runs in session 0, where no prompt can appear. */
private object NoHello : UnlockGuard {
    override val path = "hello"
    override fun protect(key: ByteArray): ByteArray = fail("Hello reached")
    override fun unprotect(blob: ByteArray): ByteArray = fail("Hello reached")
    override fun delete() = fail("Hello reached")
}

/** The DPAPI half, the real one: Windows' own DPAPI in session 0 (the CR's D3). */
private fun dpapiKeys(store: DeviceStore = MemoryDeviceStore(), time: TimeSource = TimeSource.Monotonic) = WindowsDeviceKeys(
    store, LibsodiumPrimitives, NoHello, DpapiGuard(WindowsDeviceKeys.UNLOCK_ENTROPY), { false }, time,
)

/** The contract over Windows' DPAPI half: the bind keys, the secrets and the LOGIN unlock key; Hello not set up, so the
 *  presence level is refused without reaching Hello. */
class WindowsDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = dpapiKeys()
}

/** A guard that plays Hello in memory: counts the opens (each a prompt) and can play a cancelled one. */
private class PlayedHello : UnlockGuard {
    override val path = "hello"
    var opens = 0
    var status = 0
    var deleted = false
    override fun protect(key: ByteArray): ByteArray = key.reversedArray()
    override fun unprotect(blob: ByteArray): ByteArray {
        opens++
        if (status != 0) throw authFailure(status, "played")
        return blob.reversedArray()
    }
    override fun delete() { deleted = true }
}

class WindowsDeviceKeysTest {
    private val p = LibsodiumPrimitives

    @Test
    fun aCancelledPromptIsCancelledAndEveryOtherStatusIsNot() {
        assertTrue(authFailure(NTE_USER_CANCELLED, "x").cancelled)
        assertTrue(authFailure(SCARD_W_CANCELLED_BY_USER, "x").cancelled)
        for (status in listOf(NTE_NO_KEY, NTE_BAD_KEYSET, 0x80090010.toInt() /* NTE_PERM */, 0x800704C7.toInt() /* ERROR_CANCELLED */, -1, 1)) {
            assertFalse(authFailure(status, "x").cancelled, "0x${status.toUInt().toString(16)} read as cancelled")
        }
        assertEquals("open: 0x80090036", authFailure(NTE_USER_CANCELLED, "open").message)
    }

    @Test
    fun aHelloCancelReachesTheFlowsAsCancelledAndAMissingKeyAsNot() = runTest {
        val store = MemoryDeviceStore()
        val hello = PlayedHello()
        val (iv, ct) = WindowsDeviceKeys(store, p, hello, DpapiGuard(WindowsDeviceKeys.UNLOCK_ENTROPY), { true }, TimeSource.Monotonic)
            .sealUnlockable(byteArrayOf(5), ByteArray(0), StaySignedIn.PRESENCE)
        val next = WindowsDeviceKeys(store, p, hello, DpapiGuard(WindowsDeviceKeys.UNLOCK_ENTROPY), { true }, TimeSource.Monotonic)
        hello.status = NTE_USER_CANCELLED
        assertTrue(assertFailsWith<DeviceAuthFailed> { next.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.PRESENCE) }.cancelled)
        hello.status = NTE_NO_KEY
        assertFalse(assertFailsWith<DeviceAuthFailed> { next.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.PRESENCE) }.cancelled)
        hello.status = 0
        assertContentEquals(byteArrayOf(5), next.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.PRESENCE))
    }

    @Test
    fun oneOpenServesTheWindowAndTheNextAsksAgain() = runTest {
        val store = MemoryDeviceStore()
        val hello = PlayedHello()
        val time = TestTimeSource()
        fun keys() = WindowsDeviceKeys(store, p, hello, DpapiGuard(WindowsDeviceKeys.UNLOCK_ENTROPY), { true }, time)
        val (iv, ct) = keys().sealUnlockable(byteArrayOf(1), ByteArray(0), StaySignedIn.PRESENCE)
        assertEquals(0, hello.opens, "creating the key opened it")
        val started = keys() // a new start: nothing held
        assertContentEquals(byteArrayOf(1), started.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.PRESENCE))
        assertEquals(1, hello.opens)
        time += 14.seconds
        started.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.PRESENCE)
        started.sealUnlockable(byteArrayOf(2), ByteArray(0), StaySignedIn.PRESENCE)
        assertEquals(1, hello.opens, "a second account within the window asked again")
        time += 2.seconds
        started.openUnlockable(iv, ct, ByteArray(0), StaySignedIn.PRESENCE)
        assertEquals(2, hello.opens, "the window outlived 15 s")
    }

    @Test
    fun presenceIsHellosAndLoginIsDpapisEachKeptApartAndADeleteRemovesTheHelloKey() = runTest {
        val store = MemoryDeviceStore()
        val hello = PlayedHello()
        var setUp = true
        fun keys() = WindowsDeviceKeys(store, p, hello, DpapiGuard(WindowsDeviceKeys.UNLOCK_ENTROPY), { setUp }, TimeSource.Monotonic)
        assertEquals(setOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN), keys().staySignedIn)
        val (pIv, pCt) = keys().sealUnlockable(byteArrayOf(3), ByteArray(0), StaySignedIn.PRESENCE)
        val (lIv, lCt) = keys().sealUnlockable(byteArrayOf(4), ByteArray(0), StaySignedIn.LOGIN)
        assertTrue(keys().hasUnlockKey(StaySignedIn.PRESENCE) && keys().hasUnlockKey(StaySignedIn.LOGIN))
        // the login key opens without Hello
        assertContentEquals(byteArrayOf(4), keys().openUnlockable(lIv, lCt, ByteArray(0), StaySignedIn.LOGIN))
        assertEquals(0, hello.opens, "the login level reached Hello")
        setUp = false // Hello turned off since: the presence key still opens through Hello, never through DPAPI
        assertEquals(setOf(StaySignedIn.LOGIN), keys().staySignedIn)
        assertContentEquals(byteArrayOf(3), keys().openUnlockable(pIv, pCt, ByteArray(0), StaySignedIn.PRESENCE))
        assertEquals(1, hello.opens)
        keys().deleteUnlockKey(StaySignedIn.PRESENCE)
        assertTrue(hello.deleted, "the Hello key outlived its delete")
        assertFalse(keys().hasUnlockKey(StaySignedIn.PRESENCE))
        assertContentEquals(byteArrayOf(4), keys().openUnlockable(lIv, lCt, ByteArray(0), StaySignedIn.LOGIN), "the presence delete took the login key")
        // without Hello a presence key is refused, never made under DPAPI
        assertFalse(assertFailsWith<DeviceAuthFailed> { keys().sealUnlockable(byteArrayOf(5), ByteArray(0), StaySignedIn.PRESENCE) }.cancelled)
        assertFalse(keys().hasUnlockKey(StaySignedIn.PRESENCE))
        // a login delete leaves Hello alone
        hello.deleted = false
        keys().deleteUnlockKey(StaySignedIn.LOGIN)
        assertFalse(hello.deleted, "a login delete reached Hello")
    }

    @Test
    fun theStoreHoldsNoKeyOrSecretInTheClearAndABlobOpensOnlyForItsUse() {
        val store = RecordingStore()
        val keys = dpapiKeys(store)
        keys.createBindKey("a")
        keys.putSecret("a.refresh", "refresh-token-in-the-clear")
        assertFalse(store.values.values.any { "refresh-token-in-the-clear" in it || "refresh-token" in unb64OrEmpty(it).decodeToString() })
        // a bind blob given as a secret (another DPAPI entropy) does not open
        store.write(WindowsDeviceKeys.SECRET + "b", store.read(WindowsDeviceKeys.BIND + "a")!!)
        assertNull(keys.secret("b"))
    }

    private fun unb64OrEmpty(s: String) = runCatching { me.raddatz.veyq.mobile.crypto.unb64(s) }.getOrDefault(ByteArray(0))

    private class RecordingStore : DeviceStore {
        val values = HashMap<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    /** Measures, never asserts: what this host says of Hello (session 0 under `run.py test`). */
    @Test
    fun helloAvailabilityIsAnswered() {
        println("veyq: user SID ${Hello.sid()}; Windows Hello set up: ${Hello.available()}")
    }
}
