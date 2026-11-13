package me.raddatz.veyq.mobile.qr

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The PNG's own arithmetic on every target; the JVM's `QrPngImageTest` reads the image back through `javax.imageio`. */
class QrPngTest {
    @Test
    fun itsChecksumsAreTheStandardOnes() {
        assertEquals(0xAE426082L, QrPng.crc32("IEND".encodeToByteArray()), "PNG's CRC-32 of an empty IEND")
        assertEquals(0xCBF43926L, QrPng.crc32("123456789".encodeToByteArray()), "CRC-32's check value")
        assertEquals(0x11E60398L, QrPng.adler32("Wikipedia".encodeToByteArray()), "Adler-32 of RFC 1950's example")
    }

    @Test
    fun itIsAOneBitGreyImageOfTheCodeAtTheScaleAsked() {
        val m = QrEncoder.encode(QrFixtures.texts.getValue("pair"))
        val png = QrPng.write(m, 4)
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), png.copyOf(8))
        val side = m.side * 4
        // IHDR: length 13, type, width, height, bit depth 1, colour type 0 (grey), deflate, filter 0, no interlace.
        assertEquals(listOf(13, "IHDR", side, side, 1, 0, 0, 0, 0), listOf(int(png, 8), png.decodeToString(12, 16), int(png, 16), int(png, 20), png[24].toInt(), png[25].toInt(), png[26].toInt(), png[27].toInt(), png[28].toInt()))
        assertEquals("IEND", png.decodeToString(png.size - 8, png.size - 4))
        assertFailsWith<IllegalArgumentException> { QrPng.write(m, 0) }
        assertFailsWith<IllegalArgumentException> { QrPng.write(m, QrPng.MAX_SIDE / m.side + 1) }
    }

    private fun int(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)
}
