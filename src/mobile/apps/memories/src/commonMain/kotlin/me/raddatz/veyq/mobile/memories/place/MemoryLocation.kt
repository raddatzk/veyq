package me.raddatz.veyq.mobile.memories.place

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.raddatz.veyq.mobile.map.GeoLevel
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.MemoryMedia
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A Memory's one named place (uc.memory.place; ADR 0143 D5) — the port of the web's `memoryLocation.ts`: the `location`
 * sub-object of the node's sealed meta. [name] is mandatory but for a **pending** `auto` place (armed, its name to come
 * from the first geotagged medium). [derived] is the map position: the **medoid** of the Memory's photos' GPS (`auto`,
 * moved as media come) or the framed map centre (`fixed`). A `geocoded` name may follow a moved `auto` medoid; a `manual`
 * one is frozen. [nameAnchor] is where a geocoded name was resolved and at which granularity.
 */
data class MemoryLocation(
    val name: String,
    val nameSource: NameSource,
    val derived: LatLng?,
    val derivedMode: DerivedMode,
    val nameAnchor: Anchor? = null,
) {
    enum class NameSource(val token: String) { GEOCODED("geocoded"), MANUAL("manual") }
    enum class DerivedMode(val token: String) { AUTO("auto"), FIXED("fixed") }

    /** Where a geocoded name was resolved, and at which level (road … country). */
    data class Anchor(val at: LatLng, val level: GeoLevel.Key)

    /** Armed but not yet materialized: a pending `auto` place awaiting its first geotagged medium (`isLocationPending`). */
    val pending: Boolean get() = name.isEmpty()

    /** The sub-object as the blob holds it, in the web's key order, absent fields left out; numbers as JavaScript writes them. */
    fun toJson(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["name"] = JsonPrimitive(name)
        m["nameSource"] = JsonPrimitive(nameSource.token)
        derived?.let { m["derived"] = point(it) }
        m["derivedMode"] = JsonPrimitive(derivedMode.token)
        nameAnchor?.let { a -> m["nameAnchor"] = JsonObject(point(a.at) + ("level" to JsonPrimitive(a.level.token))) }
        return JsonObject(m)
    }

    companion object {
        /** The blob's field. */
        const val FIELD = "location"

        /** A place name's bound, in code points (`MAX_PLACE_NAME`: the meta budget's). */
        const val MAX_NAME = 200

        /** The world view a map opens on with no position to show (`WORLD_VIEW`); its centre is never a stored place. */
        val WORLD_CENTER = LatLng(20.0, 0.0)
        const val WORLD_ZOOM = 2.0

        /**
         * Where a map of [width] × [height] that frames [points] opens (inbox #945): their frame, the world view where there
         * is none — and no view while they are still read (null): such a map shows nothing and asks for no tile until its
         * framing is known, so it never fetches the world's tiles first on its way to the framed level.
         */
        fun opening(points: List<LatLng>?, width: Double, height: Double): me.raddatz.veyq.mobile.map.Viewport? =
            points?.let { me.raddatz.veyq.mobile.map.Viewport.fit(it, width, height) ?: me.raddatz.veyq.mobile.map.Viewport(WORLD_CENTER, WORLD_ZOOM, width, height) }

        /** A name trimmed and cut to [MAX_NAME] code points, never splitting a character (`boundPlaceName`). */
        fun bound(v: String): String {
            val t = v.trim()
            var count = 0
            var i = 0
            while (i < t.length) {
                if (count == MAX_NAME) return t.substring(0, i)
                i += if (t[i].isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate()) 2 else 1
                count++
            }
            return t
        }

        /** Whether [p] is exactly the world view's centre — the opening view an untouched editor once stored (#834). */
        fun isWorldCentre(p: LatLng): Boolean = p.lat == WORLD_CENTER.lat && p.lng == WORLD_CENTER.lng

        private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun latLng(e: JsonElement?): LatLng? {
            val o = e as? JsonObject ?: return null
            val lat = num(o["lat"]) ?: return null
            val lng = num(o["lng"]) ?: return null
            return LatLng(lat, lng)
        }

        /**
         * A loosely typed `location` read as a [MemoryLocation], or null for "no place" (`normalizeLocation`): a name is
         * needed but for a pending `auto` + `geocoded` place; the sources fall back to `manual` / `auto`; a malformed point
         * or anchor is dropped; a `fixed` point at the world view's centre reads as no point (uc.memory.place E3).
         */
        fun normalize(raw: JsonElement?): MemoryLocation? {
            val o = raw as? JsonObject ?: return null
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::bound) ?: ""
            val nameSource = if ((o["nameSource"] as? JsonPrimitive)?.content == "geocoded" && (o["nameSource"] as JsonPrimitive).isString) NameSource.GEOCODED else NameSource.MANUAL
            val derivedMode = if ((o["derivedMode"] as? JsonPrimitive)?.let { it.isString && it.content == "fixed" } == true) DerivedMode.FIXED else DerivedMode.AUTO
            val pending = derivedMode == DerivedMode.AUTO && nameSource == NameSource.GEOCODED && name.isEmpty()
            if (name.isEmpty() && !pending) return null
            val anchorPoint = latLng(o["nameAnchor"])
            val level = ((o["nameAnchor"] as? JsonObject)?.get("level") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(GeoLevel.Key::of)
            val derived = latLng(o["derived"])
            val openingView = derivedMode == DerivedMode.FIXED && derived != null && isWorldCentre(derived)
            return MemoryLocation(
                name, nameSource,
                if (openingView) null else derived,
                if (openingView) DerivedMode.AUTO else derivedMode,
                if (anchorPoint != null && level != null) Anchor(anchorPoint, level) else null,
            )
        }

        /** The place a node's opened meta carries, or null. */
        fun of(meta: JsonObject): MemoryLocation? = normalize(meta[FIELD])

        /** [d] degrees in radians, as the web's `haversineKm` wrote it — `(d * π) / 180`, which rounds apart from `d * (π / 180)`. */
        private fun rad(d: Double): Double = (d * PI) / 180

        /** Great-circle distance (haversine), in km: only the medoid's argmin reads it. */
        private fun haversineKm(a: LatLng, b: LatLng): Double {
            val dLat = rad(b.lat - a.lat)
            val dLng = rad(b.lng - a.lng)
            val s1 = sin(dLat / 2)
            val s2 = sin(dLng / 2)
            val s = s1 * s1 + cos(rad(a.lat)) * cos(rad(b.lat)) * s2 * s2
            return 2 * 6371 * asin(min(1.0, sqrt(s)))
        }

        /**
         * The **medoid** of [points]: the one point minimising the summed haversine distance to the others — a real photo's
         * place, never the sea between them; ties to the lowest index; null for none (`computeMedoid`).
         */
        fun medoid(points: List<LatLng>): LatLng? {
            if (points.isEmpty()) return null
            var best = 0
            var bestSum = Double.POSITIVE_INFINITY
            for (i in points.indices) {
                var sum = 0.0
                for (j in points.indices) if (i != j) sum += haversineKm(points[i], points[j])
                if (sum < bestSum) {
                    bestSum = sum
                    best = i
                }
            }
            return points[best]
        }

        /** The GPS points of a Memory's own media, from their listed tiers; one without is skipped (`mediaGpsPoints`). */
        fun gpsPoints(items: List<MemoryMedia.Item>): List<LatLng> = items.mapNotNull { it.meta }.mapNotNull { m ->
            val lat = m.gpsLat
            val lng = m.gpsLng
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }

        /** A number as JavaScript's `JSON.stringify` writes it: a whole one without a fraction. */
        internal fun jsNumber(v: Double): JsonPrimitive =
            if (v == floor(v) && abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)

        private fun point(p: LatLng) = JsonObject(linkedMapOf("lat" to jsNumber(p.lat), "lng" to jsNumber(p.lng)))
    }
}
