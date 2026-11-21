package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.map.GeoLevel
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.ResolvedPlace
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A photo's place in its details (ADR 0143 D7): its small map and direction, the gates its name is looked up under — the
 * web's `resolvePlaceForCurrent`: a coordinate and no name, the meta writable here, the consent, no slideshow — and the
 * backfill that seals the name once into the raw tier, every other field verbatim, never over a name a peer sealed first.
 */
class PhotoPlaceTest {
    private val m = PlayedMeta()
    private val gps = """{"v":1,"capturedAt":1626372245000,"capturedAtSource":"exif","gpsLat":48.1369,"gpsLng":11.575,"future":[1]}"""

    private fun exif(direction: Double?, ref: String?) = MediaDetails.Exif(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, direction, ref, null, null, null,
    )

    @Test
    fun aPhotoWithGpsHasASmallMapAtZoom15WithItsTrueDirection() {
        val item = m.item(gps)
        assertEquals(PhotoPlace.PhotoMap(LatLng(48.1369, 11.575), 15.0, 271), PhotoPlace.mapOf(item, exif(270.6, "T")))
        assertEquals(null, PhotoPlace.mapOf(item, exif(90.0, "M"))?.directionTrue, "a magnetic one is no true direction")
        assertEquals(null, PhotoPlace.mapOf(item, null)?.directionTrue)
        assertNull(PhotoPlace.mapOf(m.item("""{"v":1,"capturedAt":1}"""), null), "no GPS: no map")
    }

    /** The gate case: the name is looked up only where every gate holds — never where the meta may not be written. */
    @Test
    fun theNameIsLookedUpOnlyWhereEveryGateHolds() {
        val item = m.item(gps)
        assertTrue(PhotoPlace.mayResolve(item, mayWrite = true, geocodingAvailable = true, slideshow = false))
        assertFalse(PhotoPlace.mayResolve(item, mayWrite = false, geocodingAvailable = true, slideshow = false), "without editMeta")
        assertFalse(PhotoPlace.mayResolve(item, mayWrite = true, geocodingAvailable = false, slideshow = false), "without the consent")
        assertFalse(PhotoPlace.mayResolve(item, mayWrite = true, geocodingAvailable = true, slideshow = true), "while the slideshow runs")
        assertFalse(PhotoPlace.mayResolve(m.item("""{"v":1,"gpsLat":1,"gpsLng":2,"placeName":"Here"}"""), true, true, false), "named already")
        assertFalse(PhotoPlace.mayResolve(m.item("""{"v":1}"""), true, true, false), "no coordinate")
    }

    private fun sealTier(mediaId: String, json: String) = b64(m.suite.sealField(Context("media-meta", "scopeId" to m.scopeId, "mediaId" to mediaId), m.key, json.encodeToByteArray()))
    private fun openTier(mediaId: String, wrapped: String) =
        Json.parseToJsonElement(m.suite.openField(Context("media-meta", "scopeId" to m.scopeId, "mediaId" to mediaId), m.key, unb64(wrapped)).decodeToString()) as JsonObject

    @Test
    fun theBackfillSealsTheNameOnceKeepsEveryOtherFieldAndNeverOverAPeersName() = runTest {
        val id = "00000000-0000-4000-8000-0000000000f1"
        val place = ResolvedPlace("München", GeoLevel.Key.CITY)
        val open: (String?) -> JsonObject = { TrackLabel.openRaw(m.suite, m.scopeId, id, m.key, it) }
        val seal: (JsonObject) -> String? = { TrackLabel.seal(m.suite, m.scopeId, id, m.key, it) }
        val sent = mutableListOf<String?>()
        assertEquals(TrackLabel.Outcome.Done("München"), PhotoPlace.backfill(NodeMetaWrite.State(sealTier(id, gps), 1), open, seal, place) { w, _ -> sent += w; NodeMetaWrite.Answer.Taken })
        val after = openTier(id, sent.single()!!)
        val before = Json.parseToJsonElement(gps) as JsonObject
        for (k in before.keys) assertEquals(before.getValue(k).toString(), after.getValue(k).toString(), k)
        assertEquals("\"München\"", after.getValue("placeName").toString())
        assertEquals("\"city\"", after.getValue("placeLevel").toString())
        // a peer sealed a name first: the re-based write writes nothing
        val peers = sealTier(id, gps.replace("\"future\"", "\"placeName\":\"Munich\",\"future\""))
        var sends = 0
        val out = PhotoPlace.backfill(NodeMetaWrite.State(sealTier(id, gps), 1), open, seal, place) { _, _ ->
            sends++
            NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 2))
        }
        assertEquals(TrackLabel.Outcome.Done(null) to 1, out to sends)
    }
}
