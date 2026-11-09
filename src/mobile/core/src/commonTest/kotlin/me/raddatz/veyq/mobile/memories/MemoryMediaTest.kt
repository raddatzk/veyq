package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A medium's listed tier as the web projects it (`normalizeMediaMeta`) and the grid's order (`useMediaGalleryLive`). */
class MemoryMediaTest {
    private fun meta(json: String) = MemoryMedia.metaOf(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun theListedTierIsProjectedAsTheWebDoes() {
        val m = meta("""{"v":1,"w":3000,"h":2000,"capturedAt":1784016900000,"capturedAtSource":"exif","gpsLat":54.079,"gpsLng":10.889,"placeName":"  Kühlungsborn ","extra":true}""")
        assertEquals(3000, m.width)
        assertEquals(2000, m.height)
        assertEquals(1784016900000, m.effectiveCapturedAt)
        assertEquals("exif", m.capturedAtSource)
        assertEquals("Kühlungsborn", m.placeName)
        // a correction wins; a geometry not both positive is none; a source this build does not know is none
        val c = meta("""{"w":0,"h":10,"capturedAt":1,"capturedAtOverride":2,"capturedAtSource":"guess","gpsLat":"54"}""")
        assertEquals(2L, c.effectiveCapturedAt)
        assertNull(c.width)
        assertNull(c.height)
        assertNull(c.capturedAtSource)
        assertNull(c.gpsLat, "a number as a string is no number")
    }

    @Test
    fun theGridIsNewestFirstAnUndatedRowLast() {
        fun item(id: String, at: Long?, override: Long? = null) = MemoryMedia.Item(
            "s", "n", id, MemoryMedia.Blob("b$id", 1, 29, "", null), null, null,
            at?.let { MemoryMedia.Meta(null, null, it, override, null, null, null, null, null) }, null, null, null, false, ByteArray(0),
        )
        val ordered = MemoryMedia.order(listOf(item("a", 10), item("b", null), item("c", 30), item("d", 20, override = 40), item("e", 30)))
        assertEquals(listOf("d", "c", "e", "a", "b"), ordered.map { it.mediaId })
    }
}
