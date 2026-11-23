package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgViewTest {
    private val at = "http://127.0.0.1:9"
    private val open = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"40\" height=\"20\""

    /** The hostile files a renderer runs nothing of and fetches nothing from (each app's proof); none is refused by markup. */
    private val drawnSafely = listOf(
        "$open><script>fetch(\"$at/script\")</script><rect width=\"9\" height=\"9\"/></svg>",
        "$open onload=\"fetch('$at/onload')\"><rect width=\"9\" height=\"9\"/></svg>",
        "$open><image href=\"$at/image.png\" width=\"9\" height=\"9\"/><image xlink:href=\"$at/xlink.png\"/></svg>",
        "$open><use href=\"$at/use.svg#x\"/></svg>",
        "$open><style>@import url($at/import.css); rect { fill: url($at/fill.svg#p) }</style><rect width=\"9\" height=\"9\"/></svg>",
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"$at/entity\">]>$open><text>&x;</text></svg>",
        // Illustrator's exports declare internal entities: drawn, not refused
        "<!DOCTYPE svg [<!ENTITY ns_svg \"http://www.w3.org/2000/svg\">]><svg xmlns=\"&ns_svg;\" width=\"4\" height=\"4\"/>",
        "$open><text>&lt;b&gt; &#65;&#x42; &amp;amp;</text></svg>",
    )

    @Test
    fun aForeignObjectIsRefusedHoweverItIsSpelled() {
        val refused = listOf(
            "$open><foreignObject width=\"40\" height=\"20\"><iframe xmlns=\"http://www.w3.org/1999/xhtml\" src=\"$at/frame\"/></foreignObject></svg>",
            "$open><svg:FOREIGNOBJECT xmlns:svg=\"http://www.w3.org/2000/svg\"/></svg>",
            // an internal entity whose value becomes the element where it is used
            "<!DOCTYPE svg [<!ENTITY f \"&#60;foreign&#79;bject width='9' height='9'/&#62;\">]>$open>&f;</svg>",
            "<!DOCTYPE svg [<!ENTITY f \"&#x3C;&#x66;oreignObject/&#x3E;\">]>$open>&f;</svg>",
            // one round later: `&#38;` is `&` at the declaration, the reference read where the entity is used
            "<!DOCTYPE svg [<!ENTITY f \"&#38;#60;&#38;#102;oreignObject/>\">]>$open>&f;</svg>",
        )
        for (svg in refused) assertTrue(SvgView.refused(svg.encodeToByteArray()), svg)
        for (svg in drawnSafely) assertFalse(SvgView.refused(svg.encodeToByteArray()), svg)
    }

    @Test
    fun whatTheScanCannotReadIsRefused() {
        assertTrue(SvgView.refused("<?xml version=\"1.0\" encoding=\"UTF-7\"?>$open>+ADw-foreignObject/+AD4-</svg>".encodeToByteArray()), "UTF-7")
        assertTrue(SvgView.refused("<?xml version='1.0' encoding='ISO-8859-1'?>$open/>".encodeToByteArray()), "Latin-1")
        assertTrue(SvgView.refused("<?xml version=\"1.0\" encoding=\"utf-8\"".encodeToByteArray()), "an unclosed declaration")
        assertTrue(SvgView.refused(byteArrayOf(0x3c, 0x73, 0x76, 0x67, 0x20, 0xC3.toByte(), 0x28, 0x3e)), "not UTF-8")
        val nested = "&#38;".repeat(1) + "#38;".let { "&#38;#38;#38;#38;#38;#38;#38;#38;#38;#60;" }
        assertTrue(SvgView.refused("$open><text>$nested</text></svg>".encodeToByteArray()), "references nested deeper than the scan undoes")
        assertFalse(SvgView.refused(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$open/>".encodeToByteArray()), "a byte-order mark")
        assertFalse(SvgView.refused("<?xml version=\"1.0\" encoding=\"us-ascii\"?>$open/>".encodeToByteArray()))
    }

    @Test
    fun fileViewRefusesAForeignObjectBeforeAnyRendererSeesIt() = runTest {
        val bad = "$open><foreignObject/></svg>".encodeToByteArray()
        val good = drawnSafely.first().encodeToByteArray()
        fun file(bytes: ByteArray) =
            DepotFiles.File("s", "n", "b", 1, bytes.size + 40L, null, bytes.size.toLong(), "image/svg+xml", "x.svg", null, null)
        suspend fun opened(bytes: ByteArray, svg: Boolean) = FileView.open(file(bytes), "x.svg", svg) { _, _, sink -> sink(bytes) }
        // the head alone says image: the refusal reads the whole file, so it is the open's
        assertEquals(FileView.Kind.IMAGE, FileView.decide(bad, 1, bad.size.toLong(), "image/svg+xml", "x.svg", svg = true).kind)
        assertEquals(FileView.Kind.UNSUPPORTED, opened(bad, svg = true).decision.kind)
        assertEquals(null, opened(bad, svg = true).bytes, "a refused SVG's bytes are handed to no renderer")
        val shown = opened(good, svg = true)
        assertEquals(FileView.Decision(FileView.Kind.IMAGE, FileView.SVG, "svg", 1), shown.decision)
        assertTrue(shown.bytes!!.contentEquals(good))
        assertEquals(FileView.Kind.TEXT, opened(bad, svg = false).decision.kind, "a platform that draws none shows its text")
    }

    @Test
    fun zoomStepsAreTheWebs() {
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0), SvgView.ZOOM_STEPS)
        assertEquals(1.5, SvgView.zoomIn(1.0))
        assertEquals(16.0, SvgView.zoomIn(16.0))
        assertEquals(3.0, SvgView.zoomIn(2.5))
        assertEquals(12.0, SvgView.zoomOut(16.0))
        assertEquals(1.0, SvgView.zoomOut(1.0))
        assertTrue("'--z','16.0'" in SvgView.zoomScript(99.0), "a zoom past the top is the top")
        assertTrue("'--z','1.0'" in SvgView.zoomScript(0.1))
    }

    @Test
    fun theCapWinsOverSharpness() {
        // the web's svgRaster.test.ts cases
        assertEquals(SvgView.Raster(400, 200, 2.0), SvgView.plan(200.0, 100.0, 2.0))
        val huge = SvgView.plan(2528.0, 1456.0, 3.0)
        assertTrue(huge.width.toLong() * huge.height <= SvgView.MAX_PIXELS, "$huge")
        assertTrue(huge.ratio < 3.0)
        val thin = SvgView.plan(100_000.0, 1.0, 2.0)
        assertTrue(thin.width <= SvgView.MAX_EDGE, "$thin")
        assertEquals(SvgView.Raster(1, 1, 1.0), SvgView.plan(0.0, -5.0, Double.NaN))
        for ((w, h, r) in listOf(Triple(4096.0, 4096.0, 1.0), Triple(4097.0, 4095.0, 1.0), Triple(1456.0, 1456.0, 3.0))) {
            val p = SvgView.plan(w, h, r)
            assertTrue(p.width.toLong() * p.height <= SvgView.MAX_PIXELS && p.width <= SvgView.MAX_EDGE && p.height <= SvgView.MAX_EDGE, "$w×$h@$r → $p")
        }
    }

    @Test
    fun thePageHoldsThePictureAsAnImageAndNoScript() {
        val page = SvgView.document()
        assertTrue("<img src=\"picture.svg\"" in page)
        assertTrue("default-src 'none'" in page && "script-src 'none'" in page && "img-src 'self'" in page)
        assertFalse("<script" in page.lowercase(), "no script of its own")
        assertFalse("<iframe" in page.lowercase() || "<object" in page.lowercase() || "<embed" in page.lowercase(), "never a document of the SVG")
        assertEquals(SvgView.DOCUMENT, "https://${SvgView.HOST}/${SvgView.DOCUMENT_NAME}")
        assertEquals(SvgView.PICTURE, "https://${SvgView.HOST}/${SvgView.PICTURE_NAME}")
        assertTrue(SvgView.HOST.endsWith(".invalid"))
    }
}
