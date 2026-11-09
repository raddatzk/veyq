package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A folder's download as the web's `exportSubtree.ts` writes it (manifest v2, `contracts/export/veyq-subtree.schema.json`). */
class FolderExportTest {
    private fun pot(bytes: ByteArray) = NodeExport.Content(
        "expenses.pot", 1, listOf(NodeExport.Payload("receipt.png", bytes.size.toLong(), "image/png") { bytes }),
    ) { refs -> buildJsonObject { put("receipts", kotlinx.serialization.json.JsonArray(refs)) } }

    private fun archive(root: NodeExport.Entry): Pair<List<String>, JsonObject> {
        val out = ByteArrayOutputStream()
        runBlocking {
            NodeExport.write(root, "1", 0L, { java.security.MessageDigest.getInstance("SHA-256").digest(it) }) { out.write(it) }
        }
        // the central directory, in the order the entries were written (a stream reader takes no stored entry with a descriptor)
        val file = java.io.File.createTempFile("folder", ".zip").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
        java.util.zip.ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(NodeExport.MANIFEST_PATH)).readBytes().decodeToString()).jsonObject
            return names to manifest
        }
    }

    private val tree = NodeExport.Entry(
        "FOLDER", "Trip", null, listOf(
            NodeExport.Entry("EXPENSES_POT", "Rom", pot(byteArrayOf(1, 2, 3))),
            NodeExport.Entry("FOLDER", "Sub", null, listOf(NodeExport.Entry("EXPENSES_POT", "Rom", pot(byteArrayOf(4))))),
            NodeExport.Entry("FOLDER", "Empty", null),
        ),
    )

    @Test
    fun theDirectoriesFirstThenThePayloadsThenTheManifest() {
        val (names, _) = archive(tree)
        assertEquals(
            listOf("Trip/", "Trip/Rom/", "Trip/Sub/", "Trip/Sub/Rom/", "Trip/Empty/", "Trip/Rom/receipt.png", "Trip/Sub/Rom/receipt.png", NodeExport.MANIFEST_PATH),
            names,
        )
    }

    @Test
    fun aFolderIsADirectoryWithoutContentItsChildrenUnderIt() {
        val (_, m) = archive(tree)
        val root = m["root"]!!.jsonObject
        assertEquals("n1", root["id"]!!.jsonPrimitive.content)
        assertFalse("content" in root, "a folder holds no content")
        val children = root["children"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("n2", "n3", "n5"), children.map { it["id"]!!.jsonPrimitive.content }, "ids in walk order")
        assertEquals(listOf("Trip/Rom", "Trip/Sub", "Trip/Empty"), children.map { it["path"]!!.jsonPrimitive.content })
        assertEquals("Trip/Sub/Rom", children[1]["children"]!!.jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertFalse("children" in children[2], "an empty folder lists no children")
        val ref = children[0]["content"]!!.jsonObject["receipts"]!!.jsonArray[0].jsonObject
        assertEquals("Trip/Rom/receipt.png", ref["path"]!!.jsonPrimitive.content)
        assertEquals(64, ref["sha256"]!!.jsonPrimitive.content.length)
        assertEquals("expenses", m["applications"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aTreeOfFoldersNamesNoApplication() {
        val (_, m) = archive(NodeExport.Entry("FOLDER", "Only", null, listOf(NodeExport.Entry("FOLDER", "Inner", null))))
        assertTrue(m["applications"]!!.jsonArray.isEmpty())
    }

    @Test
    fun siblingsOfOneNameAreToldApart() {
        val (names, _) = archive(NodeExport.Entry("FOLDER", "A", null, listOf(NodeExport.Entry("FOLDER", "x", null), NodeExport.Entry("FOLDER", "X", null))))
        assertEquals(listOf("A/", "A/x/", "A/X (2)/", NodeExport.MANIFEST_PATH), names)
    }

    @Test
    fun namesAreReadAsAPersonReadsThem() {
        assertEquals(listOf("foto 2", "Foto 10", "zebra"), listOf("zebra", "Foto 10", "foto 2").sortedWith { a, b -> NodeExport.naturalCompare(a, b) })
    }
}
