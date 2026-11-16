package me.raddatz.veyq.mobile.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.raddatz.veyq.mobile.session.DeviceStore

/** The recipients a consent was given for: the tile host (on Apple, [MapServices.APPLE_MAPS]) and the geocoder host. */
data class ConsentHosts(val tiles: String?, val places: String?)

/**
 * This device's consent to the map services (ADR 0016, ADR 0143 D2), the port of the web's `useMapConfig` consent: one
 * per device — never per account, owner or group —, off by default, one switch for the tiles and the geocoder, stored
 * under [KEY] in the device's own store as the hosts it was given for (`{tiles, places}`, the web's JSON). It holds only
 * while those are the hosts served now ([holds]). A withdrawal removes the key and tells every [MapServices] of the
 * process at once ([observe]), which drop their tiles, cancel what is in flight and clear the geocoder's queue.
 */
class MapConsent private constructor(private val store: DeviceStore) {
    private val _given = MutableStateFlow(read())

    /** The hosts the stored consent names; null without one. */
    val given: StateFlow<ConsentHosts?> = _given.asStateFlow()

    private val listeners = MutableStateFlow<List<() -> Unit>>(emptyList())

    /** Gives this device's consent for [hosts] (the ones served now), replacing an earlier one. */
    fun give(hosts: ConsentHosts) {
        val o = JsonObject(mapOf("tiles" to (hosts.tiles?.let(::JsonPrimitive) ?: JsonNull), "places" to (hosts.places?.let(::JsonPrimitive) ?: JsonNull)))
        store.write(KEY, o.toString())
        _given.value = hosts
        tellListeners()
    }

    /** Withdraws it (GDPR Art. 7(3)): the key is removed, and every map of the process stops at once. */
    fun withdraw() {
        store.remove(KEY)
        _given.value = null
        tellListeners()
    }

    /** Calls [listener] at once on each [give] and [withdraw]; the returned function stops that. */
    fun observe(listener: () -> Unit): () -> Unit {
        while (true) {
            val now = listeners.value
            if (listeners.compareAndSet(now, now + listener)) break
        }
        return {
            while (true) {
                val now = listeners.value
                if (listeners.compareAndSet(now, now - listener)) break
            }
        }
    }

    private fun tellListeners() = listeners.value.forEach { it() }

    /** The stored consent; the legacy value `'1'` (or anything not an object) names no host, so it is asked once more. */
    private fun read(): ConsentHosts? {
        val raw = store.read(KEY) ?: return null
        val o = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ConsentHosts(null, null)
        fun host(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return ConsentHosts(host("tiles"), host("places"))
    }

    companion object {
        /** The device store's key (the web's `veyq:maps-consent` in `localStorage`). */
        const val KEY = "maps-consent"

        private val byStore = MutableStateFlow<List<MapConsent>>(emptyList())

        /** The one consent of [store] in this process: every tenant's and account's map services read the same. */
        fun of(store: DeviceStore): MapConsent {
            while (true) {
                val now = byStore.value
                now.firstOrNull { it.store === store }?.let { return it }
                val made = MapConsent(store)
                if (byStore.compareAndSet(now, now + made)) return made
            }
        }

        /**
         * Whether a consent given for [given] holds while [now] is served: the same tile host, and the same geocoder host —
         * or none now (one the Server Owner removed is a recipient fewer, never a new one). A consent that names no host
         * (the legacy record) holds for nothing.
         */
        fun holds(given: ConsentHosts?, now: ConsentHosts): Boolean =
            given != null && given.tiles == now.tiles && (now.places == null || given.places == now.places)
    }
}

/**
 * The host a service address names (`https://tile.example/{z}/{x}/{y}.png` → `tile.example`), as the consent and the
 * settings show it; the address itself where it names none. The web's `serviceHost`.
 */
fun serviceHost(url: String): String = HOST.find(url)?.groupValues?.get(1) ?: url

private val HOST = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)")
