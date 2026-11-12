package me.raddatz.veyq.mobile.qr

import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.nio.charset.Charset
import kotlin.math.ceil

/** Images as the core's decoder takes them, made the way ZXing's own test harness makes them. */
internal object QrImages {
    /**
     * The luminance ZXing's `BufferedImageLuminanceSource` reads: a grey image's raster as it is; any other
     * `(306 R + 601 G + 117 B + 512) >> 10`, a fully transparent pixel white.
     */
    fun frame(image: BufferedImage): QrFrame {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h)
        if (image.type == BufferedImage.TYPE_BYTE_GRAY) {
            image.raster.getDataElements(0, 0, w, h, out)
        } else {
            val row = IntArray(w)
            for (y in 0 until h) {
                image.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w) {
                    val p = row[x]
                    out[y * w + x] = (if ((p.toLong() and 0xFF000000L) == 0L) 0xFF
                    else (306 * ((p shr 16) and 0xFF) + 601 * ((p shr 8) and 0xFF) + 117 * (p and 0xFF) + 0x200) shr 10).toByte()
                }
            }
        }
        return QrFrame(w, h, w, out)
    }

    /** Upstream's `AbstractBlackBoxTestCase.rotateImage`: bicubic, about the centre, onto the rotated bounds. */
    fun rotate(original: BufferedImage, degrees: Float): BufferedImage {
        if (degrees == 0.0f) return original
        var source = original
        if (source.type == BufferedImage.TYPE_BYTE_INDEXED || source.type == BufferedImage.TYPE_BYTE_BINARY) {
            val argb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
            val g = argb.createGraphics()
            g.drawImage(source, 0, 0, null)
            g.dispose()
            source = argb
        }
        val radians = Math.toRadians(degrees.toDouble())
        // Transform simply to find out the new bounding box (don't actually run the image through it)
        var at = AffineTransform()
        at.rotate(radians, source.width / 2.0, source.height / 2.0)
        var op = AffineTransformOp(at, AffineTransformOp.TYPE_BICUBIC)
        val r = op.getBounds2D(source)
        val width = ceil(r.width).toInt()
        val height = ceil(r.height).toInt()
        // Real transform, now that we know the size of the new image and how to translate after we rotate to keep it
        // centered
        at = AffineTransform()
        at.rotate(radians, width / 2.0, height / 2.0)
        at.translate((width - source.width) / 2.0, (height - source.height) / 2.0)
        op = AffineTransformOp(at, AffineTransformOp.TYPE_BICUBIC)
        return op.filter(source, BufferedImage(width, height, source.type))
    }

    /** A content's text with the segments the core leaves encoded decoded by the JVM's own character sets. */
    fun text(content: QrContent): String = content.pieces.joinToString("") {
        when (it) {
            is QrContent.Piece.Text -> it.text
            is QrContent.Piece.Encoded -> String(it.bytes, Charset.forName(it.charset))
        }
    }
}
