package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.media.VideoHeader
import java.io.File
import java.util.GregorianCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a photo's and a video's upload seals from its bytes, over the web's own fixtures (`features/memories/__fixtures__`,
 * `shared/image/__fixtures__`, made by `scripts/make_exif_fixture.py`), held to exactly what the web's
 * `readExif.fixture.test.ts` and `videoHeader.test.ts` expect of them: the parity of the two readers.
 */
class MediaMetadataFixtureTest {
    private val root = File(System.getProperty("veyq.repoRoot"))
    private fun file(path: String) = File(root, path).readBytes()
    private fun source(bytes: ByteArray) = ByteSource(bytes.size.toLong()) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }
    private fun photo(name: String) = source(file("src/web/src/features/memories/__fixtures__/$name"))
    private fun clip(name: String) = source(file("src/web/src/shared/image/__fixtures__/$name"))

    /** `new Date(y, m - 1, d, h, mi, s)` in the device's zone. */
    private fun local(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int) =
        GregorianCalendar(y, m - 1, d, h, mi, s).timeInMillis

    private fun json(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val bundle = """{"v":1,"make":"Canon","model":"EOS R","lens":"RF 50mm F1.8 STM","focalLength":50,"focalLength35":75,
        "fNumber":1.8,"exposureTime":0.002,"exposureCompensation":-0.7,"iso":200,"flash":9,"orientation":6,
        "software":"veyq fixture 1.0","artist":"A. Photographer","copyright":"(c) 2021 A. Photographer","rating":4,
        "title":"Marienplatz","description":"Marienplatz at golden hour","altitude":519.4,"imgDirection":215.3,
        "imgDirectionRef":"T","gpsAccuracy":4.8,"w":4032,"h":3024}"""
    private val bundleWithoutXmp = json(bundle).let { JsonObject(it - "title") }

    @Test
    fun aPhotoInEveryContainerReadsWhatTheWebReads() = runTest {
        val taken = local(2021, 7, 15, 18, 4, 5)
        for ((name, type, exif) in listOf(
            Triple("exif-sample.jpg", "image/jpeg", json(bundle)),
            Triple("exif-sample.png", "image/png", json(bundle)),
            Triple("exif-sample.tif", "image/tiff", bundleWithoutXmp),
            Triple("exif-sample-le.tif", "image/tiff", bundleWithoutXmp),
            Triple("exif-sample.avif", "image/avif", bundleWithoutXmp),
            Triple("exif-sample.heic", "image/heic", bundleWithoutXmp),
            Triple("exif-sample.webp", "image/webp", json(bundle)),
            Triple("exif-sample-ftyp52.heic", "image/heic", bundleWithoutXmp),
        )) {
            val read = MediaMetadata.read(photo(name), type, NOW)
            assertEquals(taken, read.capturedAt, name)
            assertEquals("exif", read.capturedAtFrom, name)
            assertEquals(48.136944444444445, read.gpsLat, name)
            assertEquals(11.575, read.gpsLng, name)
            assertEquals(exif, read.exif, name)
            // the tier as JavaScript writes it: whole numbers as integers
            assertEquals(Json.parseToJsonElement(exif.toString()).toString(), read.exif.toString(), name)
        }
    }

    @Test
    fun anXmpCaptureDateIsTakenWhereThereIsNoExifOneNotTheModifyDate() = runTest {
        val read = MediaMetadata.read(photo("exif-sample-xmpdate.png"), "image/png", NOW)
        assertEquals(local(2022, 8, 7, 6, 5, 4), read.capturedAt)
        assertEquals(json("""{"v":1,"software":"veyq fixture 1.0"}"""), read.exif)
        assertNull(read.gpsLat)
    }

    @Test
    fun eachSecondChoiceIsTakenWhereTheFirstIsMissing() = runTest {
        val read = MediaMetadata.read(photo("exif-sample-fallbacks.jpg"), "image/jpeg", NOW)
        assertEquals(local(2020, 1, 2, 3, 4, 5), read.capturedAt)
        assertEquals(-33.867777777777775, read.gpsLat)
        assertEquals(-70.67, read.gpsLng)
        assertEquals(
            json("""{"v":1,"make":"Nikon","lens":"Nikkor","software":"darktable 4.8","rating":3,"altitude":-12.5,"w":640,"h":480}"""),
            read.exif,
        )
    }

    @Test
    fun theDigitisedDateAndAnIptcObjectNameAreTaken() = runTest {
        val read = MediaMetadata.read(photo("exif-sample-iptc.jpg"), "image/jpeg", NOW)
        assertEquals(local(2019, 3, 4, 5, 6, 7), read.capturedAt)
        assertEquals(json("""{"v":1,"title":"Hofgarten"}"""), read.exif)
    }

    @Test
    fun aVideosContainerTimeAndSizeAreTheWebs() = runTest {
        val at = java.time.Instant.parse("2024-05-12T12:03:21Z").toEpochMilli()
        val dated = MediaMetadata.read(clip("dated.mp4"), "video/mp4", NOW)
        assertEquals(at, dated.capturedAt)
        assertEquals("container", dated.capturedAtFrom)
        assertNull(dated.exif)
        assertEquals(at, VideoHeader.captureTime(clip("dated-apple-keys.mov"), NOW), "Apple's key, its offset applied, wins")
        for (none in listOf("landscape.mp4", "clip.webm", "clip.mkv")) assertNull(VideoHeader.captureTime(clip(none), NOW), none)
        assertEquals(64 to 36, VideoHeader.size(clip("landscape.mp4"))?.let { it.width to it.height })
        assertEquals(36 to 64, VideoHeader.size(clip("portrait-moov-at-end.mov"))?.let { it.width to it.height }, "a quarter turn swaps")
        assertNull(VideoHeader.size(clip("clip.webm")), "Matroska's size is the platform's")
    }

    @Test
    fun theListedTierTakesTheFilesDateOnlyWhereTheBytesCarryNone() {
        val none = MediaMetadata.Read(null, "exif", null, null, null)
        assertEquals(json("""{"v":1,"w":800,"h":600,"capturedAt":5,"capturedAtSource":"file"}"""), MediaMetadata.metaTier(800, 600, none, 5))
        assertNull(MediaMetadata.metaTier(null, null, none, null), "only its version: none sealed")
        val dated = MediaMetadata.Read(7, "container", 1.5, -2.0, null)
        assertEquals(
            """{"v":1,"capturedAt":7,"capturedAtSource":"container","gpsLat":1.5,"gpsLng":-2}""",
            MediaMetadata.metaTier(null, null, dated, 5).toString(),
        )
    }

    private companion object {
        val NOW = java.time.Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()
    }
}
