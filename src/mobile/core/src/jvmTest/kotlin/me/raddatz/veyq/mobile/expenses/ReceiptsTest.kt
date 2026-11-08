package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A receipt's rules and bytes (the web's `receiptRules.test.ts`, `sniff.test.ts`, `blobMeta.test.ts`): what may be
 * picked, what the bytes are, which receipts a revision drops, the meta's layout, and a blob's frames end to end.
 */
class ReceiptsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.encodeToByteArray()

    @Test
    fun aReceiptIsClaimedByTypeElseByExtension() {
        assertTrue(Receipts.claims("image/heic", "x"))
        assertTrue(Receipts.claims("APPLICATION/PDF", "x"))
        assertFalse(Receipts.claims("text/plain", "bill.pdf"))
        assertTrue(Receipts.claims("", "Bill.PDF"))
        assertTrue(Receipts.claims(null, "scan.heif"))
        assertFalse(Receipts.claims("", "notes.txt"))
        assertFalse(Receipts.claims("", "pdf"))
    }

    @Test
    fun theBytesDecideAnImage() {
        assertEquals(Sniff.Image.JPEG, Sniff.image(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals(Sniff.Image.PNG, Sniff.image(bytes(0x89) + ascii("PNG\r\n") + bytes(0x1a, 0x0a)))
        assertNull(Sniff.image(bytes(0x89) + ascii("PNG\r\n") + bytes(0x1a, 0x0b)))
        assertEquals(Sniff.Image.GIF, Sniff.image(ascii("GIF89a")))
        assertEquals(Sniff.Image.WEBP, Sniff.image(ascii("RIFF....WEBPVP8 ")))
        assertNull(Sniff.image(ascii("RIFF....WAVEfmt ")))
        val bmp = ByteArray(26).also { ascii("BM").copyInto(it); it[14] = 40 }
        assertEquals(Sniff.Image.BMP, Sniff.image(bmp))
        assertNull(Sniff.image(bmp.copyOf().also { it[14] = 41 }))
        assertNull(Sniff.image(ascii("BM is two letters any text may start with")))
        assertEquals(Sniff.Image.AVIF, Sniff.image(bytes(0, 0, 0, 0x20) + ascii("ftypavif")))
        assertEquals(Sniff.Image.HEIC, Sniff.image(bytes(0, 0, 0, 0x20) + ascii("ftypmif1")))
        assertNull(Sniff.image(bytes(0, 0, 0, 0x20) + ascii("ftypisom")))
    }

    @Test
    fun aPdfIsItsHeaderAtTheStartOrInsideTheFirstKiBOfNoText() {
        assertTrue(Sniff.pdf(ascii("%PDF-1.7\n")))
        assertTrue(Sniff.pdf(bytes(0, 1, 2) + ascii("%PDF-1.4")))
        // A text that only mentions the header is a text.
        assertFalse(Sniff.pdf(ascii("A note: files start with %PDF-1.7")))
        assertFalse(Sniff.pdf(ByteArray(1100) + ascii("%PDF-1.4")))
        assertFalse(Sniff.pdf(ascii("%PDF")))
    }

    @Test
    fun textIsValidUtf8WithoutControlsOrAMarkedUtf16() {
        assertTrue(Sniff.isText(ascii("Grüße\tund\r\n")))
        assertFalse(Sniff.isText(bytes(0x61, 0x00, 0x62)))
        assertFalse(Sniff.isText(bytes(0xc0, 0xaf)))
        assertFalse(Sniff.isText(bytes(0xed, 0xa0, 0x80)))
        // A sequence cut off by the end is fine for a prefix, not for a complete file.
        assertTrue(Sniff.isText(ascii("ab") + bytes(0xe2, 0x82)))
        assertFalse(Sniff.isText(ascii("ab") + bytes(0xe2, 0x82), complete = true))
        assertFalse(Sniff.isText(ascii("ab") + bytes(0xe0, 0x80)))
        assertTrue(Sniff.isText(bytes(0xff, 0xfe, 0x41, 0x00)))
        assertFalse(Sniff.isText(bytes(0xff, 0xfe, 0x00, 0xd8, 0x41, 0x00)))
        // A high surrogate left open at the end does not decode either.
        assertFalse(Sniff.isText(bytes(0xff, 0xfe, 0x41, 0x00, 0x00, 0xd8)))
        // An overlong sequence, complete inside the text, is no UTF-8.
        assertFalse(Sniff.isText(bytes(0xe0, 0x80, 0x80, 0x41)))
    }

    @Test
    fun aReceiptIsShownByItsBytes() {
        assertEquals(Receipts.Kind.IMAGE, Receipts.kindOf(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals(Receipts.Kind.PDF, Receipts.kindOf(ascii("%PDF-1.7")))
        assertEquals(Receipts.Kind.OTHER, Receipts.kindOf(ascii("<html>")))
    }

    private fun listed(id: String, entry: String, createdAt: Long, dropped: Boolean = false) = Receipts.Receipt(
        id, entry, ApplicationBlobs.Listed(id, entry, Receipts.ROLE, "b$id", 1, 28, "", null, null, createdAt, null, if (dropped) 1 else null, null, false),
        null, null, 0, createdAt, dropped, false,
    )

    @Test
    fun aRevisionDropsOnlyWhatItAnswersFor() {
        val e = "entry"
        val listing = listOf(
            listed("shown", e, 10), listed("kept", e, 11), listed("mine", e, 50), listed("theirs", e, 40),
            listed("older", e, 5), listed("gone", e, 6, dropped = true), listed("other", "another", 1), listed("skewed", e, 35),
        )
        // On a revision stored at 30 that listed "shown", "kept" and "skewed" (completed at 35 by a clock running ahead);
        // this writer added "mine"; it keeps only "kept".
        assertEquals(
            listOf("shown", "mine", "older", "skewed"),
            Receipts.toDrop(listing, e, listOf("kept"), listOf("shown", "kept", "skewed") to 30L, setOf("mine")),
        )
        // A new entry (no revision shown) drops only its own.
        assertEquals(listOf("mine"), Receipts.toDrop(listing, e, emptyList(), null, setOf("mine")))
    }

    @Test
    fun aDownloadIsNamedByItsSealedNameAndGainsAnExtensionOnlyWhereItHasNone() {
        assertEquals("Bill.pdf", Receipts.downloadName(" Bill ", "application/pdf"))
        assertEquals("Bill.PDF", Receipts.downloadName("Bill.PDF", "application/pdf"))
        assertEquals("receipt.jpg", Receipts.downloadName(null, "image/jpeg"))
        assertEquals("receipt", Receipts.downloadName("", "image/avif"))
        assertEquals(".profile.png", Receipts.downloadName(".profile", "image/png"))
    }

    @Test
    fun theMetaIsCompactPaddedAndBounded() {
        val m = BlobMeta.encode("image/jpeg", " IMG\u0007 1.jpg ", 1_700_000_000_000)
        assertEquals(256, m.size)
        assertEquals("""{"v":1,"type":"image/jpeg","name":"IMG 1.jpg","modifiedAt":1700000000000}""", m.decodeToString().trimEnd())
        assertEquals("""{"v":1,"type":""}""", BlobMeta.encode("x".repeat(128), "  ", -1).decodeToString().trimEnd())
        val long = BlobMeta.encode("", "ä".repeat(200), null)
        assertEquals(255 - 1, BlobMeta.decode(long)!!.name!!.encodeToByteArray().size)
        assertEquals(BlobMeta.Opened(1, "image/png", "a", 5), BlobMeta.decode("""{"v":1,"type":"image/png","name":"a","modifiedAt":5,"extra":true}""".encodeToByteArray()))
        assertNull(BlobMeta.decode("""{"type":"x"}""".encodeToByteArray()))
        assertNull(BlobMeta.decode("[1]".encodeToByteArray()))
    }

    @Test
    fun aBlobsFramesOpenOnlyInTheirPlace() {
        val suite = V1(BouncyCastlePrimitives)
        val key = ByteArray(32) { 9 }
        val data = ByteArray(V1.FRAME_PLAINTEXT + 10) { (it % 251).toByte() }
        val n = V1.chunkCount(data.size.toLong())
        val frames = (0 until n).map { i -> suite.sealFrame(key, i, n, data.copyOfRange(i * V1.FRAME_PLAINTEXT, minOf(data.size, (i + 1) * V1.FRAME_PLAINTEXT))) }
        assertEquals(listOf(V1.FRAME_PLAINTEXT + 28, 10 + 28), frames.map { it.size })
        assertContentEquals(data, frames.mapIndexed { i, f -> suite.openFrame(key, i, n, f) }.reduce { a, b -> a + b })
        assertFailsWith<Exception> { suite.openFrame(key, 0, n, frames[1]) }
        assertFailsWith<Exception> { suite.openFrame(key, 1, n + 1, frames[1]) }
        assertEquals(1, V1.chunkCount(0))
        assertFailsWith<me.raddatz.veyq.mobile.crypto.OpenRefused> { suite.openFrame(key, 0, n, ByteArray(5)) }
    }
}
