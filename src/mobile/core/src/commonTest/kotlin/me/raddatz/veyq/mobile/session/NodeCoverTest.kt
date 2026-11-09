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
}
