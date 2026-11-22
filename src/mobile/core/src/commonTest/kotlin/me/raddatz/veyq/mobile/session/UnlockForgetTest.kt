package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedWrite
import me.raddatz.veyq.mobile.offline.SizedVolume
import me.raddatz.veyq.mobile.offline.freshTestDirectory
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unlock's *Stop trusting this device* (inbox #1052, owner 2026-11-22) over the real [ThisDevice] and [AccountDoor]:
 * the unlock's plan lists it last, and forgetting the account it shows removes that account's record, keys, kept wrap,
 * offline store (its writes waiting with it) and "Not now" — every other account's stay — sends nothing, and falls through
 * to the next trusted account, then to the sign-in. The ask before it counts the writes waiting with no key open.
 *
 * Bites: drop `forget(record.key)` from [ThisDevice.forgetLocked] and the record and its kept wrap outlive it (and the
 * unlock shows the same account again); drop the wipe and the store does; drop `noteWaiting` from the outbox's order and
 * the ask counts nothing; drop [UnlockEntry.FORGET] from [UnlockPlan] and the plan's check fails.
 */
class UnlockForgetTest {
    private val p = testPrimitives
    private val root = freshTestDirectory()
    private val keys = SoftwareDeviceKeys(p)
    private val prefs = MemoryDeviceStore()
    // on a sized volume, never the host's: the outbox refuses a write below the free-space floor (`SizedVolume`)
    private val device = ThisDevice(prefs, keys, "test device", p, offlineRoot = root).also { it.offlineFiles = SizedVolume() }

    /** Every request the forget would send: none may be. */
    private val sent = mutableListOf<String>()
    private val client: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient = { block ->
        HttpClient(MockEngine { r -> sent += r.url.toString(); respondError(HttpStatusCode.ServiceUnavailable) }, block)
    }

    @AfterTest
    fun cleanUp() = removeAll(root)

    private fun model() = SessionModel(AccountDoor(p, device, client), Hidden({}, emptySet()), RecentUses({}, emptyList()))

    /** [userId] on [host] trusted here by [method]: its record, bind key, refresh token, kept wrap and a store with [waiting] writes. */
    private suspend fun trusted(host: String, userId: String, method: TrustedDevice.Method, waiting: Int): TrustedDevice {
        val level = if (method == TrustedDevice.Method.DEVICE_KEY) StaySignedIn.LOGIN else null
        val record = TrustedDevice("https://$host", "$userId@$host", userId, method, "w-$userId", "veyq.device.$userId", level)
        device.remember(record)
        keys.createBindKey(record.alias)
        keys.putSecret(record.refreshAlias, "refresh-$userId")
        if (level != null) keys.sealUnlockable(ByteArray(32), ByteArray(0), level)
        device.keepWrap(record.key, ThisDevice.KeptWrap(record.wrapId, "wrapped-$userId", null))
        device.keepInstanceSalt(host, "salt-$host")
        device.setDeclined(record.key, true)
        val offline = device.offline!!.openFor(record.key, p.randomBytes(32), record.server, 1L shl 30)
        val box = offline.outbox(V1(p))
        repeat(waiting) { i -> assertTrue(box.put(QueuedWrite("q-$userId-$i", Queued.FOLDER_CREATE.name, "t", "s", at = i.toLong()))) }
        offline.close()
        return record
    }

    @Test
    fun theUnlockListsTheForgetLastAndNotInTheShareExtension() {
        assertEquals(
            listOf(UnlockEntry.SUBMIT, UnlockEntry.OTHER_ACCOUNT, UnlockEntry.OPEN_LINK, UnlockEntry.FORGET),
            UnlockPlan.entries(inExtension = false),
        )
        assertEquals(listOf(UnlockEntry.SUBMIT, UnlockEntry.OTHER_ACCOUNT, UnlockEntry.OPEN_LINK), UnlockPlan.entries(inExtension = true))
    }

    @Test
    fun forgettingTheUnlocksAccountRemovesItsRecordWrapAndStoreAndLeavesTheOthers() = runTest {
        // the order of trust: the erased one most recent, the unlock's first
        val other = trusted("b.example", "u-2", TrustedDevice.Method.DEVICE_KEY, waiting = 1)
        val erased = trusted("a.example", "u-1", TrustedDevice.Method.DEVICE, waiting = 2)
        val m = model()
        // the start: the remembered one's resume finds no server (this test serves none); the password one's unlock shows
        m.start()
        // the resume tried the server: the forget, after it, sends nothing more
        val before = sent.size
        assertEquals(erased, assertIs<Gate.Unlock>(m.current).record)
        assertEquals(2, m.waitingOnUnlock(), "the ask counts the unlock's account's writes with no key open")

        assertTrue(m.forgetUnlocked())

        assertEquals(before, sent.size, "nothing is sent: there is no session")
        assertNull(device.trusted(erased.key), "the record goes")
        assertNull(device.keptWrap(erased.key), "its kept wrap goes")
        assertFalse(device.offline!!.has(erased.key), "its offline store goes, the writes waiting with it")
        assertEquals(0, device.offline!!.waiting(erased.key))
        assertNull(keys.bindHmac(erased.alias, ByteArray(1)), "its bind key goes")
        assertNull(keys.secret(erased.refreshAlias))
        assertFalse(device.declined(erased.key), "its \"Not now\" goes with the trust")
        assertNull(device.instanceSalt("a.example"), "no other record names its host")

        // the other account: untouched, and the unlock falls through to it
        assertEquals(other, device.trusted(other.key))
        assertNotNull(device.keptWrap(other.key))
        assertTrue(device.offline!!.has(other.key))
        assertEquals(1, device.offline!!.waiting(other.key))
        assertNotNull(keys.bindHmac(other.alias, ByteArray(1)))
        assertEquals("refresh-u-2", keys.secret(other.refreshAlias))
        assertTrue(keys.hasUnlockKey(StaySignedIn.LOGIN), "the login level's key still opens the other account")
        assertTrue(device.declined(other.key))
        assertEquals("salt-b.example", device.instanceSalt("b.example"))
        assertEquals(other, assertIs<Gate.Unlock>(m.current).record)
        assertEquals(1, m.waitingOnUnlock())

        // the last one forgotten: the sign-in, and the login level's key with it
        assertTrue(m.forgetUnlocked())
        assertIs<Gate.SignIn>(m.current)
        assertTrue(device.trusted().isEmpty())
        assertFalse(keys.hasUnlockKey(StaySignedIn.LOGIN), "no record uses the login level's key any more")
        assertEquals(0, m.waitingOnUnlock())
        assertFalse(m.forgetUnlocked(), "outside the unlock there is nothing to forget")
        assertEquals(before, sent.size)
    }

    @Test
    fun theShareExtensionForgetsNothing() = runTest {
        val record = trusted("a.example", "u-1", TrustedDevice.Method.DEVICE, waiting = 0)
        val m = SessionModel(AccountDoor(p, device, client), Hidden({}, emptySet()), RecentUses({}, emptyList()), signInElsewhere = true)
        m.start()
        assertIs<Gate.Unlock>(m.current)
        assertFalse(m.forgetUnlocked())
        assertEquals(record, device.trusted(record.key))
    }
}
