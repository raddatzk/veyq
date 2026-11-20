package me.raddatz.veyq.mobile.android.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.media.WebpEncoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * One's own profile picture encoded by Android's own WebP encoder (ADR 0148 decision 2): the core's target here is the JVM,
 * which links no libwebp, so the host's `Bitmap.compress(WEBP_LOSSY, q)` stands behind the core's [WebpEncoder] seam —
 * the quality ladder, the pad and the seal stay the core's (`ProfileModel`). [rgba] is the cropped picture as the core
 * asks for it: straight RGBA, 4 bytes a pixel, opaque.
 */
object AndroidWebpEncoder : WebpEncoder {
    override suspend fun encode(rgba: ByteArray, width: Int, height: Int, quality: Double): ByteArray? = withContext(Dispatchers.Default) {
        if (width <= 0 || height <= 0 || rgba.size != width * height * 4) return@withContext null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, (quality * 100).toInt().coerceIn(0, 100), out)) null else out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
