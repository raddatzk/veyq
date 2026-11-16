package me.raddatz.veyq.mobile.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.Reach

/**
 * A tile server and a geocoder the test plays (ADR 0143 D2): every request the map path sends is counted with its URL
 * and headers as they reached the HTTP engine — the instrument every consent bite reads. No test calls a real tile
 * server or Nominatim. Runs on the test's virtual time; [hold] keeps every answer back until released.
 */
class PlayedMapServices(private val test: TestScope) {
    class Sent(val url: String, val headers: Map<String, List<String>>, val at: Long) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.singleOrNull()
        fun has(name: String): Boolean = headers.keys.any { it.equals(name, ignoreCase = true) }
    }

    val sent = mutableListOf<Sent>()
    val count: Int get() = sent.size
    val tiles: List<Sent> get() = sent.filter { "/reverse" !in it.url }
    val geocodes: List<Sent> get() = sent.filter { "/reverse" in it.url }

    /** Answers stay back until this completes (null: answered at once). */
    var hold: CompletableDeferred<Unit>? = null

    /** A tile's answer: status, headers, body. */
    var tile: (Sent) -> Triple<Int, Map<String, String>, ByteArray> = { Triple(200, mapOf("Cache-Control" to "max-age=600", "ETag" to "\"t1\""), TILE) }

    /** The geocoder's answer: status and JSON body; or a thrown failure. */
    var geocode: (Sent) -> Pair<Int, String> = { 200 to """{"address":{"city":"München","country":"Deutschland"}}""" }

    val dispatcher = StandardTestDispatcher(test.testScheduler)

    val client = HttpClient(MockEngine.create {
        dispatcher = this@PlayedMapServices.dispatcher
        addHandler { r ->
            val s = Sent(r.url.toString(), r.headers.entries().associate { it.key to it.value }, test.testScheduler.currentTime)
            sent += s
            hold?.await()
            if ("/reverse" in s.url) {
                val (status, body) = geocode(s)
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                val (status, headers, body) = tile(s)
                respond(body, HttpStatusCode.fromValue(status), headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray()))
            }
        }
    }) { expectSuccess = false }

    fun http(origin: String = ORIGIN) = MapHttp(client, origin)

    val store = MemoryDeviceStore()
    val consent: MapConsent get() = MapConsent.of(store)

    /** The map services of a tenant whose server serves [offer], on this device, on the test's virtual time. */
    fun services(
        offer: MapOffer = OFFER,
        recipient: TileRecipient = TileRecipient.TILE_HOST,
        reach: StateFlow<Reach> = MutableStateFlow(Reach.Online),
    ): MapServices = MapServices(offer, consent, http(), recipient, reach, now = { test.testScheduler.currentTime }, context = dispatcher)

    companion object {
        const val ORIGIN = "https://family.example"
        const val TEMPLATE = "https://tiles.example/{z}/{x}/{y}.png"
        const val GEOCODER = "https://nominatim.example"
        val OFFER = MapOffer(TEMPLATE, "© OpenStreetMap contributors", GEOCODER)
        val TILE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        /** Berlin's centre (the CR's known tile: `12/2200/1343`). */
        val BERLIN = LatLng(52.52, 13.405)
    }
}
