package me.raddatz.veyq.mobile.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The writer read back by the JDK's own ZIP reader: entries, bytes, CRCs, and the ZIP64 branches at a lowered limit. */
class ZipTest {
    private suspend fun archive(zip64At: Long = ZipWriter.U32_MAX): File {
        val out = ByteArrayOutputStream()
        val zip = ZipWriter({ out.write(it) }, 1_762_680_612_000L, zip64At)
        zip.addDirectory("Trip")
        zip.addFile("Trip/a.bin", 300_000) { emit -> repeat(3) { emit(ByteArray(100_000) { i -> (i * 7 + it).toByte() }) } }
        zip.addBytes("Trip/Übersicht.csv", "date,kind\r\n".encodeToByteArray())
        zip.addBytes(".veyq/manifest.json", "{}".encodeToByteArray())
        zip.finish()
        return File.createTempFile("zip-test", ".zip").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    private fun check(file: File) = ZipFile(file).use { z ->
        assertEquals(listOf("Trip/", "Trip/a.bin", "Trip/Übersicht.csv", ".veyq/manifest.json"), z.entries().toList().map { it.name })
        val a = z.getInputStream(z.getEntry("Trip/a.bin")).readBytes()
        val expected = ByteArray(300_000).also { b -> repeat(3) { k -> repeat(100_000) { i -> b[k * 100_000 + i] = (i * 7 + k).toByte() } } }
        assertContentEquals(expected, a)
        assertEquals(CRC32().apply { update(expected) }.value, z.getEntry("Trip/a.bin").crc)
        assertEquals("date,kind\r\n", z.getInputStream(z.getEntry("Trip/Übersicht.csv")).readBytes().decodeToString())
        assertEquals(0L, z.getEntry("Trip/").size)
    }

    @Test
    fun theJdkReadsWhatItWrites() = runTest { check(archive()) }

    @Test
    fun theZip64BranchesReadToo() = runTest { check(archive(zip64At = 1000)) }

    @Test
    fun crcIsTheIeeeOne() {
        val b = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        assertEquals(0x414fa339L, Crc32.of(b))
    }

    @Test
    fun aSourceOfAnotherSizeFailsTheArchive() = runTest {
        val zip = ZipWriter({}, 0)
        assertFailsWith<ZipWriter.ZipFailure> { zip.addFile("x", 5) { it(ByteArray(4)) } }
        assertFailsWith<ZipWriter.ZipFailure> { zip.addFile("y", 3) { it(ByteArray(4)) } }
    }

    @Test
    fun anUnsafePathIsRefused() {
        val zip = ZipWriter({}, 0)
        for (bad in listOf("", "/abs", "a/../b", "a//b", "a\\b")) assertFailsWith<ZipWriter.ZipFailure>(bad) { zip.addDirectory(bad) }
    }

    @Test
    fun theDosStampIsUtc() {
        // 2025-11-09T09:30:12Z: date ((45 shl 9) or (11 shl 5) or 9), time ((9 shl 11) or (30 shl 5) or 6)
        assertEquals(((45 shl 9) or (11 shl 5) or 9) to ((9 shl 11) or (30 shl 5) or 6), ZipWriter.dosStamp(1_762_680_612_000L))
    }
}
