package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.LiveHttp
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The device-wide Recent of before inbox #1423 (`veyq.recent` in the host's device store, ids in clear) is removed as the
 * bridge starts and never read into an account; what else the host keeps there stays.
 * Bite (made red once, then restored): `Bridge.live` without its `store.remove(LEGACY_RECENT)`.
 */
class LegacyRecentTest {
    @Test
    fun theDeviceWideRecentOfBeforeIsGoneAtTheStart() {
        val store = MemoryDeviceStore().apply {
            write(Bridge.LEGACY_RECENT, "s\tn\t1")
            write(Bridge.HIDDEN, "s2")
        }
        val b = Bridge.live(BouncyCastlePrimitives, { block -> LiveHttp.client(block) }, Config("legacy recent test"), store, { NoDeviceKeys }, JvmFiles)
        try {
            assertEquals(Answer.Done, runBlocking { b.ask(Call.SessionStart) })
            assertNull(store.read(Bridge.LEGACY_RECENT))
            assertEquals("s2", store.read(Bridge.HIDDEN))
        } finally {
            b.shutdown()
        }
    }
}
