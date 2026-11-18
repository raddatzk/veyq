package me.raddatz.veyq.mobile.account

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.session.testPrimitives

/**
 * The read order over the web's three kit fixtures (`src/web/src/features/account/emergencyKit/__fixtures__/`, what each
 * is: `emergencyKit.import.test.ts`), the platform's drawing played (blank pages here: steps 1, 2 and 4 and the pages
 * asked). A platform's test reads the files; the JVM's also draws them for real (`KitFixtureTest`, PDFBox).
 */
abstract class KitFixtureCases {
    /** The fixture's bytes, read from the repository root the test task names. */
    abstract fun fixture(name: String): ByteArray

    /** Whether this run re-records `kit-app.pdf` (`VEYQ_WRITE_FIXTURES=1`, the JVM's `KitWriterTest`): then it is not held. */
    open val recording: Boolean = false

    private val p = testPrimitives

    @Test
    fun aKitPdfKitSavedIsReadByItsRawTokenWithNoPageDrawn() = runTest {
        val bytes = fixture("kit-pdfkit-saved.pdf")
        val played = KitImportTest.PlayedPictures(qrOn = mapOf(1 to KitImportTest.TOKEN_A))
        val found = assertNotNull(KitImport.read(p, bytes, played))
        assertEquals("alex.beispiel@example.org", found.kit.email)
        assertEquals(KitImportTest.KEY_HEX, hex(found.kit.secretKey))
        assertEquals("https://familie.example.org", found.kit.server)
        assertEquals(KitImport.ServerFrom.TOKEN, found.serverFrom)
        assertEquals(emptyList(), played.asked)
        // The core's PDF reader on a real viewer's save: its Keywords and its first address field.
        val pdf = KitPdf.parse(bytes)
        assertTrue(pdf.keywords().orEmpty().contains(KitImportTest.TOKEN_A), "Keywords: ${pdf.keywords()?.take(16)}")
        assertEquals("familie.example.org", pdf.field("address1"))
    }

    /**
     * `kit-app.pdf`, the kit the core's [KitWriter] wrote: read by its raw token with no page drawn, its first address
     * field the server's host — and, on every platform the cases run on, written again byte for byte from its inputs
     * (the fixtures' fake data, English, 2026-11-18), so a writer that formats a number or orders a map otherwise on
     * one platform is red there.
     */
    @Test
    fun theKitTheCoreWroteIsReadByItsRawTokenAndWrittenAgainByteForByte() = runTest {
        val bytes = fixture("kit-app.pdf")
        val played = KitImportTest.PlayedPictures(qrOn = mapOf(1 to KitImportTest.TOKEN_A))
        val found = assertNotNull(KitImport.read(p, bytes, played))
        assertEquals("alex.beispiel@example.org", found.kit.email)
        assertEquals(KitImportTest.KEY_HEX, hex(found.kit.secretKey))
        assertEquals("https://familie.example.org", found.kit.server)
        assertEquals(KitImport.ServerFrom.TOKEN, found.serverFrom)
        assertEquals(emptyList(), played.asked)
        val pdf = KitPdf.parse(bytes)
        assertEquals(EmergencyKit.write(p, found.kit.email, found.kit.secretKey, found.kit.server), pdf.keywords())
        assertEquals("familie.example.org", pdf.field("address1"))
        if (!recording) {
            val written = KitWriter.pdf(p, found.kit.email, found.kit.secretKey, found.kit.server, KitWords.of("en"), KIT_APP_DAY)
            assertContentEquals(bytes, written, "kit-app.pdf is not what KitWriter writes here")
        }
        found.kit.wipe()
    }

    @Test
    fun aPrintedAndAPhotographedKitAreLookedForOnTheirFirstPages() = runTest {
        for (name in listOf("kit-printed.pdf", "kit-photo.pdf")) {
            val bytes = fixture(name)
            // The premise, as the web's test asserts it: nothing but the drawing carries the kit.
            assertNull(EmergencyKit.read(p, CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()), name)
            val pdf = KitPdf.parse(bytes)
            assertNull(pdf.keywords()?.takeIf { "veyqkit:" in it }, name)
            assertNull(pdf.field("address1"), name)
            val played = KitImportTest.PlayedPictures(pages = 5)
            assertNull(KitImport.read(p, bytes, played), name)
            assertEquals(listOf("pages", "page 1@2400", "page 1@2000", "page 2@2400", "page 2@2000", "page 3@2400", "page 3@2000"), played.asked, name)
        }
    }

    companion object {
        /** 2026-11-18, the day `kit-app.pdf` says it was created on. */
        const val KIT_APP_DAY = 20775L
    }
}
