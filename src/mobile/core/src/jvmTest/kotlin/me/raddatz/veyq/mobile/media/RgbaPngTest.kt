package me.raddatz.veyq.mobile.media

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `RgbaPng` read back by `javax.imageio`, pixel for pixel, alpha included and never premultiplied. */
class RgbaPngTest {
    @Test
    fun javaxImageioReadsTheSamePixels() {
        val width = 3
        val height = 2
        val argb = intArrayOf(0xFFDC1E28.toInt(), 0x80143CC8.toInt(), 0x00000000, 0x01FFFFFF, 0xFF000000.toInt(), 0x7F7F7F7F)
        val rgba = ByteArray(width * height * 4)
        argb.forEachIndexed { i, p ->
            rgba[i * 4] = (p ushr 16).toByte(); rgba[i * 4 + 1] = (p ushr 8).toByte(); rgba[i * 4 + 2] = p.toByte(); rgba[i * 4 + 3] = (p ushr 24).toByte()
        }
        val image = ImageIO.read(ByteArrayInputStream(RgbaPng.write(width, height, rgba)))
        assertEquals(width to height, image.width to image.height)
        for (y in 0 until height) for (x in 0 until width) {
            assertEquals(argb[y * width + x], image.getRGB(x, y), "($x, $y)")
        }
    }

    /** The bytes Windows' tests hold the DLL's answer to (`CoreTest`) and feed WIC (`AvatarsTest`): one transparent pixel. */
    @Test
    fun oneTransparentPixelIsTheseBytes() {
        assertEquals(ONE_TRANSPARENT_PIXEL, RgbaPng.write(1, 1, ByteArray(4)).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun aPictureTooLargeOrOfTheWrongLengthIsRefused() {
        assertFailsWith<IllegalArgumentException> { RgbaPng.write(RgbaPng.MAX_SIDE + 1, 1, ByteArray((RgbaPng.MAX_SIDE + 1) * 4)) }
        assertFailsWith<IllegalArgumentException> { RgbaPng.write(0, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { RgbaPng.write(2, 2, ByteArray(15)) }
    }

    private companion object {
        const val ONE_TRANSPARENT_PIXEL = "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c48900000010494441547801010500fa" +
            "ff000000000000050001647895380000000049454e44ae426082"
    }
}
