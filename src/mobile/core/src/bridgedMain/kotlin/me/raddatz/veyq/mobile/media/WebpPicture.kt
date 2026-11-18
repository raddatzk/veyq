@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.media

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import me.raddatz.veyq.mobile.webp.WebPDecodeRGBAInto
import me.raddatz.veyq.mobile.webp.WebPGetInfo

/**
 * A WebP decoded by the core, for a host whose platform cannot: a member's picture is a WebP (ADR 0127), and Windows
 * decodes WebP only through the Store's WebP Image Extension, which a person can remove (ADR 0139 decision 6). libwebp's
 * own decoder, built from the web encoder's pin (core/webp/build_libwebp.py), answers the picture as an [RgbaPng], which
 * every platform decodes. Null for what is no still WebP (an animation, broken bytes) and for a picture with a side past
 * [RgbaPng.MAX_SIDE]: its size is read from the header before a byte of pixels is allocated.
 */
object WebpPicture {
    fun png(webp: ByteArray): ByteArray? = decodeRgba(webp)?.let { (width, height, rgba) -> RgbaPng.write(width, height, rgba) }
}

/** The picture's width, height and straight RGBA pixels; null as [WebpPicture.png] says. */
internal fun decodeRgba(webp: ByteArray): Triple<Int, Int, ByteArray>? {
    if (webp.isEmpty()) return null
    return webp.usePinned { input ->
        val data = input.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
        val size = memScoped {
            val w = alloc<IntVar>()
            val h = alloc<IntVar>()
            if (WebPGetInfo(data, webp.size.convert(), w.ptr, h.ptr) == 0) null else w.value to h.value
        } ?: return@usePinned null
        val (width, height) = size
        if (width !in 1..RgbaPng.MAX_SIDE || height !in 1..RgbaPng.MAX_SIDE) return@usePinned null
        val rgba = ByteArray(width * height * 4)
        val decoded = rgba.usePinned { out ->
            WebPDecodeRGBAInto(data, webp.size.convert(), out.addressOf(0).reinterpret(), rgba.size.convert(), width * 4)
        }
        if (decoded == null) null else Triple(width, height, rgba)
    }
}
