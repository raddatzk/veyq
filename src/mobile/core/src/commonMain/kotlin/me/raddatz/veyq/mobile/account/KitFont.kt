package me.raddatz.veyq.mobile.account

/**
 * The three of the PDF's standard 14 fonts the Emergency Kit is drawn in ([KitWriter]), never embedded: every PDF reader
 * carries them. Each is used with `/WinAnsiEncoding`, so a character is one byte of Windows-1252 ([code]); one that
 * encoding lacks is drawn as `?` (an email in another script — its token, and so its QR, keeps it whole).
 *
 * The advance widths are what right alignment, centring and wrapping need: Helvetica's and Helvetica-Bold's for the
 * codes 32-255 under WinAnsiEncoding, in 1/1000 of the font size, taken from Adobe's Core 14 AFM files (`Helvetica.afm`,
 * `Helvetica-Bold.afm`, Copyright (c) 1985, 1987, 1989, 1990, 1997 Adobe Systems Incorporated, All Rights Reserved; the
 * AFM files may be used, copied and distributed for any purpose and without charge, with or without modification,
 * provided that all copyright notices are retained — only the `WX` values are kept here, by WinAnsi code instead of by
 * glyph name). `KitWriterTest` holds each width to PDFBox's copy of the same metrics. Courier-Bold is monospaced: 600.
 * No kerning is applied (jsPDF, the web's writer, applies its own pairs, so a line may wrap a word apart from the web's).
 */
internal enum class KitFont(val resource: String, val baseFont: String, private val widths: IntArray?) {
    HELVETICA("F1", "Helvetica", Widths.HELVETICA),
    HELVETICA_BOLD("F2", "Helvetica-Bold", Widths.HELVETICA_BOLD),
    COURIER_BOLD("F3", "Courier-Bold", null),
    ;

    /** The advance width of WinAnsi [code] in 1/1000 of the font size. */
    fun width(code: Int): Int = widths?.getOrNull(code - 32) ?: 600

    /** The width of [text] drawn at [size] points. */
    fun width(text: String, size: Double): Double = text.sumOf { width(code(it)) } * size / 1000.0

    companion object {
        /** Windows-1252's 0x80-0x9F; a NUL stands for a code the encoding leaves undefined. */
        private const val HIGH = "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ"

        /** [c]'s WinAnsi code; `?` for a character the encoding lacks (a control character included). */
        fun code(c: Char): Int = when (c.code) {
            in 0x20..0x7e, in 0xa0..0xff -> c.code
            0 -> '?'.code
            else -> HIGH.indexOf(c).let { if (it >= 0) 0x80 + it else '?'.code }
        }
    }
}

private object Widths {
    val HELVETICA = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 0,
        556, 0, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,
        0, 222, 222, 333, 333, 350, 556, 1000, 333, 1000, 500, 333, 944, 0, 500, 667,
        278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
        400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611,
        667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500,
    )
    val HELVETICA_BOLD = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 0,
        556, 0, 278, 556, 500, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,
        0, 278, 278, 500, 500, 350, 556, 1000, 333, 1000, 556, 333, 944, 0, 500, 667,
        278, 333, 556, 556, 556, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,
        400, 584, 333, 333, 333, 611, 556, 278, 333, 333, 365, 556, 834, 834, 834, 611,
        722, 722, 722, 722, 722, 722, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278,
        611, 611, 611, 611, 611, 611, 611, 584, 611, 611, 611, 611, 611, 556, 611, 556,
    )
}
