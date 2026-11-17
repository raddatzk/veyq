package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.map.GeoLevel
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The cross-surface fixture (ADR 0143 D5, D8; ADR 0144): a node's sealed meta as the web seals it — its date, its place,
 * its cover, its participants and its banner, beside a field no surface here knows — and a medium's listed tier as the
 * web seals it (a GPX track's `trackName`, `trackActivity`; a photo's `capturedAtOverride` and `placeName`; a newer
 * client's field). The core reads each as the web wrote it, and after a date write, a place write, a participants write,
 * a label edit and a capture-time write **every other field is byte for byte as it was, in its place**: the write
 * re-seals the blob it opened raw, never one rebuilt from what it parsed.
 */
class CrossSurfaceFixtureTest {
    private val m = PlayedMeta()

    /** The node's meta as the web's `sealNodeMeta` writes it (`JSON.stringify` of `{v, date, location, cover, …}`). */
    private val webMeta = """{"v":1,"date":{"mode":"range","from":"2026-05-01","to":"2026-05-04"},""" +
        """"location":{"name":"Lisboa","nameSource":"geocoded","derived":{"lat":38.7223,"lng":-9.1393},"derivedMode":"auto",""" +
        """"nameAnchor":{"lat":38.7223,"lng":-9.1393,"level":"city"}},""" +
        """"cover":{"source":{"kind":"upload","ref":{"blobId":"7f3a9c1e-0b2d-4c5e-8f60-112233445566","chunkCount":1,"byteSize":48231,""" +
        """"wrappedBlobKey":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","ownerId":"5c09e000-0000-4000-8000-0000000000d1","role":"COVER"}},""" +
        """"crop":{"x":0,"y":0.125,"width":1,"height":0.75}},""" +
        """"participants":{"userIds":["u1","u2"],"futureSub":1},""" +
        """"banner":{"medium":"video","blob":{"blobId":"0b1c2d3e-4f50-4617-8899-aabbccddeeff","chunkCount":3,"byteSize":9437184,""" +
        """"wrappedBlobKey":"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="},"poster":{"blobId":"1c2d3e4f-5061-4728-899a-abbccddeeff0",""" +
        """"chunkCount":1,"byteSize":81234,"wrappedBlobKey":"AgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICE="},"type":"video/mp4",""" +
        """"sha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","frame":"fit","focal":{"x":0.5,"y":0.4375}},""" +
        """"futureField":{"a":[1,"x",null],"b":{"c":true},"n":2.5e-7}}"""

    private fun fieldsOf(json: String) = Json.parseToJsonElement(json) as JsonObject

    /** Every field of [before] but [changed] is in [after] byte for byte, the keys in their order. */
    private fun assertKept(before: JsonObject, after: JsonObject, changed: String) {
        assertEquals(before.keys.toList(), after.keys.toList(), "every field kept, in its place")
        for (k in before.keys - changed) assertEquals(before.getValue(k).toString(), after.getValue(k).toString(), k)
    }

    @Test
    fun theCoreReadsTheWebsDatePlaceAndCover() {
        val meta = m.open(m.seal(webMeta))
        val facts = MemoryFacts.Facts.of(meta)
        assertEquals(MemoryDate(MemoryDate.Mode.RANGE, "2026-05-01", "2026-05-04"), facts.date)
        assertEquals(
            MemoryLocation("Lisboa", MemoryLocation.NameSource.GEOCODED, LatLng(38.7223, -9.1393), MemoryLocation.DerivedMode.AUTO,
                MemoryLocation.Anchor(LatLng(38.7223, -9.1393), GeoLevel.Key.CITY)),
            facts.location,
        )
        assertEquals("7f3a9c1e-0b2d-4c5e-8f60-112233445566", NodeCover.read(meta)?.blobId)
    }

    @Test
    fun aDateWriteKeepsEveryOtherFieldByteForByte() = runTest {
        val base = NodeMetaWrite.State(m.seal(webMeta), 3)
        val server = PlayedMeta.Server()
        val d = MemoryDate(MemoryDate.Mode.ONGOING, "2026-05-01")
        MemoryFacts.write(base, m.open, m.sealMeta, MemoryFacts.dateEdit(d, MemoryFacts.listedField(m.open, base, MemoryDate.FIELD)), server::send)
        val after = m.opened(server.sent.single().first)
        assertKept(fieldsOf(webMeta), after, MemoryDate.FIELD)
        assertEquals("""{"mode":"ongoing","from":"2026-05-01"}""", after.getValue("date").toString())
    }

    /** The cover case: a place write keeps the cover (and every other field) — it is never re-sealed from the parsed meta. */
    @Test
    fun aPlaceWriteKeepsTheCoverAndEveryOtherFieldByteForByte() = runTest {
        val base = NodeMetaWrite.State(m.seal(webMeta), 3)
        val server = PlayedMeta.Server()
        val rome = MemoryLocation("Roma", MemoryLocation.NameSource.MANUAL, LatLng(41.9, 12.5), MemoryLocation.DerivedMode.FIXED)
        MemoryFacts.write(base, m.open, m.sealMeta, MemoryFacts.placeEdit(rome, MemoryFacts.listedField(m.open, base, MemoryLocation.FIELD)), server::send)
        val after = m.opened(server.sent.single().first)
        assertKept(fieldsOf(webMeta), after, MemoryLocation.FIELD)
        assertNotNull(NodeCover.read(after), "the cover is still there")
        assertEquals(rome, MemoryLocation.of(after))
        // and the self-heal's write too
        val healed = PlayedMeta.Server()
        MemoryFacts.write(base, m.open, m.sealMeta, MemoryFacts.reconcileLocation(LatLng(38.71, -9.13)) { null }, healed::send)
        assertKept(fieldsOf(webMeta), m.opened(healed.sent.single().first), MemoryLocation.FIELD)
    }

    /** A GPX track's listed tier as the web seals it (`sealMetadata` with its label, `prune`d), and a newer client's field. */
    private val webTrackTier = """{"v":1,"capturedAt":1731000000000,"capturedAtSource":"file","trackName":"Alps day 2",""" +
        """"trackActivity":"hiking","futureTrackField":{"segments":[3,4],"note":"x"}}"""

    private val mediaId = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private fun sealTier(json: String) = b64(m.suite.sealField(Context("media-meta", "scopeId" to m.scopeId, "mediaId" to mediaId), m.key, json.encodeToByteArray()))
    private fun openTier(wrapped: String) =
        Json.parseToJsonElement(m.suite.openField(Context("media-meta", "scopeId" to m.scopeId, "mediaId" to mediaId), m.key, unb64(wrapped)).decodeToString()) as JsonObject

    @Test
    fun aLabelEditKeepsEveryOtherFieldOfTheTracksTierByteForByte() = runTest {
        val meta = assertNotNull(MemoryMedia.openMeta(m.suite, m.scopeId, mediaId, m.key, sealTier(webTrackTier)))
        assertEquals("Alps day 2", meta.trackName)
        assertEquals("hiking", meta.trackActivity)
        val sent = mutableListOf<String?>()
        val out = TrackLabel.relabel(
            NodeMetaWrite.State(sealTier(webTrackTier), 2),
            { TrackLabel.openRaw(m.suite, m.scopeId, mediaId, m.key, it) },
            { TrackLabel.seal(m.suite, m.scopeId, mediaId, m.key, it) },
            TrackLabel.Label("Alps, day two", "walking"),
        ) { wrapped, _ -> sent += wrapped; NodeMetaWrite.Answer.Taken }
        assertEquals(TrackLabel.Outcome.Done("Alps, day two"), out)
        val before = fieldsOf(webTrackTier)
        val after = openTier(sent.single()!!)
        assertEquals(before.keys.toList(), after.keys.toList())
        for (k in before.keys - setOf("trackName", "trackActivity")) assertEquals(before.getValue(k).toString(), after.getValue(k).toString(), k)
        assertEquals("\"walking\"", after.getValue("trackActivity").toString())
    }

    @Test
    fun anActivityThisBuildDoesNotKnowIsReadAsNoneAndKeptUntilEdited() = runTest {
        val tier = """{"v":1,"trackName":"Paddle","trackActivity":"kayaking"}"""
        fun relabel(label: TrackLabel.Label, into: MutableList<String?>) = suspend {
            TrackLabel.relabel(
                NodeMetaWrite.State(sealTier(tier), 1),
                { TrackLabel.openRaw(m.suite, m.scopeId, mediaId, m.key, it) },
                { TrackLabel.seal(m.suite, m.scopeId, mediaId, m.key, it) },
                label,
            ) { wrapped, _ -> into += wrapped; NodeMetaWrite.Answer.Taken }
        }
        assertEquals(TrackLabel.Label("Paddle", null), TrackLabel.labelOf(Json.parseToJsonElement(tier) as JsonObject))
        val renamed = mutableListOf<String?>()
        relabel(TrackLabel.Label("Paddle home", null), renamed)()
        assertEquals("\"kayaking\"", openTier(renamed.single()!!).getValue("trackActivity").toString(), "the name edited, the activity left as it was")
        val edited = mutableListOf<String?>()
        relabel(TrackLabel.Label("Paddle", "water"), edited)()
        assertEquals("\"water\"", openTier(edited.single()!!).getValue("trackActivity").toString())
    }

    @Test
    fun aPeersLabelWrittenMeanwhileIsAConflictAnyOtherPeersWriteMerges() = runTest {
        val listed = sealTier(webTrackTier)
        suspend fun relabel(peer: String): Pair<TrackLabel.Outcome, Int> {
            var sends = 0
            val out = TrackLabel.relabel(
                NodeMetaWrite.State(listed, 1),
                { TrackLabel.openRaw(m.suite, m.scopeId, mediaId, m.key, it) },
                { TrackLabel.seal(m.suite, m.scopeId, mediaId, m.key, it) },
                TrackLabel.Label("Mine", "running"),
            ) { _, _ -> if (sends++ == 0) NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(sealTier(peer), 2)) else NodeMetaWrite.Answer.Taken }
            return out to sends
        }
        assertEquals(TrackLabel.Outcome.Conflict("Alps day 2", "cycling") to 1, relabel(webTrackTier.replace("hiking", "cycling")), "a peer's activity")
        assertEquals(TrackLabel.Outcome.Done("Mine") to 2, relabel(webTrackTier.replace("1731000000000", "1731000000001")), "a peer's capture time merges")
    }

    @Test
    fun theCoreReadsTheWebsParticipants() = assertEquals(listOf("u1", "u2"), MemoryFacts.Facts.of(m.open(m.seal(webMeta))).participants)

    /** A participants write keeps the date, the place, the cover, the banner and the unknown field byte for byte. */
    @Test
    fun aParticipantsWriteKeepsEveryOtherFieldByteForByte() = runTest {
        val base = NodeMetaWrite.State(m.seal(webMeta), 3)
        val server = PlayedMeta.Server()
        me.raddatz.veyq.mobile.memories.Participants.write(base, m.open, m.sealMeta, listOf("u1", "u2", "u3"), listOf("u1", "u2"), server::send)
        val after = m.opened(server.sent.single().first)
        assertKept(fieldsOf(webMeta), after, me.raddatz.veyq.mobile.memories.Participants.FIELD)
        assertEquals("""{"userIds":["u1","u2","u3"],"futureSub":1}""", after.getValue("participants").toString(), "the sub-object's own unknown field kept")
    }

    /** A photo's listed tier as the web seals it: a correction, a resolved place name, a track name and a newer client's field. */
    private val webPhotoTier = """{"v":1,"w":4032,"h":3024,"capturedAt":1626372245000,"capturedAtSource":"exif",""" +
        """"capturedAtOverride":1626372240000,"gpsLat":48.1371,"gpsLng":11.5754,"placeName":"München","placeLevel":"city",""" +
        """"trackName":"Isar","futurePhotoField":{"x":[1,2.5e-7],"y":null}}"""

    /** Bite (d): a time write re-sealed from the parsed tier (`MemoryMedia.Meta`) loses the unknown field — red here. */
    @Test
    fun aCaptureTimeWriteAndItsResetKeepEveryOtherFieldOfTheTierByteForByte() = runTest {
        val listed = NodeMetaWrite.State(sealTier(webPhotoTier), 6)
        val open: (String?) -> JsonObject = { TrackLabel.openRaw(m.suite, m.scopeId, mediaId, m.key, it) }
        val seal: (JsonObject) -> String? = { TrackLabel.seal(m.suite, m.scopeId, mediaId, m.key, it) }
        assertEquals(1626372240000L, MemoryMedia.openMeta(m.suite, m.scopeId, mediaId, m.key, listed.wrappedMeta)?.capturedAtOverride)
        val before = fieldsOf(webPhotoTier)
        val set = mutableListOf<String?>()
        me.raddatz.veyq.mobile.memories.CaptureTime.write(listed, open, seal, 1700000040000L) { w, _ -> set += w; NodeMetaWrite.Answer.Taken }
        val corrected = openTier(set.single()!!)
        assertEquals(before.keys.toList(), corrected.keys.toList(), "every field kept, in its place")
        for (k in before.keys - "capturedAtOverride") assertEquals(before.getValue(k).toString(), corrected.getValue(k).toString(), k)
        assertEquals("1700000040000", corrected.getValue("capturedAtOverride").toString())
        val reset = mutableListOf<String?>()
        me.raddatz.veyq.mobile.memories.CaptureTime.write(listed, open, seal, null) { w, _ -> reset += w; NodeMetaWrite.Answer.Taken }
        val back = openTier(reset.single()!!)
        assertEquals(before.keys.toList() - "capturedAtOverride", back.keys.toList())
        for (k in back.keys) assertEquals(before.getValue(k).toString(), back.getValue(k).toString(), k)
    }
}
