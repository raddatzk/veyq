package me.raddatz.veyq.mobile.account

import kotlin.test.Test
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
 * is: `emergencyKit.import.test.ts`), the platform's drawing and the QR played. A platform's test reads the files.
 */
abstract class KitFixtureCases {
    /** The fixture's bytes, read from the repository root the test task names. */
    abstract fun fixture(name: String): ByteArray

    private val p = testPrimitives

    @Test
    fun aKitPdfKitSavedIsReadByItsRawTokenWithNoPageDrawn() = runTest {
        val bytes = fixture("kit-pdfkit-saved.pdf")
        val played = KitImportTest.PlayedPictures(qrOn = mapOf(1 to KitImportTest.TOKEN_A))
        val found = assertNotNull(KitImport.read(p, bytes, played, played::qr))
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
            assertNull(KitImport.read(p, bytes, played, played::qr), name)
            assertEquals(listOf("pages", "page 1@2400", "page 2@2400", "page 3@2400"), played.asked, name)
        }
    }
}
