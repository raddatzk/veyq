package me.raddatz.veyq.mobile.map

import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.BERLIN
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The viewport's math (ADR 0143 D1): Web Mercator, the tiles that cover a view and no more, framing, the gestures. */
class ViewportTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9, what: String = "") =
        assertTrue(abs(expected - actual) < eps, "$what: expected $expected, was $actual")

    @Test
    fun berlinAtLevelTwelveIsTile2200Over1343() {
        val p = Mercator.project(BERLIN)
        assertEquals(2200, floor(p.x * 4096).toInt())
        assertEquals(1343, floor(p.y * 4096).toInt())
        assertTrue(TileKey(12, 2200, 1343) in Viewport(BERLIN, 12.0, 1.0, 1.0).tiles().map { it.key })
    }

    @Test
    fun knownPointsProjectAndComeBack() {
        near(0.5, Mercator.project(LatLng(0.0, 0.0)).x)
        near(0.5, Mercator.project(LatLng(0.0, 0.0)).y)
        near(0.0, Mercator.project(LatLng(Mercator.MAX_LAT, -180.0)).y, 1e-9, "the north edge")
        near(1.0, Mercator.project(LatLng(-89.0, 180.0)).y, 1e-9, "clamped to the south edge")
        for (p in listOf(BERLIN, LatLng(-33.8688, 151.2093), LatLng(64.1466, -21.9426))) {
            val back = Mercator.unproject(Mercator.project(p))
            near(p.lat, back.lat, 1e-9, "lat")
            near(p.lng, back.lng, 1e-9, "lng")
        }
    }

    @Test
    fun aViewIsCoveredByTheTilesItTouchesAndNoOther() {
        val v = Viewport(BERLIN, 12.0, 800.0, 600.0)
        val tiles = v.tiles()
        assertEquals(15, tiles.size)
        for (t in tiles) {
            val (x, y) = v.toScreen(MapPoint(t.x, t.y))
            val edge = t.size * Mercator.worldPx(12.0)
            assertEquals(256.0, edge)
            assertTrue(x < 800 && x + edge > 0 && y < 600 && y + edge > 0, "${t.key.id} reaches into the view")
        }
    }

    @Test
    fun aFractionalZoomDrawsTheFloorLevelScaled() {
        val v = Viewport(BERLIN, 12.5, 800.0, 600.0)
        assertEquals(12, v.level())
        val t = v.tiles().first()
        near(256.0 * 1.4142135623730951, t.size * Mercator.worldPx(12.5), 1e-6)
        assertEquals(19, Viewport(BERLIN, 21.0, 10.0, 10.0).level(), "never above the pyramid")
    }

    @Test
    fun theWorldRepeatsSidewaysAndEndsAtThePoles() {
        val v = Viewport(LatLng(0.0, 179.99), 2.0, 600.0, 2000.0)
        val keys = v.tiles()
        assertTrue(keys.all { it.key.x in 0..3 && it.key.y in 0..3 }, "every key inside the level")
        assertTrue(keys.any { it.x >= 1.0 && it.key.x == 0 }, "east of the antimeridian the west's tiles")
        assertTrue(keys.none { it.y < 0 || it.y >= 1 }, "no row beyond the world")
    }

    @Test
    fun aTileIdParsesOnlyInsideThePyramid() {
        assertEquals(TileKey(12, 2200, 1343), TileKey.parse("12/2200/1343"))
        assertNull(TileKey.parse("12/4096/0"))
        assertNull(TileKey.parse("20/0/0"))
        assertNull(TileKey.parse("1/0"))
        assertNull(TileKey.parse("a/b/c"))
    }

    @Test
    fun framingFitsThePointsWithThePaddingAndAtMostLevelSixteen() {
        val one = assertNotNull(Viewport.fit(listOf(BERLIN), 400.0, 300.0))
        assertEquals(16.0, one.zoom)
        near(BERLIN.lat, one.center.lat, 1e-9, "lat")
        near(BERLIN.lng, one.center.lng, 1e-9, "lng")

        val munich = LatLng(48.137, 11.575)
        val both = assertNotNull(Viewport.fit(listOf(BERLIN, munich), 400.0, 300.0))
        for (p in listOf(BERLIN, munich)) {
            val (x, y) = both.toScreen(Mercator.project(p))
            assertTrue(x >= 50 - 1e-6 && x <= 350 + 1e-6 && y >= 50 - 1e-6 && y <= 250 + 1e-6, "$p inside the padding: $x, $y")
        }
        assertNull(Viewport.fit(emptyList(), 400.0, 300.0))
    }

    @Test
    fun aPanFollowsTheFingerAndAZoomKeepsThePlaceUnderIt() {
        val v = Viewport(BERLIN, 12.0, 800.0, 600.0)
        val p = Mercator.project(BERLIN)
        val panned = v.panBy(100.0, -50.0)
        val (x, y) = panned.toScreen(p)
        near(500.0, x, 1e-6)
        near(250.0, y, 1e-6)

        val q = MapPoint(p.x + 0.0001, p.y + 0.00005)
        val (qx, qy) = v.toScreen(q)
        val zoomed = v.zoomAbout(1.5, qx, qy)
        val (zx, zy) = zoomed.toScreen(q)
        near(qx, zx, 1e-6)
        near(qy, zy, 1e-6)
        assertEquals(13.5, zoomed.zoom)
    }
}
