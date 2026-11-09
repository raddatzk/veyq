package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.memories.MediaDetails.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The lightbox's details as the web's `MediaLightbox.vue` shows them, and an original's download name (`download.ts`). */
class MediaDetailsTest {
    private fun item(meta: MemoryMedia.Meta? = null, type: String? = "image/jpeg", name: String? = "IMG_1.jpg", bytes: Long = 1028) =
        MemoryMedia.Item("s", "n", "m", MemoryMedia.Blob("b", 1, bytes, "", null), null, null, meta, type, name, null, false, ByteArray(0))

    private fun exif(json: String) = MediaDetails.exifOf(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun theRowsAreTheWebsInItsOrderAnEmptyOneLeftOut() {
        val meta = MemoryMedia.Meta(4000, 3000, 1000L, 2000L, "exif", 54.07912345, 10.8891, "Kühlungsborn", "city")
        val e = exif(
            """{"v":1,"make":"Canon","model":"EOS R6","focalLength":35.4,"focalLength35":0,"fNumber":1.8,"exposureTime":0.004,
            "exposureCompensation":-0.33,"iso":400,"flash":16,"w":4000,"h":3000,"rating":3.6,"altitude":12.4,"imgDirection":181.6,
            "imgDirectionRef":"M","gpsAccuracy":4.25,"lens":"  ","title":"x"}""",
        )
        val rows = MediaDetails.rows(item(meta), e)
        assertEquals(
            listOf(
                Field.CAPTURED, Field.FILE_NAME, Field.FILE_SIZE, Field.FILE_FORMAT, Field.CAMERA, Field.FOCAL_LENGTH, Field.APERTURE,
                Field.EXPOSURE, Field.EXPOSURE_COMPENSATION, Field.ISO, Field.FLASH, Field.DIMENSIONS, Field.TITLE, Field.RATING,
                Field.ALTITUDE, Field.DIRECTION_MAGNETIC, Field.GPS_ACCURACY, Field.CORRECTED, Field.PLACE, Field.LOCATION,
            ),
            rows.map { it.field },
        )
        val by = rows.associateBy { it.field }
        assertEquals(2000L, by[Field.CAPTURED]!!.number, "the correction is the time shown")
        assertEquals(1000L, by[Field.FILE_SIZE]!!.number, "the plaintext size: the stored less a frame's overhead")
        assertEquals("Canon EOS R6", by[Field.CAMERA]!!.value)
        assertEquals("35 mm", by[Field.FOCAL_LENGTH]!!.value)
        assertEquals("f/1.8", by[Field.APERTURE]!!.value)
        assertEquals("1/250 s", by[Field.EXPOSURE]!!.value)
        assertEquals("-0.3 EV", by[Field.EXPOSURE_COMPENSATION]!!.value)
        assertEquals(0L, by[Field.FLASH]!!.number, "16: bit 0 clear, it did not fire")
        assertEquals("4000 × 3000 px", by[Field.DIMENSIONS]!!.value)
        assertEquals("★★★★☆", by[Field.RATING]!!.value)
        assertEquals(182L, by[Field.DIRECTION_MAGNETIC]!!.number)
        assertEquals("4.3 m", by[Field.GPS_ACCURACY]!!.value)
        assertEquals("54.079123, 10.889100", by[Field.LOCATION]!!.value)
    }

    @Test
    fun withoutTheExifTierTheListingAloneIsShown() {
        val rows = MediaDetails.rows(item(type = null, name = null), null)
        assertEquals(listOf(Field.FILE_SIZE), rows.map { it.field })
        assertEquals(listOf(Field.FLASH), MediaDetails.rows(item(type = null, name = null, bytes = 28), exif("""{"flash":1}""")).map { it.field })
        assertEquals(1L, MediaDetails.rows(item(), exif("""{"flash":25}""")).single { it.field == Field.FLASH }.number)
    }

    @Test
    fun numbersPrintAsJavaScriptsToFixed() {
        assertEquals("1.8", MediaDetails.fixed(1.8, 1))
        assertEquals("2", MediaDetails.fixed(2.04, 1))
        assertEquals("0", MediaDetails.fixed(-0.04, 1))
        assertEquals("-12.5", MediaDetails.fixed(-12.5, 1))
        assertEquals("1.000000", MediaDetails.fixed(1.0, 6, keepZeros = true))
        assertEquals("-0.500000", MediaDetails.fixed(-0.5, 6, keepZeros = true))
    }

    @Test
    fun anOriginalIsSavedUnderItsOwnNameElseTheMemorysAndItsPlace() {
        assertEquals("IMG_1.jpg", MediaDetails.downloadName(item(), "Rom", 0))
        assertEquals("IMG_1.jpg", MediaDetails.downloadName(item(name = "IMG_1"), "Rom", 0), "a sealed name gains its type's extension")
        assertEquals("Rom 2026 3.jpg", MediaDetails.downloadName(item(name = null), "Rom/2026", 2))
        assertEquals("memory 1.bin", MediaDetails.downloadName(item(name = " ", type = "image/x-unknown"), " - ", 0))
        assertNull(MediaDetails.exifOf(Json.parseToJsonElement("""{"make":5}""") as JsonObject).make)
    }
}
