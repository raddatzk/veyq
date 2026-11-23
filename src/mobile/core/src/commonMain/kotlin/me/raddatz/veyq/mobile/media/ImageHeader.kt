package me.raddatz.veyq.mobile.media

/**
 * An image's pixel size read from the first bytes of its file, never from decoded pixels (inbox #558; the web's pick-time
 * tile probe and upload, `mediaDeriver.ts` `probeDimensions`, draw it through `@veyq/core-js/media`): a 12-megapixel photo
 * decoded is 48.8 MB against the few kilobytes that name its size.
 *
 * The formats a camera or a phone hands over that are simple to read: JPEG (the frame header, with the EXIF orientation
 * applied, as a browser draws it — `image-orientation: from-image` and `createImageBitmap` swap width and height for 5–8),
 * PNG, GIF and WebP. Anything else — HEIC, AVIF, an unknown or damaged header — answers null, and the caller asks its
 * platform's decoder instead. Reads are windows of [WINDOW], only as far as a JPEG's segments before its frame header
 * reach, at most [MAX_SEGMENTS] of them.
 */
object ImageHeader {
    /** Bytes per read: a phone JPEG's EXIF segment, with its thumbnail, fits; one that does not costs a second read. */
    const val WINDOW = 64 * 1024
    /** A JPEG's segments walked before its frame header, at most. */
    const val MAX_SEGMENTS = 256

    /** A size in pixels; a `Double`, as an unsigned 32-bit width may be. */
    class Size(val width: Double, val height: Double)

    /** The pixel size [file]'s header names, as a browser shows it, or null. */
    suspend fun size(file: MediaBytes): Size? {
        val r = HeaderReader(file, WINDOW, Double.MAX_VALUE, Int.MAX_VALUE)
        val n = r.hold(0.0, WINDOW)
        if (n < 0) return null
        if (n >= 3 && r.u8(0.0) == 0xFF && r.u8(1.0) == 0xD8 && r.u8(2.0) == 0xFF) return jpeg(r)
        if (n >= 24 && r.u8(0.0) == 0x89 && r.fourcc(1.0, "PNG\r\n\u001a\n") && r.fourcc(12.0, "IHDR")) return sized(r.u32be(16.0), r.u32be(20.0))
        if (n >= 10 && (r.fourcc(0.0, "GIF87a") || r.fourcc(0.0, "GIF89a"))) return sized(u16le(r, 6.0).toDouble(), u16le(r, 8.0).toDouble())
        if (n >= 30 && r.fourcc(0.0, "RIFF") && r.fourcc(8.0, "WEBP")) return webp(r)
        return null
    }

    private fun u16le(r: HeaderReader, at: Double) = r.u8(at) or (r.u8(at + 1) shl 8)
    private fun u24le(r: HeaderReader, at: Double) = r.u8(at) or (r.u8(at + 1) shl 8) or (r.u8(at + 2) shl 16)

    /** A size, or null where either side is zero (a header that names no size). */
    private fun sized(w: Double, h: Double): Size? = if (w > 0 && h > 0) Size(w, h) else null

    /** A WebP's size from its first chunk: lossy (`VP8 `), lossless (`VP8L`) or extended (`VP8X`). */
    private fun webp(r: HeaderReader): Size? = when {
        r.fourcc(12.0, "VP8 ") ->
            if (r.u8(23.0) == 0x9D && r.u8(24.0) == 0x01 && r.u8(25.0) == 0x2A) sized((u16le(r, 26.0) and 0x3FFF).toDouble(), (u16le(r, 28.0) and 0x3FFF).toDouble()) else null
        r.fourcc(12.0, "VP8L") -> {
            if (r.u8(20.0) != 0x2F) null
            else {
                val bits = r.u8(21.0) or (r.u8(22.0) shl 8) or (r.u8(23.0) shl 16) or (r.u8(24.0) shl 24)
                sized(((bits and 0x3FFF) + 1).toDouble(), (((bits ushr 14) and 0x3FFF) + 1).toDouble())
            }
        }
        r.fourcc(12.0, "VP8X") -> sized((u24le(r, 24.0) + 1).toDouble(), (u24le(r, 27.0) + 1).toDouble())
        else -> null
    }

    /** The JPEG markers that start a frame header (SOF0–SOF15 without DHT C4, JPG C8 and DAC CC): they carry the size. */
    private fun isFrame(kind: Int): Boolean = kind in 0xC0..0xCF && kind != 0xC4 && kind != 0xC8 && kind != 0xCC

    /** A JPEG's size from its frame header, walking the segments before it; the EXIF orientation from an APP1 `Exif` one. */
    private suspend fun jpeg(r: HeaderReader): Size? {
        var orientation = 1
        var at = 2.0
        for (segment in 0 until MAX_SEGMENTS) {
            if (at + 4 > r.size || r.hold(at, 4) < 4) return null
            if (r.u8(at) != 0xFF) return null
            val kind = r.u8(at + 1)
            if (kind == 0xFF) {
                at += 1 // a fill byte before the marker
                continue
            }
            if (kind == 0xD8 || kind == 0x01 || kind in 0xD0..0xD7) {
                at += 2 // a marker without a segment
                continue
            }
            if (kind == 0xD9 || kind == 0xDA) return null // the image ends, or its data starts, before any frame header
            val length = r.u16be(at + 2)
            if (length < 2) return null
            if (isFrame(kind)) {
                if (at + 9 > r.size || r.hold(at, 9) < 9) return null
                val h = r.u16be(at + 5).toDouble()
                val w = r.u16be(at + 7).toDouble()
                return if (orientation in 5..8) sized(h, w) else sized(w, h)
            }
            if (kind == 0xE1 && at + 4 + (length - 2) <= r.size && r.hold(at + 4, length - 2) == length - 2) {
                val app1 = r.copy(at + 4, length - 2)
                if (Bytes.ascii(app1, 0, "Exif\u0000\u0000")) orientation = orientationOf(app1, 6) ?: orientation
            }
            at += 2 + length
        }
        return null
    }

    /** The `Orientation` tag (0x0112) of the TIFF header at [base] of [b]'s first IFD, or null. */
    private fun orientationOf(b: ByteArray, base: Int): Int? {
        if (b.size - base < 8) return null
        val little = b[base].toInt() == 0x49 && b[base + 1].toInt() == 0x49
        if (!little && !(b[base].toInt() == 0x4D && b[base + 1].toInt() == 0x4D)) return null
        fun u16(at: Int) = if (little) Bytes.u16le(b, base + at) else Bytes.u16be(b, base + at)
        fun u32(at: Int) = if (little) Bytes.u32le(b, base + at) else Bytes.u32be(b, base + at)
        val tiff = b.size - base
        if (u16(2) != 42) return null
        val ifd = u32(4)
        if (ifd + 2 > tiff) return null
        val first = ifd.toInt()
        val count = u16(first)
        for (i in 0 until count) {
            val entry = first + 2 + i * 12
            if (entry + 12 > tiff) return null
            if (u16(entry) == 0x0112) return u16(entry + 8)
        }
        return null
    }
}
