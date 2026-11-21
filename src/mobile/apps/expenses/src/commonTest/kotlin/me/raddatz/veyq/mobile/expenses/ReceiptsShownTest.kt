package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a receipt is shown and what a share takes, one rule on every app (CR D2, D8): its kind and media type from its bytes
 * alone (Apple's ImageIO copy gone), a PDF viewer's page cap, and a share's count and byte budget (Android's 40 MiB cap
 * gone).
 */
class ReceiptsShownTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
    private val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\n".encodeToByteArray()
    private val text = "Total: 12.34 EUR\n".encodeToByteArray()

    @Test
    fun aReceiptIsWhatItsBytesSay() {
        assertEquals(Receipts.Kind.IMAGE, Receipts.kindOf(jpeg))
        assertEquals(Receipts.Kind.PDF, Receipts.kindOf(pdf))
        assertEquals(Receipts.Kind.OTHER, Receipts.kindOf(text))
        assertEquals("image/jpeg", Receipts.mediaTypeOf(jpeg))
        assertEquals("application/pdf", Receipts.mediaTypeOf(pdf))
        assertNull(Receipts.mediaTypeOf(text))
    }

    @Test
    fun aFilePickedByItsPathIsNamedByItsLastPartAndTypedByItsBytes() {
        val photo = Receipts.picked("/home/anna/Bilder/Quittung März.jpg", jpeg)
        assertEquals("Quittung März.jpg" to "image/jpeg", photo.name to photo.type)
        val scan = Receipts.picked("C:\\Users\\Ben\\Documents\\scan.bin", pdf)
        assertEquals("scan.bin" to "application/pdf", scan.name to scan.type)
        // no platform names a type here: bytes that are no receipt are refused whatever the name says
        val notes = Receipts.picked("/tmp/notes.jpg", text)
        assertEquals("notes.jpg", notes.name)
        assertFalse(Receipts.claims(notes.type, notes.name))
        assertTrue(Receipts.claims(photo.type, photo.name))
        assertNull(Receipts.picked("/tmp/gone.pdf", null).bytes)
    }

    @Test
    fun aPdfViewerShowsTwentyPagesAndSaysWhenThereAreMore() {
        assertEquals(20, Receipts.PDF_PAGES_SHOWN)
        assertEquals(3, Receipts.pagesShown(3))
        assertEquals(20, Receipts.pagesShown(20))
        assertEquals(20, Receipts.pagesShown(57))
        assertNull(Receipts.pagesCut(20))
        assertEquals(Notice(Msg.RECEIPTS_PAGES_CUT, 20, 21), Receipts.pagesCut(21))
    }

    @Test
    fun aShareIsHeldWithinOneBudget() {
        assertEquals(64L * 1024 * 1024, PotShare.BUDGET)
        val intake = PotShare.Intake()
        val nine = 9L * 1024 * 1024
        // seven receipts of 9 MiB are 63 MiB: within the budget
        repeat(7) { assertTrue(intake.take("image/jpeg", "r$it.jpg", nine), "receipt $it") }
        assertEquals(PotShare.BUDGET - 7 * nine, intake.room)
        // the eighth would pass it: left out, while a small one still fits
        assertFalse(intake.take("image/jpeg", "r7.jpg", nine))
        assertTrue(intake.take("application/pdf", "small.pdf", 1024))
        // what is no receipt does not count against the budget
        assertFalse(intake.take("text/plain", "notes.txt", 10))
    }

    @Test
    fun aShareTakesAtMostAHundredFiles() {
        val intake = PotShare.Intake()
        repeat(PotShare.MAX_FILES) { assertTrue(intake.take("image/png", "p$it.png", 10)) }
        assertFalse(intake.take("image/png", "one-more.png", 10))
    }
}
