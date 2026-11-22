package me.raddatz.veyq.mobile.android.apps.memories

import me.raddatz.veyq.mobile.media.Derivation
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
 * A picked photo's or video's tiers on Android, to the core's spec (`MediaUpload`'s, the web's `mediaDeriver.ts`): the
 * `PREVIEW` JPEG from the original — upright (the decoder applies the EXIF orientation), its long edge at most
 * `MediaUpload.PREVIEW_EDGE`, never enlarged, at its quality — and the `THUMBNAIL` JPEG made from that preview
 * (`MediaUpload.THUMBNAIL_EDGE`, its quality); a video's poster, the frame at `MediaUpload.posterAtMs` turned as it is
 * shown, at the preview's size and quality, as both tiers. Null where the original does not decode here: it then goes up
 * alone.
 */
internal object MediaDerive {
    /** A quality of the core's spec (0 to 1) as `Bitmap.compress` takes it (0 to 100). */
    private fun percent(quality: Double) = (quality * 100).roundToInt()

    fun of(context: Context, uri: Uri, type: String): Derivation.Derived? = runCatching {
        if (type.startsWith("video/")) video(context, uri) else image(context, uri)
    }.getOrNull()

    /** The size [w]×[h] scaled to fit [edge] on its long side, never enlarged; each side at least 1. */
    fun fit(w: Int, h: Int, edge: Int): Pair<Int, Int> {
        val long = max(w, h)
        if (long <= edge) return w to h
        val scale = edge.toDouble() / long
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    private fun image(context: Context, uri: Uri): Derivation.Derived {
        var full = 0 to 0
        val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            full = info.size.width to info.size.height
            val (w, h) = fit(info.size.width, info.size.height, MediaUpload.PREVIEW_EDGE)
            if (w != info.size.width) decoder.setTargetSize(w, h)
        }
        // the size as it is shown: the decoder's own, turned where it reported the stored one of a turned photo
        val turned = (preview.width > preview.height) != (full.first > full.second) && full.first != full.second
        val shown = if (turned) full.second to full.first else full
        val previewJpeg = jpeg(preview, percent(MediaUpload.PREVIEW_QUALITY))
        preview.recycle()
        val thumb = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(previewJpeg))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (w, h) = fit(info.size.width, info.size.height, MediaUpload.THUMBNAIL_EDGE)
            if (w != info.size.width) decoder.setTargetSize(w, h)
        }
        val thumbJpeg = jpeg(thumb, percent(MediaUpload.THUMBNAIL_QUALITY))
        thumb.recycle()
        return Derivation.Derived(previewJpeg, thumbJpeg, shown.first, shown.second)
    }

    private fun video(context: Context, uri: Uri): Derivation.Derived? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atMs = MediaUpload.posterAtMs(duration)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rotation == 90 || rotation == 270) h to w else w to h
            val (tw, th) = fit(dw, dh, MediaUpload.PREVIEW_EDGE)
            var frame = r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, tw, th) ?: return null
            // a frame handed over as stored, not as shown, is turned here
            if (rotation != 0 && (frame.width > frame.height) != (dw > dh) && dw != dh) {
                val turned = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                frame.recycle()
                frame = turned
            }
            val poster = jpeg(frame, percent(MediaUpload.PREVIEW_QUALITY))
            frame.recycle()
            return Derivation.Derived(poster, poster, dw, dh)
        } finally {
            r.release()
        }
    }

    /**
     * A banner's image (ADR 0144 decision 5): the picture at [uri] upright, made the cover's JPEG (core's
     * `NodeCover.encodedSize` and `QUALITY_PERCENT`, the web's `COVER_SPEC`); null where it does not decode here.
     */
    fun bannerImage(context: Context, uri: Uri): ByteArray? = runCatching {
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = me.raddatz.veyq.mobile.session.NodeCover.encodedSize(info.size.width, info.size.height)
            if (size[0] != info.size.width || size[1] != info.size.height) decoder.setTargetSize(size[0], size[1])
        }
        jpeg(b, me.raddatz.veyq.mobile.session.NodeCover.QUALITY_PERCENT).also { b.recycle() }
    }.getOrNull()

    /**
     * A banner video's poster (ADR 0144 decision 5): its first frame, turned as it is shown, as the cover's JPEG; null where
     * no frame decodes here (the dialog says so).
     */
    fun bannerPoster(context: Context, uri: Uri): ByteArray? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@runCatching null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@runCatching null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rotation == 90 || rotation == 270) h to w else w to h
            val size = me.raddatz.veyq.mobile.session.NodeCover.encodedSize(dw, dh)
            var frame = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size[0], size[1]) ?: return@runCatching null
            if (rotation != 0 && (frame.width > frame.height) != (dw > dh) && dw != dh) {
                val turned = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                frame.recycle()
                frame = turned
            }
            jpeg(frame, me.raddatz.veyq.mobile.session.NodeCover.QUALITY_PERCENT).also { frame.recycle() }
        } finally {
            r.release()
        }
    }.getOrNull()

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
