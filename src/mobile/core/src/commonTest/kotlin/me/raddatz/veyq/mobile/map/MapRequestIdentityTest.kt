package me.raddatz.veyq.mobile.map

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.map.PlayedMapServices.Companion.BERLIN
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D3 (ADR 0143): every tile and geocoder request names the app and the instance — `User-Agent: veyq/<version>
 * (+https://veyq.app; <instance origin>)` — and nothing that names the person: no `Referer`, no cookie, no token. Read off
 * the requests as they reached the HTTP engine, over the played services and over a signed-in account whose own client
 * carries a bearer.
 */
class MapRequestIdentityTest {
    private val view = Viewport(BERLIN, 12.0, 800.0, 600.0)

    private fun assertIdentity(origin: String, headers: (String) -> String?, has: (String) -> Boolean, what: String) {
        assertEquals("veyq/${VeyqAccount.MAJOR} (+https://veyq.app; $origin)", headers(HttpHeaders.UserAgent), what)
        for (h in listOf(HttpHeaders.Referrer, HttpHeaders.Cookie, HttpHeaders.Authorization)) assertFalse(has(h), "$what carries no $h")
    }

    // bite (d): a request without D3's User-Agent → red here
    @Test
    fun aTileAndAGeocodeNameTheAppAndTheInstanceAndNotThePerson() = runTest {
        val played = PlayedMapServices(this)
        val s = played.services()
        s.turnOn()
        val v = s.tiles!!.view()
        v.show(view)
        v.tile(TileKey(12, 2200, 1343))
        s.geocoder!!.resolve(52.52, 13.405, "de")
        assertEquals(2, played.count)
        played.sent.forEach { r -> assertIdentity(PlayedMapServices.ORIGIN, r::header, r::has, r.url) }
    }

    // bite (d), its other half: the map path through the account's own client (its bearer) → red here
    @Test
    fun aSignedInTenantsMapServicesSendNoBearerAndNameItsOrigin() = runTest {
        val t = PlayedTenant()
        t.capabilities = """[{"id":"memories.map","serviceUrl":"https://tile.map.example/{z}/{x}/{y}.png","serviceAttribution":"© OpenStreetMap contributors"},""" +
            """{"id":"memories.places","serviceUrl":"https://geo.map.example"}]"""
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        assertTrue(tenant.servicesRead)
        assertEquals("© OpenStreetMap contributors", tenant.serviceAttribution(MapServices.MAP))

        val s = MapServices.of(tenant)
        assertEquals(ConsentHosts("tile.map.example", "geo.map.example"), s.currentHosts)
        assertEquals("© OpenStreetMap contributors", s.attribution)
        s.turnOn()
        val v = assertNotNull(s.tiles).view()
        v.show(view)
        assertNotNull(v.tile(TileKey(12, 2200, 1343)))
        assertNotNull(assertNotNull(s.geocoder).resolve(52.52, 13.405, "de"))

        assertEquals(listOf("tile.map.example", "geo.map.example"), t.mapRequests.map { it.url.host })
        t.mapRequests.forEach { r ->
            assertIdentity(tenant.origin, { r.headers[it] }, { r.headers.contains(it) }, r.url.toString())
        }
        s.close()
        account.retire()
    }
}
