package me.raddatz.veyq.mobile.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.offline.AccountOffline
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tiles across launches (ADR 0143 D4 as amended for inbox #939), over a signed-in tenant the test plays with a real
 * offline store: a tile viewed in one launch is served in the next without a request; it lives in the automatic tier
 * (counted, kept by the sweep while its tenant is, dropped by "Clear cache", not kept at the limit `0`); a withdrawal of
 * the consent drops it; and offline it is never shown.
 *
 * Bites: `MapServices.of` without its shelf → the second launch asks again (red in the first case); the sweep not reaching
 * the tiles → they go at the next sweep (red in the second); `shelve` keeping on a withdrawal → the tiles stay (red in the
 * third). That the shelf is read only behind the gate is `MapTilesTest`'s: offline at a launch no tile service exists.
 */
class MapTileShelfTest {
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private val view = Viewport(PlayedMapServices.BERLIN, 12.0, 800.0, 600.0)
    private val berlin = TileKey(12, 2200, 1343)

    private suspend fun play(): PlayedTenant = PlayedTenant().also { t ->
        played += t
        t.capabilities = """[{"id":"memories.map","serviceUrl":"https://tile.map.example/{z}/{x}/{y}.png","serviceAttribution":"© OpenStreetMap contributors"}]"""
        t.scope("s1")
        t.remember()
    }

    private suspend fun VeyqAccount.tenant(): TenantSession = open(tenants.first())

    private suspend fun AccountOffline.tiles(): Int = store.entries().count { it.key.kind == KeptRead.TILE }

    /** What the services' own coroutines do on the default dispatcher, waited for in real time. */
    private suspend fun until(what: String, check: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!check()) delay(10) }.also { assertTrue(check(), what) }
    }

    /** One launch: the tenant opened, its map shown over Berlin, [berlin] asked; what it answered. */
    private suspend fun launch(t: PlayedTenant, consent: Boolean = true): Pair<VeyqAccount, ByteArray?> {
        val a = t.resume()
        val s = MapServices.of(a.tenant())
        if (consent) s.turnOn()
        // offline the capabilities are not read: no tiles at all (D9)
        val v = s.tiles?.view()
        v?.show(view)
        val bytes = v?.tile(berlin)
        s.close()
        return a to bytes
    }

    @Test
    fun aTileViewedInAnEarlierLaunchIsServedFromTheStoreWithoutARequest() = runTest {
        val t = play()
        val (first, bytes) = launch(t)
        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(1, t.mapRequests.size)
        assertEquals(1, assertNotNull(first.offline).tiles(), "the tile was not kept in the store")
        first.retire()

        val (second, again) = launch(t)
        assertContentEquals(byteArrayOf(1, 2, 3), again)
        assertEquals(1, t.mapRequests.size, "a tile viewed in an earlier launch was fetched again")
        second.retire()
    }

    @Test
    fun aKeptTileIsTheAutomaticTiersKeptByTheSweepDroppedByClearCacheAndNotKeptAtTheLimitZero() = runTest {
        val t = play()
        val (a, _) = launch(t)
        val o = assertNotNull(a.offline)
        assertTrue(o.usage().automatic > 0, "a tile is not counted as recently viewed")
        o.sweep()
        assertEquals(1, o.tiles(), "the sweep dropped a tile of a tenant still listed")
        o.clearCache()
        assertEquals(0, o.tiles(), "Clear cache kept a tile")
        o.setLimit(0)
        a.retire()
        val (b, _) = launch(t)
        assertEquals(0, assertNotNull(b.offline).tiles(), "a tile kept at the limit 0")
        assertEquals(2, t.mapRequests.size)
        b.retire()
    }

    @Test
    fun aWithdrawalDropsTheKeptTiles() = runTest {
        val t = play()
        val a = t.resume()
        val s = MapServices.of(a.tenant())
        s.turnOn()
        val v = assertNotNull(s.tiles).view()
        v.show(view)
        assertNotNull(v.tile(berlin))
        val o = assertNotNull(a.offline)
        assertEquals(1, o.tiles())
        s.turnOff()
        until("a withdrawal left the kept tiles") { o.tiles() == 0 }
        s.close()
        a.retire()
    }

    @Test
    fun offlineNoKeptTileIsShown() = runTest {
        val t = play()
        launch(t).first.retire()
        t.reachable = false
        val (offline, bytes) = launch(t, consent = false)
        assertNull(bytes, "a kept tile was shown offline")
        assertEquals(1, assertNotNull(offline.offline).tiles(), "offline dropped the kept tiles: offline is no withdrawal")
        offline.retire()
    }
}
