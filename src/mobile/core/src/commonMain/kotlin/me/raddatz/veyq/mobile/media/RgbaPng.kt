package me.raddatz.veyq.mobile.media

import me.raddatz.veyq.mobile.qr.QrPng

/**
 * Straight (not premultiplied) RGBA pixels as a PNG: 8 bits a channel, colour type 6, each row unfiltered and the whole
 * stored, not compressed, as [QrPng] writes its codes (deflate's stored blocks, so no compressor is needed). It is how a
 * picture the core decoded reaches a host ([WebpPicture] on Linux and Windows): every platform decodes a PNG with its own
 * codec, so no pixel format crosses the bridge.
 */
object RgbaPng {
    /** The largest side written: a member's picture is 256 px; a picture past this is refused, not drawn. */
    const val MAX_SIDE = 4096

    fun write(width: Int, height: Int, rgba: ByteArray): ByteArray {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "a $width × $height image" }
        require(rgba.size == width * height * 4) { "${rgba.size} bytes for $width × $height pixels" }
        val row = width * 4
        val raw = ByteArray((1 + row) * height)
        // each row: filter type 0, then its pixels as they are
        for (y in 0 until height) rgba.copyInto(raw, y * (1 + row) + 1, y * row, (y + 1) * row)
        val ihdr = ByteArray(13).also {
            int(it, 0, width)
            int(it, 4, height)
            it[8] = 8 // bit depth
            it[9] = 6 // colour type: RGB with alpha; compression, filter and interlace 0
        }
        val idat = QrPng.zlibStored(raw)
        val out = ByteArray(8 + 25 + 12 + idat.size + 12)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A).copyInto(out)
        var at = chunk(out, 8, "IHDR", ihdr)
        at = chunk(out, at, "IDAT", idat)
        chunk(out, at, "IEND", ByteArray(0))
        return out
    }

    private fun chunk(out: ByteArray, at: Int, type: String, data: ByteArray): Int {
        val typeBytes = type.encodeToByteArray()
        int(out, at, data.size)
        typeBytes.copyInto(out, at + 4)
        data.copyInto(out, at + 8)
        int(out, at + 8 + data.size, QrPng.crc32(typeBytes, data).toInt())
        return at + 12 + data.size
    }

    private fun int(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }
}
