package me.raddatz.veyq.mobile.media

import me.raddatz.veyq.mobile.crypto.b64

/**
 * A cover drawn from an SVG (uc.platform.node-cover main flow 3, the web's `coverWrite.ts` over `rasterizeSvg`): the SVG is
 * never stored — it is drawn once on this device with its long edge at [LONG_EDGE] (a small one drawn up to it) and stored
 * as one WebP of that drawing ([QUALITY]), so a logo's transparency stays. What every app decides alike is here: whether a
 * file is taken ([plan]: an SVG whose markup [SvgMarkup] does not refuse and that has a size of its own), the size it is
 * drawn at, the one page a web view draws it from ([document]), and the WebP ([encode]). The platform only draws.
 */
object SvgCover {
    /** The drawing's long edge: the cover's (`COVER_SPEC.maxEdge`, `NodeCover.LONG_EDGE`). */
    const val LONG_EDGE = 2048

    /** The WebP's quality (`COVER_SPEC.quality`). */
    const val QUALITY = 0.85

    /** The most bytes of an SVG a cover is drawn from (the web's cover source limit, 40 MB). */
    const val MOST_BYTES = 40L * 1024 * 1024

    /** What a picked SVG is drawn at: [width] × [height] pixels. */
    data class Plan(val width: Int, val height: Int)

    /**
     * The drawing of an SVG's [bytes], or null where the cover dialog refuses it as unreadable: markup [SvgMarkup] refuses
     * (a `foreignObject`, anything not UTF-8), no `svg` root, or no size of its own ([size]).
     */
    fun plan(bytes: ByteArray): Plan? {
        if (bytes.isEmpty() || bytes.size > MOST_BYTES || SvgMarkup.refused(bytes)) return null
        val (w, h) = size(bytes.decodeToString()) ?: return null
        val scale = LONG_EDGE / maxOf(w, h)
        return Plan(maxOf(1, kotlin.math.round(w * scale).toInt()), maxOf(1, kotlin.math.round(h * scale).toInt()))
    }

    /**
     * The SVG's own size in CSS pixels, as the web's `<img>` reads it: its root's `width` and `height` (unitless or `px`,
     * `pt`, `pc`, `in`, `cm`, `mm`, `em`, `ex`; a percentage is none), one missing taken from the `viewBox`'s aspect; with
     * neither, the `viewBox`'s own size (our reading: a browser may call such an SVG unsized, the apps draw it at its
     * viewBox). Null where none of it gives a positive size.
     */
    fun size(text: String): Pair<Double, Double>? {
        val tag = rootTag(text) ?: return null
        val attrs = attributes(tag)
        val w = attrs["width"]?.let(::length)
        val h = attrs["height"]?.let(::length)
        val box = attrs["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 && it.all(Double::isFinite) }
        return when {
            w != null && h != null -> w to h
            w != null && box != null -> w to w * box[3] / box[2]
            h != null && box != null -> h * box[2] / box[3] to h
            w == null && h == null && box != null -> box[2] to box[3]
            else -> null
        }?.takeIf { (a, b) -> a > 0 && b > 0 && a.isFinite() && b.isFinite() }
    }

    /** The root element's start tag: the first `svg` element (any prefix) past the prolog, its text up to its `>`. */
    private fun rootTag(text: String): String? {
        val m = Regex("<(?:[A-Za-z_][\\w.-]*:)?svg(?=[\\s/>])").find(text) ?: return null
        var i = m.range.last + 1
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return text.substring(m.range.first, i)
            i++
        }
        return null
    }

    private fun attributes(tag: String): Map<String, String> =
        Regex("([A-Za-z_:][\\w.:-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").findAll(tag)
            .associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groups[4]?.value ?: "") }

    /** A length in CSS pixels; null for a percentage, an unknown unit or none that is positive. */
    private fun length(raw: String): Double? {
        val m = Regex("^\\s*([+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$").find(raw) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val px = when (m.groupValues[2].lowercase()) {
            "", "px" -> n
            "pt" -> n * 96 / 72
            "pc" -> n * 16
            "in" -> n * 96
            "cm" -> n * 96 / 2.54
            "mm" -> n * 96 / 25.4
            "em" -> n * 16
            "ex" -> n * 8
            else -> return null
        }
        return px.takeIf { it > 0 && it.isFinite() }
    }

    /** What a page drawing the SVG may load: the picture as a `data:` image, nothing else, no script. */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"

    /**
     * The one page a platform's web view draws the SVG from (Android's `WebView`, iOS's `WKWebView`, Windows' WebView2): the
     * SVG as an `<img>` of a `data:` URL — the engine's image mode, which runs no script and loads nothing the SVG names —
     * at exactly [plan]'s size in CSS pixels, on a transparent page, under [CONTENT_SECURITY_POLICY]. The host lays the view
     * out at that size at one pixel per CSS pixel and takes its picture.
     */
    fun document(bytes: ByteArray, plan: Plan): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$CONTENT_SECURITY_POLICY\">" +
            "<meta name=\"viewport\" content=\"width=${plan.width}, initial-scale=1\">" +
            "<link rel=\"icon\" href=\"data:,\">" +
            "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}" +
            "img{display:block;width:${plan.width}px;height:${plan.height}px}</style></head>" +
            "<body><img alt=\"\" src=\"data:image/svg+xml;base64,${b64(bytes)}\"></body></html>"

    /**
     * [rgba] — the platform's drawing of [width] × [height] pixels, 4 bytes a pixel, not premultiplied: [plan]'s size, or
     * smaller with its aspect where a platform draws no larger (Android's web view, within its window) — as the cover's
     * WebP; null where it does not encode or is no drawing of that size.
     */
    suspend fun encode(encoder: WebpEncoder?, rgba: ByteArray, width: Int, height: Int): ByteArray? {
        if (encoder == null || width <= 0 || height <= 0 || width > LONG_EDGE || height > LONG_EDGE || rgba.size != width * height * 4) return null
        return encoder.encode(rgba, width, height, QUALITY)
    }
}
