package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a Pot takes from a share (ADR 0131 decision 2): images and PDFs within its receipts' rules, at most 100 at once. */
class PotShareTest {
    private class F(val type: String?, val name: String?, val size: Long)

    private fun split(files: List<F>) = PotShare.split(files, { it.type }, { it.name }, { it.size })

    @Test
    fun aPotTakesImagesAndPdfsWithinTheReceiptsRules() {
        val photo = F("image/jpeg", "IMG_1.jpg", 2_000_000)
        val pdf = F("application/pdf", "rechnung.pdf", 80_000)
        val byName = F(null, "scan.HEIC", 1_000)
        val text = F("text/plain", "notes.txt", 10)
        val tooLarge = F("image/png", "huge.png", Receipts.MAX_BYTES + 1)
        val atTheCap = F("image/png", "cap.png", Receipts.MAX_BYTES)
        val empty = F("image/png", "empty.png", 0)
        val (fit, refused) = split(listOf(photo, text, pdf, tooLarge, byName, atTheCap, empty))
        assertEquals(listOf(photo, pdf, byName, atTheCap), fit)
        assertEquals(listOf(text, tooLarge, empty), refused)
    }

    @Test
    fun atMostAHundredGoIntoOneExpenseTheRestAreLeftOut() {
        val files = List(103) { F("image/png", "p$it.png", 10) }
        val (fit, refused) = split(files)
        assertEquals(files.take(100), fit)
        assertEquals(files.drop(100), refused)
    }
}
