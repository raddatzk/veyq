package me.raddatz.veyq.mobile.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.round

/** One level of a reverse-geocoded address, finest to coarsest: its granularity and its name (the web's `GeoLevel`). */
data class GeoLevel(val key: Key, val name: String) {
    /** The granularities, as the web names them in a sealed place's `placeLevel`. */
    enum class Key(val token: String) {
        ROAD("road"), SUBURB("suburb"), CITY("city"), COUNTY("county"), ISLAND("island"), STATE("state"), COUNTRY("country");

        companion object {
            fun of(token: String): Key? = entries.firstOrNull { it.token == token }
        }
    }
}

/** What one resolution yields: the name to seal and the level it came from (the web's `ResolvedPlace`). */
data class ResolvedPlace(val name: String, val level: GeoLevel.Key)

/**
 * The reverse geocoder (ADR 0016, ADR 0143 D2), the port of the web's `geocode.ts` and `placeName.ts`: the operator's
 * Nominatim-compatible service at [base], asked `GET {base}/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=&lon=
 * &accept-language=` with `Accept: application/json` and D3's identification ([MapHttp]) — a bare coordinate, no veyq
 * identity, no Memory. Only while [gate] holds (a geocoder served and this device's consent holding for it), checked when
 * the request is about to leave.
 *
 * The provider's policy is held here, not by a caller: answers are cached on the coordinate rounded to 3 decimals
 * (≈ 110 m) with the language, so every photo of one spot is one lookup and callers arriving together share it;
 * requests are serialised at least [MIN_INTERVAL_MS] apart; there is no retry, and a failure resolves to null and stays
 * the answer for that spot this session. [clear] empties the cache and the queue at once (a withdrawal, a close).
 */
class Geocoder internal constructor(
    private val http: MapHttp,
    private val base: String,
    private val gate: () -> Boolean,
    private val now: () -> Long,
    context: CoroutineContext,
) {
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val queue = Mutex()
    private val lock = SpinLock()
    private val cache = HashMap<String, Deferred<List<GeoLevel>?>>()
    private var lastStartedAt: Long? = null
    private var generation = 0L

    /** The address hierarchy at a coordinate, finest to coarsest, or null: not allowed, not reached, unresolvable. */
    suspend fun reverse(lat: Double, lng: Double, lang: String): List<GeoLevel>? {
        val key = "${round3(lat)},${round3(lng)},$lang"
        val pending = lock.locked {
            val gen = generation
            cache[key] ?: scope.async { queued(gen) { ask(lat, lng, lang) } }.also { cache[key] = it }
        }
        return try {
            pending.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** One coordinate's name at its [defaultLevel], to seal once into a medium's listed tier; null as [reverse]. */
    suspend fun resolve(lat: Double, lng: Double, lang: String): ResolvedPlace? =
        reverse(lat, lng, lang)?.let(::defaultLevel)?.let { ResolvedPlace(it.name, it.key) }

    /** Empties the cache and the queue: nothing waiting is sent (a withdrawal, a lock, a sign-out, a tenant switch). */
    fun clear() {
        lock.locked {
            generation++
            cache.clear()
        }
        scope.coroutineContext.cancelChildren()
    }

    /** Runs [task] after at least [MIN_INTERVAL_MS] since the previous one started: serialised, first come first served. */
    private suspend fun <T> queued(gen: Long, task: suspend () -> T?): T? = queue.withLock {
        lastStartedAt?.let { val wait = MIN_INTERVAL_MS - (now() - it); if (wait > 0) delay(wait) }
        if (lock.locked { gen != generation }) return@withLock null
        lastStartedAt = now()
        task()
    }

    private suspend fun ask(lat: Double, lng: Double, lang: String): List<GeoLevel>? {
        if (!gate()) return null // the off state: no coordinate leaves the device
        val url = base.trimEnd('/') + "/reverse?format=jsonv2&addressdetails=1&zoom=18" +
            "&lat=${plain(lat)}&lon=${plain(lng)}&accept-language=${encode(lang)}"
        val a = http.get(url, listOf("Accept" to "application/json")) ?: return null
        if (a.status !in 200..299) return null
        val body = runCatching { Json.parseToJsonElement(a.body.decodeToString()) }.getOrNull() ?: return null
        return nominatimLevels(body).ifEmpty { null }
    }

    companion object {
        /** The policy's absolute floor between two requests. */
        const val MIN_INTERVAL_MS = 1000L

        /**
         * The finest-to-coarsest hierarchy of a Nominatim answer (`addressdetails=1`); empty for an error, no address, or
         * anything else.
         */
        fun nominatimLevels(data: Any?): List<GeoLevel> {
            val d = data as? JsonObject ?: return emptyList()
            if (d["error"].let { it != null && it !is kotlinx.serialization.json.JsonNull }) return emptyList()
            val a = d["address"] as? JsonObject ?: return emptyList()
            fun str(k: String): String? = (a[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
            val levels = mutableListOf<GeoLevel>()
            (str("road") ?: str("pedestrian") ?: str("footway"))?.let { road ->
                levels += GeoLevel(GeoLevel.Key.ROAD, str("house_number")?.let { "$road ${(a["house_number"] as JsonPrimitive).content}" } ?: road)
            }
            (str("neighbourhood") ?: str("suburb") ?: str("quarter") ?: str("city_district"))?.let { levels += GeoLevel(GeoLevel.Key.SUBURB, it) }
            (str("city") ?: str("town") ?: str("village") ?: str("municipality"))?.let { levels += GeoLevel(GeoLevel.Key.CITY, it) }
            (str("county") ?: str("state_district"))?.let { levels += GeoLevel(GeoLevel.Key.COUNTY, it) }
            str("island")?.let { levels += GeoLevel(GeoLevel.Key.ISLAND, it) }
            (str("state") ?: str("region"))?.let { levels += GeoLevel(GeoLevel.Key.STATE, it) }
            str("country")?.let { levels += GeoLevel(GeoLevel.Key.COUNTRY, it) }
            return levels
        }

        /** The level a fresh place starts at: the city where there is one, else the coarsest; null for none. */
        fun defaultLevel(levels: List<GeoLevel>): GeoLevel? =
            if (levels.isEmpty()) null else levels.firstOrNull { it.key == GeoLevel.Key.CITY } ?: levels.last()

        /** The name at level [key], or null where the hierarchy has none. */
        fun nameAtLevel(levels: List<GeoLevel>, key: GeoLevel.Key): String? = levels.firstOrNull { it.key == key }?.name

        /** A coordinate rounded to 3 decimals (the cache's grain), as a whole number of thousandths. */
        private fun round3(v: Double): Long = round(v * 1000).toLong()

        /** A coordinate as a plain decimal, never in exponent form (JavaScript's `String(number)` for these values). */
        internal fun plain(v: Double): String {
            val s = v.toString()
            if ('E' !in s && 'e' !in s) return s.removeSuffix(".0")
            val micro = round(abs(v) * 1e9).toLong()
            val whole = micro / 1_000_000_000
            val frac = (micro % 1_000_000_000).toString().padStart(9, '0').trimEnd('0')
            return (if (v < 0) "-" else "") + whole + (if (frac.isEmpty()) "" else ".$frac")
        }

        private fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() && b >= 0 || c == '-' || c == '_' || c == '.' || c == '~') append(c)
                else append('%').append(((b.toInt() and 0xff) or 0x100).toString(16).substring(1).uppercase())
            }
        }
    }
}
