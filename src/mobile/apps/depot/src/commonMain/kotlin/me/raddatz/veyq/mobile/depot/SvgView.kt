package me.raddatz.veyq.mobile.depot

/**
 * An SVG file's view in the apps (inbox #1059, ADR 0155 § The file facts' slice D9 and D15; the web's `svgRaster.ts` and
 * `SvgView.vue`): what every app decides alike, so a peer's SVG is held to one threat model whatever draws it.
 *
 * - [refused]: an SVG holding a `foreignObject` is not shown — the web's taint gate refuses it, and the apps refuse it by
 *   its markup, before any renderer sees it (fail closed: anything the scan cannot read is refused too).
 * - [document]: the one page an app's web view shows (Android's `WebView`, Apple's `WKWebView`, Windows' WebView2): the SVG
 *   only as an `<img>`, the engine's image mode, which runs no script and loads nothing an SVG names; a policy that lets
 *   the page load nothing but the picture; no script of its own. The app serves [DOCUMENT] and [PICTURE] from memory and
 *   refuses every other request and navigation, and turns the page's script off.
 * - [ZOOM_STEPS], [zoomIn], [zoomOut], [zoomScript]: the web's nine zoom steps; a zoom lays the picture out again at its
 *   scale (CSS `zoom`), so the engine paints its vectors anew — sharp, never an enlarged bitmap.
 * - [plan]: the pixel cap of a picture an app rasterizes itself (Linux): [MAX_PIXELS], [MAX_EDGE] on a side, the web's
 *   `planRaster`. A web view paints only the tiles in view, so its zoom raises no bitmap past the view.
 */
object SvgView {
    /** The host the view is served under: `.invalid` resolves nowhere (RFC 6761). */
    const val HOST = "svg.veyq.invalid"
    /** The view's page, served from memory ([document]). */
    const val DOCUMENT = "https://$HOST/view.html"
    /** The SVG's bytes, served from memory as `image/svg+xml` — only as the page's `<img>`, never navigated to. */
    const val PICTURE = "https://$HOST/picture.svg"
    /** The page's own name and the picture's, relative to wherever a platform serves them (Apple's scheme is its own). */
    const val DOCUMENT_NAME = "view.html"
    const val PICTURE_NAME = "picture.svg"

    /** What the page may load: the picture, nothing else; no script, no frame, no form, no base, no connection. */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'none'; frame-src 'none'; " +
            "form-action 'none'; base-uri 'none'"

    /** The web's zoom steps (`SvgView.vue`'s `ZOOM_STEPS`): ×1 is the picture fitted into the view. */
    val ZOOM_STEPS: List<Double> = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0)

    /** The next step above [zoom], or the top one. */
    fun zoomIn(zoom: Double): Double = ZOOM_STEPS.firstOrNull { it > zoom } ?: ZOOM_STEPS.last()

    /** The next step below [zoom], or ×1. */
    fun zoomOut(zoom: Double): Double = ZOOM_STEPS.lastOrNull { it < zoom } ?: ZOOM_STEPS.first()

    /** The most pixels one rasterized picture holds: 4096 × 4096 (64 MiB RGBA; the web's `SVG_MAX_PIXELS`). */
    const val MAX_PIXELS = 4096 * 4096
    /** The longest side, in pixels, of one rasterized picture (the web's `SVG_MAX_EDGE`). */
    const val MAX_EDGE = 8192

    /** One raster: its pixel size and the pixels per laid-out unit it was planned at. */
    data class Raster(val width: Int, val height: Int, val ratio: Double)

    /**
     * The raster of a region [width] × [height] laid-out units at [ratio] pixels per unit: the ratio itself where it fits
     * [MAX_PIXELS] and [MAX_EDGE], else the largest that does — the cap wins over sharpness (the web's `planRaster`).
     */
    fun plan(width: Double, height: Double, ratio: Double): Raster {
        val w = maxOf(1.0, width)
        val h = maxOf(1.0, height)
        val wanted = if (ratio.isFinite() && ratio > 0) ratio else 1.0
        val k = minOf(wanted, kotlin.math.sqrt(MAX_PIXELS / (w * h)), MAX_EDGE / w, MAX_EDGE / h)
        // floor, not round: a rounded-up side could carry the area one row over the cap
        return Raster(maxOf(1, kotlin.math.floor(w * k).toInt()), maxOf(1, kotlin.math.floor(h * k).toInt()), k)
    }

    /**
     * The page a web view shows: the picture ([PICTURE_NAME]) as its one `<img>`, fitted into the view and never enlarged
     * past its own size, laid out again at a zoom ([zoomScript]); not selectable, draggable or a touch target, so no
     * engine's menu offers to open it. No script; [CONTENT_SECURITY_POLICY] as the page's policy.
     */
    fun document(): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$CONTENT_SECURITY_POLICY\">" +
            "<meta name=\"referrer\" content=\"no-referrer\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=16\">" +
            "<style>:root{--z:1;color-scheme:light dark}html,body{margin:0;height:100%;background:transparent}" +
            "body{display:flex;overflow:auto}" +
            "img{margin:auto;display:block;max-width:100vw;max-height:100vh;zoom:var(--z);pointer-events:none;" +
            "user-select:none;-webkit-user-select:none;-webkit-user-drag:none;-webkit-touch-callout:none}</style>" +
            "</head><body><img src=\"$PICTURE_NAME\" alt=\"\" draggable=\"false\"></body></html>"

    /**
     * The app's own script that sets the page's zoom to [zoom] (one of [ZOOM_STEPS]; anything else is clamped into them),
     * keeping the point at the view's centre where it was. It runs as the app's, beside the page's script turned off.
     */
    fun zoomScript(zoom: Double): String {
        val z = zoom.coerceIn(ZOOM_STEPS.first(), ZOOM_STEPS.last())
        return "(function(){var e=document.scrollingElement,r=document.documentElement," +
            "b=parseFloat(getComputedStyle(r).getPropertyValue('--z'))||1,k=$z/b," +
            "cx=e.scrollLeft+innerWidth/2,cy=e.scrollTop+innerHeight/2;" +
            "r.style.setProperty('--z','$z');e.scrollLeft=cx*k-innerWidth/2;e.scrollTop=cy*k-innerHeight/2;})()"
    }

    /** How many rounds of character references [refused] undoes before it gives up and refuses (a nesting that deep is no drawing). */
    private const val UNESCAPE_ROUNDS = 8

    /**
     * Whether an SVG's [bytes] are refused, never handed to a renderer: they hold a `foreignObject` (the web refuses one by
     * its tainted canvas; a renderer that drew one would draw a document of HTML), spelled with any case or prefix, or
     * built by character references — an internal entity's value `&#60;foreign&#79;bject` becomes markup where it is
     * used, `&#38;#…;` one round later; or they are not UTF-8 throughout, or declare an encoding other than UTF-8, so the
     * scan could not read what the renderer reads (fail closed).
     */
    fun refused(bytes: ByteArray): Boolean {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val text = try {
            bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return true
        }
        if (!utf8Declared(text)) return true
        var s = text
        repeat(UNESCAPE_ROUNDS) {
            if (s.contains("foreignobject", ignoreCase = true)) return true
            val next = unescape(s)
            if (next == s) return false
            s = next
        }
        return true
    }

    /** Whether [text]'s XML declaration, if it has one, names UTF-8 (or US-ASCII, a subset), or none. */
    private fun utf8Declared(text: String): Boolean {
        var at = 0
        while (at < text.length && text[at].isWhitespace()) at++
        if (!text.startsWith("<?xml", at)) return true
        val end = text.indexOf("?>", at)
        if (end < 0) return false
        val declaration = text.substring(at, end)
        val name = declaration.indexOf("encoding")
        if (name < 0) return true
        var i = name + "encoding".length
        while (i < declaration.length && (declaration[i].isWhitespace() || declaration[i] == '=')) i++
        if (i >= declaration.length || (declaration[i] != '"' && declaration[i] != '\'')) return false
        val close = declaration.indexOf(declaration[i], i + 1)
        if (close < 0) return false
        return declaration.substring(i + 1, close).trim().lowercase() in setOf("utf-8", "utf8", "us-ascii", "ascii")
    }

    /** [s] with its numeric character references and the five predefined entities replaced by what they stand for. */
    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                out.append(c)
                i++
                continue
            }
            val ref = s.substring(i + 1, semi)
            val replaced: String? = when {
                ref.startsWith("#x") || ref.startsWith("#X") -> ref.substring(2).toIntOrNull(16)?.let(::codePoint)
                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::codePoint)
                ref == "lt" -> "<"
                ref == "gt" -> ">"
                ref == "amp" -> "&"
                ref == "quot" -> "\""
                ref == "apos" -> "'"
                else -> null
            }
            if (replaced == null) {
                out.append(c)
                i++
            } else {
                out.append(replaced)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}
