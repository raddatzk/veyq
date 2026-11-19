package me.raddatz.veyq.mobile.map

import me.raddatz.veyq.mobile.session.OutsideAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.BERLIN
import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.OFFER
import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.TILE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tiles (ADR 0143 D2, D4) over a tile server the test plays: nothing before the consent, nothing after a withdrawal
 * (a queued tile included), only tiles in view, kept in memory for the session with the policy's headers honoured, and
 * dropped when the tenant's map path closes.
 */
class MapTilesTest {
    /** 800 × 600 at z 12 over Berlin: columns 2198–2202, rows 1342–1344 — fifteen tiles. */
    private val view = Viewport(BERLIN, 12.0, 800.0, 600.0)
    private val berlin = TileKey(12, 2200, 1343)

    // bite (a): the gate where the request leaves removed → a tile fetched before the consent, the count 1, red here
    @Test
    fun noTileIsRequestedBeforeTheConsent() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services()
        val v = s.tiles!!.view()
        v.show(view)
        assertNull(v.tile(berlin))
        advanceUntilIdle()
        assertEquals(0, played.count, "no request leaves before the consent")

        s.turnOn()
        assertContentEquals(TILE, v.tile(berlin))
        assertEquals(listOf("https://tiles.example/12/2200/1343.png"), played.sent.map { it.url })
    }

    // bite (b): the withdrawal not reaching the services (`consentChanged` ignoring all but a consent) → the queued tile still leaves, red here
    @Test
    fun aWithdrawalStopsATileThatWaitsAndCancelsTheOnesInFlight() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services()
        s.turnOn()
        val v = s.tiles!!.view()
        val keys = v.show(view).map { it.key }.take(MapTiles.PARALLEL + 1)
        played.hold = CompletableDeferred()
        val asked = keys.map { k -> async { v.tile(k) } }
        advanceUntilIdle()
        assertEquals(MapTiles.PARALLEL, played.count, "${MapTiles.PARALLEL} in flight, one queued behind them")

        s.turnOff()
        played.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(MapTiles.PARALLEL, played.count, "the queued tile never leaves after the withdrawal")
        assertTrue(asked.all { it.await() == null }, "what was in flight is dropped")
        assertEquals(0, s.tiles!!.cached)
    }

    // bite (g): the in-view check removed → a tile beyond the viewport fetched, red here
    @Test
    fun onlyTilesInViewAreRequestedNoRingAhead() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services()
        s.turnOn()
        val v = s.tiles!!.view()
        val shown = v.show(view).map { it.key }.toSet()
        assertEquals((2198..2202).flatMap { x -> (1342..1344).map { y -> TileKey(12, x, y) } }.toSet(), shown)

        assertNull(v.tile(TileKey(12, 2203, 1343)), "the column just right of the view")
        assertNull(v.tile(TileKey(12, 2200, 1345)), "the row just below it")
        assertNull(v.tile(TileKey(13, 4400, 2686)), "the next level")
        assertEquals(0, played.count)
        assertContentEquals(TILE, v.tile(TileKey(12, 2202, 1344)))
        assertEquals(1, played.count)

        v.close()
        assertNull(v.tile(berlin), "a closed map asks for nothing")
        assertEquals(1, played.count)
    }

    @Test
    fun aKeptTileIsServedFromMemoryWhileFreshThenRevalidatedConditionally() = runTest {
        val played = PlayedMapServices(this)
        played.tile = { r ->
            if (r.header("If-None-Match") == "\"t1\"") Triple(304, mapOf("Cache-Control" to "max-age=600"), ByteArray(0))
            else Triple(200, mapOf("Cache-Control" to "max-age=600", "ETag" to "\"t1\""), TILE)
        }
        val s = played.services()
        s.turnOn()
        val v = s.tiles!!.view()
        v.show(view)
        assertContentEquals(TILE, v.tile(berlin))
        assertContentEquals(TILE, v.tile(berlin))
        assertEquals(1, played.count, "fresh: from memory")

        advanceTimeBy(601_000)
        assertContentEquals(TILE, v.tile(berlin), "a 304 keeps the bytes")
        assertEquals(2, played.count)
        assertEquals("\"t1\"", played.sent[1].header("If-None-Match"))
        assertContentEquals(TILE, v.tile(berlin))
        assertEquals(2, played.count, "fresh again after the 304")
        assertTrue(played.sent.none { it.has("Cache-Control") || it.has("Pragma") }, "never asked with no-cache")
    }

    @Test
    fun theCacheIsBoundedLeastRecentlyUsedFirstAndKeepsNoStoreAnswerNot() = runTest {
        val played = PlayedMapServices(this)
        played.consent.give(ConsentHosts("tiles.example", "nominatim.example"))
        val tiles = MapTiles(played.http(), PlayedMapServices.TEMPLATE, { true }, { testScheduler.currentTime }, played.dispatcher, capacity = 2)
        val v = tiles.view()
        val (a, b, c) = v.show(view).map { it.key }
        v.tile(a)
        v.tile(b)
        v.tile(a) // a used last: b goes first
        v.tile(c)
        assertEquals(3, played.count)
        v.tile(a)
        assertEquals(3, played.count, "a stayed")
        v.tile(b)
        assertEquals(4, played.count, "b was evicted")

        played.tile = { Triple(200, mapOf("Cache-Control" to "no-store"), TILE) }
        val d = v.show(view).map { it.key }[3]
        v.tile(d)
        v.tile(d)
        assertEquals(6, played.count, "no-store: never kept")
    }

    @Test
    fun aWithdrawalDropsTheKeptTiles() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services()
        s.turnOn()
        val v = s.tiles!!.view()
        v.show(view)
        v.tile(berlin)
        assertEquals(1, s.tiles!!.cached)
        s.turnOff()
        assertEquals(0, s.tiles!!.cached)
    }

    // bite (f): the cache kept after `closeTenant()` (close() not dropping) → red here
    @Test
    fun closingTheTenantsMapPathDropsEveryTileAndAsksNothingMore() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services()
        s.turnOn()
        val v = s.tiles!!.view()
        v.show(view)
        v.tile(berlin)
        assertEquals(1, s.tiles!!.cached)

        s.close() // the bridge's closeTenant(), a lock, a sign-out
        assertEquals(0, s.tiles!!.cached, "nothing of the map stays in memory")
        assertNull(v.tile(berlin))
        advanceUntilIdle()
        assertEquals(1, played.count)
    }

    @Test
    fun onlyTheServedHttpsTemplateIsAskedAndNoOtherPlaceholder() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        for (template in listOf("http://tiles.example/{z}/{x}/{y}.png", "https://tiles.example/{z}/{x}/{y}{foo}.png")) {
            val s = played.services(MapOffer(template, null, OFFER.geocoder))
            s.turnOn()
            val v = s.tiles!!.view()
            v.show(view)
            assertNull(v.tile(berlin), template)
        }
        advanceUntilIdle()
        assertEquals(0, played.count)

        val sub = played.services(MapOffer("https://{s}.tiles.example/{z}/{x}/{y}{r}.png", null, OFFER.geocoder))
        sub.turnOn()
        sub.tiles!!.view().also { it.show(view) }.tile(berlin)
        assertEquals("https://a.tiles.example/12/2200/1343.png", played.sent.single().url)
    }

    @Test
    fun aTilesLifetimeIsReadFromItsHeaders() {
        fun life(vararg h: Pair<String, String>) = MapTiles.lifetimeMs(OutsideAnswer(200, h.associate { it.first.lowercase() to it.second }, ByteArray(0)))
        assertEquals(600_000, life("Cache-Control" to "public, max-age=600"))
        assertEquals(0, life("Cache-Control" to "no-cache, max-age=600"))
        assertEquals(3_600_000, life("Date" to "Mon, 16 Nov 2026 10:00:00 GMT", "Expires" to "Mon, 16 Nov 2026 11:00:00 GMT"))
        assertEquals(360_000, life("Date" to "Mon, 16 Nov 2026 10:00:00 GMT", "Last-Modified" to "Mon, 16 Nov 2026 09:00:00 GMT"))
        assertEquals(0, life())
        assertFalse(life("Cache-Control" to "max-age=-5") < 0)
    }

    /** A shelf the test plays: what is kept by id, and how often it was read. */
    private class PlayedShelf : TileShelf {
        val kept = HashMap<String, ShelvedTile>()
        var gets = 0
        override suspend fun get(id: String): ShelvedTile? = kept[id].also { gets++ }
        override suspend fun put(id: String, tile: ShelvedTile) { kept[id] = tile }
        override suspend fun drop(template: String?) { kept.keys.removeAll { template == null || !it.startsWith("$template\n") } }
    }

    // #939, bite: the shelf not read on a memory miss (`known = kept`) → the second session asks again, red here
    @Test
    fun aShelvedTileIsServedInALaterSessionWhileFreshThenRevalidatedWithItsValidators() = runTest {
        val played = PlayedMapServices(this)
        played.tile = { r ->
            if (r.header("If-None-Match") == "\"t1\"") Triple(304, mapOf("Cache-Control" to "max-age=600"), ByteArray(0))
            else Triple(200, mapOf("Cache-Control" to "max-age=600", "ETag" to "\"t1\""), TILE)
        }
        val shelf = PlayedShelf()
        played.consent.give(ConsentHosts("tiles.example", "nominatim.example"))
        fun session() = MapTiles(played.http(), PlayedMapServices.TEMPLATE, { true }, { testScheduler.currentTime }, played.dispatcher, shelf = shelf)
        val first = session().view()
        first.show(view)
        assertContentEquals(TILE, first.tile(berlin))
        assertEquals(listOf(TileShelf.id(PlayedMapServices.TEMPLATE, berlin)), shelf.kept.keys.toList())

        // a later launch: nothing in memory, the shelf answers while the tile is fresh
        val second = session().view()
        second.show(view)
        assertContentEquals(TILE, second.tile(berlin))
        assertEquals(1, played.count, "a fresh shelved tile was asked again")

        // expired: asked with its validators, the 304 keeps the shelved bytes and moves its expiry on the shelf
        advanceTimeBy(601_000)
        val third = session().view()
        third.show(view)
        assertContentEquals(TILE, third.tile(berlin))
        assertEquals(2, played.count)
        assertEquals("\"t1\"", played.sent[1].header("If-None-Match"))
        assertTrue(shelf.kept.values.single().expires > testScheduler.currentTime, "the 304's freshness not shelved")
    }

    // #939, bite: the gate checked after the shelf read instead of before → the shelf is read with the gate shut, red here
    @Test
    fun theShelfIsNotReadBehindAShutGateNorFilledWithANoStoreAnswer() = runTest {
        val played = PlayedMapServices(this)
        val shelf = PlayedShelf()
        var open = false
        val tiles = MapTiles(played.http(), PlayedMapServices.TEMPLATE, { open }, { testScheduler.currentTime }, played.dispatcher, shelf = shelf)
        shelf.kept[TileShelf.id(PlayedMapServices.TEMPLATE, berlin)] = ShelvedTile(TILE, null, null, Long.MAX_VALUE)
        val v = tiles.view()
        v.show(view)
        assertNull(v.tile(berlin), "a shelved tile shown behind a shut gate (offline, no consent)")
        assertEquals(0, shelf.gets, "the shelf was read behind a shut gate")

        open = true
        played.tile = { Triple(200, mapOf("Cache-Control" to "no-store"), TILE) }
        val other = v.show(view).map { it.key }.first { it != berlin }
        assertContentEquals(TILE, v.tile(other))
        assertFalse(TileShelf.id(PlayedMapServices.TEMPLATE, other) in shelf.kept, "a no-store answer was shelved")
    }
}
