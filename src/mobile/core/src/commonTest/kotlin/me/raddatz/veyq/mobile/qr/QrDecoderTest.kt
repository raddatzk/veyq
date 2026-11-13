package me.raddatz.veyq.mobile.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The core's QR decoder over the codes veyq draws (`QrFixtures`: the web's own `qrcode` output, inverted, and a
 * photo-like degraded set), on every target the tests run on; ZXing's corpus is the JVM's `QrCorpusTest`.
 */
class QrDecoderTest {
    @Test
    fun everyFixtureReadsAsItsText() {
        val names = QrFixtures.texts.keys.flatMap { listOf(it, "$it-inverted") } +
            listOf("pair-rotated", "pair-perspective", "pair-blurred", "pair-lowcontrast")
        for (name in names) {
            val text = QrFixtures.texts.getValue(name.substringBefore('-'))
            assertEquals(text, QrDecoder.decode(QrFixtures.frame(name)), name)
            assertEquals(text, QrDecoder.decode(QrFixtures.frame(name), tryHarder = true), "$name trying harder")
        }
    }

    @Test
    fun aCodeSeenInAMirrorReads() {
        val frame = QrFixtures.frame("pair")
        val mirrored = ByteArray(frame.width * frame.height)
        for (y in 0 until frame.height) for (x in 0 until frame.width) mirrored[y * frame.width + x] = frame.luminance[y * frame.width + (frame.width - 1 - x)]
        assertEquals(QrFixtures.texts["pair"], QrDecoder.decode(QrFrame(frame.width, frame.height, frame.width, mirrored)))
    }

    @Test
    fun aFrameWithPaddedRowsReadsAsItsPixels() {
        val frame = QrFixtures.frame("signin")
        val stride = frame.width + 13
        val padded = ByteArray(stride * frame.height) { 0x55 }
        for (y in 0 until frame.height) frame.luminance.copyInto(padded, y * stride, y * frame.width, (y + 1) * frame.width)
        assertEquals(QrFixtures.texts["signin"], QrDecoder.decode(QrFrame(frame.width, frame.height, stride, padded)))
    }

    @Test
    fun aFrameWithoutACodeOrWithHalfOfOneReadsNothing() {
        assertNull(QrDecoder.decode(QrFrame(640, 480, 640, ByteArray(640 * 480) { 0x80.toByte() })), "a grey frame")
        var seed = 129
        val noise = ByteArray(640 * 480) { seed = seed * 1103515245 + 12345; (seed ushr 16).toByte() }
        assertNull(QrDecoder.decode(QrFrame(640, 480, 640, noise), tryHarder = true), "noise")
        val frame = QrFixtures.frame("kit")
        val top = frame.luminance.copyOf(frame.width * (frame.height / 2))
        assertNull(QrDecoder.decode(QrFrame(frame.width, frame.height / 2, frame.width, top), tryHarder = true), "the top half of a code")
    }

    @Test
    fun aFrameItsBytesDoNotHoldIsRefused() {
        assertFailsWith<IllegalArgumentException> { QrFrame(0, 10, 10, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { QrFrame(10, 10, 9, ByteArray(100)) }
        assertFailsWith<IllegalArgumentException> { QrFrame(10, 10, 12, ByteArray(12 * 9 + 9)) }
        QrFrame(10, 10, 12, ByteArray(12 * 9 + 10))
    }

    /**
     * What a frame costs: a camera's 1280×720 with a pairing code in it and without, and a kit's PDF page rendered
     * 2400 px wide (A4 at about 290 dpi, the code 57 mm) read once trying harder. Printed for the CR, never asserted.
     */
    @Test
    fun aFramesCostIsMeasured() {
        val pair = QrFixtures.frame("pair")
        val camera = place(pair, 1280, 720, 3, 470, 200, 150)
        val empty = QrFrame(1280, 720, 1280, ByteArray(1280 * 720) { (it % 1280 / 10 + 90).toByte() })
        val page = place(QrFixtures.frame("kit"), 2400, 3394, 5, 1500, 2600, 255)
        assertEquals(QrFixtures.texts["pair"], QrDecoder.decode(camera))
        assertNull(QrDecoder.decode(empty))
        assertEquals(QrFixtures.texts["kit"], QrDecoder.decode(page, tryHarder = true))
        assertEquals(QrFixtures.texts["kit"], QrDecoder.decode(page))
        println(
            "QR decode: 1280x720 with a code ${average(20) { QrDecoder.decode(camera) }}, without ${average(20) { QrDecoder.decode(empty) }}; " +
                "a 2400x3394 page trying harder ${average(3) { QrDecoder.decode(page, tryHarder = true) }}, not ${average(3) { QrDecoder.decode(page) }}",
        )
    }

    private fun average(runs: Int, block: () -> Unit): Duration {
        block() // warm up
        val start = TimeSource.Monotonic.markNow()
        repeat(runs) { block() }
        return start.elapsedNow() / runs
    }

    /** [code] scaled by [scale] (nearest) onto a [width]×[height] frame of [background] at ([left], [top]). */
    private fun place(code: QrFrame, width: Int, height: Int, scale: Int, left: Int, top: Int, background: Int): QrFrame {
        val out = ByteArray(width * height) { background.toByte() }
        for (y in 0 until code.height * scale) for (x in 0 until code.width * scale) {
            out[(top + y) * width + left + x] = code.luminance[(y / scale) * code.width + x / scale]
        }
        return QrFrame(width, height, width, out)
    }
}
