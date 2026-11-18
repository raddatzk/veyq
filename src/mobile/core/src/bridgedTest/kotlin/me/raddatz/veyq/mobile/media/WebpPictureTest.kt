package me.raddatz.veyq.mobile.media

import me.raddatz.veyq.mobile.blob.inflate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A WebP decoded by the core's libwebp into a PNG, as Windows draws a member's picture where its WebP extension is gone
 * (and Linux could): a lossless one and a lossy one with an alpha channel, each read back from the PNG's own stream. The
 * expected pixels are what libwebp's `dwebp` (Homebrew's 1.6.0) wrote for the same files, its default (fancy) upsampling.
 */
class WebpPictureTest {
    @Test
    fun aLosslessPictureDecodes() {
        // a 1×1 lossless WebP (Linux's avatars test's and Windows' AvatarsTest's): one transparent black pixel
        val png = assertNotNull(WebpPicture.png(LOSSLESS_1X1), "the lossless WebP did not decode")
        val (width, height, rgba) = pixels(png)
        assertEquals(1 to 1, width to height)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), rgba)
        // the bytes Windows' CoreTest holds the DLL's answer to (RgbaPngTest's)
        assertContentEquals(RgbaPng.write(1, 1, ByteArray(4)), png)
    }

    @Test
    fun aLossyPictureWithAlphaDecodes() {
        // 4×4, VP8X with ALPH (lossless alpha) and VP8: two rows red and opaque, two blue at half alpha (cwebp -q 95 -alpha_q 100)
        val (width, height, rgba) = pixels(assertNotNull(WebpPicture.png(hex(LOSSY_4X4_ALPHA)), "the lossy WebP did not decode"))
        assertEquals(4 to 4, width to height)
        val rows = listOf(listOf(221, 32, 43, 255), listOf(177, 45, 88, 255), listOf(64, 47, 153, 128), listOf(21, 60, 197, 128))
        for (y in 0 until 4) for (x in 0 until 4) {
            val at = (y * 4 + x) * 4
            val got = (0 until 4).map { rgba[at + it].toInt() and 0xFF }
            assertEquals(rows[y][3], got[3], "alpha at ($x, $y)")
            for (c in 0 until 3) assertTrue(kotlin.math.abs(got[c] - rows[y][c]) <= 2, "($x, $y): $got, dwebp ${rows[y]}")
        }
    }

    @Test
    fun whatIsNoWebpIsNoPicture() {
        assertNull(WebpPicture.png(ByteArray(0)))
        assertNull(WebpPicture.png("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray() + ByteArray(40) { 1 }))
        assertNull(WebpPicture.png(LOSSLESS_1X1.copyOf(20)), "a WebP cut short")
    }

    /** The PNG's width, height and RGBA, read from its IHDR and its IDAT inflated (one row filter 0 each, as RgbaPng writes). */
    private fun pixels(png: ByteArray): Triple<Int, Int, ByteArray> {
        fun int(at: Int) = (png[at].toInt() and 0xFF shl 24) or (png[at + 1].toInt() and 0xFF shl 16) or (png[at + 2].toInt() and 0xFF shl 8) or (png[at + 3].toInt() and 0xFF)
        assertEquals("IHDR", png.decodeToString(12, 16))
        val width = int(16)
        val height = int(20)
        assertEquals(8 to 6, png[24].toInt() to png[25].toInt(), "8 bits, RGBA")
        val idat = 8 + 25
        assertEquals("IDAT", png.decodeToString(idat + 4, idat + 8))
        val raw = assertNotNull(inflate(png.copyOfRange(idat + 8, idat + 8 + int(idat)), 1 shl 20))
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            assertEquals(0, raw[y * (1 + width * 4)].toInt(), "row $y's filter")
            raw.copyInto(rgba, y * width * 4, y * (1 + width * 4) + 1, (y + 1) * (1 + width * 4))
        }
        return Triple(width, height, rgba)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        val LOSSLESS_1X1 = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0x00,
            0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88.toByte(), 0x88.toByte(), 0xfe.toByte(), 0x07, 0x00,
        )
        const val LOSSY_4X4_ALPHA = "524946468a00000057454250565038580a00000010000000030000030000414c50480d000000010ff0c0ff888830" +
            "0f11fd0f060056503820560000003004009d012a0400040000800825a80274b700fd009801fc03f0031403f807b7fdd807f000606b36c0" +
            "0000feff4b3fe460fa4141ffdc7c3e93d20fd8a10a78f843e5ccfff5662669b01ba716485ad0f60e3ff1ab8000"
    }
}
