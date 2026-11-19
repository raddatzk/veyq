package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.qr.readRepoFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The core's ZIP reader (ADR 0152 decision 2) over what the core's own writer writes, over the web's download, and over a
 * file it must never read whole: the screen and the open read the end, the directory and the entries' headers, an entry's
 * bytes only when they are asked for.
 */
class ZipReaderTest {
    private val big = ByteArray(3 * 1024 * 1024 + 17) { (it * 31 + 7).toByte() }

    private suspend fun written(zip64At: Long = ZipWriter.U32_MAX): ByteArray {
        val out = ArrayList<ByteArray>()
        val zip = ZipWriter({ out += it }, 1_762_680_612_000L, zip64At)
        zip.addDirectory("Trip")
        zip.addFile("Trip/big.bin", big.size.toLong()) { emit -> big.toList().chunked(500_000).forEach { emit(it.toByteArray()) } }
        zip.addBytes("Trip/Übersicht.csv", "date,kind\r\n".encodeToByteArray())
        zip.addBytes(".veyq/manifest.json", "{}".encodeToByteArray())
        zip.finish()
        val all = ByteArray(out.sumOf { it.size })
        var o = 0
        for (b in out) { b.copyInto(all, o); o += b.size }
        return all
    }

    private suspend fun readBack(bytes: ByteArray) {
        val source = CountingSource("t.zip", bytes)
        val zip = ZipReader.open(source.size, source::read)
        assertEquals(listOf("Trip/", "Trip/big.bin", "Trip/Übersicht.csv", ".veyq/manifest.json"), zip.entries.map { it.name })
        assertTrue(source.read < 70_000, "the open read ${source.read} bytes of ${bytes.size}")
        val got = ArrayList<ByteArray>()
        zip.stream("Trip/big.bin") { got += it }
        assertTrue(got.all { it.size <= ZipReader.READ_CHUNK }, "a frame at a time")
        assertContentEquals(big, got.reduce { a, b -> a + b })
        assertEquals(Crc32.of(big), zip.entry("Trip/big.bin")!!.crc32)
        assertContentEquals(big.copyOfRange(1_000_000, 1_000_100), zip.readEntry("Trip/big.bin", 1_000_000, 100))
        assertEquals("date,kind\r\n", zip.bytes("Trip/Übersicht.csv", 100).decodeToString())
    }

    @Test
    fun theCoresOwnDownloadReadsBack() = runTest { readBack(written()) }

    @Test
    fun andItsZip64Branches() = runTest { readBack(written(zip64At = 1000)) }

    @Test
    fun aFlippedBitIsACrcMismatchAtTheEndOfItsStream() = runTest {
        val bytes = written()
        val zip = ZipReader.open(bytes.size.toLong()) { from, length -> bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt()) }
        bytes[(zip.entry("Trip/big.bin")!!.dataAt + 5).toInt()] = (bytes[(zip.entry("Trip/big.bin")!!.dataAt + 5).toInt()].toInt() xor 1).toByte()
        val e = assertFailsWith<ZipReader.ZipError> { zip.stream("Trip/big.bin") {} }
        assertEquals(ZipReader.ZipError.Code.CRC_MISMATCH, e.code)
    }

    @Test
    fun theScreenAndTheOpenNeverReadAPayload() = runTest {
        // a download of one 3 MiB file, as the core writes it: the screen and the open read its end, its directory, the
        // headers and the manifest - never the payload (lever: read the source whole in `ZipReader.open`)
        val out = ArrayList<ByteArray>()
        val content = NodeExport.Content("depot.file", 1, listOf(NodeExport.Payload.whole("big.bin", big.size.toLong(), "application/octet-stream") { big }), asFile = true) { refs ->
            kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("depot.file")); put("schema", kotlinx.serialization.json.JsonPrimitive(1)); put("file", refs.single()) }
        }
        NodeExport.write(NodeExport.Entry("FOLDER", "Trip", null, listOf(NodeExport.Entry("FILE", "big.bin", content))), "1", 0L) { out += it }
        val bytes = out.reduce { a, b -> a + b }
        val screen = CountingSource("t.zip", bytes)
        assertTrue(ImportSource.isDownload(screen))
        assertTrue(screen.read < 70_000, "the screen read ${screen.read} of ${bytes.size} bytes")
        val opening = CountingSource("t.zip", bytes)
        val opened = SubtreeImport.open(ImportInput.Zip(opening))
        assertEquals(big.size.toLong(), opened.source.size("Trip/big.bin"))
        assertTrue(opening.read < 70_000, "the open read ${opening.read} of ${bytes.size} bytes")
    }

    @Test
    fun aZip64EntryPast4GiBIsListedWithoutReadingIt() = runTest {
        // a sparse file: a local header with its ZIP64 extra, 5 GiB the reader never asks for, the descriptor, the directory
        val size = 5L * 1024 * 1024 * 1024 + 3
        val name = "Trip/film.mov".encodeToByteArray()
        val le = Le()
        val head = le.u32(0x04034b50).u16(45).u16(0x0808).u16(0).u16(0).u16(0).u32(0).u32(0xffffffffL).u32(0xffffffffL)
            .u16(name.size).u16(20).bytes(name).u16(1).u16(16).u64(0).u64(0).done()
        val descAt = head.size + size
        val desc = Le().u32(0x08074b50).u32(0x1234).u64(size).u64(size).done()
        val cdAt = descAt + desc.size
        val central = Le().u32(0x02014b50).u16(45).u16(45).u16(0x0808).u16(0).u16(0).u16(0).u32(0x1234).u32(0xffffffffL).u32(0xffffffffL)
            .u16(name.size).u16(20).u16(0).u16(0).u16(0).u32(0).u32(0).bytes(name).u16(1).u16(16).u64(size).u64(size).done()
        val eocd64At = cdAt + central.size
        val tail = Le().u32(0x06064b50).u64(44).u16(45).u16(45).u32(0).u32(0).u64(1).u64(1).u64(central.size.toLong()).u64(cdAt)
            .u32(0x07064b50).u32(0).u64(eocd64At).u32(1)
            .u32(0x06054b50).u16(0).u16(0).u16(0xffff).u16(0xffff).u32(0xffffffffL).u32(0xffffffffL).u16(0).done()
        val total = eocd64At + tail.size
        val segments = listOf(0L to head, descAt to desc, cdAt to central, eocd64At to tail)
        val source = CountingSource("film.zip", ByteArray(0), total) { from, length ->
            val out = ByteArray(minOf(length.toLong(), total - from).toInt())
            for ((at, bytes) in segments) {
                for (i in out.indices) {
                    val p = from + i - at
                    if (p >= 0 && p < bytes.size) out[i] = bytes[p.toInt()]
                }
            }
            out
        }
        val zip = ZipReader.open(source.size, source::read)
        val e = zip.entry("Trip/film.mov")!!
        assertEquals(size, e.size)
        assertEquals(head.size.toLong(), e.dataAt)
        assertTrue(source.read < 70_000, "read ${source.read} bytes of a ${total}-byte file")
    }

    /** A little-endian record builder (the writer's `Le`, open-ended). */
    private class Le {
        private val out = ArrayList<Byte>()
        fun u16(v: Int) = apply { out += v.toByte(); out += (v shr 8).toByte() }
        fun u32(v: Long) = apply { for (i in 0 until 4) out += (v shr (8 * i)).toByte() }
        fun u32(v: Int) = u32(v.toLong() and 0xffffffffL)
        fun u64(v: Long) = apply { for (i in 0 until 8) out += (v shr (8 * i)).toByte() }
        fun bytes(v: ByteArray) = apply { out += v.toList() }
        fun done() = out.toByteArray()
    }

    @Test
    fun theLimitsAreTheSchemas() {
        // the contract's maxLength / maxItems of every field core reads (the web pins its own LIMITS in importHardening.test.ts)
        val schema = Json.parseToJsonElement(readRepoFile("contracts/export/veyq-subtree.schema.json").decodeToString()).jsonObject
        fun at(vararg path: String): Int = path.fold(schema as kotlinx.serialization.json.JsonElement) { o, k -> (o as JsonObject)[k]!! }.jsonPrimitive.int
        assertEquals(
            mapOf(
                "appVersion" to at("properties", "appVersion", "maxLength"),
                "applications" to at("properties", "applications", "maxItems"),
                "kind" to at("\$defs", "node", "properties", "kind", "maxLength"),
                "name" to at("\$defs", "node", "properties", "name", "maxLength"),
                "path" to at("\$defs", "archivePath", "maxLength"),
                "contentType" to at("\$defs", "content", "properties", "type", "maxLength"),
                "mediaType" to at("\$defs", "payload", "properties", "mediaType", "maxLength"),
            ),
            Manifest.LIMITS,
        )
    }
}
