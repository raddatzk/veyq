package me.raddatz.veyq.mobile.qr

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `QrPng` read back by `javax.imageio`, module for module; its zlib stream by `java.util.zip`; and the web's interop
 * fixture held to what the core writes today (`coreQrCode.test.ts` decodes it with the web's `jsqr`).
 */
class QrPngImageTest {
    @Test
    fun javaxImageioReadsTheSameModules() {
        for ((text, scale) in listOf(QrFixtures.texts.getValue("kit") to 3, "veyq".repeat(583).dropLast(1) to 2, "1" to 7)) {
            val m = QrEncoder.encode(text)
            val image = ImageIO.read(ByteArrayInputStream(QrPng.write(m, scale)))
            assertEquals(m.side * scale, image.width)
            assertEquals(m.side * scale, image.height)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y) and 0xFFFFFF
                assertEquals(if (m.dark(x / scale, y / scale)) 0x000000 else 0xFFFFFF, rgb, "$text at ($x, $y)")
            }
        }
    }

    @Test
    fun itsStoredBlocksInflateAcrossABlockBoundary() {
        val data = ByteArray(65535 * 2 + 17) { (it * 31).toByte() }
        val inflater = Inflater()
        inflater.setInput(QrPng.zlibStored(data))
        val out = ByteArray(data.size + 1)
        val n = inflater.inflate(out)
        assertEquals(true, inflater.finished(), "the stream ends, its Adler-32 checked")
        assertContentEquals(data, out.copyOf(n))
    }

    @Test
    fun theWebsInteropFixtureIsWhatTheCoreWrites() {
        val png = QrPng.write(QrEncoder.encode(QrFixtures.texts.getValue("pair")), 4)
        val fixture = File(System.getProperty("veyq.repoRoot"), WEB_FIXTURE)
        // VEYQ_WRITE_QR_FIXTURE=1 writes it anew, after a deliberate change to the writer.
        if (System.getenv("VEYQ_WRITE_QR_FIXTURE") == "1") fixture.writeBytes(png)
        assertContentEquals(fixture.readBytes(), png, "$WEB_FIXTURE is not what QrPng writes: rewrite it with VEYQ_WRITE_QR_FIXTURE=1")
    }

    companion object {
        const val WEB_FIXTURE = "src/web/src/features/account/pairing/__fixtures__/core-pairing-code.png"
    }
}
