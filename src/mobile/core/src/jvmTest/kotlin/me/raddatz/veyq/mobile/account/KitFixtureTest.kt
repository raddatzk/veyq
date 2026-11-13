package me.raddatz.veyq.mobile.account

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.session.testPrimitives
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/** The web's kit fixtures on the JVM (`veyq.repoRoot`), and the whole read order over pages PDFBox draws. */
class KitFixtureTest : KitFixtureCases() {
    override fun fixture(name: String): ByteArray =
        File(System.getProperty("veyq.repoRoot") ?: error("veyq.repoRoot is not set"), "src/web/src/features/account/emergencyKit/__fixtures__/$name").readBytes()

    @Test
    fun aPrintedKitIsReadThroughTheQrOnItsDrawnPage() = readThroughItsQr("kit-printed.pdf")

    @Test
    fun aPhotographedKitIsReadThroughTheQrOnItsDrawnPage() = readThroughItsQr("kit-photo.pdf")

    private fun readThroughItsQr(name: String) = runTest {
        val drawing = PdfBoxPictures(fixture(name))
        val found = assertNotNull(KitImport.read(testPrimitives, fixture(name), drawing), "$name: no kit read")
        assertEquals("alex.beispiel@example.org", found.kit.email)
        assertEquals(KitImportTest.KEY_HEX, hex(found.kit.secretKey))
        assertEquals("https://familie.example.org", found.kit.server)
        assertEquals(KitImport.ServerFrom.TOKEN, found.serverFrom)
        assertEquals(listOf(KitImport.PICTURE_PIXELS), drawing.widths.distinct())
        found.kit.wipe()
    }

    /**
     * A platform's drawing played by Apache PDFBox (test-only, never shipped): each page rendered [width] px wide in
     * 8-bit grey, as the apps' renderers draw it for the core. Every fixture is a PDF, so no picture is decoded.
     */
    private class PdfBoxPictures(private val bytes: ByteArray) : KitPictures {
        val widths = mutableListOf<Int>()

        override suspend fun pdfPages(): Int? = runCatching { Loader.loadPDF(bytes).use { it.numberOfPages } }.getOrNull()

        override suspend fun pdfPage(page: Int, width: Int): QrFrame? = runCatching {
            widths += width
            Loader.loadPDF(bytes).use { doc ->
                val box = doc.getPage(page - 1).cropBox
                val turned = doc.getPage(page - 1).rotation % 180 != 0
                val image = PDFRenderer(doc).renderImage(page - 1, width / (if (turned) box.height else box.width), ImageType.GRAY)
                val pixels = image.raster.getDataElements(0, 0, image.width, image.height, null) as ByteArray
                QrFrame(image.width, image.height, image.width, pixels)
            }
        }.getOrNull()

        override suspend fun picture(longSide: Int): QrFrame? = null
    }
}
