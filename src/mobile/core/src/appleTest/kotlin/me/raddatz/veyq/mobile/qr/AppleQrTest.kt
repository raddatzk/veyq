package me.raddatz.veyq.mobile.qr

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Apple camera's door into the scan: a plane handed over as `NSData` arrives whole, at its stride, fitted. */
@OptIn(ExperimentalForeignApi::class)
class AppleQrTest {
    private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    @Test
    fun aPlaneAtAWiderStrideReadsAsTheCode() {
        val code = QrFixtures.frame("pair")
        val stride = code.width + 64 // a pixel buffer's bytes per row are padded
        val plane = ByteArray(stride * code.height) { 0x55 }
        for (y in 0 until code.height) code.luminance.copyInto(plane, y * stride, y * code.width, (y + 1) * code.width)
        val frame = AppleQr.frame(plane.toNSData(), code.width, code.height, stride)
        assertEquals(stride, frame.rowStride)
        assertEquals(QrFixtures.texts.getValue("pair"), QrDecoder.decode(frame))
    }

    @Test
    fun aFullHdPlaneIsFittedToTheScansEdge() {
        val frame = AppleQr.frame(ByteArray(1920 * 1080).toNSData(), 1920, 1080, 1920)
        assertEquals(960 to 540, frame.width to frame.height)
    }
}
