package me.raddatz.veyq.mobile.media

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cover drawn from an SVG ([SvgCover], the web's `rasterizeSvg` at `COVER_SPEC`): drawn with its long edge at 2048 — a
 * small logo drawn up to it —, its own size read from its root as the web's `<img>` reads it, refused where its markup is
 * ([SvgMarkup]) or it has no size, drawn from one page that loads nothing but the picture, stored as a WebP. Bites, each
 * measured red: [SvgCover.plan] without its [SvgMarkup.refused] gate, `size` without the viewBox's aspect for a missing
 * side, a percentage read as pixels, the page's policy allowing more than `data:` images.
 */
class SvgCoverTest {
    private fun svg(root: String, body: String = "<rect width=\"10\" height=\"10\"/>") = "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" $root>$body</svg>".encodeToByteArray()

    @Test
    fun aSmallLogoIsDrawnUpToTheCoversLongEdge_itsAspectKept() {
        assertEquals(SvgCover.Plan(2048, 1024), SvgCover.plan(svg("width=\"64\" height=\"32\"")))
        assertEquals(SvgCover.Plan(1024, 2048), SvgCover.plan(svg("width=\"1in\" height=\"2in\"")))
        assertEquals(SvgCover.Plan(2048, 2048), SvgCover.plan(svg("width='5000px' height='5000px'")), "a large one is drawn down to it")
    }

    @Test
    fun aMissingSideIsTakenFromTheViewBox_noSideAtAllIsTheViewBoxsOwnSize() {
        assertEquals(100.0 to 50.0, SvgCover.size(svg("width=\"100\" viewBox=\"0 0 40 20\"").decodeToString()))
        assertEquals(80.0 to 20.0, SvgCover.size(svg("height=\"20\" viewBox=\"0,0,40,10\"").decodeToString()))
        assertEquals(40.0 to 10.0, SvgCover.size(svg("viewBox=\"0 0 40 10\"").decodeToString()))
    }

    @Test
    fun anSvgWithNoSizeOfItsOwnOrRefusedMarkupIsUnreadable() {
        assertNull(SvgCover.plan(svg("")), "no width, height or viewBox: unsized, as the web's svg_unsized")
        assertNull(SvgCover.plan(svg("width=\"100%\" height=\"100%\"")), "a percentage is no size of its own")
        assertNull(SvgCover.plan(svg("width=\"10\" height=\"10\"", "<foreignObject><p>hi</p></foreignObject>")), "a foreignObject is refused")
        assertNull(SvgCover.plan(svg("width=\"10\" height=\"10\"", "<foreign&#79;bject/>")), "spelled by a character reference too")
        assertNull(SvgCover.plan("<html><body>no svg</body></html>".encodeToByteArray()), "no svg root")
        assertNull(SvgCover.plan(ByteArray(0)))
    }

    @Test
    fun thePageLoadsNothingButThePictureAtThePlannedSize() {
        val page = SvgCover.document("<svg/>".encodeToByteArray(), SvgCover.Plan(300, 150))
        assertTrue("img-src data:;" in page && "default-src 'none'" in page && "script-src 'none'" in page)
        assertTrue("width:300px;height:150px" in page)
        assertTrue("src=\"data:image/svg+xml;base64,PHN2Zy8+\"" in page)
    }

    @Test
    fun theDrawingIsEncodedAtTheCoversQuality_aWrongSizeIsNot() = runTest {
        var asked: Double? = null
        val encoder = WebpEncoder { _, _, _, q -> asked = q; byteArrayOf(1) }
        assertEquals(1, SvgCover.encode(encoder, ByteArray(8), 2, 1)?.size)
        assertEquals(0.85, asked)
        assertNull(SvgCover.encode(encoder, ByteArray(7), 2, 1))
        assertNull(SvgCover.encode(null, ByteArray(8), 2, 1))
        assertNull(SvgCover.encode(encoder, ByteArray(4 * 4096), 4096, 1), "never past the cover's long edge")
    }
}
