package me.raddatz.veyq.mobile.media

import me.raddatz.veyq.mobile.blob.UploadSource

/**
 * A picked photo's or video's tiers as the device derives them (the web's `mediaDeriver.ts`): the platform's service,
 * which Memories' uploads, a link guest's uploads and the import of a download each hand on. The spec of the tiers is
 * Memories' (`MediaUpload`'s edges and qualities).
 */
object Derivation {
    /**
     * What the platform derived: the `PREVIEW` JPEG (long edge ≤ `MediaUpload.PREVIEW_EDGE`, quality
     * `MediaUpload.PREVIEW_QUALITY`, upright) and the `THUMBNAIL` JPEG made from it (≤ `MediaUpload.THUMBNAIL_EDGE`,
     * `MediaUpload.THUMBNAIL_QUALITY`) — a video's poster, taken at `MediaUpload.posterAtMs`, as both —, and the size as it
     * is shown (a video's only where its header names none); all null where the original does not decode here (it then
     * goes alone).
     */
    class Derived(val preview: ByteArray?, val thumbnail: ByteArray?, val width: Int?, val height: Int?)

    /**
     * A platform's tiers of a picked photo or video: Android's `ImageDecoder` and `MediaMetadataRetriever`, Apple's ImageIO
     * and AVFoundation. It calls [done] once, on any thread, with what it derived — [Derived]'s nulls where the original does
     * not decode there.
     */
    interface Deriver {
        fun derive(source: UploadSource, type: String, done: (Derived) -> Unit)
    }
}
