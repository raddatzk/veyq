package me.raddatz.veyq.mobile.blob

/**
 * What a blob's decrypted bytes are, by their magic numbers alone — a port of the image, text and PDF part of
 * `src/web/src/core/blob/sniff.ts`. The bytes decide; a claimed type or name is never consulted, so a peer's file
 * labelled "image" that is no image is never handed to an image decoder as one.
 */
object Sniff {
    enum class Image(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), GIF("image/gif"), WEBP("image/webp"), AVIF("image/avif"), BMP("image/bmp"), HEIC("image/heic") }

    private val AVIF = setOf("avif", "avis")
    private val HEIC = setOf("heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "heif", "mif1", "msf1")
    private val BMP_DIB = setOf(12, 40, 52, 56, 64, 108, 124)
    private val TEXT_CONTROLS = setOf(0x09, 0x0a, 0x0c, 0x0d, 0x1b)
    private const val PDF_WINDOW = 1024

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    fun asciiAt(b: ByteArray, offset: Int, length: Int): String =
        buildString { for (i in offset until minOf(b.size, offset + length)) append(u(b, i).toChar()) }

    fun hasAscii(b: ByteArray, offset: Int, ascii: String) = b.size >= offset + ascii.length && asciiAt(b, offset, ascii.length) == ascii

    /** The raster image format [b] is, or null. */
    fun image(b: ByteArray): Image? {
        if (b.size >= 3 && u(b, 0) == 0xff && u(b, 1) == 0xd8 && u(b, 2) == 0xff) return Image.JPEG
        if (b.size >= 8 && u(b, 0) == 0x89 && hasAscii(b, 1, "PNG\r\n") && u(b, 6) == 0x1a && u(b, 7) == 0x0a) return Image.PNG
        if (hasAscii(b, 0, "GIF87a") || hasAscii(b, 0, "GIF89a")) return Image.GIF
        if (hasAscii(b, 0, "RIFF") && hasAscii(b, 8, "WEBP")) return Image.WEBP
        if (b.size >= 26 && u(b, 0) == 0x42 && u(b, 1) == 0x4d && u(b, 15) == 0 && u(b, 16) == 0 && u(b, 17) == 0 && u(b, 14) in BMP_DIB) return Image.BMP
        if (hasAscii(b, 4, "ftyp")) {
            val brand = asciiAt(b, 8, 4)
            if (brand in AVIF) return Image.AVIF
            if (brand in HEIC) return Image.HEIC
        }
        return null
    }

    /**
     * Whether [b] is valid text: a UTF-16 byte-order mark followed by a decodable body, or valid UTF-8 with no NUL and no
     * control byte but tab, line feed, form feed, carriage return and escape. A sequence cut off by the end is not an error
     * while [b] is a prefix, but is when it is the [complete] file.
     */
    fun isText(b: ByteArray, complete: Boolean = false): Boolean {
        if (b.size >= 2 && ((u(b, 0) == 0xff && u(b, 1) == 0xfe) || (u(b, 0) == 0xfe && u(b, 1) == 0xff))) {
            return utf16Decodes(b, littleEndian = u(b, 0) == 0xff)
        }
        for (i in b.indices) if (u(b, i) < 0x20 && u(b, i) !in TEXT_CONTROLS) return false
        return utf8Valid(b, complete)
    }

    private fun utf16Decodes(b: ByteArray, littleEndian: Boolean): Boolean {
        val n = b.size - (b.size % 2)
        var i = 2
        var pendingHigh = false
        while (i < n) {
            val unit = if (littleEndian) u(b, i) or (u(b, i + 1) shl 8) else (u(b, i) shl 8) or u(b, i + 1)
            if (unit in 0xD800..0xDBFF) {
                if (pendingHigh) return false
                pendingHigh = true
            } else if (unit in 0xDC00..0xDFFF) {
                if (!pendingHigh) return false
                pendingHigh = false
            } else if (pendingHigh) {
                return false
            }
            i += 2
        }
        // A fatal decoder refuses a high surrogate left open at the end.
        return !pendingHigh
    }

    private fun utf8Valid(b: ByteArray, complete: Boolean): Boolean {
        var i = 0
        while (i < b.size) {
            val c = u(b, i)
            val (n, min) = when {
                c < 0x80 -> 0 to 0
                c in 0xc2..0xdf -> 1 to 0x80
                c in 0xe0..0xef -> 2 to 0x800
                c in 0xf0..0xf4 -> 3 to 0x10000
                else -> return false
            }
            if (i + n >= b.size && n > 0) {
                // Cut off by the end: valid so far is valid for a prefix, never for the complete file.
                if (complete) return false
                for (k in i + 1 until b.size) if (u(b, k) and 0xc0 != 0x80) return false
                return prefixOk(b, i)
            }
            var cp = if (n == 0) c else c and (0x3f shr n)
            for (k in 1..n) {
                val cc = u(b, i + k)
                if (cc and 0xc0 != 0x80) return false
                cp = (cp shl 6) or (cc and 0x3f)
            }
            if (n > 0 && (cp < min || cp > 0x10ffff || cp in 0xd800..0xdfff)) return false
            i += n + 1
        }
        return true
    }

    /** A truncated last sequence at [i] that could still complete validly (its second byte within the lead's range). */
    private fun prefixOk(b: ByteArray, i: Int): Boolean {
        if (i + 1 >= b.size) return true
        val c = u(b, i)
        val c1 = u(b, i + 1)
        return when (c) {
            0xe0 -> c1 >= 0xa0
            0xed -> c1 <= 0x9f
            0xf0 -> c1 >= 0x90
            0xf4 -> c1 <= 0x8f
            else -> true
        }
    }

    /**
     * Whether [b] is a PDF: `%PDF-` at byte 0, or within the first KiB but then only where [b] is NOT valid text (a text
     * that merely mentions the header is a text).
     */
    fun pdf(b: ByteArray): Boolean {
        if (hasAscii(b, 0, "%PDF-")) return true
        for (i in 1..PDF_WINDOW) {
            if (i >= b.size) break
            if (u(b, i) == 0x25 && hasAscii(b, i, "%PDF-")) return !isText(b)
        }
        return false
    }
}
