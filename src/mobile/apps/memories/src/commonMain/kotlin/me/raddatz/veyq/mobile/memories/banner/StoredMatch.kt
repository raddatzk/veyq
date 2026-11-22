package me.raddatz.veyq.mobile.memories.banner

import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.SharedBlob
import me.raddatz.veyq.mobile.memories.MemoryMedia

/**
 * What of a picked file the Memory already stores (ADR 0013 § 5, ADR 0144 decision 5) — a port of the web's
 * `storedMatch.ts`: a banner picked equal to a gallery original, or a gallery upload equal to the video banner, shares the
 * stored object instead of storing the bytes again. Equal is: the same sealed size, then the same SHA-256 of the plaintext —
 * the `sha256` a gallery original's and a video banner's `blob-meta` carry, and the video banner's `sha256` in the node's
 * sealed meta. A medium uploaded before the digest existed carries none and is never matched.
 *
 * Which tier a slot shares — only where the stored bytes are the very bytes the slot would hold:
 *  - an image banner: the medium's PREVIEW (the same spec — long edge 2048, quality 0.85 — of the same original);
 *  - a video banner: the medium's ORIGINAL (the slot holds the file as it is), and its PREVIEW (the first-frame poster) as
 *    the banner's poster;
 *  - a gallery original: a video banner's `BANNER` blob — never an image banner, which holds a derived JPEG, not the file.
 */
object StoredMatch {
    /**
     * The first live, unblocked medium of [items] whose original equals a file of [size] plaintext bytes whose digest is
     * [digest] (size first; the listing holds live media only), or null.
     */
    fun equalMedium(items: List<MemoryMedia.Item>, size: Long, digest: String): MemoryMedia.Item? {
        val sealed = SharedBlob.sealedByteSize(size)
        return items.firstOrNull { !it.blocked && it.original.byteSize == sealed && it.sha256 != null && it.sha256 == digest }
    }

    /** A medium's tier as a stored blob a new row may share; null where the medium has no such tier (the upload then runs). */
    fun tier(item: MemoryMedia.Item, role: String): SharedBlob.Stored? {
        val b = when (role) {
            MemoryMedia.ORIGINAL -> item.original
            MemoryMedia.PREVIEW -> item.preview
            else -> null
        } ?: return null
        return SharedBlob.Stored(item.key, item.mediaId, role, b.blobId, b.chunkCount, b.byteSize, b.wrappedBlobKey)
    }

    /**
     * The node's video banner as the stored original of a gallery upload of the same file: its `BANNER` blob, matched by the
     * banner's sealed `sha256` and its size, under the node's [nodeKey]. Null for none, an image banner, another file.
     */
    fun bannerOriginal(meta: JsonObject?, nodeId: String, nodeKey: ByteArray, digest: String, byteSize: Long): SharedBlob.Stored? {
        val b = meta?.let { Banner.of(it) } ?: return null
        if (b.medium != Banner.Medium.VIDEO || b.sha256 != digest || b.blob.byteSize != byteSize) return null
        return SharedBlob.Stored(nodeKey, nodeId, Banner.ROLE, b.blob.blobId, b.blob.chunkCount, b.blob.byteSize, b.blob.wrappedBlobKey)
    }

    /**
     * Whether [item] is the banner's picture (ADR 0102 decision 7, the web's `isBannerPicture`): its original's SHA-256 is
     * the one the banner was made from. A banner or a medium written without a digest is no match.
     */
    fun isBannerPicture(banner: Banner.Read?, item: MemoryMedia.Item): Boolean {
        val digest = banner?.sha256 ?: return false
        return item.sha256 == digest
    }
}
