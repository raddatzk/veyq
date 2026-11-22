package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.map.GeoLevel
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Memory's place (uc.memory.place; ADR 0143 D5): the web's `memoryLocation.test.ts` case for case — the medoid, the
 * photos' points, what reads as a place (the name's bound, the pending arm, the world view's centre as no point), the
 * editor's write keeping the date (W5 on a `409`), and the `auto` self-heal's re-medoid with its name followed.
 */
class MemoryLocationTest {
    private val m = PlayedMeta()
    private fun place(json: String) = MemoryLocation.normalize(Json.parseToJsonElement(json))
    private val manual = MemoryLocation.NameSource.MANUAL
    private val geocoded = MemoryLocation.NameSource.GEOCODED
    private val auto = MemoryLocation.DerivedMode.AUTO
    private val fixed = MemoryLocation.DerivedMode.FIXED

    @Test
    fun theMedoidResistsASeaOutlierTheMeanWouldPullIntoTheOcean() {
        val a = LatLng(28.27, -16.61)
        val b = LatLng(28.28, -16.6)
        val c = LatLng(30.0, -40.0)
        val medoid = MemoryLocation.medoid(listOf(a, b, c))
        assertTrue(medoid == a || medoid == b)
        assertEquals(LatLng(10.0, 20.0), MemoryLocation.medoid(listOf(LatLng(10.0, 20.0))))
        assertNull(MemoryLocation.medoid(emptyList()))
        assertEquals(LatLng(0.0, 0.0), MemoryLocation.medoid(listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))), "a tie: the lowest index")
    }

    @Test
    fun thePhotosPointsComeFromTheListedTierSkippingOneWithout() {
        val items = listOf(
            m.item("""{"v":1,"gpsLat":28.27,"gpsLng":-16.61}"""),
            m.item("""{"v":1,"capturedAt":1}"""),
            m.item(null),
            m.item("""{"v":1,"gpsLat":48.85,"gpsLng":2.35}"""),
        )
        assertEquals(listOf(LatLng(28.27, -16.61), LatLng(48.85, 2.35)), MemoryLocation.gpsPoints(items))
    }

    @Test
    fun aNameIsCutToItsBoundInCodePointsNeverSplittingACharacter() {
        val read = place("""{"name":"${"😀".repeat(MemoryLocation.MAX_NAME + 5)}","nameSource":"manual","derivedMode":"fixed"}""")!!
        assertEquals("😀".repeat(MemoryLocation.MAX_NAME), read.name)
        assertEquals("Rom", MemoryLocation.bound("  Rom  "))
    }

    @Test
    fun aNameIsNeededButForAPendingAutoPlace() {
        assertNull(place("""{"nameSource":"manual","derivedMode":"fixed"}"""))
        assertNull(place("""{"name":"   ","nameSource":"manual"}"""))
        assertNull(place("""{"name":"","nameSource":"manual","derivedMode":"auto"}"""))
        assertNull(MemoryLocation.normalize(null))
        val pending = place("""{"nameSource":"geocoded","derivedMode":"auto"}""")
        assertEquals(MemoryLocation("", geocoded, null, auto), pending)
        assertTrue(pending!!.pending)
        assertFalse(MemoryLocation("Teneriffa", geocoded, null, auto).pending)
    }

    @Test
    fun aFixedPointAtTheWorldViewsCentreReadsAsNoneAHairAwayIsAPoint() {
        fun at(lat: Double, lng: Double, mode: String = "fixed") =
            place("""{"name":"Lido di Ostia","nameSource":"manual","derivedMode":"$mode","derived":{"lat":$lat,"lng":$lng}}""")
        assertEquals(MemoryLocation("Lido di Ostia", manual, null, auto), at(20.0, 0.0))
        assertEquals(LatLng(20.000001, 0.0), at(20.000001, 0.0)?.derived)
        assertEquals(fixed, at(20.0, 0.000001)?.derivedMode)
        assertEquals(LatLng(20.0, 0.0), at(20.0, 0.0, "auto")?.derived, "a medoid is the photos' own, whatever it is")
    }

    @Test
    fun theSourcesFallBackAndAMalformedPointOrAnchorIsDropped() {
        assertEquals(MemoryLocation("X", manual, null, auto), place("""{"name":"X","derived":{"lat":"no"},"nameAnchor":{"lat":1,"lng":2}}"""))
        val full = """{"name":"Teneriffa","nameSource":"geocoded","derived":{"lat":28.27,"lng":-16.61},"derivedMode":"auto","nameAnchor":{"lat":28.27,"lng":-16.61,"level":"city"}}"""
        val read = place(full)!!
        assertEquals(MemoryLocation("Teneriffa", geocoded, LatLng(28.27, -16.61), auto, MemoryLocation.Anchor(LatLng(28.27, -16.61), GeoLevel.Key.CITY)), read)
        assertEquals(full, read.toJson().toString(), "written back as the web wrote it")
        assertEquals("""{"name":"Rom","nameSource":"manual","derived":{"lat":41,"lng":12.5},"derivedMode":"fixed"}""",
            MemoryLocation("Rom", manual, LatLng(41.0, 12.5), fixed).toJson().toString(), "a whole number as JavaScript writes it")
    }

    private val date = """{"mode":"range","from":"2026-05-01","to":"2026-05-04"}"""
    private val rome = MemoryLocation("Rom", manual, LatLng(41.9, 12.5), fixed)

    private suspend fun save(place: MemoryLocation?, base: NodeMetaWrite.State, server: PlayedMeta.Server) =
        MemoryFacts.write(base, m.open, m.sealMeta, MemoryFacts.placeEdit(place, MemoryFacts.listedField(m.open, base, MemoryLocation.FIELD)), server::send).first

    @Test
    fun settingAPlaceKeepsTheDateAndANameOverItsBoundIsWrittenCut() = runTest {
        val server = PlayedMeta.Server()
        save(rome.copy(name = "x".repeat(MemoryLocation.MAX_NAME + 9)), NodeMetaWrite.State(m.seal("""{"v":1,"date":$date}"""), 1), server)
        val sent = m.opened(server.sent.single().first)
        assertEquals(Json.parseToJsonElement(date), sent["date"])
        assertEquals("x".repeat(MemoryLocation.MAX_NAME), MemoryLocation.of(sent)?.name)
    }

    @Test
    fun removingThePlaceKeepsTheDateAndANameOnlyPlaceRoundTrips() = runTest {
        val server = PlayedMeta.Server()
        save(null, NodeMetaWrite.State(m.seal("""{"v":1,"date":$date,"location":{"name":"Rom","nameSource":"manual","derivedMode":"auto"}}"""), 1), server)
        val sent = m.opened(server.sent.single().first)
        assertEquals(setOf("v", "date"), sent.keys)
        val nameOnly = MemoryLocation("Ostia", manual, null, auto)
        save(nameOnly, NodeMetaWrite.State(null, 2), server)
        assertEquals("""{"v":1,"location":{"name":"Ostia","nameSource":"manual","derivedMode":"auto"}}""", m.opened(server.sent.last().first).toString())
    }

    @Test
    fun w5OnAStaleRevisionOnlyThePlaceIsAppliedAgainAPeersDateSurvives() = runTest {
        val peers = m.seal("""{"v":1,"date":$date}""")
        val server = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 5)))
        assertEquals(MemoryFacts.Outcome.Written, save(rome, NodeMetaWrite.State(null, 4), server))
        val retried = m.opened(server.sent[1].first)
        assertEquals(Json.parseToJsonElement(date), retried["date"])
        assertEquals(rome, MemoryLocation.of(retried))
        // a peer's PLACE written meanwhile is a conflict: the editor re-opens on it
        val peerPlace = m.seal("""{"v":1,"location":{"name":"Paris","nameSource":"manual","derivedMode":"auto"}}""")
        val again = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peerPlace, 9)))
        val out = save(rome, NodeMetaWrite.State(null, 8), again)
        assertEquals(MemoryFacts.Outcome.Conflict(MemoryFacts.Facts(null, MemoryLocation("Paris", manual, null, auto))), out)
        assertEquals(1, again.sent.size)
    }

    private val levels = listOf(GeoLevel(GeoLevel.Key.ROAD, "Calle 1"), GeoLevel(GeoLevel.Key.CITY, "La Laguna"), GeoLevel(GeoLevel.Key.COUNTRY, "España"))

    @Test
    fun theAutoPlaceFollowsItsMedoidAndItsGeocodedNameAtItsLevel() = runTest {
        val asked = mutableListOf<LatLng>()
        val levelsAt: suspend (LatLng) -> List<GeoLevel>? = { asked += it; levels }
        val p = LatLng(28.48, -16.31)
        // a pending arm: named at the default level
        val pending = MemoryLocation("", geocoded, null, auto)
        assertEquals(MemoryLocation("La Laguna", geocoded, p, auto, MemoryLocation.Anchor(p, GeoLevel.Key.CITY)), MemoryFacts.nextAuto(pending, p, levelsAt))
        // a moved medoid keeps its granularity
        val road = MemoryLocation("Old road", geocoded, LatLng(0.0, 0.0), auto, MemoryLocation.Anchor(LatLng(0.0, 0.0), GeoLevel.Key.ROAD))
        assertEquals("Calle 1", MemoryFacts.nextAuto(road, p, levelsAt).name)
        // a manual name only moves, never looked up
        asked.clear()
        assertEquals(MemoryLocation("Mine", manual, p, auto), MemoryFacts.nextAuto(MemoryLocation("Mine", manual, null, auto), p, levelsAt))
        assertEquals(emptyList(), asked)
        // no lookup allowed (no consent): re-medoided, the name left
        assertEquals(MemoryLocation("", geocoded, p, auto), MemoryFacts.nextAuto(pending, p) { null })
    }

    @Test
    fun theSelfHealMovesAnAutoPlaceNeverAFixedOneAndWritesNothingUnchanged() = runTest {
        val p = LatLng(28.48, -16.31)
        val heal = MemoryFacts.reconcileLocation(p) { levels }
        val server = PlayedMeta.Server()
        suspend fun run(json: String?) = MemoryFacts.write(NodeMetaWrite.State(json?.let(m::seal), 1), m.open, m.sealMeta, heal, server::send).first
        assertEquals(MemoryFacts.Outcome.Unchanged, run("""{"v":1,"location":{"name":"Rom","nameSource":"manual","derived":{"lat":41,"lng":12},"derivedMode":"fixed"}}"""))
        assertEquals(MemoryFacts.Outcome.Unchanged, run("""{"v":1,"date":$date}"""), "no place: nothing armed")
        assertEquals(MemoryFacts.Outcome.Written, run("""{"v":1,"date":$date,"location":{"nameSource":"geocoded","derivedMode":"auto"}}"""))
        val sent = m.opened(server.sent.single().first)
        assertEquals("La Laguna", MemoryLocation.of(sent)?.name)
        assertEquals(Json.parseToJsonElement(date), sent["date"])
        assertEquals(MemoryFacts.Outcome.Unchanged, run(sent.toString()), "already there: no write")
        assertNotEquals(MemoryFacts.Outcome.Written, run("""{"v":1,"location":{"name":"Rom","nameSource":"manual","derived":{"lat":28.48,"lng":-16.31},"derivedMode":"auto"}}"""))
    }

    /** #945: a map opens on what it frames, the world where there is nothing — and on no view while that is still read. */
    @Test
    fun aMapOpensOnItsFramingAndOnNoViewWhileItIsRead() {
        assertNull(MemoryLocation.opening(null, 800.0, 600.0), "a view before the points were read")
        val world = MemoryLocation.opening(emptyList(), 800.0, 600.0)!!
        assertEquals(MemoryLocation.WORLD_CENTER to MemoryLocation.WORLD_ZOOM, world.center to world.zoom)
        val framed = MemoryLocation.opening(listOf(LatLng(52.52, 13.40), LatLng(52.50, 13.45)), 800.0, 600.0)!!
        assertTrue(framed.zoom > 10, "framed at ${framed.zoom}")
    }
}
