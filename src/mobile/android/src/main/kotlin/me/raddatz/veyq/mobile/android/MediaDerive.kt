package me.raddatz.veyq.mobile.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import me.raddatz.veyq.mobile.memories.MediaUpload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picked photo's or video's tiers on Android (the web's `mediaDeriver.ts`, which core's `MediaUpload.Derived` names): the
 * `PREVIEW` JPEG from the original — upright (the decoder applies the EXIF orientation), its long edge at most
 * [PREVIEW_EDGE], never enlarged, quality [PREVIEW_QUALITY] — and the `THUMBNAIL` JPEG made from that preview ([THUMB_EDGE],
 * [THUMB_QUALITY]); a video's poster, the frame at `min(0.1 s, duration / 2)` turned as it is shown, at the preview's size
 * and quality, as both tiers. Null where the original does not decode here: it then goes up alone.
 */
internal object MediaDerive {
    const val PREVIEW_EDGE = 2048
    const val PREVIEW_QUALITY = 85
    const val THUMB_EDGE = 512
    const val THUMB_QUALITY = 80

    fun of(context: Context, uri: Uri, type: String): MediaUpload.Derived? = runCatching {
        if (type.startsWith("video/")) video(context, uri) else image(context, uri)
    }.getOrNull()

    /** The size [w]×[h] scaled to fit [edge] on its long side, never enlarged; each side at least 1. */
    fun fit(w: Int, h: Int, edge: Int): Pair<Int, Int> {
        val long = max(w, h)
        if (long <= edge) return w to h
        val scale = edge.toDouble() / long
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    private fun image(context: Context, uri: Uri): MediaUpload.Derived {
        var full = 0 to 0
        val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            full = info.size.width to info.size.height
            val (w, h) = fit(info.size.width, info.size.height, PREVIEW_EDGE)
            if (w != info.size.width) decoder.setTargetSize(w, h)
        }
        // the size as it is shown: the decoder's own, turned where it reported the stored one of a turned photo
        val turned = (preview.width > preview.height) != (full.first > full.second) && full.first != full.second
        val shown = if (turned) full.second to full.first else full
        val previewJpeg = jpeg(preview, PREVIEW_QUALITY)
        preview.recycle()
        val thumb = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(previewJpeg))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = fit(info.size.width, info.size.height, THUMB_EDGE)
            if (w != info.size.width) decoder.setTargetSize(w, h)
        }
        val thumbJpeg = jpeg(thumb, THUMB_QUALITY)
        thumb.recycle()
        return MediaUpload.Derived(previewJpeg, thumbJpeg, shown.first, shown.second)
    }

    private fun video(context: Context, uri: Uri): MediaUpload.Derived? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atMs = minOf(100L, duration / 2)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rotation == 90 || rotation == 270) h to w else w to h
            val (tw, th) = fit(dw, dh, PREVIEW_EDGE)
            var frame = r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, tw, th) ?: return null
            // a frame handed over as stored, not as shown, is turned here
            if (rotation != 0 && (frame.width > frame.height) != (dw > dh) && dw != dh) {
                val turned = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                frame.recycle()
                frame = turned
            }
            val poster = jpeg(frame, PREVIEW_QUALITY)
            frame.recycle()
            return MediaUpload.Derived(poster, poster, dw, dh)
        } finally {
            r.release()
        }
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
