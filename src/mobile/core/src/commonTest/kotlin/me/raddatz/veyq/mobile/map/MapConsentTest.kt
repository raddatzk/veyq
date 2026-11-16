package me.raddatz.veyq.mobile.map

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.GEOCODER
import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.OFFER
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The map consent (ADR 0016, ADR 0143 D2): the web's `useMapConfig.test.ts` case for case over the core's
 * [MapServices] and [MapConsent] — one consent per device, off by default, one switch for both services, holding only
 * for the hosts it names — plus what only the apps have: Apple Maps as the tiles' recipient and the offline state.
 */
class MapConsentTest {
    private fun offer(tiles: String? = PlayedMapServices.TEMPLATE, places: String? = GEOCODER, attribution: String? = "© OSM") =
        MapOffer(tiles, attribution, places)

    @Test
    fun isOffByDefaultNoMapNoGeocoding() = runTest {
        val s = PlayedMapServices(this).services()
        assertEquals(MapState.Consent(ConsentHosts("tiles.example", "nominatim.example"), renewal = false), s.state.value)
        assertFalse(s.available)
        assertFalse(s.geocodingAvailable)
        assertNull(s.renewal)
    }

    @Test
    fun theConsentTurnsOnBothTilesAndGeocodingNoMiddleStage() = runTest {
        val s = PlayedMapServices(this).services()
        s.turnOn()
        assertEquals(MapState.Shown, s.state.value)
        assertTrue(s.available)
        assertTrue(s.geocodingAvailable)
    }

    @Test
    fun geocodingStaysOffWhereNoGeocoderIsServedTheMapStillShowsForTypedNames() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val s = played.services(offer(places = null))
        assertTrue(s.available)
        assertFalse(s.geocodingAvailable)
        assertNull(s.geocoder)
    }

    @Test
    fun withNoTileTemplateThereIsNoMapAtAllEvenConsented() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val s = played.services(offer(tiles = null))
        assertEquals(MapState.NotOffered, s.state.value)
        assertFalse(s.available)
        assertFalse(s.geocodingAvailable)
        assertNull(s.tiles)
        s.turnOn() // nothing to consent to
        assertEquals(MapState.NotOffered, s.state.value)
    }

    @Test
    fun theConsentIsTheDevicesEveryTenantsServicesAndALaterStartReadIt() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        assertTrue(played.services().available, "another tenant's services on this device")
        assertSame(MapConsent.of(played.store), played.consent)
        val restarted = MemoryDeviceStore().also { it.write(MapConsent.KEY, played.store.read(MapConsent.KEY)!!) }
        assertEquals(ConsentHosts("tiles.example", "nominatim.example"), MapConsent.of(restarted).given.value)
    }

    @Test
    fun readsTheAddressesTheServerCarriesOnItsCapabilitiesAndOffersNoMapWhereItServesNone() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services(offer(tiles = "https://tiles.other.example/{z}/{x}/{y}.png", attribution = "© Other"))
        assertTrue(s.offered)
        assertEquals("© Other", s.attribution)
        assertEquals("tiles.other.example", s.currentHosts.tiles)
        assertFalse(played.services(offer(tiles = "")).offered)
    }

    @Test
    fun aWithdrawalRemovesTheStoredRecordAndEveryMapOfTheDeviceFollowsAtOnce() = runTest {
        val played = PlayedMapServices(this)
        val a = played.services()
        val b = played.services()
        a.turnOn()
        assertNotNull(played.store.read(MapConsent.KEY))
        assertEquals(MapState.Shown, b.state.value)
        b.turnOff()
        assertNull(played.store.read(MapConsent.KEY))
        assertFalse(a.available, "the other map tears down without a restart")
        assertEquals(MapState.Consent(a.currentHosts, renewal = false), a.state.value)
    }

    @Test
    fun namesAServiceByItsHostTheAddressItselfWhereItNamesNone() {
        assertEquals("tile.openstreetmap.org", serviceHost("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertEquals("{s}.tiles.example:8443", serviceHost("https://{s}.tiles.example:8443/{z}/{x}/{y}.png"))
        assertEquals("tiles", serviceHost("tiles"))
    }

    @Test
    fun storesTheTileHostAndTheGeocoderHostItWasGivenFor() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val o = Json.parseToJsonElement(played.store.read(MapConsent.KEY)!!).jsonObject
        assertEquals("tiles.example", o["tiles"]!!.jsonPrimitive.content)
        assertEquals("nominatim.example", o["places"]!!.jsonPrimitive.content)
    }

    @Test
    fun holdsForTheSameHostsWhateverThePath() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val s = played.services(offer(tiles = "https://tiles.example/other/{z}/{x}/{y}.png"))
        assertTrue(s.available)
        assertNull(s.renewal)
    }

    // bite (c): the consent kept across a changed tile host — `holds` ignoring the tile host → no renewal, red here
    @Test
    fun aChangedTileHostIsNoConsentNoMapUntilTheMemberAgreesAndTheRenewalNamesTheNewHost() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val s = played.services(offer(tiles = "https://tiles.other.example/{z}/{x}/{y}.png"))
        assertFalse(s.available, "no map from a host the member did not consent to")
        assertFalse(s.geocodingAvailable)
        assertEquals(ConsentHosts("tiles.other.example", "nominatim.example"), s.renewal)
        assertEquals(MapState.Consent(s.currentHosts, renewal = true), s.state.value)
        s.turnOn()
        assertTrue(s.available)
    }

    @Test
    fun aChangedOrNewlyAddedGeocoderHostAsksAgainOneTheServerOwnerRemovedNeedsNoNewConsent() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val changed = played.services(offer(places = "https://geo.other.example"))
        assertFalse(changed.geocodingAvailable, "no request to a geocoder the member did not consent to")
        assertFalse(changed.available)

        val removed = played.services(offer(places = null)) // a recipient fewer
        assertTrue(removed.available)
        assertFalse(removed.geocodingAvailable)

        removed.turnOn() // given while no geocoder was served
        val added = played.services(offer())
        assertFalse(added.geocodingAvailable)
        assertFalse(added.available)
    }

    @Test
    fun aRecordThatNamesNoHostTheWebsLegacyOneIsAskedOnceMore() = runTest {
        val played = PlayedMapServices(this)
        played.store.write(MapConsent.KEY, "1")
        val s = played.services()
        assertFalse(s.available)
        assertEquals(ConsentHosts(null, null), s.consentGivenFor)
        assertEquals(ConsentHosts("tiles.example", "nominatim.example"), s.renewal)
        assertEquals(MapState.Consent(s.currentHosts, renewal = true), s.state.value)
    }

    @Test
    fun onAppleTheConsentNamesAppleMapsMapKitFetchesTheMapTheGeocoderIsTheOperators() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services(recipient = TileRecipient.APPLE_MAPS)
        assertEquals(ConsentHosts(MapServices.APPLE_MAPS, "nominatim.example"), s.currentHosts)
        assertNull(s.tiles, "the core fetches no tile on Apple")
        assertNull(s.attribution, "MapKit shows Apple's own")
        s.turnOn()
        assertTrue(s.available)
        assertTrue(s.geocodingAvailable)
        assertEquals("Apple Maps", Json.parseToJsonElement(played.store.read(MapConsent.KEY)!!).jsonObject["tiles"]!!.jsonPrimitive.content)
    }

    @Test
    fun offMeansOffOnAppleTooNoMapWhereTheServerServesNone() = runTest {
        val played = PlayedMapServices(this)
        played.services(recipient = TileRecipient.APPLE_MAPS).turnOn()
        val off = played.services(offer(tiles = null), TileRecipient.APPLE_MAPS)
        assertEquals(MapState.NotOffered, off.state.value)
        assertFalse(off.available)
    }

    @Test
    fun offlineTheCapabilitiesWereNotReadNoMapTheMapNeedsAConnection() = runTest {
        val played = PlayedMapServices(this)
        played.services().turnOn()
        val s = played.services(MapOffer(null, null, null, read = false))
        assertEquals(MapState.Offline, s.state.value)
        assertFalse(s.available)
        assertFalse(s.geocodingAvailable)
        assertTrue(played.services(OFFER).available, "the consent itself stays")
    }
}
