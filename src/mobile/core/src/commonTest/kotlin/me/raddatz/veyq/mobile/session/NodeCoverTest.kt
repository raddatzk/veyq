package me.raddatz.veyq.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The cover field as the web's `coverModel.ts` reads and writes it. */
class NodeCoverTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun aBrokenCropIsTheFullFrameAndACropStaysInsideTheImage() {
        assertEquals(NodeCover.Crop.FULL, NodeCover.crop(null))
        assertEquals(NodeCover.Crop.FULL, NodeCover.crop(json("""{"width":-1,"height":"x"}""")))
        assertEquals(NodeCover.Crop(0.5, 0.25, 0.5, 0.75), NodeCover.crop(json("""{"x":0.9,"y":0.5,"width":0.5,"height":0.75}""")))
        assertEquals(NodeCover.Crop(0.0, 0.0, 1.0, 1.0), NodeCover.crop(json("""{"x":-3,"y":0,"width":7,"height":1}""")))
    }

    @Test
    fun anUploadedCoverIsWrittenInTheWebsShapeAndReadBack() {
        val plan = ApplicationBlobs.Plan("b-1", 2, 300, "d3JhcA==", "node-1")
        val cover = NodeCover.uploaded(plan, NodeCover.Crop(0.1, 0.0, 0.5, 1.0))
        assertEquals(
            """{"source":{"kind":"upload","ref":{"blobId":"b-1","chunkCount":2,"byteSize":300,"wrappedBlobKey":"d3JhcA==","ownerId":"node-1","role":"COVER"}},""" +
                """"crop":{"x":0.1,"y":0,"width":0.5,"height":1}}""",
            cover.toString(),
        )
        val read = NodeCover.read(JsonObject(mapOf("cover" to cover)))!!
        assertEquals("b-1", read.blobId)
        assertEquals(2, read.chunkCount)
        assertEquals(NodeCover.Crop(0.1, 0.0, 0.5, 1.0), read.crop)
    }

    @Test
    fun aCoverOfAnotherSourceOrWithoutItsRefIsNoneTheAppDraws() {
        assertNull(NodeCover.read(json("""{"cover":{"source":{"kind":"gallery"}}}""") as JsonObject))
        assertNull(NodeCover.read(json("""{"cover":{"source":{"kind":"upload","ref":{"blobId":"b"}}}}""") as JsonObject))
        assertNull(NodeCover.read(json("""{"date":"2026"}""") as JsonObject))
    }

    @Test
    fun a_lists_covers_keep_what_still_holds_and_open_what_changed() {
        val held = mapOf("a" to "b1@0,0,1,1", "b" to "b2@0,0,1,1", "gone" to "b3@0,0,1,1")
        val named = mapOf("a" to "b1@0,0,1,1", "b" to "b2@0.5,0,0.5,1", "new" to "b4@0,0,1,1")
        val c = NodeCover.changes(named, held)
        // the same blob and crop: kept; a new crop or a new cover: opened; one no longer named: dropped
        kotlin.test.assertEquals(setOf("a"), c.keep)
        kotlin.test.assertEquals(mapOf("b" to "b2@0.5,0,0.5,1", "new" to "b4@0,0,1,1"), c.open)
    }

    @Test
    fun a_covers_jpeg_is_the_webs_spec_and_its_size_keeps_the_aspect_without_enlarging() {
        // the web's COVER_SPEC: long edge 2048, quality 0.85; a list's picture at least 384 on its short edge
        kotlin.test.assertEquals(2048, NodeCover.LONG_EDGE)
        kotlin.test.assertEquals(0.85, NodeCover.QUALITY)
        kotlin.test.assertEquals(kotlin.math.round(NodeCover.QUALITY * 100).toInt(), NodeCover.QUALITY_PERCENT)
        kotlin.test.assertEquals(384, NodeCover.THUMBNAIL_EDGE)
        kotlin.test.assertContentEquals(intArrayOf(2048, 1536), NodeCover.encodedSize(4000, 3000))
        kotlin.test.assertContentEquals(intArrayOf(1152, 2048), NodeCover.encodedSize(3024, 5376))
        kotlin.test.assertContentEquals(intArrayOf(2048, 1), NodeCover.encodedSize(10000, 2))
        // a picture inside the spec is never enlarged
        kotlin.test.assertContentEquals(intArrayOf(800, 600), NodeCover.encodedSize(800, 600))
        kotlin.test.assertContentEquals(intArrayOf(2048, 2048), NodeCover.encodedSize(2048, 2048))
    }
}
