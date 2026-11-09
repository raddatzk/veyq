package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.DownloadName
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.depot.FileView.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The web's `view.ts` decision and `sniff.ts` rules as a file's page in the app takes them: the bytes decide. */
class FileViewTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)
    private val pdf = "%PDF-1.7\n".encodeToByteArray()
    private val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".encodeToByteArray()
    private val text = "hello, world\n".encodeToByteArray()
    private val html = "<html><script>alert(1)</script></html>".encodeToByteArray()

    @Test
    fun theBytesDecideNotTheClaim() {
        // a PNG claimed as text is an image; a text claimed as PNG is no image (and, unclaimed as text, nothing to show)
        assertEquals(Kind.IMAGE, FileView.decide(png, 1, 10, "text/plain", "notes.txt").kind)
        assertEquals(Kind.UNSUPPORTED, FileView.decide(text, 1, 13, "image/png", "a.png").kind)
        assertEquals(Kind.PDF, FileView.decide(pdf, 1, 9, null, "x.bin").kind)
        assertEquals("video/mp4", FileView.decide(mp4, 1, 12, "text/plain", null).mime)
    }

    @Test
    fun aTextNeedsItsClaimAndAViewerNamesBySniffedType() {
        assertEquals(Kind.TEXT, FileView.decide(text, 1, 13, null, "readme.md").kind)
        assertEquals(Kind.UNSUPPORTED, FileView.decide(text, 1, 13, "application/octet-stream", "blob").kind)
        // markup claimed as html is shown as its TEXT, never rendered
        assertEquals(FileView.Decision(Kind.TEXT, "text/plain", "txt", 1), FileView.decide(html, 1, html.size.toLong(), "text/html", "x.html"))
        assertEquals("jpg", FileView.decide(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0), 1, 4, null, "evil.html").fileExtension)
    }

    @Test
    fun capsAndTheTruncatedText() {
        assertEquals(Kind.TOO_LARGE, FileView.decide(png, 300, FileView.IMAGE_CAP + 1, null, null).kind)
        val long = FileView.decide(text, 10, 10L * 262_144, "text/plain", null)
        assertEquals(4, long.frames, "a text reads its first MiB: four frames")
        assertEquals(true, long.truncated)
        assertEquals(Kind.EMPTY, FileView.decide(ByteArray(0), 1, 0, "text/plain", null).kind)
    }

    @Test
    fun mediaContainers() {
        assertEquals(Sniff.Stream(false, "audio/mpeg"), Sniff.stream("ID3\u0004".encodeToByteArray()))
        assertEquals(Sniff.Stream(false, "audio/wav"), Sniff.stream("RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray()))
        assertNull(Sniff.stream(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".encodeToByteArray()), "a HEIC is an image")
        assertNull(Sniff.stream(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41, 0)), "a UTF-16 text's mark is no MPEG frame")
    }

    @Test
    fun theDownloadNameAsTheWebsCoreWritesIt() {
        assertEquals("Report.pdf", DownloadName.of("application/pdf", "file", "Report", "x.pdf"))
        assertEquals("photo.png", DownloadName.of("image/jpeg", "file", " photo.png "), "an extension is never replaced")
        assertEquals("upload.jpg", DownloadName.of("image/jpeg", "file", "", "upload"))
        assertEquals("file", DownloadName.of("application/x-unknown", "file", null, null))
        assertEquals(".bashrc.pdf", DownloadName.of("application/pdf", "file", ".bashrc"), "a leading dot is no extension")
    }

    @Test
    fun textDecodesByItsMark() {
        assertEquals("hi", Sniff.decodeText(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0x68, 0x69)))
        assertEquals("hi", Sniff.decodeText(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x68, 0, 0x69, 0)))
    }
}
