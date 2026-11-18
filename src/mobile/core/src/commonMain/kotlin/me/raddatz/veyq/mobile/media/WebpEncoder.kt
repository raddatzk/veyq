package me.raddatz.veyq.mobile.media

/**
 * The one seam a profile picture is encoded through (ADR 0148 decision 2): [rgba] of [width] × [height] pixels, 4 bytes
 * each, to a lossy WebP at [quality] (0…1, the web's `deriveImage` scale), or null where it does not encode. The quality
 * ladder and the pad's budget are the core's (`ProfileModel`); the encoder only encodes.
 */
fun interface WebpEncoder {
    suspend fun encode(rgba: ByteArray, width: Int, height: Int, quality: Double): ByteArray?
}

/** The encoder the core holds on this target: libwebp's on every native one, none on the JVM (the Android host's). */
expect val coreWebpEncoder: WebpEncoder?
