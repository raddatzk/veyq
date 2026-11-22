package me.raddatz.veyq.mobile.memories.place

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.ResolvedPlace
import me.raddatz.veyq.mobile.memories.ListedTierWrite
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MediaMetadata
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession
import kotlin.math.roundToInt

/**
 * A photo's place in the lightbox's details (ADR 0143 D7): a small map at [ZOOM] centred on its GPS, with the direction
 * the camera faced where its EXIF names a true one, and its **place name resolved once ever** and sealed into its listed
 * tier (`placeName`, `placeLevel`; the web's `resolvePlaceForCurrent` and `backfillPlaceName`) — under the web's gates
 * ([mayResolve]: a coordinate and no name yet, a geocoder this device's consent holds for, not while the slideshow runs,
 * and the medium's meta writable by whoever looks), through the raw read-modify-write [TrackLabel] does, every other field
 * verbatim. A name per open is what ADR 0016's discipline rules out: once per place, sealed.
 */
object PhotoPlace {
    /** The zoom the photo's map shows. */
    const val ZOOM = 15.0

    /** A place name's cap in UTF-8 bytes (the web's `PLACE_NAME_MAX_BYTES`). */
    const val NAME_MAX_BYTES = 96

    /** The photo's map: its point, the zoom, and the direction it faced (whole degrees, true north) where known. */
    data class PhotoMap(val at: LatLng, val zoom: Double, val directionTrue: Int?)

    /** [item]'s map, or null where it has no GPS; [exif] its heavy tier where read (the direction is in it). */
    fun mapOf(item: MemoryMedia.Item, exif: MediaDetails.Exif?): PhotoMap? {
        val m = item.meta ?: return null
        val lat = m.gpsLat ?: return null
        val lng = m.gpsLng ?: return null
        val direction = exif?.imgDirection?.takeIf { exif.imgDirectionRef != "M" }?.roundToInt()?.let { ((it % 360) + 360) % 360 }
        return PhotoMap(LatLng(lat, lng), ZOOM, direction)
    }

    /**
     * Whether [item]'s name is looked up now: it has a coordinate and no name, [mayWrite] (the medium's meta may be written
     * by whoever looks: `MemoryRights.editMeta`), place names may be looked up ([geocodingAvailable]), and no slideshow runs.
     */
    fun mayResolve(item: MemoryMedia.Item, mayWrite: Boolean, geocodingAvailable: Boolean, slideshow: Boolean): Boolean {
        val m = item.meta ?: return false
        return m.gpsLat != null && m.gpsLng != null && m.placeName == null && mayWrite && geocodingAvailable && !slideshow
    }

    /** [raw] with the resolved name and level, every other field verbatim; null where it already has a name (no write). */
    fun backfilled(raw: JsonObject, place: ResolvedPlace): JsonObject? {
        val existing = (raw["placeName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (!existing.isNullOrEmpty()) return null
        val fields = LinkedHashMap<String, JsonElement>(raw)
        fields["placeName"] = JsonPrimitive(MediaMetadata.clampUtf8(place.name.trim(), NAME_MAX_BYTES))
        fields["placeLevel"] = JsonPrimitive(place.level.token)
        return JsonObject(fields)
    }

    /**
     * The backfill's read-modify-write over [send] (the seam a test plays a server through, [ListedTierWrite.write]):
     * [place] sealed into the tier as listed; on a `409 stale_revision` once more on the server's tier — where that one
     * already carries a name, nothing is written. Whether the tier holds a name now.
     */
    suspend fun backfill(
        listed: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String?,
        place: ResolvedPlace,
        send: suspend (wrappedMeta: String?, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): TrackLabel.Outcome = when (
        val r = ListedTierWrite.write(listed, open, seal, own = { null }, edit = { backfilled(it, place) }, send = send)
    ) {
        ListedTierWrite.Result.Done -> TrackLabel.Outcome.Done(place.name)
        // a name is there already (this tier's or a peer's): nothing written; no field of its own conflicts
        ListedTierWrite.Result.Unchanged, is ListedTierWrite.Result.Conflict -> TrackLabel.Outcome.Done(null)
        is ListedTierWrite.Result.Failed -> TrackLabel.Outcome.Failed(r.code)
    }

    /**
     * [item]'s name resolved and sealed on the tenant's server where [mayResolve] holds: the coordinate alone goes to the
     * geocoder ([names]), the name into the tier. The name sealed, or null (gated, unanswered or not written).
     */
    suspend fun resolve(tenant: TenantSession, item: MemoryMedia.Item, names: PlaceNames?, mayWrite: Boolean, slideshow: Boolean): String? {
        if (names == null || !mayResolve(item, mayWrite, names.available, slideshow)) return null
        val m = item.meta ?: return null
        val place = names.maps.geocoder?.resolve(m.gpsLat!!, m.gpsLng!!, names.lang()) ?: return null
        val out = TrackLabel.onServer(tenant, item) { listed, open, seal, send -> backfill(listed, open, seal, place, send) }
        return (out as? TrackLabel.Outcome.Done)?.name
    }
}
