package me.raddatz.veyq.mobile.map

import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.map.GeoLevel.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The geocoder (ADR 0016, ADR 0143 D2): the web's `geocode.test.ts` and `placeName.test.ts` case for case, over a
 * geocoder the test plays on virtual time — the levels, the off state, the cache on the rounded coordinate and the
 * language, the ≥ 1 s queue, no retry — plus the withdrawal that clears the queue.
 */
class GeocoderTest {
    private val address = """{"address":{"road":"Musterstraße","house_number":"12","suburb":"Bilk","city":"Düsseldorf","state":"Nordrhein-Westfalen","country":"Deutschland"}}"""
    private val hierarchy = listOf(
        GeoLevel(Key.ROAD, "Musterstraße 12"),
        GeoLevel(Key.SUBURB, "Bilk"),
        GeoLevel(Key.CITY, "Düsseldorf"),
        GeoLevel(Key.STATE, "Nordrhein-Westfalen"),
        GeoLevel(Key.COUNTRY, "Deutschland"),
    )

    private fun on(played: PlayedMapServices, offer: MapOffer = PlayedMapServices.OFFER): MapServices =
        played.services(offer).also { it.turnOn() }

    // ---- geocode.test.ts

    @Test
    fun makesNoRequestWithoutAGeocoderOrWithoutTheConsent() = runTest {
        val played = PlayedMapServices(this)
        val noGeocoder = on(played, PlayedMapServices.OFFER.copy(geocoder = null))
        assertNull(noGeocoder.geocoder)
        val notConsented = played.services(PlayedMapServices.OFFER.copy(tileTemplate = "https://tiles.other.example/{z}/{x}/{y}.png"))
        assertNull(notConsented.geocoder!!.reverse(28.27, -16.61, "de"))
        advanceUntilIdle()
        assertEquals(0, played.count, "no coordinate ever leaves the device")
    }

    @Test
    fun resolvesTheHierarchyFromAnIdentityFreeCoordinateRequest() = runTest {
        val played = PlayedMapServices(this)
        played.geocode = { 200 to address }
        val g = on(played, PlayedMapServices.OFFER.copy(geocoder = "https://nominatim.example/")).geocoder!!
        assertEquals(hierarchy, g.reverse(51.2, 6.77, "de"))
        val u = Url(played.sent.single().url)
        assertEquals("/reverse", u.encodedPath)
        assertEquals("jsonv2", u.parameters["format"])
        assertEquals("1", u.parameters["addressdetails"])
        assertEquals("18", u.parameters["zoom"])
        assertEquals("51.2", u.parameters["lat"])
        assertEquals("6.77", u.parameters["lon"])
        assertEquals("de", u.parameters["accept-language"])
        assertEquals("application/json", played.sent.single().header("Accept"))
    }

    @Test
    fun aProviderErrorAnEmptyAddressANonOkAnswerOrAFailureIsNull() = runTest {
        val answers = listOf<(PlayedMapServices.Sent) -> Pair<Int, String>>(
            { 200 to """{"error":"Unable to geocode"}""" },
            { 200 to """{"address":{}}""" },
            { 500 to "{}" },
            { throw IllegalStateException("offline") },
        )
        for ((i, answer) in answers.withIndex()) {
            val played = PlayedMapServices(this)
            played.geocode = answer
            assertNull(on(played).geocoder!!.reverse(1.0, 2.0, "en"), "answer $i")
        }
    }

    @Test
    fun defaultsToTheCityElseTheCoarsestLevel() {
        assertEquals(GeoLevel(Key.CITY, "Düsseldorf"), Geocoder.defaultLevel(hierarchy))
        val rural = listOf(GeoLevel(Key.STATE, "Kanaren"), GeoLevel(Key.COUNTRY, "Spanien"))
        assertEquals(GeoLevel(Key.COUNTRY, "Spanien"), Geocoder.defaultLevel(rural))
        assertNull(Geocoder.defaultLevel(emptyList()))
        assertEquals("Düsseldorf", Geocoder.nameAtLevel(hierarchy, Key.CITY))
        assertNull(Geocoder.nameAtLevel(hierarchy, Key.COUNTY))
    }

    @Test
    fun includesTheIslandLevelBetweenCityAndState() {
        val body = Json.parseToJsonElement("""{"address":{"city":"Sant Francesc","island":"Formentera","state":"Illes Balears","country":"España"}}""")
        assertEquals(
            listOf(GeoLevel(Key.CITY, "Sant Francesc"), GeoLevel(Key.ISLAND, "Formentera"), GeoLevel(Key.STATE, "Illes Balears"), GeoLevel(Key.COUNTRY, "España")),
            Geocoder.nominatimLevels(body),
        )
    }

    @Test
    fun aCoordinateIsWrittenAsAPlainDecimal() {
        assertEquals("51.2", Geocoder.plain(51.2))
        assertEquals("-16.61", Geocoder.plain(-16.61))
        assertEquals("13", Geocoder.plain(13.0))
        assertEquals("0.00001", Geocoder.plain(0.00001))
        assertEquals("-0.000123", Geocoder.plain(-0.000123))
    }

    // ---- placeName.test.ts

    @Test
    fun asksOncePerPlaceNotOncePerPhoto() = runTest {
        val played = PlayedMapServices(this)
        val g = on(played).geocoder!!
        val results = (0 until 12).map { i -> g.resolve(48.1371 + i * 0.00001, 11.5751 + i * 0.00001, "de") }
        assertEquals(1, played.count)
        assertTrue(results.all { it == ResolvedPlace("München", Key.CITY) })
    }

    @Test
    fun sharesOneInFlightRequestBetweenCallersThatArriveTogether() = runTest {
        val played = PlayedMapServices(this)
        played.hold = CompletableDeferred()
        val g = on(played).geocoder!!
        val all = (0 until 5).map { async { g.resolve(48.137, 11.575, "de") } }
        advanceUntilIdle()
        played.hold!!.complete(Unit)
        assertTrue(all.awaitAll().all { it?.name == "München" })
        assertEquals(1, played.count)
    }

    @Test
    fun keysTheCacheOnTheLanguageToo() = runTest {
        val played = PlayedMapServices(this)
        val g = on(played).geocoder!!
        g.resolve(48.137, 11.575, "de")
        g.resolve(48.137, 11.575, "en")
        assertEquals(2, played.count)
    }

    @Test
    fun aProviderFailureResolvesToNullAndDoesNotWedgeTheQueueBehindIt() = runTest {
        val played = PlayedMapServices(this)
        var first = true
        played.geocode = { if (first) { first = false; throw IllegalStateException("network") } else 200 to """{"address":{"city":"Berlin"}}""" }
        val g = on(played).geocoder!!
        assertNull(g.resolve(52.52, 13.405, "de"))
        assertEquals(ResolvedPlace("Berlin", Key.CITY), g.resolve(50.11, 8.68, "de"))
        assertNull(g.resolve(52.52, 13.405, "de"), "no retry: the failure stays the answer for that spot")
        assertEquals(2, played.count)
    }

    // bite (e): the cache keyed on the exact coordinate → a second geocode 50 m away leaves inside the second, red here
    @Test
    fun aPointFiftyMetresAwayIsTheCachedAnswerAndRequestsAreAtLeastOneSecondApart() = runTest {
        val played = PlayedMapServices(this)
        val g = on(played).geocoder!!
        val start = testScheduler.currentTime
        val here = async { g.resolve(52.5200, 13.4050, "de") }
        val near = async { g.resolve(52.52045, 13.4050, "de") } // 50 m north: the same 110 m cell
        val far = async { g.resolve(48.137, 11.575, "de") }
        listOf(here, near, far).awaitAll()
        assertEquals(2, played.count, "the point 50 m away sent nothing")
        assertEquals(here.await(), near.await())
        assertEquals(start, played.sent[0].at)
        assertTrue(played.sent[1].at - played.sent[0].at >= Geocoder.MIN_INTERVAL_MS, "serialised at least 1 s apart")
    }

    @Test
    fun aWithdrawalClearsTheQueueNothingWaitingLeaves() = runTest {
        val played = PlayedMapServices(this)
        val s = on(played)
        val g = s.geocoder!!
        g.resolve(52.52, 13.405, "de")
        val waiting = async { g.resolve(48.137, 11.575, "de") } // queued for the next second
        runCurrent() // it reaches the queue's wait, no time passes
        s.turnOff()
        advanceUntilIdle()
        assertNull(waiting.await())
        assertEquals(1, played.count)
        s.turnOn()
        g.resolve(52.52, 13.405, "de")
        assertEquals(2, played.count, "the cache went with the withdrawal")
    }
}
