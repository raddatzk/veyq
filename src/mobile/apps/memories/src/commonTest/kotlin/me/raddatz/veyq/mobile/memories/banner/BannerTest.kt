package me.raddatz.veyq.mobile.memories.banner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.memories.place.PlayedMeta
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The banner's shape as the web reads and seals it (`bannerModel.test.ts`, ported case by case), and its frame
 * (ADR 0144 decision 4): 16:9 above a 720-unit column, 1:1 at or below, never taller than 60 % of the viewport, and the
 * surround's 32 px picture.
 */
class BannerTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun ref(id: String) = """{"blobId":"$id","chunkCount":2,"byteSize":1234,"wrappedBlobKey":"d3JhcA=="}"""
    private fun r(id: String) = Banner.Ref(id, 2, 1234, "d3JhcA==")

    /** The digest of 'abc' as `sha256` carries it. */
    private val digest = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"

    @Test
    fun readsAnImageBannerAndNotAnOlderClientsDescription() {
        val b = Banner.read(json("""{"medium":"image","blob":${ref("b1")},"frame":"fill","focal":{"x":0.25,"y":0.75},"alt":"  Sunset  "}"""))
        assertEquals(Banner.Read(Banner.Medium.IMAGE, r("b1"), null, null, null, Banner.Frame.FILL, Banner.Focal(0.25, 0.75)), b)
        assertEquals(Banner.ROLE, b!!.pictureRole)
        assertEquals(r("b1"), b.picture)
    }

    @Test
    fun readsAVideoBannerWithItsPosterTypeAndDigest() {
        val b = Banner.read(json("""{"medium":"video","blob":${ref("v")},"poster":${ref("p")},"type":"video/quicktime","sha256":"$digest","origin":{"mediaId":"m1"},"frame":"fit","focal":{"x":0.5,"y":0.5}}"""))!!
        assertEquals(r("p"), b.poster)
        assertEquals("video/quicktime", b.type)
        assertEquals(digest, b.sha256)
        assertEquals(Banner.Frame.FIT, b.frame)
        assertEquals(r("p"), b.picture, "a video's frame draws its poster")
        assertEquals(Banner.POSTER_ROLE, b.pictureRole)
    }

    @Test
    fun aMalformedDigestIsNone() {
        for (sha in listOf("\"abc\"", "\"${digest}A\"", "\"${digest.take(42)}B\"", "42")) {
            assertNull(Banner.read(json("""{"medium":"video","blob":${ref("v")},"sha256":$sha}"""))?.sha256, sha)
        }
    }

    @Test
    fun degradesNeverThrows() {
        val b = Banner.read(json("""{"medium":"video","blob":${ref("v")},"frame":"stretch","focal":{"x":2,"y":"a"},"type":"text/html"}"""))!!
        assertEquals(Banner.CENTRE, b.focal)
        assertEquals(Banner.Frame.FILL, b.frame)
        assertEquals(Banner.DEFAULT_VIDEO_TYPE, b.type)
        assertNull(b.poster, "a video whose poster is gone still plays")
        assertNull(Banner.shown(b), "but draws no frame")
    }

    @Test
    fun anUnknownShapeIsNoBanner() {
        for (raw in listOf("null", "\"x\"", "[]", "{}", """{"medium":"audio","blob":${ref("a")}}""", """{"medium":"image"}""", """{"medium":"image","blob":{"blobId":"x"}}""")) {
            assertNull(Banner.read(json(raw)), raw)
        }
    }

    @Test
    fun anImageNeverReadsAPoster() {
        val b = Banner.read(json("""{"medium":"image","blob":${ref("b")},"poster":${ref("p")}}"""))!!
        assertNull(b.poster)
        assertNull(b.type)
    }

    @Test
    fun aFocalPointIsKeptOnTheMedium() {
        assertEquals(Banner.Focal(0.0, 1.0), Banner.focal(json("""{"x":0,"y":1}""")))
        assertEquals(Banner.CENTRE, Banner.focal(json("""{"x":-0.1,"y":0.5}""")))
        assertEquals(Banner.CENTRE, Banner.focal(null))
    }

    @Test
    fun sealsOnlyTheFourFieldsOfARefAPosterAndTypeOnlyForAVideoTheDigestOnlyWhenWellFormed() {
        assertEquals(
            """{"medium":"image","blob":${ref("b")},"frame":"fill","focal":{"x":0.1235,"y":1}}""",
            Banner.sealed(Banner.Medium.IMAGE, r("b"), r("p"), null, null, Banner.Frame.FILL, Banner.Focal(0.123456, 1.0)).toString(),
        )
        assertEquals(
            """{"medium":"video","blob":${ref("v")},"poster":${ref("p")},"type":"video/webm","sha256":"$digest","frame":"fit","focal":{"x":0.5,"y":0.5}}""",
            Banner.sealed(Banner.Medium.VIDEO, r("v"), r("p"), "video/webm", digest).toString(),
        )
        assertFalse(Banner.sealed(Banner.Medium.IMAGE, r("b"), null, null, "not-a-digest").containsKey("sha256"))
        assertEquals("fit", (Banner.sealed(Banner.Medium.IMAGE, r("b"), null, null, null)["frame"] as JsonPrimitive).content, "this client writes the whole picture")
    }

    @Test
    fun theSlotsAreReadFromTheRawValue() {
        assertEquals(Banner.Slots("b", "p"), Banner.slots(json("""{"blob":{"blobId":"b"},"poster":{"blobId":"p"}}""")))
        assertEquals(Banner.Slots.NONE, Banner.slots(null))
    }

    @Test
    fun theFrameIs16To9AboveA720ColumnAnd1To1AtOrBelowNeverTallerThan60PercentOfTheViewport() {
        assertEquals(BannerFrame.Size(721.0, 721.0 * 9 / 16, true), BannerFrame.of(721.0, 2000.0))
        assertEquals(BannerFrame.Size(720.0, 720.0, false), BannerFrame.of(720.0, 2000.0))
        assertEquals(BannerFrame.Size(390.0, 390.0, false), BannerFrame.of(390.0, 844.0))
        // a short viewport: the box keeps the column's width and is lowered to 60 % of it
        assertEquals(BannerFrame.Size(390.0, 300.0, false), BannerFrame.of(390.0, 500.0))
        assertEquals(BannerFrame.Size(1600.0, 480.0, true), BannerFrame.of(1600.0, 800.0))
    }

    @Test
    fun theSurroundIsThePictureAtMost32PixelsOnItsLongEdge() {
        assertContentEquals(intArrayOf(32, 18), BannerFrame.surround(1920, 1080))
        assertContentEquals(intArrayOf(24, 32), BannerFrame.surround(3000, 4000))
        assertContentEquals(intArrayOf(20, 10), BannerFrame.surround(20, 10), "never enlarged")
        assertContentEquals(intArrayOf(32, 1), BannerFrame.surround(10000, 10))
    }

    /**
     * The meta budget, measured: the worst case — 100 participants, an uploaded cover, a place at its name's bound in
     * 4-byte characters, a range date and a video banner with its poster, type and digest — seals under 8192 characters
     * and opens back whole.
     */
    @Test
    fun theWorstCaseSealsUnder8192Characters() {
        val m = PlayedMeta()
        val ids = (0 until 100).map { "00000000-0000-4000-8000-" + it.toString().padStart(12, '0') }
        val plan = ApplicationBlobs.Plan("0b1c2d3e-4f50-4617-8899-aabbccddeeff", 40, 10_485_760, "A".repeat(80), m.nodeId)
        val banner = Banner.sealed(
            Banner.Medium.VIDEO, Banner.Ref(plan.blobId, 40, 10_485_760, "A".repeat(80)), Banner.Ref(plan.blobId, 1, 300_000, "A".repeat(80)),
            "video/quicktime", digest, Banner.Frame.FIT, Banner.Focal(0.33333, 0.66666),
        )
        val meta = JsonObject(mapOf(
            "v" to JsonPrimitive(1),
            "date" to MemoryDate(MemoryDate.Mode.RANGE, "2024-07-01", "2024-07-31").toJson(),
            "location" to buildJsonObject {
                put("name", "😀".repeat(MemoryLocation.MAX_NAME)); put("nameSource", "geocoded")
                put("derived", buildJsonObject { put("lat", 52.51234567891234); put("lng", 13.43219876543219) }); put("derivedMode", "auto")
                put("nameAnchor", buildJsonObject { put("lat", 52.51234567891234); put("lng", 13.43219876543219); put("level", "road") })
            },
            "participants" to buildJsonObject { put("userIds", kotlinx.serialization.json.JsonArray(ids.map { JsonPrimitive(it) })) },
            "cover" to NodeCover.uploaded(plan, NodeCover.Crop(0.123456789, 0.2, 0.5, 0.28125)),
            "banner" to banner,
        ))
        val sealed = m.sealMeta(meta)
        assertTrue(sealed.length <= 8192, "${sealed.length} characters")
        assertEquals(banner, m.opened(sealed)["banner"], "it opens back whole")
    }
}
