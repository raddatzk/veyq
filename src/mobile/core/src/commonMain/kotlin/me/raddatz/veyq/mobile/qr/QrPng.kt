package me.raddatz.veyq.mobile.qr

/**
 * A [QrMatrix] as a PNG for the desktops (D2 of the QR CR), answered as shown bytes (ADR 0131 decision 1) so the text
 * a code holds never crosses the bridge: a 1-bit greyscale image, each module [scale] × [scale] pixels, dark modules
 * black on white, the quiet zone included. Its pixels are stored, not compressed (deflate's stored blocks), so no
 * compressor is needed; a code of version 40 at scale 8 (1480 px) is about 275 KB.
 */
object QrPng {
    /** The largest image side written: a host asks for a scale its screen needs, not an unbounded one. */
    const val MAX_SIDE = 4096

    fun write(matrix: QrMatrix, scale: Int): ByteArray {
        require(scale >= 1) { "scale $scale" }
        val side = matrix.side * scale
        require(side <= MAX_SIDE) { "a $side px image" }
        // Each row: filter type 0, then the pixels at 1 bit each, most significant first, 1 white and 0 black.
        val rowBytes = (side + 7) / 8
        val raw = ByteArray((1 + rowBytes) * side)
        for (y in 0 until side) {
            val row = y * (1 + rowBytes)
            for (x in 0 until side) {
                if (!matrix.dark(x / scale, y / scale)) {
                    val at = row + 1 + x / 8
                    raw[at] = (raw[at].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
            // The bits past the last pixel stay 0: a decoder ignores them.
        }
        val out = Bytes()
        out.add(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", Bytes().apply { int(side); int(side); byte(1); byte(0); byte(0); byte(0); byte(0) }.toArray())
        chunk(out, "IDAT", zlibStored(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toArray()
    }

    private fun chunk(out: Bytes, type: String, data: ByteArray) {
        val typeBytes = type.encodeToByteArray()
        out.int(data.size)
        out.add(typeBytes)
        out.add(data)
        out.int(crc32(typeBytes, data).toInt())
    }

    /** A zlib stream (RFC 1950) of stored deflate blocks (RFC 1951 3.2.4), each at most 65535 bytes, and its Adler-32. */
    internal fun zlibStored(data: ByteArray): ByteArray {
        val out = Bytes()
        out.byte(0x78) // deflate, 32 KiB window
        out.byte(0x01) // no dictionary, the fastest level; 0x7801 is a multiple of 31
        var at = 0
        do {
            val length = minOf(65535, data.size - at)
            val last = at + length == data.size
            out.byte(if (last) 1 else 0) // BFINAL, BTYPE 00
            out.byte(length and 0xFF)
            out.byte(length ushr 8)
            out.byte(length.inv() and 0xFF)
            out.byte((length.inv() ushr 8) and 0xFF)
            out.add(data.copyOfRange(at, at + length))
            at += length
        } while (!last)
        out.int(adler32(data).toInt())
        return out.toArray()
    }

    internal fun adler32(data: ByteArray): Long {
        var a = 1L
        var b = 0L
        for (d in data) {
            a = (a + (d.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    private val CRC_TABLE = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    /** The CRC-32 (ISO 3309, PNG's) of [parts] in order. */
    internal fun crc32(vararg parts: ByteArray): Long {
        var c = 0xFFFFFFFFL
        for (part in parts) for (d in part) c = CRC_TABLE[((c xor (d.toLong() and 0xFF)) and 0xFF).toInt()] xor (c ushr 8)
        return c xor 0xFFFFFFFFL
    }

    /** A growable byte buffer, big-endian integers. */
    private class Bytes {
        private var buf = ByteArray(256)
        private var size = 0

        fun byte(v: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v.toByte()
        }

        fun int(v: Int) {
            byte(v ushr 24); byte(v ushr 16); byte(v ushr 8); byte(v)
        }

        fun add(bytes: ByteArray) {
            if (size + bytes.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + bytes.size))
            bytes.copyInto(buf, size)
            size += bytes.size
        }

        fun toArray(): ByteArray = buf.copyOf(size)
    }
}
