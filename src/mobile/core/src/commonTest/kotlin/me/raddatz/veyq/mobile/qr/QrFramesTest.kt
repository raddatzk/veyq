package me.raddatz.veyq.mobile.qr

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A camera's plane made a frame the scan reads: the long edge within [QrScan.MAX_EDGE], the code still readable. */
class QrFramesTest {
    @Test
    fun aPlaneThatFitsIsTakenAsItIsStrideAndAll() {
        val plane = ByteArray(1300 * 719 + 1280)
        val frame = QrFrames.fit(1280, 720, 1300, plane)
        assertSame(plane, frame.luminance, "no copy")
        assertEquals(listOf(1280, 720, 1300), listOf(frame.width, frame.height, frame.rowStride))
    }

    @Test
    fun aLargerPlaneIsScaledByTheSmallestWholeFactorThatFits() {
        listOf(
            Triple(1920, 1080, 960 to 540), // factor 2
            Triple(1281, 720, 640 to 360), // one pixel over: factor 2, never 1281
            Triple(4032, 3024, 1008 to 756), // factor 4 (3 would leave 1344)
            Triple(720, 2560, 360 to 1280), // the long edge upright, exactly at the bound
        ).forEach { (w, h, expected) ->
            val frame = QrFrames.fit(w, h, w, ByteArray(w * h))
            assertEquals(expected, frame.width to frame.height, "${w}x$h")
            assertTrue(maxOf(frame.width, frame.height) <= QrScan.MAX_EDGE)
            assertEquals(frame.width, frame.rowStride)
        }
    }

    @Test
    fun eachPixelIsItsBlocksMeanReadPastTheRowStride() {
        // 4x2 at a stride of 6 (the padding 0x7f, never read), scaled by 2 to 2x1 (maxEdge 2)
        val plane = byteArrayOf(
            0, 10, 200.toByte(), 255.toByte(), 0x7f, 0x7f,
            20, 30, 100, 101, 0x7f, 0x7f,
        )
        val frame = QrFrames.fit(4, 2, 6, plane, maxEdge = 2)
        assertEquals(listOf(15, (200 + 255 + 100 + 101) / 4), frame.luminance.map { it.toInt() and 0xff })
    }

    @Test
    fun aPlaneShorterThanItsSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> { QrFrames.fit(2000, 1000, 2000, ByteArray(2000 * 999)) }
    }

    @Test
    fun aCodeSeenByALargerCameraStillReadsOnceScaled() {
        // the rotated pairing fixture drawn twice its size, as a 4K camera would see it, scaled back for the scan
        val small = QrFixtures.frame("pair-rotated")
        val w = small.width * 2
        val h = small.height * 2
        val big = ByteArray(w * h) { i -> small.luminance[(i / w / 2) * small.width + (i % w) / 2] }
        val frame = QrFrames.fit(w, h, w, big, maxEdge = maxOf(small.width, small.height))
        assertEquals(small.width to small.height, frame.width to frame.height)
        assertEquals(QrFixtures.texts.getValue("pair"), QrDecoder.decode(frame))
    }

    @Test
    fun theTimedDecodeReportsTheFrameAndWhetherItReadButNeverTheText() = runTest {
        val heard = mutableListOf<Pair<String, Boolean>>()
        val decode = QrFrames.timedDecode { w, h, millis, read ->
            assertTrue(millis >= 0.0)
            heard += "${w}x$h" to read
        }
        val code = QrFixtures.frame("pair")
        assertEquals(QrFixtures.texts.getValue("pair"), decode(code))
        assertEquals(null, decode(QrFrame(64, 48, 64, ByteArray(64 * 48) { 0x80.toByte() })))
        assertEquals(listOf("${code.width}x${code.height}" to true, "64x48" to false), heard)
    }
}
