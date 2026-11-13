package me.raddatz.veyq.mobile.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The core's QR writer, read back by the core's reader: every text veyq draws, at level M, up to version 40. */
class QrEncoderTest {
    private val texts = QrFixtures.texts.values + listOf(
        "", "0123456789012", "VEYQ SAFETY:$%*+-./", "Grüße — 漢字 ✓ 🙂", "https://veyq.example.com/s/abc#k=xyz",
    )

    @Test
    fun everyTextReadsBackAsItself() {
        for (text in texts) assertEquals(text, QrDecoder.decode(frame(QrEncoder.encode(text))), text)
    }

    @Test
    fun theLevelIsMAndTheQuietZoneFourModules() {
        for (text in texts) {
            val m = QrEncoder.encode(text)
            val bits = BitMatrix(m.size)
            for (y in 0 until m.size) for (x in 0 until m.size) if (m.dark(x + QrMatrix.QUIET_ZONE, y + QrMatrix.QUIET_ZONE)) bits.set(x, y)
            assertEquals(ErrorCorrectionLevel.M, BitMatrixParser(bits).readFormatInformation().errorCorrectionLevel, text)
            assertEquals(m.size + 8, m.side)
            for (i in 0 until m.side) for (z in 0 until 4) {
                assertFalse(m.dark(i, z) || m.dark(z, i) || m.dark(i, m.side - 1 - z) || m.dark(m.side - 1 - z, i), "the quiet zone of $text")
            }
            assertTrue(m.dark(4, 4) && m.dark(m.side - 5, 4) && m.dark(4, m.side - 5), "the finder patterns' corners of $text")
        }
    }

    @Test
    fun theLargestBytePayloadOfLevelMFitsVersion40AndOneByteMoreIsRefused() {
        val largest = "veyq".repeat(583).dropLast(1) // 2331 bytes in byte mode
        assertEquals(2331, largest.encodeToByteArray().size)
        val m = QrEncoder.encode(largest)
        assertEquals(177, m.size, "version 40")
        assertEquals(largest, QrDecoder.decode(frame(m, 2)))
        assertFailsWith<IllegalArgumentException> { QrEncoder.encode(largest + "q") }
    }

    /** [m] drawn black on white, [scale] pixels a module, its quiet zone included. */
    private fun frame(m: QrMatrix, scale: Int = 3): QrFrame {
        val side = m.side * scale
        return QrFrame(side, side, side, ByteArray(side * side) { if (m.dark((it % side) / scale, (it / side) / scale)) 0 else -1 })
    }
}
